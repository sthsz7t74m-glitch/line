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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    private final JdbcTemplate jdbc;
    private final LineWebhookSupport line;

    public ScheduleManagementFilter(JdbcTemplate jdbc, LineWebhookSupport line) {
        this.jdbc = jdbc;
        this.line = line;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
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
        List<Map<String, Object>> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(FlexUi.text("予定を追加してみよう", "md", "regular", "#526D82"));
            body.add(FlexUi.button("予定を追加", "予定追加", "#8DCAF1"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                ScheduleRow row = rows.get(i);
                String date = row.at().atZoneSameInstant(TOKYO)
                        .format(DateTimeFormatter.ofPattern("M/d（E） HH:mm", Locale.JAPANESE));
                String title = (i + 1) + ". " + row.title();
                if (row.recurrence() != null && !row.recurrence().isBlank()) {
                    title += "  ［" + row.recurrence() + "］";
                }
                body.add(FlexUi.card("#F7FAFF", "16px", "md", List.of(
                        FlexUi.text(title, "md", "bold", "#243B53"),
                        FlexUi.text(date, "sm", "regular", "#526D82"),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("変更", "予定変更案内 " + (i + 1), "#6CA6E5"),
                                FlexUi.button("削除", "予定削除選択 " + (i + 1), "#D96C75")
                        ))
                )));
            }
            body.add(FlexUi.button("予定をすべて削除", "予定全削除確認", "#B94A55"));
        }
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8E9CB3"));
        return FlexUi.bubble(
                FlexUi.vertical("#DDEBFF", "16px", "md", List.of(
                        FlexUi.text("今後の予定", "xl", "bold", "#2E6FC4"),
                        FlexUi.text(rows.isEmpty() ? "予定はまだないよ" : rows.size() + "件を表示中",
                                "sm", "regular", "#526D82")
                )),
                FlexUi.vertical("#FAFCFF", "16px", "md", body)
        );
    }

    private Map<String, Object> deleteChoiceBubble(int number, ScheduleRow row) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.button("この予定だけ削除", "予定削除実行 " + number, "#D96C75"));
        if (row.seriesId() != null && !row.seriesId().isBlank()) {
            body.add(FlexUi.button("この回以降を削除", "予定削除実行 " + number + " この回以降", "#C95C66"));
            body.add(FlexUi.button("繰り返し全部を削除", "予定削除実行 " + number + " シリーズ全部", "#B94A55"));
        }
        body.add(FlexUi.button("やめる", "予定一覧", "#8E9CB3"));
        return FlexUi.bubble(
                FlexUi.vertical("#FFE8EB", "16px", "md", List.of(
                        FlexUi.text("この予定を削除する？", "xl", "bold", "#A33A45"),
                        FlexUi.text(row.title(), "md", "regular", "#6D3B42")
                )),
                FlexUi.vertical("#FFF9FA", "16px", "md", body)
        );
    }

    private Map<String, Object> deleteAllConfirmBubble() {
        return FlexUi.bubble(
                FlexUi.vertical("#FFE8EB", "16px", "md", List.of(
                        FlexUi.text("今後の予定を全部削除？", "xl", "bold", "#A33A45"),
                        FlexUi.text("この操作は元に戻せないよ", "sm", "regular", "#6D3B42")
                )),
                FlexUi.vertical("#FFF9FA", "16px", "md", List.of(
                        FlexUi.button("全部削除する", "予定全削除実行", "#B94A55"),
                        FlexUi.button("やめる", "予定一覧", "#8E9CB3")
                ))
        );
    }

    private Map<String, Object> changeGuideBubble(int number, ScheduleRow row) {
        return FlexUi.bubble(
                FlexUi.vertical("#DDEBFF", "16px", "md", List.of(
                        FlexUi.text("予定を変更", "xl", "bold", "#2E6FC4"),
                        FlexUi.text(row.title(), "md", "regular", "#526D82")
                )),
                FlexUi.vertical("#FAFCFF", "16px", "md", List.of(
                        FlexUi.text("変更内容をそのまま送ってね", "md", "bold", "#334E68"),
                        FlexUi.text("例：予定変更 " + number + " 2026-08-01 20:00 会議", "sm", "regular", "#526D82"),
                        FlexUi.button("入力を始める", "予定変更 " + number + " ", "#6CA6E5"),
                        FlexUi.button("予定一覧へ戻る", "予定一覧", "#8E9CB3")
                ))
        );
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

    private void replyText(String replyToken, String text) throws Exception {
        line.reply(replyToken, List.of(Map.of("type", "text", "text", text)));
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        line.reply(replyToken, List.of(FlexUi.flexMessage(altText, bubble)));
    }

    private record ScheduleRow(long id, String title, OffsetDateTime at, String seriesId, String recurrence) {}
}
