package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
        String safe = text.length() > 5000 ? text.substring(0, 5000) : text;
        send(userId, Map.of("type", "text", "text", safe));
    }

    public void pushScheduleReminder(String userId, long scheduleId, String title,
                                     OffsetDateTime startsAt, int minutesBefore) {
        String timing = minutesBefore == 0 ? "まもなく始まるよ" : "あと" + minutesBefore + "分だよ";

        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", box("#DDEBFF", "18px", List.of(
                text("予定のお知らせ", "xl", "bold", "#334E68", "center"),
                text(timing, "md", "bold", "#526D82", "center")
        )));
        bubble.put("body", box("#FAFCFF", "16px", List.of(
                text(title, "xl", "bold", "#263746", "center"),
                text(startsAt.format(DateTimeFormatter.ofPattern("M月d日(E) H:mm")),
                        "md", "regular", "#526D82", "center"),
                text("忘れ物はない？", "sm", "regular", "#6B7F90", "center")
        )));
        bubble.put("footer", box("#EEF5FF", "12px", List.of(
                buttonRow(
                        button("あと10分", "スヌーズ 10 " + scheduleId, "#7EAEE8"),
                        button("あと30分", "スヌーズ 30 " + scheduleId, "#8FC0E8")
                ),
                button("明日にする", "スヌーズ 明日 " + scheduleId, "#A995D8")
        )));

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "予定のお知らせ：" + title);
        flex.put("contents", bubble);
        send(userId, flex);
    }

    private void send(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank()) return;
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "to", userId,
                    "messages", List.of(message)
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

    private Map<String, Object> box(String backgroundColor, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", backgroundColor);
        box.put("paddingAll", padding);
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> buttonRow(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "box");
        row.put("layout", "horizontal");
        row.put("spacing", "sm");
        row.put("contents", List.of(left, right));
        return row;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("flex", 1);
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "text");
        component.put("text", value);
        component.put("size", size);
        component.put("weight", weight);
        component.put("color", color);
        component.put("align", align);
        component.put("wrap", true);
        return component;
    }
}
