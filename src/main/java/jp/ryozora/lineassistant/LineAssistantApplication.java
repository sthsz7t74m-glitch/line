package jp.ryozora.lineassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LineProperties.class)
public class LineAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(LineAssistantApplication.class, args);
    }
}
