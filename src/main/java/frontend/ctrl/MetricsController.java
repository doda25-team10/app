package frontend.ctrl;

import java.util.Random;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping(path = "/metrics")
public class MetricsController {

    private final Random random = new Random();

    @GetMapping({"", "/"})
    public String metrics() {
        double r = random.nextDouble(); // random value between 0.0 and 1.0

        return "# HELP my_random This is just a random 'gauge' for illustration.\n" +
           "# TYPE my_random gauge\n" +
           "my_random " + r + "\n\n";
    }
}
