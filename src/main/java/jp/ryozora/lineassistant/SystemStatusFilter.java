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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class SystemStatusFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of("通知履歴", "通知の履歴", "診断", "システム診断", "状態確認");
    private static final ZoneOffset TOKYO = ZoneOffset.ofHours(9);

    private final LineWebhookSupport webhook;
    private final NotificationHistoryStore history;
    private final JdbcTemplate jdbc;

    public SystemStatusFilter(LineWebhookSupport webhook, NotificationHistoryStore history, JdbcTemplate jdbc) {
        this.webhook = webhook;
        this.history = history;
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
                if (!COMMANDS.contains(event.text())) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                Map<String, Object> message = event.text().contains("診断") || event.text().equals("状態確認")
                        ? diagnosticsMessage(event.userId()) : historyMessage(event.userId());
                webhook.reply(event.replyToken(), List.of(message));
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

    private Map<String, Object> historyMessage(String userId) {
        List<NotificationHistoryStore.Entry> entries = history.list(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        if (entries.isEmpty()) {
            body.add(FlexUi.card("#F7F9FC", "12px", "xs", List.of(
                    FlexUi.text("通知履歴はまだないよ", "md", "bold", "#526D82"),
                    FlexUi.text("雨・予定・タスク・習慣などの通知が届くと、ここに直近20件を保存するよ", "xxs", "regular", "#8A96A6")
            )));
        } else {
            entries.stream().limit(10).forEach(entry -> body.add(FlexUi.card("#F5F8FD", "10px", "xs", List.of(
                    FlexUi.text(entry.type(), "sm", "bold", "#334E68"),
                    FlexUi.text(entry.summary(), "xs", "regular", "#526D82"),
                    FlexUi.text(entry.sentAt().format(DateTimeFormatter.ofPattern("M/d H:mm")), "xxs", "regular", "#8A96A6")
            ))));
            if (entries.size() > 10) {
                body.add(FlexUi.text("ほか" + (entries.size() - 10) + "件を保存中", "xxs", "regular", "#8A96A6", "center"));
            }
        }
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("通知設定", "通知設定", "#7898CF"),
                FlexUi.button("診断", "診断", "#667EA8")
        )));
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8793A5"));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#E8F1FA", "14px", "xs", List.of(
                        FlexUi.text("通知履歴", "xl", "bold", "#456B91"),
                        FlexUi.text(entries.size() + "件保存中・再起動時にリセット", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage("通知履歴", bubble);
    }

    private Map<String, Object> diagnosticsMessage(String userId) {
        boolean databaseOk;
        try {
            Integer value = jdbc.queryForObject("select 1", Integer.class);
            databaseOk = value != null && value == 1;
        } catch (RuntimeException e) {
            databaseOk = false;
        }
        String now = OffsetDateTime.now(TOKYO).format(DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"));
        List<Map<String, Object>> statuses = List.of(
                status("アプリ", "ベンリー 0.11.1", true),
                status("データベース", databaseOk ? "接続OK" : "接続エラー", databaseOk),
                status("通知履歴", history.count(userId) + "件", true),
                status("共通UI", "Flexカード有効", true),
                status("確認時刻", now, true)
        );

        List<Map<String, Object>> body = new ArrayList<>(statuses);
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("通知履歴", "通知履歴", "#667EA8"),
                FlexUi.button("操作メニュー", "操作メニュー", "#7B8798")
        )));
        body.add(FlexUi.button("🏠 ホーム", "ホーム", "#8793A5"));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical(databaseOk ? "#E7F7F1" : "#FFE8EB", "14px", "xs", List.of(
                        FlexUi.text("システム診断", "xl", "bold", databaseOk ? "#2E9B6B" : "#A33A45"),
                        FlexUi.text(databaseOk ? "主要機能は正常" : "確認が必要な項目があるよ", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage("システム診断", bubble);
    }

    private Map<String, Object> status(String label, String value, boolean ok) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "box");
        row.put("layout", "horizontal");
        row.put("spacing", "sm");
        row.put("paddingAll", "10px");
        row.put("cornerRadius", "10px");
        row.put("backgroundColor", ok ? "#F4F9F6" : "#FFF3F4");
        row.put("contents", List.of(
                FlexUi.text(label, "sm", "bold", "#526D82"),
                FlexUi.text(value, "sm", "regular", ok ? "#3E805A" : "#B54752", "end")
        ));
        return row;
    }
}
