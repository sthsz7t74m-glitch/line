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
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RecordManagementFilter extends OncePerRequestFilter {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final BenlyStore store;
    private final LineProperties props;
    private final HttpClient client = HttpClient.newHttpClient();

    public RecordManagementFilter(ObjectMapper mapper, JdbcTemplate jdbc, BenlyStore store, LineProperties props) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.store = store;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
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
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                handle(event.path("replyToken").asText(), userId, input);
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Existing webhook processing remains the fallback.
        }

        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private boolean supports(String input) {
        return input.equals("メモ一覧") || input.equals("タスク一覧")
                || input.startsWith("メモ編集案内 ") || input.startsWith("メモお気に入り実行 ")
                || input.startsWith("メモ削除確認 ") || input.startsWith("メモ削除実行 ")
                || input.equals("メモ全削除確認") || input.equals("メモ全削除実行")
                || input.startsWith("タスク完了確認 ") || input.startsWith("タスク完了実行 ")
                || input.startsWith("タスク延期案内 ")
                || input.startsWith("タスク削除確認 ") || input.startsWith("タスク削除実行 ")
                || input.equals("タスク全削除確認") || input.equals("タスク全削除実行");
    }

    private void handle(String replyToken, String userId, String input) throws Exception {
        if (input.equals("メモ一覧")) {
            replyFlex(replyToken, "メモ一覧", memoListBubble(userId));
            return;
        }
        if (input.equals("タスク一覧")) {
            replyFlex(replyToken, "未完了タスク", taskListBubble(userId));
            return;
        }
        if (input.equals("メモ全削除確認")) {
            replyFlex(replyToken, "メモをすべて削除", clearConfirmBubble("メモ", "メモ全削除実行", "メモ一覧"));
            return;
        }
        if (input.equals("メモ全削除実行")) {
            int count = store.clearMemos(userId);
            replyText(replyToken, count == 0 ? "削除するメモはなかったよ。" : "✓ メモを全部削除したよ（" + count + "件）。");
            return;
        }
        if (input.equals("タスク全削除確認")) {
            replyFlex(replyToken, "タスクをすべて削除", clearConfirmBubble("未完了タスク", "タスク全削除実行", "タスク一覧"));
            return;
        }
        if (input.equals("タスク全削除実行")) {
            int count = jdbc.update("delete from tasks where line_user_id=? and completed=false", userId);
            replyText(replyToken, count == 0 ? "削除する未完了タスクはなかったよ。" : "✓ 未完了タスクを全部削除したよ（" + count + "件）。");
            return;
        }

        Integer number = extractNumber(input);
        if (number == null) {
            replyText(replyToken, "対象を確認できなかったよ。一覧から選び直してね。");
            return;
        }

        if (input.startsWith("メモ")) {
            MemoRow memo = memoByNumber(userId, number);
            if (memo == null) {
                replyText(replyToken, "そのメモは見つからなかったよ。メモ一覧を開き直してね。");
                return;
            }
            if (input.startsWith("メモ編集案内 ")) {
                replyFlex(replyToken, "メモを編集", editGuideBubble("メモを編集", memo.content(),
                        "例：メモ編集 " + number + " 牛乳を2本買う", "メモ一覧"));
                return;
            }
            if (input.startsWith("メモお気に入り実行 ")) {
                Boolean favorite = store.toggleMemoFavorite(userId, memo.id());
                replyText(replyToken, Boolean.TRUE.equals(favorite) ? "★ お気に入りに追加したよ。" : "☆ お気に入りを外したよ。");
                return;
            }
            if (input.startsWith("メモ削除確認 ")) {
                replyFlex(replyToken, "メモを削除", itemConfirmBubble("このメモを削除する？", memo.content(),
                        "削除する", "メモ削除実行 " + number, "メモ一覧"));
                return;
            }
            boolean deleted = store.deleteMemo(userId, memo.id());
            replyText(replyToken, deleted ? "✓ メモを削除したよ。" : "メモを削除できなかったよ。");
            return;
        }

        TaskRow task = taskByNumber(userId, number);
        if (task == null) {
            replyText(replyToken, "そのタスクは見つからなかったよ。タスク一覧を開き直してね。");
            return;
        }
        if (input.startsWith("タスク完了確認 ")) {
            replyFlex(replyToken, "タスクを完了", itemConfirmBubble("このタスクを完了にする？", task.title(),
                    "完了にする", "タスク完了実行 " + number, "タスク一覧"));
            return;
        }
        if (input.startsWith("タスク完了実行 ")) {
            boolean completed = store.completeTask(userId, task.id());
            replyText(replyToken, completed ? "✓ タスク「" + task.title() + "」を完了にしたよ。" : "タスクを完了にできなかったよ。");
            return;
        }
        if (input.startsWith("タスク延期案内 ")) {
            replyFlex(replyToken, "タスクを延期", editGuideBubble("タスクを延期", task.title(),
                    "例：タスク延期 " + number + " 明日", "タスク一覧"));
            return;
        }
        if (input.startsWith("タスク削除確認 ")) {
            replyFlex(replyToken, "タスクを削除", itemConfirmBubble("このタスクを削除する？", task.title(),
                    "削除する", "タスク削除実行 " + number, "タスク一覧"));
            return;
        }
        int changed = jdbc.update("delete from tasks where id=? and line_user_id=? and completed=false", task.id(), userId);
        replyText(replyToken, changed == 1 ? "✓ タスクを削除したよ。" : "タスクを削除できなかったよ。");
    }

    private Map<String, Object> memoListBubble(String userId) {
        List<MemoRow> rows = memoRows(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(text("メモはまだないよ", "md", "regular", "#526D82"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                MemoRow row = rows.get(i);
                String label = (row.favorite() ? "★ " : "") + (i + 1) + ". " + row.content();
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(text(label, "md", "bold", "#243B53"));
                if (row.tags() != null && !row.tags().isBlank()) {
                    card.add(text("#" + row.tags().replace(",", "  #"), "xs", "regular", "#7A6B89"));
                }
                card.add(horizontal(List.of(
                        button("編集", "メモ編集案内 " + (i + 1), "#6CA6E5"),
                        button(row.favorite() ? "★解除" : "★お気に入り", "メモお気に入り実行 " + (i + 1), "#D7A63E"),
                        button("削除", "メモ削除確認 " + (i + 1), "#D96C75")
                )));
                body.add(vertical("#F7FAFF", card));
            }
            body.add(button("メモをすべて削除", "メモ全削除確認", "#B94A55"));
        }
        body.add(button("メモを追加", "メモ追加", "#E4A5BF"));
        body.add(button("← メモ・タスク", "記録メニュー", "#8E9CB3"));
        body.add(button("🏠 ホーム", "ホーム", "#8E9CB3"));
        return listBubble("メモ一覧", rows.size() + "件を表示中", "#DDF5EE", "#2E9B6B", body);
    }

    private Map<String, Object> taskListBubble(String userId) {
        List<TaskRow> rows = taskRows(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(text("未完了タスクはゼロ！", "md", "regular", "#526D82"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                TaskRow row = rows.get(i);
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(text((i + 1) + ". " + priorityLabel(row.priority()) + row.title(), "md", "bold", "#243B53"));
                card.add(text(dueLabel(row.dueAt()), "sm", "regular", row.overdue() ? "#B94A55" : "#526D82"));
                card.add(horizontal(List.of(
                        button("完了", "タスク完了確認 " + (i + 1), "#4AAE9E"),
                        button("延期", "タスク延期案内 " + (i + 1), "#6CA6E5"),
                        button("削除", "タスク削除確認 " + (i + 1), "#D96C75")
                )));
                body.add(vertical("#F7FAFF", card));
            }
            body.add(button("未完了タスクをすべて削除", "タスク全削除確認", "#B94A55"));
        }
        body.add(button("タスクを追加", "タスク追加", "#71C9B7"));
        body.add(button("← メモ・タスク", "記録メニュー", "#8E9CB3"));
        body.add(button("🏠 ホーム", "ホーム", "#8E9CB3"));
        return listBubble("未完了タスク", rows.size() + "件を表示中", "#DDF5EE", "#2E9B6B", body);
    }

    private List<MemoRow> memoRows(String userId) {
        store.ensureUser(userId);
        return jdbc.query("""
                select id,content,favorite,tags from memos
                where line_user_id=? and archived=false
                order by favorite desc,created_at desc limit 30
                """, (rs, i) -> new MemoRow(rs.getLong("id"), rs.getString("content"),
                rs.getBoolean("favorite"), rs.getString("tags")), userId);
    }

    private List<TaskRow> taskRows(String userId) {
        store.ensureUser(userId);
        return jdbc.query("""
                select id,title,priority,due_at from tasks
                where line_user_id=? and completed=false
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                         due_at nulls last,created_at limit 30
                """, (rs, i) -> {
            Timestamp due = rs.getTimestamp("due_at");
            boolean overdue = due != null && due.toInstant().isBefore(java.time.Instant.now());
            return new TaskRow(rs.getLong("id"), rs.getString("title"), rs.getString("priority"), due, overdue);
        }, userId);
    }

    private MemoRow memoByNumber(String userId, int number) {
        List<MemoRow> rows = memoRows(userId);
        return number > 0 && number <= rows.size() ? rows.get(number - 1) : null;
    }

    private TaskRow taskByNumber(String userId, int number) {
        List<TaskRow> rows = taskRows(userId);
        return number > 0 && number <= rows.size() ? rows.get(number - 1) : null;
    }

    private String priorityLabel(String priority) {
        if ("HIGH".equals(priority)) return "[高] ";
        if ("LOW".equals(priority)) return "[低] ";
        return "[中] ";
    }

    private String dueLabel(Timestamp due) {
        if (due == null) return "期限なし";
        String value = due.toInstant().atZone(TOKYO).format(DateTimeFormatter.ofPattern("M/d（E） H:mm"));
        return due.toInstant().isBefore(java.time.Instant.now()) ? "期限切れ  " + value : "期限  " + value;
    }

    private Map<String, Object> listBubble(String title, String subtitle, String headerColor,
                                           String accent, List<Map<String, Object>> body) {
        Map<String, Object> bubble = baseBubble();
        bubble.put("header", vertical(headerColor, List.of(
                text(title, "xl", "bold", accent),
                text(subtitle, "sm", "regular", "#526D82")
        )));
        bubble.put("body", vertical("#FAFCFF", body));
        return bubble;
    }

    private Map<String, Object> itemConfirmBubble(String title, String item, String actionLabel,
                                                   String actionMessage, String cancelMessage) {
        Map<String, Object> bubble = baseBubble();
        bubble.put("header", vertical("#FFE8EB", List.of(
                text(title, "xl", "bold", "#A33A45"),
                text(item, "md", "regular", "#6D3B42")
        )));
        bubble.put("body", vertical("#FFF9FA", List.of(
                button(actionLabel, actionMessage, "#B94A55"),
                button("やめる", cancelMessage, "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> clearConfirmBubble(String subject, String execute, String cancel) {
        return itemConfirmBubble(subject + "を全部削除する？", "この操作は元に戻せないよ",
                "全部削除する", execute, cancel);
    }

    private Map<String, Object> editGuideBubble(String title, String item, String example, String back) {
        Map<String, Object> bubble = baseBubble();
        bubble.put("header", vertical("#DDEBFF", List.of(
                text(title, "xl", "bold", "#2E6FC4"),
                text(item, "md", "regular", "#526D82")
        )));
        bubble.put("body", vertical("#FAFCFF", List.of(
                text("変更内容を送ってね", "md", "bold", "#334E68"),
                text(example, "sm", "regular", "#526D82"),
                button("← 一覧へ戻る", back, "#8E9CB3")
        )));
        return bubble;
    }

    private Map<String, Object> baseBubble() {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");
        return bubble;
    }

    private Map<String, Object> vertical(String background, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "vertical");
        box.put("backgroundColor", background);
        box.put("paddingAll", "16px");
        box.put("spacing", "md");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "horizontal");
        box.put("spacing", "sm");
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", value);
        text.put("size", size);
        text.put("weight", weight);
        text.put("color", color);
        text.put("wrap", true);
        return text;
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

    private Integer extractNumber(String input) {
        Matcher matcher = NUMBER.matcher(input);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        sendReply(replyToken, List.of(flex));
    }

    private void replyText(String replyToken, String value) throws Exception {
        sendReply(replyToken, List.of(Map.of("type", "text", "text", value)));
    }

    private void sendReply(String replyToken, List<Map<String, Object>> messages) throws Exception {
        String json = mapper.writeValueAsString(Map.of("replyToken", replyToken, "messages", messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.line.me/v2/bot/message/reply"))
                .header("Authorization", "Bearer " + props.channelAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> result = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (result.statusCode() / 100 != 2) throw new IllegalStateException("LINE API error: " + result.statusCode());
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

    private record MemoRow(long id, String content, boolean favorite, String tags) {}
    private record TaskRow(long id, String title, String priority, Timestamp dueAt, boolean overdue) {}

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
