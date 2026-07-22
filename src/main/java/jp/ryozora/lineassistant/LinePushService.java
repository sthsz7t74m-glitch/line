package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Service
public class LinePushService {
    private final LineProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public LinePushService(LineProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public void push(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) return;
        try {
            String safe = text.length() > 5000 ? text.substring(0, 5000) : text;
            String json = mapper.writeValueAsString(Map.of(
                    "to", userId,
                    "messages", List.of(Map.of("type", "text", "text", safe))
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/push"))
                    .header("Authorization", "Bearer " + props.channelAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LINE push failed: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LINE push interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("LINE push failed", e);
        }
    }
}
