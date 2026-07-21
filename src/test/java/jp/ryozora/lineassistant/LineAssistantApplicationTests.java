package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "line.bot.channel-secret=test",
        "line.bot.channel-access-token=test"
})
class LineAssistantApplicationTests {
    @Test
    void contextLoads() {}
}
