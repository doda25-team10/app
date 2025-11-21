package frontend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = { "MODEL_HOST=http://non.existing.server" })
class MainTests {

    @Test
    void contextLoads() {}
}