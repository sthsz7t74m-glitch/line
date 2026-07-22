package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class LineWebhookSupport {
    private final ObjectMapper mapper;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public LineWebhookSupport(ObjectMapper mapper, LineProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public List<TextEvent> textEvents(byte[] body) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<TextEvent> events = new ArrayList<>();
        for (JsonNode event : root.path("events")) {
            if (!"message".equals(event.path("type").asText())) continue;
            if (!"text".equals(event.path("message").path("type").asText())) continue;
            events.add(new TextEvent(
                    event.path("source").path("userId").asText(),
                    event.path("replyToken").asText(),
                    normalize(event.path("message").path("text").asText())
            ));
        }
        return events;
    }

    public boolean isAuthorized(byte[] body, String receivedSignature, String userId) {
        return validSignature(body, receivedSignature) && allowed(userId);
    }

    public void reply(String replyToken, List<Map<String, Object>> messages) throws Exception {
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LINE API error: HTTP " + response.statusCode());
        }
    }

    private boolean validSignature(byte[] body, String received) {
        if (received == null || props.channelSecret() == null || props.channelSecret().isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.channelSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank() || props.ownerUserId().equals(userId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    public record TextEvent(String userId, String replyToken, String text) {}
}
