package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "line.bot.channel-secret=test",
        "line.bot.channel-access-token=test",
        "spring.datasource.url=jdbc:h2:mem:lineassistant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.task.scheduling.enabled=false"
})
class LineAssistantApplicationTests {
    @Test
    void contextLoads() {}
}
