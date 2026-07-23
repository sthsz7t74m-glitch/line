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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 39)
public class CompactGrowthCalendarUiFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "カレンダー", "週間カレンダー",
            "プロフィール", "ステータス", "レベル", "経験値", "称号",
            "実績", "実績一覧", "バッジ"
    );
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final LineWebhookSupport webhook;
    private final RpgService rpgService;
    private final JdbcTemplate jdbc;

    public CompactGrowthCalendarUiFilter(LineWebhookSupport webhook,
                                         RpgService rpgService,
                                         JdbcTemplate jdbc) {
        this.webhook = webhook;
        this.rpgService = rpgService;
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
                if (!COMMANDS.contains(input)) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                Map<String, Object> bubble;
                String altText;
                if (input.contains("カレンダー")) {
                    bubble = calendarBubble(event.userId());
                    altText = "週間カレンダー";
                } else if (Set.of("実績", "実績一覧", "バッジ").contains(input)) {
                    bubble = achievementsBubble(rpgService.handle(event.userId(), input));
                    altText = "実績一覧";
                } else {
                    bubble = profileBubble(rpgService.handle(event.userId(), input));
                    altText = "プロフィール";
                }

                Map<String, Object> flex = new LinkedHashMap<>(FlexUi.flexMessage(altText, bubble));
                flex.put("quickReply", Map.of("items", List.of(
                        quick("🏠 ホーム", "ホーム"),
                        quick("操作メニュー", "操作メニュー")
                )));
                webhook.reply(event.replyToken(), List.of(flex));
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

    private Map<String, Object> calendarBubble(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(7).atStartOfDay();
        List<CalendarEvent> rows = jdbc.query("""
                select title, starts_at
                from schedules
                where line_user_id=? and starts_at>=? and starts_at<?
                order by starts_at
                """, (rs, i) -> new CalendarEvent(
                rs.getString("title"),
                rs.getTimestamp("starts_at").toInstant().atZone(TOKYO).toLocalDateTime()
        ), userId, Timestamp.from(start.atZone(TOKYO).toInstant()), Timestamp.from(end.atZone(TOKYO).toInstant()));

        List<Map<String, Object>> days = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = today.plusDays(offset);
            List<String> events = rows.stream()
                    .filter(row -> row.at().toLocalDate().equals(date))
                    .map(row -> row.at().format(DateTimeFormatter.ofPattern("H:mm")) + "　" + row.title())
                    .toList();
            if (!events.isEmpty()) {
                String dateLabel = date.format(DateTimeFormatter.ofPattern("M/d")) + "（"
                        + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPANESE) + "）";
                days.add(dayCard(dateLabel, events));
            }
        }
        if (days.isEmpty()) days.add(text("今後7日間の予定はまだないよ", "md", "regular", "#526D82"));

        List<Map<String, Object>> body = new ArrayList<>(days);
        body.add(horizontal(List.of(
                button("予定一覧", "予定一覧", "#4E85D1"),
                button("予定追加", "予定追加", "#76B4E3")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));
        return bubble("週間カレンダー", "今日から7日間を表示", "#2E6FC4", "#E5EFFF", body);
    }

    private Map<String, Object> dayCard(String date, List<String> events) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(text(date, "md", "bold", "#2E6FC4"));
        for (String event : events) content.add(text(event, "sm", "regular", "#334E68"));
        return groupBox("#F5F8FD", "10px", content);
    }

    private Map<String, Object> profileBubble(String raw) {
        List<String> lines = cleanLines(raw);
        String level = findStartsWith(lines, "レベル ", "レベル 1");
        String title = findStartsWith(lines, "称号", "称号「はじめての冒険者」");
        String totalExp = findStartsWith(lines, "累計経験値", "累計経験値 0");
        String progress = lines.stream().filter(v -> v.contains("■") || v.contains("□")).findFirst().orElse("");
        String remaining = findStartsWith(lines, "次のレベルまで", "");
        String unlocked = findStartsWith(lines, "解除した実績", "");

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(groupBox("#F4EEFF", "14px", List.of(
                text(level, "xxl", "bold", "#7957C7"),
                text(title, "md", "bold", "#4D426B"),
                text(totalExp, "sm", "regular", "#6D6287")
        )));
        if (!progress.isBlank()) {
            List<Map<String, Object>> block = new ArrayList<>();
            block.add(text(progress, "sm", "bold", "#7957C7"));
            if (!remaining.isBlank()) block.add(text(remaining, "xs", "regular", "#6D6287"));
            body.add(groupBox("#FAF8FF", "10px", block));
        }
        if (!unlocked.isBlank()) body.add(text(unlocked, "sm", "bold", "#5D4A89"));

        List<Map<String, Object>> stats = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("タスク完了") || line.startsWith("買い物完了")
                    || line.startsWith("登録した予定") || line.startsWith("保存中のメモ")) {
                stats.add(text(line, "xs", "regular", "#526D82"));
            }
        }
        if (!stats.isEmpty()) body.add(groupBox("#F5F7FB", "10px", stats));
        body.add(horizontal(List.of(
                button("実績", "実績一覧", "#9A7BD0"),
                button("ミッション", "今日のミッション", "#7957C7")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));
        return bubble("プロフィール", "成長状況", "#7957C7", "#EFE7FF", body);
    }

    private Map<String, Object> achievementsBubble(String raw) {
        List<String> lines = cleanLines(raw);
        String summary = lines.stream().filter(v -> v.startsWith("実績一覧")).findFirst().orElse("実績一覧");
        List<Map<String, Object>> unlocked = new ArrayList<>();
        List<Map<String, Object>> locked = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("【解除】") && !line.startsWith("【未解除】")) continue;
            boolean done = line.startsWith("【解除】");
            String name = line.replace("【解除】", "").replace("【未解除】", "").strip();
            String description = i + 1 < lines.size() ? lines.get(i + 1) : "";
            Map<String, Object> card = achievementCard(name, description, done);
            if (done) unlocked.add(card); else locked.add(card);
        }

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(groupBox("#F4EEFF", "12px", List.of(
                text(summary, "xl", "bold", "#7957C7"),
                text("解除済みを上に表示", "xs", "regular", "#6D6287")
        )));
        if (!unlocked.isEmpty()) {
            body.add(text("解除済み", "sm", "bold", "#5B9E76"));
            body.addAll(unlocked);
        }
        if (!locked.isEmpty()) {
            body.add(text("未解除", "sm", "bold", "#8A96A6"));
            body.addAll(locked);
        }
        if (unlocked.isEmpty() && locked.isEmpty()) body.add(text("実績情報はまだないよ", "md", "regular", "#526D82"));
        body.add(horizontal(List.of(
                button("プロフィール", "プロフィール", "#7062AD"),
                button("ミッション", "今日のミッション", "#7957C7")
        )));
        body.add(button("🏠 ホーム", "ホーム", "#8592A6"));
        return bubble("実績", "解除状況", "#7957C7", "#EFE7FF", body);
    }

    private Map<String, Object> achievementCard(String name, String description, boolean unlocked) {
        return groupBox(unlocked ? "#EEF8F2" : "#F5F6F8", "10px", List.of(
                text((unlocked ? "✓ " : "○ ") + name, "sm", "bold", unlocked ? "#3E805A" : "#7A8798"),
                text(description, "xxs", "regular", unlocked ? "#5D7767" : "#98A1AE")
        ));
    }

    private Map<String, Object> bubble(String title, String subtitle, String accent,
                                       String headerColor, List<Map<String, Object>> body) {
        return FlexUi.bubble(
                FlexUi.vertical(headerColor, "12px", "xs", List.of(
                        text(title, "xl", "bold", accent),
                        text(subtitle, "xxs", "regular", "#6A788B")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
    }

    private Map<String, Object> groupBox(String background, String padding, List<Map<String, Object>> contents) {
        Map<String, Object> box = new LinkedHashMap<>(FlexUi.vertical(background, padding, "xs", contents));
        box.put("cornerRadius", "12px");
        return box;
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        return FlexUi.horizontal(contents);
    }

    private Map<String, Object> text(String value, String size, String weight, String color) {
        return FlexUi.text(value, size, weight, color);
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>(FlexUi.button(label, message, color));
        button.put("flex", 1);
        return button;
    }

    private Map<String, Object> quick(String label, String message) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", message
        ));
    }

    private List<String> cleanLines(String raw) {
        return raw == null ? List.of() : raw.lines().map(String::strip).filter(v -> !v.isBlank()).toList();
    }

    private String findStartsWith(List<String> lines, String prefix, String fallback) {
        return lines.stream().filter(v -> v.startsWith(prefix)).findFirst().orElse(fallback);
    }

    private record CalendarEvent(String title, LocalDateTime at) { }
}