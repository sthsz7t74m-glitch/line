package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
public class LineWebhookController {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final BenlyCommandService commandService;
    private final HttpClient client = HttpClient.newHttpClient();

    public LineWebhookController(
            LineProperties props,
            ObjectMapper mapper,
            BenlyCommandService commandService
    ) {
        this.props = props;
        this.mapper = mapper;
        this.commandService = commandService;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "app", "benly",
                "version", "0.2.0",
                "status", "running",
                "storage", "postgresql"
        );
    }

    @PostMapping("/line/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "x-line-signature", required = false) String signature,
            @RequestBody String body
    ) {
        if (!validSignature(body, signature)) {
            return ResponseEntity.status(401).build();
        }

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) continue;

                String replyToken = event.path("replyToken").asText();
                String text = event.path("message").path("text").asText();
                reply(replyToken, commandService.handle(userId, text));
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid LINE webhook", e);
        }
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank()
                || props.ownerUserId().equals(userId);
    }

    private boolean validSignature(String body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Signature verification failed", e);
        }
    }

    private void reply(String replyToken, String text) {
        try {
            String safeText = text.length() > 5000 ? text.substring(0, 5000) : text;
            String json = mapper.writeValueAsString(Map.of(
                    "replyToken", replyToken,
                    "messages", List.of(Map.of("type", "text", "text", safeText))
            ));

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api.line.me/v2/bot/message/reply"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "LINE API error: " + response.statusCode() + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Reply failed", e);
        }
    }
}
