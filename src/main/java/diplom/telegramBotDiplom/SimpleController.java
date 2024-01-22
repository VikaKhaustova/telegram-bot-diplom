package diplom.telegramBotDiplom;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimpleController {

    @GetMapping("/api/hello")
    public String sayHello() {
        return "Hello, world!";
    }
}
