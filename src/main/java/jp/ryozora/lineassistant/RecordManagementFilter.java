package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    private final JdbcTemplate jdbc;
    private final BenlyStore store;
    private final LineWebhookSupport line;

    public RecordManagementFilter(JdbcTemplate jdbc, BenlyStore store, LineWebhookSupport line) {
        this.jdbc = jdbc;
        this.store = store;
        this.line = line;
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
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        try {
            for (LineWebhookSupport.TextEvent event : line.textEvents(body)) {
                String input = event.text();
                if (!supports(input)) continue;
                if (!line.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                handle(event.replyToken(), event.userId(), input);
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
            body.add(FlexUi.text("メモはまだないよ", "md", "regular", "#526D82"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                MemoRow row = rows.get(i);
                String label = (row.favorite() ? "★ " : "") + (i + 1) + ". " + row.content();
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(FlexUi.text(label, "md", "bold", "#243B53"));
                if (row.tags() != null && !row.tags().isBlank()) {
                    card.add(FlexUi.text("#" + row.tags().replace(",", "  #"), "xs", "regular", "#7A6B89"));
                }
                card.add(FlexUi.horizontal(List.of(
                        FlexUi.button("編集", "メモ編集案内 " + (i + 1), "#6CA6E5"),
                        FlexUi.button(row.favorite() ? "★解除" : "★お気に入り", "メモお気に入り実行 " + (i + 1), "#D7A63E"),
                        FlexUi.button("削除", "メモ削除確認 " + (i + 1), "#D96C75")
                )));
                body.add(FlexUi.card("#F7FAFF", "16px", "md", card));
            }
            body.add(FlexUi.button("メモをすべて削除", "メモ全削除確認", "#B94A55"));
        }
        body.add(FlexUi.button("メモを追加", "メモ追加", "#E4A5BF"));
        body.add(FlexUi.button("← メモ・タスク", "記録メニュー", "#8E9CB3"));
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8E9CB3"));
        return listBubble("メモ一覧", rows.size() + "件を表示中", "#DDF5EE", "#2E9B6B", body);
    }

    private Map<String, Object> taskListBubble(String userId) {
        List<TaskRow> rows = taskRows(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(FlexUi.text("未完了タスクはゼロ！", "md", "regular", "#526D82"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                TaskRow row = rows.get(i);
                List<Map<String, Object>> card = new ArrayList<>();
                card.add(FlexUi.text((i + 1) + ". " + priorityLabel(row.priority()) + row.title(), "md", "bold", "#243B53"));
                card.add(FlexUi.text(dueLabel(row.dueAt()), "sm", "regular", row.overdue() ? "#B94A55" : "#526D82"));
                card.add(FlexUi.horizontal(List.of(
                        FlexUi.button("完了", "タスク完了確認 " + (i + 1), "#4AAE9E"),
                        FlexUi.button("延期", "タスク延期案内 " + (i + 1), "#6CA6E5"),
                        FlexUi.button("削除", "タスク削除確認 " + (i + 1), "#D96C75")
                )));
                body.add(FlexUi.card("#F7FAFF", "16px", "md", card));
            }
            body.add(FlexUi.button("未完了タスクをすべて削除", "タスク全削除確認", "#B94A55"));
        }
        body.add(FlexUi.button("タスクを追加", "タスク追加", "#71C9B7"));
        body.add(FlexUi.button("← メモ・タスク", "記録メニュー", "#8E9CB3"));
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8E9CB3"));
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
        return FlexUi.bubble(
                FlexUi.vertical(headerColor, "16px", "md", List.of(
                        FlexUi.text(title, "xl", "bold", accent),
                        FlexUi.text(subtitle, "sm", "regular", "#526D82")
                )),
                FlexUi.vertical("#FAFCFF", "16px", "md", body)
        );
    }

    private Map<String, Object> itemConfirmBubble(String title, String item, String actionLabel,
                                                   String actionMessage, String cancelMessage) {
        return FlexUi.bubble(
                FlexUi.vertical("#FFE8EB", "16px", "md", List.of(
                        FlexUi.text(title, "xl", "bold", "#A33A45"),
                        FlexUi.text(item, "md", "regular", "#6D3B42")
                )),
                FlexUi.vertical("#FFF9FA", "16px", "md", List.of(
                        FlexUi.button(actionLabel, actionMessage, "#B94A55"),
                        FlexUi.button("やめる", cancelMessage, "#8E9CB3")
                ))
        );
    }

    private Map<String, Object> clearConfirmBubble(String subject, String execute, String cancel) {
        return itemConfirmBubble(subject + "を全部削除する？", "この操作は元に戻せないよ",
                "全部削除する", execute, cancel);
    }

    private Map<String, Object> editGuideBubble(String title, String item, String example, String back) {
        return FlexUi.bubble(
                FlexUi.vertical("#DDEBFF", "16px", "md", List.of(
                        FlexUi.text(title, "xl", "bold", "#2E6FC4"),
                        FlexUi.text(item, "md", "regular", "#526D82")
                )),
                FlexUi.vertical("#FAFCFF", "16px", "md", List.of(
                        FlexUi.text("変更内容を送ってね", "md", "bold", "#334E68"),
                        FlexUi.text(example, "sm", "regular", "#526D82"),
                        FlexUi.button("← 一覧へ戻る", back, "#8E9CB3")
                ))
        );
    }

    private Integer extractNumber(String input) {
        Matcher matcher = NUMBER.matcher(input);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        line.reply(replyToken, List.of(FlexUi.flexMessage(altText, bubble)));
    }

    private void replyText(String replyToken, String value) throws Exception {
        line.reply(replyToken, List.of(Map.of("type", "text", "text", value)));
    }

    private record MemoRow(long id, String content, boolean favorite, String tags) {}
    private record TaskRow(long id, String title, String priority, Timestamp dueAt, boolean overdue) {}
}
