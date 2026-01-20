package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private final AtomicLong counterSpam = new AtomicLong();
    private final AtomicLong counterHam = new AtomicLong();

    private final AtomicLong gaugeLastLength = new AtomicLong();

    // Buckets: 0.1s, 0.5s, 1.0s, +Inf
    private final AtomicLong histBucket01 = new AtomicLong();
    private final AtomicLong histBucket05 = new AtomicLong();
    private final AtomicLong histBucket10 = new AtomicLong();
    private final AtomicLong histBucketInf = new AtomicLong();
    private final DoubleAdder histSum = new DoubleAdder();
    private final AtomicLong histCount = new AtomicLong();

    // Buckets: 1s, 5s, 10s, 15s, 30s, +Inf
    private final AtomicLong firstRequestHistBucket01 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket05 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket10 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket15 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket30 = new AtomicLong();
    private final AtomicLong firstRequestHistBucketInf = new AtomicLong();
    private final DoubleAdder firstRequestHistSum = new DoubleAdder();
    private final AtomicLong firstRequestHistCount = new AtomicLong();

    // Buckets: 1s, 5s, 10s, 15s, 30s, +Inf
    private final AtomicLong interRequestHistBucket01 = new AtomicLong();
    private final AtomicLong interRequestHistBucket05 = new AtomicLong();
    private final AtomicLong interRequestHistBucket10 = new AtomicLong();
    private final AtomicLong interRequestHistBucket15 = new AtomicLong();
    private final AtomicLong interRequestHistBucket30 = new AtomicLong();
    private final AtomicLong interRequestHistBucketInf = new AtomicLong();
    private final DoubleAdder interRequestHistSum = new DoubleAdder();
    private final AtomicLong interRequestHistCount = new AtomicLong();

    // Buckets: 5s, 10s, 20s, 30s, 60s, 120s, +Inf
    private final AtomicLong timeOnPageBucket05 = new AtomicLong();
    private final AtomicLong timeOnPageBucket10 = new AtomicLong();
    private final AtomicLong timeOnPageBucket20 = new AtomicLong();
    private final AtomicLong timeOnPageBucket30 = new AtomicLong();
    private final AtomicLong timeOnPageBucket60 = new AtomicLong();
    private final AtomicLong timeOnPageBucket120 = new AtomicLong();
    private final AtomicLong timeOnPageBucketInf = new AtomicLong();
    private final DoubleAdder timeOnPageSum = new DoubleAdder();
    private final AtomicLong timeOnPageCount = new AtomicLong();


    private final RestTemplateBuilder rest;
    private final AtomicLong pageAbandoned = new AtomicLong();
    private final String version;

    private String modelHost;
    private long prevRequestTime;

    public FrontendController(RestTemplateBuilder rest, Environment env, BuildProperties buildProperties) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.version = buildProperties.getVersion();
        assertModelHost();
    }

    private void assertModelHost() {
        if (modelHost == null || modelHost.strip().isEmpty()) {
            System.err.println("ERROR: ENV variable MODEL_HOST is null or empty");
            System.exit(1);
        }
        modelHost = modelHost.strip();
        if (modelHost.indexOf("://") == -1) {
            var m = "ERROR: ENV variable MODEL_HOST is missing protocol, like \"http://...\" (was: \"%s\")\n";
            System.err.printf(m, modelHost);
            System.exit(1);
        } else {
            System.out.printf("Working with MODEL_HOST=\"%s\"\n", modelHost);
        }
    }

    @GetMapping("")
    public String redirectToSlash(HttpServletRequest request) {
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("/")
    public String index(Model m, HttpSession session) {
        // Store the time of page opening
        session.setAttribute("pageOpenTime", System.nanoTime());
        session.setAttribute("firstReq", true);
        session.setAttribute("sessionMetricsRecorded", false);

        pageAbandoned.incrementAndGet();
        session.setAttribute("hasMadePrediction", false);

        m.addAttribute("hostname", modelHost);
        prevRequestTime = System.nanoTime();
        return "sms/index";
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms, HttpSession session) {
        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

        long startTime = System.nanoTime();

        if (session.getAttribute("pageOpenTime") == null) {
            session.setAttribute("pageOpenTime", startTime);
            session.setAttribute("firstReq", true);
            session.setAttribute("hasMadePrediction", false);
            session.setAttribute("sessionMetricsRecorded", false);
            pageAbandoned.incrementAndGet();
            prevRequestTime = System.nanoTime();
        }


        long firstReqTime = startTime - (long)session.getAttribute("pageOpenTime");
        long interRequestTime = startTime - prevRequestTime;
        prevRequestTime = startTime;

        try {
            sms.result = getPrediction(sms);
        } finally {
            long durationNanos = System.nanoTime() - startTime;
            recordMetrics(sms.result, sms.sms.length(), durationNanos, firstReqTime, interRequestTime, session);
        }

        System.out.printf("Prediction: %s\n", sms.result);
        return sms;
    }

    private String getPrediction(Sms sms) {
        try {
            var url = new URI(modelHost + "/predict");
            var c = rest.build().postForEntity(url, sms, Sms.class);
            return c.getBody().result.trim();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private void recordMetrics(String result, int length, long durationNanos, long firstReqTime, long interRequestTime, HttpSession session) {
        if ("SPAM".equalsIgnoreCase(result)) {
            counterSpam.incrementAndGet();
        } else {
            counterHam.incrementAndGet();
        }

        gaugeLastLength.set(length);

        double durationSeconds = durationNanos / 1_000_000_000.0;
        histSum.add(durationSeconds);
        histCount.incrementAndGet();
        
        if (durationSeconds <= 0.1) histBucket01.incrementAndGet();
        if (durationSeconds <= 0.5) histBucket05.incrementAndGet();
        if (durationSeconds <= 1.0) histBucket10.incrementAndGet();
        histBucketInf.incrementAndGet(); // +Inf always increments

        Boolean firstReq = (Boolean)session.getAttribute("firstReq");
        if(firstReq != null && firstReq) {
            session.setAttribute("firstReq", false);
            double firstReqSeconds = firstReqTime / 1_000_000_000.0;
            firstRequestHistSum.add(firstReqSeconds);
            firstRequestHistCount.incrementAndGet();

            if (firstReqSeconds <= 1.0) firstRequestHistBucket01.incrementAndGet();
            if (firstReqSeconds <= 5.0) firstRequestHistBucket05.incrementAndGet();
            if (firstReqSeconds <= 10.0) firstRequestHistBucket10.incrementAndGet();
            if (firstReqSeconds <= 15.0) firstRequestHistBucket15.incrementAndGet();
            if (firstReqSeconds <= 30.0) firstRequestHistBucket30.incrementAndGet();
            firstRequestHistBucketInf.incrementAndGet(); // +Inf always increments
        }

        double interRequestSeconds = interRequestTime / 1_000_000_000.0;
        interRequestHistSum.add(interRequestSeconds);
        interRequestHistCount.incrementAndGet();
        if (interRequestSeconds <= 1.0) interRequestHistBucket01.incrementAndGet();
        if (interRequestSeconds <= 5.0) interRequestHistBucket05.incrementAndGet();
        if (interRequestSeconds <= 10.0) interRequestHistBucket10.incrementAndGet();
        if (interRequestSeconds <= 15.0) interRequestHistBucket15.incrementAndGet();
        if (interRequestSeconds <= 30.0) interRequestHistBucket30.incrementAndGet();
        interRequestHistBucketInf.incrementAndGet(); // +Inf always increments

        Boolean hasMadePrediction = (Boolean)session.getAttribute("hasMadePrediction");
        if (hasMadePrediction != null && !hasMadePrediction) {
            pageAbandoned.decrementAndGet();
            session.setAttribute("hasMadePrediction", true);
        }
    }

    public static class SessionReport {
        public Double timeSpentSeconds;
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Void> reportSession(@RequestBody(required = false) SessionReport report, HttpSession session) {

        Boolean alreadyRecorded = (Boolean) session.getAttribute("sessionMetricsRecorded");
        if (alreadyRecorded != null && alreadyRecorded) {
            return ResponseEntity.ok().build();
        }
        session.setAttribute("sessionMetricsRecorded", true);

        double timeSpentSeconds = 0.0;
        Object openObj = session.getAttribute("pageOpenTime");
        if (openObj instanceof Long) {
            long open = (Long) openObj;
            timeSpentSeconds = (System.nanoTime() - open) / 1_000_000_000.0;
        } else if (report != null && report.timeSpentSeconds != null) {
            timeSpentSeconds = report.timeSpentSeconds;
        }

        timeOnPageSum.add(timeSpentSeconds);
        timeOnPageCount.incrementAndGet();
        if (timeSpentSeconds <= 5) timeOnPageBucket05.incrementAndGet();
        if (timeSpentSeconds <= 10) timeOnPageBucket10.incrementAndGet();
        if (timeSpentSeconds <= 20) timeOnPageBucket20.incrementAndGet();
        if (timeSpentSeconds <= 30) timeOnPageBucket30.incrementAndGet();
        if (timeSpentSeconds <= 60) timeOnPageBucket60.incrementAndGet();
        if (timeSpentSeconds <= 120) timeOnPageBucket120.incrementAndGet();
        timeOnPageBucketInf.incrementAndGet();


        return ResponseEntity.ok().build();
    }


    // --- Prometheus endpoint ---
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> metrics() {
        StringBuilder sb = new StringBuilder();

        // 1. Counter: sms_predictions_total
        sb.append("# HELP sms_predictions_total Total number of prediction requests\n");
        sb.append("# TYPE sms_predictions_total counter\n");
        sb.append("sms_predictions_total{result=\"spam\",version=\"").append(version).append("\"} ").append(counterSpam.get()).append("\n");
        sb.append("sms_predictions_total{result=\"ham\",version=\"").append(version).append("\"} ").append(counterHam.get()).append("\n");

        // 2. Gauge: sms_last_text_length
        sb.append("# HELP sms_last_text_length Length of the last checked SMS text\n");
        sb.append("# TYPE sms_last_text_length gauge\n");
        sb.append("sms_last_text_length{version=\"").append(version).append("\"} ").append(gaugeLastLength.get()).append("\n");

        // 3. Histogram: sms_prediction_duration_seconds
        sb.append("# HELP sms_prediction_duration_seconds Prediction latency in seconds\n");
        sb.append("# TYPE sms_prediction_duration_seconds histogram\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"0.1\",version=\"").append(version).append("\"} ").append(histBucket01.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"0.5\",version=\"").append(version).append("\"} ").append(histBucket05.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"1.0\",version=\"").append(version).append("\"} ").append(histBucket10.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"+Inf\",version=\"").append(version).append("\"} ").append(histBucketInf.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_sum{version=\"").append(version).append("\"} ").append(histSum.sum()).append("\n");
        sb.append("sms_prediction_duration_seconds_count{version=\"").append(version).append("\"} ").append(histCount.get()).append("\n");

        // 4. Histogram: first_sms_request_duration_seconds
        sb.append("# HELP sms_first_request_duration_seconds First request latency in seconds\n");
        sb.append("# TYPE sms_first_request_duration_seconds histogram\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"1\",version=\"").append(version).append("\"} ").append(firstRequestHistBucket01.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"5\",version=\"").append(version).append("\"} ").append(firstRequestHistBucket05.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"10\",version=\"").append(version).append("\"} ").append(firstRequestHistBucket10.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"15\",version=\"").append(version).append("\"} ").append(firstRequestHistBucket15.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"30\",version=\"").append(version).append("\"} ").append(firstRequestHistBucket30.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"+Inf\",version=\"").append(version).append("\"} ").append(firstRequestHistBucketInf.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_sum{version=\"").append(version).append("\"} ").append(firstRequestHistSum.sum()).append("\n");
        sb.append("sms_first_request_duration_seconds_count{version=\"").append(version).append("\"} ").append(firstRequestHistCount.get()).append("\n");

        // 5. Histogram: inter_sms_request_duration_seconds
        sb.append("# HELP sms_inter_request_duration_seconds Inter request latency in seconds\n");
        sb.append("# TYPE sms_inter_request_duration_seconds histogram\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"1\",version=\"").append(version).append("\"} ").append(interRequestHistBucket01.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"5\",version=\"").append(version).append("\"} ").append(interRequestHistBucket05.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"10\",version=\"").append(version).append("\"} ").append(interRequestHistBucket10.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"15\",version=\"").append(version).append("\"} ").append(interRequestHistBucket15.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"30\",version=\"").append(version).append("\"} ").append(interRequestHistBucket30.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_bucket{le=\"+Inf\",version=\"").append(version).append("\"} ").append(interRequestHistBucketInf.get()).append("\n");
        sb.append("sms_inter_request_duration_seconds_sum{version=\"").append(version).append("\"} ").append(interRequestHistSum.sum()).append("\n");
        sb.append("sms_inter_request_duration_seconds_count{version=\"").append(version).append("\"} ").append(interRequestHistCount.get()).append("\n");

        // 6. Counter: sms_pages_abandoned_total
        sb.append("# HELP sms_pages_abandoned_total Total number of abandoned SMS pages\n");
        sb.append("# TYPE sms_pages_abandoned_total counter\n");
        sb.append("sms_pages_abandoned_total{version=\"").append(version).append("\"} ").append(pageAbandoned.get()).append("\n");


        // 7. Histogram: sms_time_on_page_seconds
        sb.append("# HELP sms_time_on_page_seconds Time a user spends on the page (seconds)\n");
        sb.append("# TYPE sms_time_on_page_seconds histogram\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"5\",version=\"").append(version).append("\"} ").append(timeOnPageBucket05.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"10\",version=\"").append(version).append("\"} ").append(timeOnPageBucket10.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"20\",version=\"").append(version).append("\"} ").append(timeOnPageBucket20.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"30\",version=\"").append(version).append("\"} ").append(timeOnPageBucket30.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"60\",version=\"").append(version).append("\"} ").append(timeOnPageBucket60.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"120\",version=\"").append(version).append("\"} ").append(timeOnPageBucket120.get()).append("\n");
        sb.append("sms_time_on_page_seconds_bucket{le=\"+Inf\",version=\"").append(version).append("\"} ").append(timeOnPageBucketInf.get()).append("\n");
        sb.append("sms_time_on_page_seconds_sum{version=\"").append(version).append("\"} ").append(timeOnPageSum.sum()).append("\n");
        sb.append("sms_time_on_page_seconds_count{version=\"").append(version).append("\"} ").append(timeOnPageCount.get()).append("\n");


        return ResponseEntity.ok().body(sb.toString());
    }
}