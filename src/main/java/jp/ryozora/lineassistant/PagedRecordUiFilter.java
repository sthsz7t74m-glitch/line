package jp.ryozora.lineassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PagedRecordUiFilter extends OncePerRequestFilter {
    private static final int PAGE_SIZE = 5;
    private static final Pattern PAGE = Pattern.compile("^(メモ一覧|タスク一覧)(?:\\s+(\\d+))?$");
    private static final Pattern DETAIL = Pattern.compile("^(メモ詳細|タスク詳細)\\s+(\\d+)$");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public PagedRecordUiFilter(ObjectMapper mapper, JdbcTemplate jdbc, LineProperties props) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedRequest wrapped = new CachedRequest(request, body);
        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;
                String input = normalize(event.path("message").path("text").asText());
                Matcher pageMatcher = PAGE.matcher(input);
                Matcher detailMatcher = DETAIL.matcher(input);
                if (!pageMatcher.matches() && !detailMatcher.matches()) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
                String replyToken = event.path("replyToken").asText();
                if (pageMatcher.matches()) {
                    int page = pageMatcher.group(2) == null ? 1 : Math.max(1, Integer.parseInt(pageMatcher.group(2)));
                    if (pageMatcher.group(1).startsWith("メモ")) {
                        replyFlex(replyToken, "メモ一覧", memoListBubble(userId, page));
                    } else {
                        replyFlex(replyToken, "タスク一覧", taskListBubble(userId, page));
                    }
                } else {
                    int number = Integer.parseInt(detailMatcher.group(2));
                    if (detailMatcher.group(1).startsWith("メモ")) {
                        replyFlex(replyToken, "メモ詳細", memoDetailBubble(userId, number));
                    } else {
                        replyFlex(replyToken, "タスク詳細", taskDetailBubble(userId, number));
                    }
                }
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Existing webhook pipeline remains available as a fallback.
        }
        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private Map<String, Object> memoListBubble(String userId, int requestedPage) {
        List<MemoRow> all = memoRows(userId);
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, pages);
        int from = Math.min((page - 1) * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<Map<String, Object>> body = new ArrayList<>();
        if (all.isEmpty()) {
            body.add(emptyState("メモはまだないよ", "残したいことを追加してみよう", "メモ追加"));
        } else {
            for (int index = from; index < to; index++) {
                MemoRow row = all.get(index);
                int number = index + 1;
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(text((row.favorite() ? "★ " : "") + row.content(), "sm", "bold", "#243B53"));
                if (row.tags() != null && !row.tags().isBlank()) {
                    card.add(text("#" + row.tags().replace(",", "  #"), "xxs", "regular", "#8A6C80"));
                }
                card.add(horizontal(List.of(
                        secondary("詳細", "メモ詳細 " + number),
                        secondary("編集", "メモ編集案内 " + number),
                        danger("削除", "メモ削除確認 " + number)
                )));
                body.add(group("#F8FAFC", card));
            }
            body.add(pageControls("メモ一覧", page, pages));
            body.add(primary("メモを追加", "メモ追加", "#D989AD"));
        }
        body.add(horizontal(List.of(
                secondary("← 戻る", "記録メニュー"),
                secondary("🏠 ホーム", "ホーム")
        )));
        return bubble("メモ一覧", all.isEmpty() ? "0件" : all.size() + "件・" + page + "/" + pages + "ページ",
                "#D989AD", "#FBEAF2", body);
    }

    private Map<String, Object> taskListBubble(String userId, int requestedPage) {
        List<TaskRow> all = taskRows(userId);
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, pages);
        int from = Math.min((page - 1) * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<Map<String, Object>> body = new ArrayList<>();
        if (all.isEmpty()) {
            body.add(emptyState("未完了タスクはゼロ", "新しいタスクを追加できるよ", "タスク追加"));
        } else {
            for (int index = from; index < to; index++) {
                TaskRow row = all.get(index);
                int number = index + 1;
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(text(priority(row.priority()) + row.title(), "sm", "bold", "#243B53"));
                String due = due(row.dueAt());
                if (!due.isBlank()) card.add(text(due, "xxs", "regular", row.overdue() ? "#B94A55" : "#718096"));
                card.add(horizontal(List.of(
                        primary("完了", "タスク完了確認 " + number, "#4AAE9E"),
                        secondary("詳細", "タスク詳細 " + number),
                        danger("削除", "タスク削除確認 " + number)
                )));
                body.add(group("#F7FBFA", card));
            }
            body.add(pageControls("タスク一覧", page, pages));
            body.add(primary("タスクを追加", "タスク追加", "#4AAE9E"));
        }
        body.add(horizontal(List.of(
                secondary("← 戻る", "記録メニュー"),
                secondary("🏠 ホーム", "ホーム")
        )));
        return bubble("タスク一覧", all.isEmpty() ? "0件" : all.size() + "件・" + page + "/" + pages + "ページ",
                "#2E9B6B", "#E7F7F1", body);
    }

    private Map<String, Object> memoDetailBubble(String userId, int number) {
        List<MemoRow> rows = memoRows(userId);
        if (number < 1 || number > rows.size()) return missingBubble("メモ", "メモ一覧");
        MemoRow row = rows.get(number - 1);
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(group("#F8FAFC", List.of(
                text(row.content(), "md", "bold", "#243B53"),
                text(row.favorite() ? "★ お気に入り" : "通常メモ", "xxs", "regular", "#718096"),
                text(row.tags() == null || row.tags().isBlank() ? "タグなし" : "#" + row.tags().replace(",", "  #"),
                        "xxs", "regular", "#8A6C80")
        )));
        body.add(horizontal(List.of(
                primary("編集", "メモ編集案内 " + number, "#D989AD"),
                secondary(row.favorite() ? "★解除" : "★追加", "メモお気に入り実行 " + number)
        )));
        body.add(danger("削除", "メモ削除確認 " + number));
        body.add(horizontal(List.of(secondary("← 一覧", "メモ一覧"), secondary("🏠 ホーム", "ホーム"))));
        return bubble("メモ詳細", "No." + number, "#D989AD", "#FBEAF2", body);
    }

    private Map<String, Object> taskDetailBubble(String userId, int number) {
        List<TaskRow> rows = taskRows(userId);
        if (number < 1 || number > rows.size()) return missingBubble("タスク", "タスク一覧");
        TaskRow row = rows.get(number - 1);
        List<Map<String, Object>> body = new ArrayList<>();
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(text(row.title(), "md", "bold", "#243B53"));
        content.add(text("優先度：" + priorityName(row.priority()), "xxs", "regular", "#718096"));
        String due = due(row.dueAt());
        if (!due.isBlank()) content.add(text(due, "xxs", "regular", row.overdue() ? "#B94A55" : "#718096"));
        body.add(group("#F7FBFA", content));
        body.add(horizontal(List.of(
                primary("完了", "タスク完了確認 " + number, "#4AAE9E"),
                secondary("延期", "タスク延期案内 " + number)
        )));
        body.add(danger("削除", "タスク削除確認 " + number));
        body.add(horizontal(List.of(secondary("← 一覧", "タスク一覧"), secondary("🏠 ホーム", "ホーム"))));
        return bubble("タスク詳細", "No." + number, "#2E9B6B", "#E7F7F1", body);
    }

    private List<MemoRow> memoRows(String userId) {
        return jdbc.query("""
                select id, content, favorite, tags
                from memos
                where line_user_id=? and archived=false
                order by favorite desc, created_at desc
                limit 100
                """, (rs, i) -> new MemoRow(rs.getLong("id"), rs.getString("content"),
                rs.getBoolean("favorite"), rs.getString("tags")), userId);
    }

    private List<TaskRow> taskRows(String userId) {
        return jdbc.query("""
                select id, title, priority, due_at
                from tasks
                where line_user_id=? and completed=false
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                         due_at nulls last, created_at
                limit 100
                """, (rs, i) -> {
            Timestamp due = rs.getTimestamp("due_at");
            java.time.OffsetDateTime at = due == null ? null : due.toInstant().atZone(TOKYO).toOffsetDateTime();
            boolean overdue = at != null && at.toInstant().isBefore(java.time.Instant.now());
            return new TaskRow(rs.getLong("id"), rs.getString("title"), rs.getString("priority"), at, overdue);
        }, userId);
    }

    private Map<String, Object> emptyState(String title, String description, String addCommand) {
        return group("#F8FAFC", List.of(
                text(title, "md", "bold", "#526D82"),
                text(description, "xxs", "regular", "#8A96A6"),
                primary("追加する", addCommand, "#667EA8")
        ));
    }

    private Map<String, Object> pageControls(String command, int page, int pages) {
        if (pages <= 1) return text("全件表示中", "xxs", "regular", "#98A1AE");
        List<Map<String, Object>> controls = new ArrayList<>();
        if (page > 1) controls.add(secondary("← 前へ", command + " " + (page - 1)));
        controls.add(text(page + " / " + pages, "xs", "bold", "#526D82"));
        if (page < pages) controls.add(secondary("次へ →", command + " " + (page + 1)));
        return horizontal(controls);
    }

    private Map<String, Object> missingBubble(String kind, String listCommand) {
        return bubble(kind + "詳細", "対象が見つからないよ", "#718096", "#EEF2F7", List.of(
                text("一覧を開き直して選び直してね", "sm", "regular", "#718096"),
                primary("一覧へ", listCommand, "#667EA8"),
                secondary("🏠 ホーム", "ホーム")
        ));
    }

    private Map<String, Object> bubble(String title, String subtitle, String accent,
                                       String headerColor, List<Map<String, Object>> body) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        bubble.put("header", vertical(headerColor, "12px", "xs", List.of(
                text(title, "lg", "bold", accent),
                text(subtitle, "xxs", "regular", "#718096")
        )));
        bubble.put("body", vertical("#FCFDFE", "12px", "sm", body));
        return bubble;
    }

    private Map<String, Object> group(String background, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("cornerRadius", "12px");
        box.put("paddingAll", "10px");
        box.put("spacing", "xs");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> vertical(String background, String padding, String spacing,
                                         List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("paddingAll", padding);
        box.put("spacing", spacing);
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        return Map.of("type", "box", "layout", "horizontal", "spacing", "sm", "contents", contents);
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        return Map.of("type", "text", "text", value, "size", size, "weight", weight,
                "color", color, "wrap", true, "flex", 1);
    }

    private Map<String, Object> primary(String label, String message, String color) {
        return button(label, message, "primary", color);
    }

    private Map<String, Object> secondary(String label, String message) {
        return button(label, message, "secondary", null);
    }

    private Map<String, Object> danger(String label, String message) {
        return button(label, message, "primary", "#C95C66");
    }

    private Map<String, Object> button(String label, String message, String style, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", style);
        button.put("height", "sm");
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        if (color != null) button.put("color", color);
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private String priority(String value) {
        return switch (value == null ? "MEDIUM" : value) {
            case "HIGH" -> "[高] ";
            case "LOW" -> "[低] ";
            default -> "[中] ";
        };
    }

    private String priorityName(String value) {
        return switch (value == null ? "MEDIUM" : value) {
            case "HIGH" -> "高";
            case "LOW" -> "低";
            default -> "中";
        };
    }

    private String due(java.time.OffsetDateTime dueAt) {
        if (dueAt == null) return "期限なし";
        String label = dueAt.atZoneSameInstant(TOKYO).format(DateTimeFormatter.ofPattern("M/d（E）H:mm", java.util.Locale.JAPANESE));
        return dueAt.toInstant().isBefore(java.time.Instant.now()) ? "期限切れ：" + label : "期限：" + label;
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("メモ", "メモ一覧"),
                quick("タスク", "タスク一覧")
        )));
        sendReply(replyToken, List.of(flex));
    }

    private Map<String, Object> quick(String label, String message) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", message
        ));
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

    private String normalize(String value) {
        return value == null ? "" : value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record MemoRow(long id, String content, boolean favorite, String tags) { }
    private record TaskRow(long id, String title, String priority,
                           java.time.OffsetDateTime dueAt, boolean overdue) { }

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
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
