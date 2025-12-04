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
        return "";
    }
}
