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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PagedRecordUiFilter extends OncePerRequestFilter {
    private static final int PAGE_SIZE = 5;
    private static final Pattern PAGE = Pattern.compile("^(メモ一覧|タスク一覧)(?:\\s+(\\d+))?$");
    private static final Pattern DETAIL = Pattern.compile("^(メモ詳細|タスク詳細)\\s+(\\d+)$");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final JdbcTemplate jdbc;
    private final LineWebhookSupport webhook;

    public PagedRecordUiFilter(JdbcTemplate jdbc, LineWebhookSupport webhook) {
        this.jdbc = jdbc;
        this.webhook = webhook;
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
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        try {
            for (LineWebhookSupport.TextEvent event : webhook.textEvents(body)) {
                Matcher pageMatcher = PAGE.matcher(event.text());
                Matcher detailMatcher = DETAIL.matcher(event.text());
                if (!pageMatcher.matches() && !detailMatcher.matches()) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                Map<String, Object> bubble;
                String altText;
                if (pageMatcher.matches()) {
                    int page = pageMatcher.group(2) == null ? 1 : Math.max(1, Integer.parseInt(pageMatcher.group(2)));
                    if (pageMatcher.group(1).startsWith("メモ")) {
                        bubble = memoListBubble(event.userId(), page);
                        altText = "メモ一覧";
                    } else {
                        bubble = taskListBubble(event.userId(), page);
                        altText = "タスク一覧";
                    }
                } else {
                    int number = Integer.parseInt(detailMatcher.group(2));
                    if (detailMatcher.group(1).startsWith("メモ")) {
                        bubble = memoDetailBubble(event.userId(), number);
                        altText = "メモ詳細";
                    } else {
                        bubble = taskDetailBubble(event.userId(), number);
                        altText = "タスク詳細";
                    }
                }

                Map<String, Object> flex = new LinkedHashMap<>(FlexUi.flexMessage(altText, bubble));
                flex.put("quickReply", Map.of("items", List.of(
                        FlexUi.quick("🏠 ホーム", "ホーム"),
                        FlexUi.quick("メモ", "メモ一覧"),
                        FlexUi.quick("タスク", "タスク一覧")
                )));
                webhook.reply(event.replyToken(), List.of(flex));
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
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(text(row.title(), "md", "bold", "#243B53"));
        content.add(text("優先度：" + priorityName(row.priority()), "xxs", "regular", "#718096"));
        String due = due(row.dueAt());
        if (!due.isBlank()) content.add(text(due, "xxs", "regular", row.overdue() ? "#B94A55" : "#718096"));

        List<Map<String, Object>> body = new ArrayList<>();
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
        return FlexUi.bubble(
                FlexUi.vertical(headerColor, "12px", "xs", List.of(
                        text(title, "lg", "bold", accent),
                        text(subtitle, "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
    }

    private Map<String, Object> group(String background, List<Map<String, Object>> contents) {
        return FlexUi.card(background, "10px", "xs", contents);
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        return FlexUi.horizontal(contents);
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        Map<String, Object> text = new LinkedHashMap<>(FlexUi.text(value, size, weight, color));
        text.put("flex", 1);
        return text;
    }

    private Map<String, Object> primary(String label, String message, String color) {
        return styledButton(label, message, "primary", color);
    }

    private Map<String, Object> secondary(String label, String message) {
        return styledButton(label, message, "secondary", null);
    }

    private Map<String, Object> danger(String label, String message) {
        return styledButton(label, message, "primary", "#C95C66");
    }

    private Map<String, Object> styledButton(String label, String message, String style, String color) {
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
        String label = dueAt.atZoneSameInstant(TOKYO)
                .format(DateTimeFormatter.ofPattern("M/d（E）H:mm", java.util.Locale.JAPANESE));
        return dueAt.toInstant().isBefore(java.time.Instant.now()) ? "期限切れ：" + label : "期限：" + label;
    }

    private record MemoRow(long id, String content, boolean favorite, String tags) { }
    private record TaskRow(long id, String title, String priority,
                           java.time.OffsetDateTime dueAt, boolean overdue) { }
}
