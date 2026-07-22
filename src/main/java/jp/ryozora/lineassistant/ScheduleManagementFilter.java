package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduleManagementFilter extends OncePerRequestFilter {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public ScheduleManagementFilter(ObjectMapper mapper, JdbcTemplate jdbc, LineProperties props) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedRequest wrapped = new CachedRequest(request, body);

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String input = event.path("message").path("text").asText().strip();
                if (!supports(input)) continue;

                String signature = request.getHeader("x-line-signature");
                if (!validSignature(body, signature)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String replyToken = event.path("replyToken").asText();
                handle(replyToken, userId, input);
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the normal webhook controller.
        }

        try {
            filterChain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private boolean supports(String input) {
        return input.equals("予定一覧")
                || input.startsWith("予定削除選択 ")
                || input.startsWith("予定削除実行 ")
                || input.equals("予定全削除確認")
                || input.equals("予定全削除実行")
                || input.startsWith("予定変更案内 ");
    }

    private void handle(String replyToken, String userId, String input) throws Exception {
        if (input.equals("予定一覧")) {
            replyFlex(replyToken, "今後の予定", scheduleListBubble(userId));
            return;
        }
        if (input.equals("予定全削除確認")) {
            replyFlex(replyToken, "予定をすべて削除", deleteAllConfirmBubble());
            return;
        }
        if (input.equals("予定全削除実行")) {
            int count = jdbc.update("delete from schedules where line_user_id=? and starts_at>=current_timestamp", userId);
            replyText(replyToken, count == 0 ? "削除する予定はなかったよ。" : "✓ 今後の予定を全部削除したよ（" + count + "件）。");
            return;
        }

        Integer number = extractNumber(input);
        if (number == null) {
            replyText(replyToken, "予定番号を確認できなかったよ。もう一度一覧から選んでね。");
            return;
        }

        ScheduleRow row = rowByNumber(userId, number);
        if (row == null) {
            replyText(replyToken, "その予定は見つからなかったよ。予定一覧を開き直してね。");
            return;
        }

        if (input.startsWith("予定変更案内 ")) {
            replyFlex(replyToken, "予定の変更", changeGuideBubble(number, row));
            return;
        }

        if (input.startsWith("予定削除選択 ")) {
            replyFlex(replyToken, "予定の削除", deleteChoiceBubble(number, row));
            return;
        }

        String scope = input.contains("この回以降") ? "future" : input.contains("シリーズ全部") ? "all" : "one";
        int changed;
        if ("one".equals(scope) || row.seriesId() == null || row.seriesId().isBlank()) {
            changed = jdbc.update("delete from schedules where line_user_id=? and id=?", userId, row.id());
        } else if ("all".equals(scope)) {
            changed = jdbc.update("delete from schedules where line_user_id=? and series_id=?", userId, row.seriesId());
        } else {
            changed = jdbc.update("delete from schedules where line_user_id=? and series_id=? and starts_at>=?",
                    userId, row.seriesId(), Timestamp.from(row.at().toInstant()));
        }
        replyText(replyToken, changed == 0 ? "予定を削除できなかったよ。" : "✓ 予定「" + row.title() + "」を削除したよ。");
    }

    private Map<String, Object> scheduleListBubble(String userId) {
        List<ScheduleRow> rows = upcomingRows(userId);
        Map<String, Object> bubble = bubbleBase();
        bubble.put("header", verticalBox("#DDEBFF", List.of(
                text("今後の予定", "xl", "bold", "#2E6FC4"),
                text(rows.isEmpty() ? "予定はまだないよ" : rows.size() + "件を表示中", "sm", "regular", "#526D82")
        )));

        List<Map<String, Object>> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(text("予定を追加してみよう", "md", "regular", "#526D82"));
            body.add(button("予定を追加", "明日19時 ", "#8DCAF1"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                ScheduleRow row = rows.get(i);
                String date = row.at().atZoneSameInstant(TOKYO).format(DateTimeFormatter.ofPattern("M/d（E） HH:mm", Locale.JAPANESE));
                String title = (i + 1) + ". " + row.title();
                if (row.recurrence() != null && !row.recurrence().isBlank()) title += "  ［" + row.recurrence() + "］";
                body.add(verticalBox("#F7FAFF", List.of(
                        text(title, "md", "bold", "#243B53"),
                        text(date, "sm", "regular", "#526D82"),
                        horizontalBox(List.of(
                                button("変更", "予定変更案内 " + (i + 1), "#6CA6E5"),
                                button("削除", "予定削除選択 " + (i + 1), "#D96C75")
                        ))
                )));
            }
            body.add(button("予定をすべて削除", "予定全削除確認", "#B94A55"));
        }
        body.add(button("🏠 ホーム", "ホーム", "#8E9CB3"));
        bubble.put("body", verticalBox("#FAFCFF", body));
        return bubble;
    }

    private Map<String, Object> deleteChoiceBubble(int number, ScheduleRow row) {
        Map<String, Object> bubble = bubbleBase();
        bubble.put("header", verticalBox("#FFE8EB", List.of(
                text("この予定を削除する？", "xl", "bold", "#A33A45"),
                text(row.title(), "md", "regular", "#6D3B42")
        )));
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(button("この予定だけ削除", "予定削除実行 " + number, "#D96C75"));
        if (row.seriesId() != null && !row.seriesId().isBlank()) {
            body.add(button("この回以降を削除", "予定削除実行 " + number + " この回以降", "#C95C66"));
            body.add(button("繰り返し全部を削除", "予定削除実行 " + number + " シリーズ全部", "#B94A55"));
        }
        body.add(button("やめる", "予定一覧", "#8E9CB3"));
        bubble.put("body", verticalBox("#FFF9FA", body));
        return bubble;
    }

    private Map<String, Object> deleteAllConfirmBubble() {
        Map<String, Object> bubble = bubbleBase();
        bubble.put("header", verticalBox("#FFE8EB", List.of(
                text("今後の予定を全部削除？", "xl", "bold", "#A33A45"),
                text("この操作は元に戻せないよ", "sm", "regular", "#6D3B42")
        )));
        bubble.put("body", verticalBox("#FFF9FA", List.of(
                button("全部削除する", "予定全削除実行", "#B94A55"),
                button("やめる", "予定一覧", "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> changeGuideBubble(int number, ScheduleRow row) {
        Map<String, Object> bubble = bubbleBase();
        bubble.put("header", verticalBox("#DDEBFF", List.of(
                text("予定を変更", "xl", "bold", "#2E6FC4"),
                text(row.title(), "md", "regular", "#526D82")
        )));
        bubble.put("body", verticalBox("#FAFCFF", List.of(
                text("変更内容をそのまま送ってね", "md", "bold", "#334E68"),
                text("例：予定変更 " + number + " 2026-08-01 20:00 会議", "sm", "regular", "#526D82"),
                button("入力を始める", "予定変更 " + number + " ", "#6CA6E5"),
                button("予定一覧へ戻る", "予定一覧", "#8E9CB3")
        )));
        return bubble;
    }

    private List<ScheduleRow> upcomingRows(String userId) {
        return jdbc.query("""
                select id,title,starts_at,series_id,recurrence_label
                from schedules
                where line_user_id=? and starts_at>=current_timestamp
                order by starts_at
                limit 30
                """, (rs, i) -> new ScheduleRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atOffset(java.time.ZoneOffset.ofHours(9)),
                rs.getString("series_id"),
                rs.getString("recurrence_label")
        ), userId);
    }

    private ScheduleRow rowByNumber(String userId, int number) {
        if (number < 1) return null;
        List<ScheduleRow> rows = upcomingRows(userId);
        return number <= rows.size() ? rows.get(number - 1) : null;
    }

    private Integer extractNumber(String input) {
        Matcher matcher = NUMBER.matcher(input);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Map<String, Object> bubbleBase() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        return bubble;
    }

    private Map<String, Object> verticalBox(String backgroundColor, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", backgroundColor);
        box.put("paddingAll", "16px");
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> horizontalBox(List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "horizontal");
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "text");
        component.put("text", value);
        component.put("size", size);
        component.put("weight", weight);
        component.put("color", color);
        component.put("wrap", true);
        return component;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private void replyText(String replyToken, String text) throws Exception {
        sendReply(replyToken, List.of(Map.of("type", "text", "text", text)));
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        sendReply(replyToken, List.of(flex));
    }

    private void sendReply(String replyToken, List<Map<String, Object>> messages) throws Exception {
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> result = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() / 100 != 2) throw new IllegalStateException("LINE API error: HTTP " + result.statusCode());
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

    private record ScheduleRow(long id, String title, OffsetDateTime at, String seriesId, String recurrence) {}

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
