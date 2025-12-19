package frontend.ctrl;

import dev.doda.team.lib.VersionUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HelloWorldController {

    @GetMapping("/")
    @ResponseBody
    public String index() {
		var version = new VersionUtil().getVersion();
		return "Hello World!  lib-version=" + version;
    }
}