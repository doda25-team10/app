package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicLong;

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

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private final AtomicLong smsCounter = new AtomicLong(); // <- Counter

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
        // relative REST requests in JS will end up on / and not on /sms
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("/")
    public String index(Model m) {
        smsCounter.incrementAndGet(); // <- Increment counter on GET
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {
        smsCounter.incrementAndGet(); // <- Increment counter on POST
        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);
        sms.result = getPrediction(sms);
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

    // --- Prometheus endpoint for /sms counter ---
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> metrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP sms_requests_total Total number of hits to /sms\n");
        sb.append("# TYPE sms_requests_total counter\n");
        sb.append("sms_requests_total ").append(smsCounter.get()).append("\n");
        return ResponseEntity.ok().body(sb.toString());
    }
}
