package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

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

    // Buckets: 1.0s, 1.5s, 2.0s, +Inf
    private final AtomicLong firstRequestHistBucket01 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket05 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket10 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket15 = new AtomicLong();
    private final AtomicLong firstRequestHistBucket30 = new AtomicLong();
    private final AtomicLong firstRequestHistBucketInf = new AtomicLong();
    private final DoubleAdder firstRequestHistSum = new DoubleAdder();
    private final AtomicLong firstRequestHistCount = new AtomicLong();

    private String modelHost;
    private RestTemplateBuilder rest;

    public FrontendController(RestTemplateBuilder rest, Environment env) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
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
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms, HttpSession session) {
        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

        long startTime = System.nanoTime();
        long firstReqTime = startTime - (long)session.getAttribute("pageOpenTime");

        try {
            sms.result = getPrediction(sms);
        } finally {
            long durationNanos = System.nanoTime() - startTime;
            recordMetrics(sms.result, sms.sms.length(), durationNanos, firstReqTime);
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

    private void recordMetrics(String result, int length, long durationNanos, long firstReqTime) {
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

    // --- Prometheus endpoint ---
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> metrics() {
        StringBuilder sb = new StringBuilder();

        // 1. Counter: sms_predictions_total
        sb.append("# HELP sms_predictions_total Total number of prediction requests\n");
        sb.append("# TYPE sms_predictions_total counter\n");
        sb.append("sms_predictions_total{result=\"spam\"} ").append(counterSpam.get()).append("\n");
        sb.append("sms_predictions_total{result=\"ham\"} ").append(counterHam.get()).append("\n");

        // 2. Gauge: sms_last_text_length
        sb.append("# HELP sms_last_text_length Length of the last checked SMS text\n");
        sb.append("# TYPE sms_last_text_length gauge\n");
        sb.append("sms_last_text_length ").append(gaugeLastLength.get()).append("\n");

        // 3. Histogram: sms_prediction_duration_seconds
        sb.append("# HELP sms_prediction_duration_seconds Prediction latency in seconds\n");
        sb.append("# TYPE sms_prediction_duration_seconds histogram\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"0.1\"} ").append(histBucket01.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"0.5\"} ").append(histBucket05.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"1.0\"} ").append(histBucket10.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_bucket{le=\"+Inf\"} ").append(histBucketInf.get()).append("\n");
        sb.append("sms_prediction_duration_seconds_sum ").append(histSum.sum()).append("\n");
        sb.append("sms_prediction_duration_seconds_count ").append(histCount.get()).append("\n");

        // 4. Histogram: first_sms_request_duration_seconds
        sb.append("# HELP sms_first_request_duration_seconds First request latency in seconds\n");
        sb.append("# TYPE sms_first_request_duration_seconds histogram\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"1\"} ").append(firstRequestHistBucket01.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"5\"} ").append(firstRequestHistBucket05.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"10\"} ").append(firstRequestHistBucket10.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"15\"} ").append(firstRequestHistBucket15.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"30\"} ").append(firstRequestHistBucket30.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_bucket{le=\"+Inf\"} ").append(firstRequestHistBucketInf.get()).append("\n");
        sb.append("sms_first_request_duration_seconds_sum ").append(firstRequestHistSum.sum()).append("\n");
        sb.append("sms_first_request_duration_seconds_count ").append(firstRequestHistCount.get()).append("\n");

        return ResponseEntity.ok().body(sb.toString());
    }
}