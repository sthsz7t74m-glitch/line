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
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class GlobalSearchFilter extends OncePerRequestFilter {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final int PER_TYPE = 4;

    private final LineWebhookSupport webhook;
    private final JdbcTemplate jdbc;

    public GlobalSearchFilter(LineWebhookSupport webhook, JdbcTemplate jdbc) {
        this.webhook = webhook;
        this.jdbc = jdbc;
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
            for (LineWebhookSupport.TextEvent event : webhook.textEvents(body)) {
                String input = event.text();
                if (!input.equals("検索") && !input.startsWith("検索 ")
                        && !input.equals("全体検索") && !input.startsWith("全体検索 ")) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String keyword = input.startsWith("全体検索")
                        ? input.substring("全体検索".length()).strip()
                        : input.substring("検索".length()).strip();
                webhook.reply(event.replyToken(), List.of(keyword.isBlank()
                        ? guideMessage() : resultMessage(event.userId(), keyword)));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            try {
                chain.doFilter(wrapped, response);
            } catch (Exception fallback) {
                if (fallback instanceof IOException io) throw io;
                throw new IOException(fallback);
            }
        }
    }

    private Map<String, Object> resultMessage(String userId, String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";
        List<Result> results = new ArrayList<>();

        jdbc.query("""
                select content from memos
                where line_user_id=? and archived=false
                  and (lower(content) like ? or lower(coalesce(tags,'')) like ?)
                order by favorite desc,created_at desc limit ?
                """, rs -> results.add(new Result("メモ", rs.getString("content"), "#D989AD", "メモ一覧")),
                userId, pattern, pattern, PER_TYPE);

        jdbc.query("""
                select title,due_at from tasks
                where line_user_id=? and completed=false and lower(title) like ?
                order by due_at nulls last,created_at limit ?
                """, rs -> {
            Timestamp due = rs.getTimestamp("due_at");
            String detail = rs.getString("title");
            if (due != null) detail += "　期限 " + due.toInstant().atZone(TOKYO)
                    .format(DateTimeFormatter.ofPattern("M/d H:mm"));
            results.add(new Result("タスク", detail, "#2E9B6B", "タスク一覧"));
        }, userId, pattern, PER_TYPE);

        jdbc.query("""
                select name from shopping_items
                where line_user_id=? and purchased=false and lower(name) like ?
                order by created_at limit ?
                """, rs -> results.add(new Result("買い物", rs.getString("name"), "#D89A34", "買い物一覧")),
                userId, pattern, PER_TYPE);

        jdbc.query("""
                select title,starts_at from schedules
                where line_user_id=? and lower(title) like ?
                order by starts_at desc limit ?
                """, rs -> {
            Timestamp at = rs.getTimestamp("starts_at");
            String detail = rs.getString("title");
            if (at != null) detail += "　" + at.toInstant().atZone(TOKYO)
                    .format(DateTimeFormatter.ofPattern("M/d H:mm"));
            results.add(new Result("予定", detail, "#4F7FC7", "予定一覧"));
        }, userId, pattern, PER_TYPE);

        jdbc.query("""
                select description,amount,spent_on from expenses
                where line_user_id=? and (lower(description) like ? or lower(category) like ?)
                order by spent_on desc,id desc limit ?
                """, rs -> results.add(new Result("支出",
                        rs.getString("description") + "　" + String.format("%,d円", rs.getInt("amount"))
                                + "　" + rs.getDate("spent_on").toLocalDate().format(DateTimeFormatter.ofPattern("M/d")),
                        "#C68A2B", "支出一覧")), userId, pattern, pattern, PER_TYPE);

        List<Map<String, Object>> body = new ArrayList<>();
        if (results.isEmpty()) {
            body.add(FlexUi.card("#F7F9FC", "12px", "xs", List.of(
                    FlexUi.text("「" + keyword + "」は見つからなかったよ", "md", "bold", "#526D82"),
                    FlexUi.text("表記を短くしたり、別の単語でも試してみてね", "xxs", "regular", "#8A96A6")
            )));
        } else {
            for (Result result : results.stream().limit(15).toList()) {
                body.add(FlexUi.card("#F7F9FC", "10px", "xs", List.of(
                        FlexUi.text(result.type(), "xs", "bold", result.accent()),
                        FlexUi.text(result.detail(), "sm", "regular", "#334E68"),
                        FlexUi.button("一覧を開く", result.listCommand(), result.accent())
                )));
            }
            if (results.size() > 15) {
                body.add(FlexUi.text("ほか" + (results.size() - 15) + "件", "xxs", "regular", "#8A96A6", "center"));
            }
        }
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("もう一度検索", "検索 ", "#667EA8"),
                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
        )));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#EEF2F7", "14px", "xs", List.of(
                        FlexUi.text("全体検索", "xl", "bold", "#526D82"),
                        FlexUi.text("「" + keyword + "」・" + results.size() + "件", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage("検索結果：" + keyword, bubble);
    }

    private Map<String, Object> guideMessage() {
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#EEF2F7", "14px", "xs", List.of(
                        FlexUi.text("全体検索", "xl", "bold", "#526D82"),
                        FlexUi.text("探したい単語を続けて送ってね", "sm", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F7F9FC", "10px", "xs", List.of(
                                FlexUi.text("検索 牛乳", "sm", "bold", "#334E68"),
                                FlexUi.text("検索 病院", "sm", "bold", "#334E68"),
                                FlexUi.text("検索 会議", "sm", "bold", "#334E68")
                        )),
                        FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                ))
        );
        return FlexUi.flexMessage("全体検索の使い方", bubble);
    }

    private record Result(String type, String detail, String accent, String listCommand) { }
}
