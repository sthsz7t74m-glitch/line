package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class UnifiedResultUiFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "今日の天気", "明日の天気", "天気", "傘いる？", "傘いる", "雨降る？", "雨降る", "洗濯できる？", "洗濯できる",
            "カレンダー", "週間カレンダー", "今日のダッシュボード",
            "家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出", "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ",
            "今日の習慣", "習慣一覧", "習慣統計", "習慣の記録",
            "プロフィール", "ステータス", "レベル", "経験値", "称号", "実績", "実績一覧", "バッジ",
            "自分のデータ", "統計", "今日のミッション", "今週ランキング"
    );

    private final LineWebhookSupport webhook;
    private final WeatherCommandService weatherCommandService;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final RpgService rpgService;
    private final BenlyCommandService commandService;

    public UnifiedResultUiFilter(LineWebhookSupport webhook,
                                 WeatherCommandService weatherCommandService,
                                 ExpenseService expenseService,
                                 HabitService habitService,
                                 RpgService rpgService,
                                 BenlyCommandService commandService) {
        this.webhook = webhook;
        this.weatherCommandService = weatherCommandService;
        this.expenseService = expenseService;
        this.habitService = habitService;
        this.rpgService = rpgService;
        this.commandService = commandService;
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

                String result = resultFor(event.userId(), input);
                if (result == null || result.startsWith("受け取ったよ：")) continue;

                Style style = styleFor(input);
                Map<String, Object> bubble = weatherCommandService.isWeatherCommand(input)
                        ? weatherBubble(style, result)
                        : resultBubble(style, result);
                Map<String, Object> flex = new LinkedHashMap<>(FlexUi.flexMessage(style.title(), bubble));
                flex.put("quickReply", quickReply(style));
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

    private String resultFor(String userId, String input) {
        if (weatherCommandService.isWeatherCommand(input)) return weatherCommandService.handle(userId, input);
        if (expenseService.supports(input)) return expenseService.handle(userId, input);
        if (habitService.supports(input)) return habitService.handle(userId, input);
        if (rpgService.supports(input)) return rpgService.handle(userId, input);
        return commandService.handle(userId, input);
    }

    private Style styleFor(String input) {
        if (weatherCommandService.isWeatherCommand(input)) {
            return new Style("天気", "", "#3B82C4", "#E5F3FF", "予定メニュー");
        }
        if (Set.of("家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出", "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ").contains(input)) {
            return new Style("お金・家計簿", "支出と集計", "#D88916", "#FFF2DE", "お金メニュー");
        }
        if (Set.of("今日の習慣", "習慣一覧", "習慣統計", "習慣の記録").contains(input)) {
            return new Style("習慣・成長", "今日の状況と記録", "#5B9E76", "#E8F7EF", "成長メニュー");
        }
        if (Set.of("プロフィール", "ステータス", "レベル", "経験値", "称号", "実績", "実績一覧", "バッジ", "今日のミッション", "今週ランキング").contains(input)) {
            return new Style("ベンリー成長記録", "プロフィール・実績", "#7957C7", "#EFE7FF", "成長メニュー");
        }
        if (Set.of("カレンダー", "週間カレンダー", "今日のダッシュボード").contains(input)) {
            return new Style("予定・カレンダー", "スケジュールを確認", "#2E6FC4", "#E5EFFF", "予定メニュー");
        }
        return new Style("ベンリー", "情報をまとめて表示", "#526D82", "#F1F5F9", "ホーム");
    }

    private Map<String, Object> weatherBubble(Style style, String raw) {
        List<String> lines = raw.lines().map(String::strip).filter(v -> !v.isBlank()).toList();
        String title = firstMatching(lines, v -> v.contains("今日の天気") || v.contains("明日の天気"), "天気");
        String area = valueAfter(lines, "対象地域：", "");
        String condition = firstMatching(lines, v -> !v.contains("天気") && !v.contains("対象地域")
                && (v.contains("晴") || v.contains("くもり") || v.contains("雨") || v.contains("雪")), "");
        String temperatures = firstMatching(lines, v -> v.startsWith("最高 "), "");
        String current = firstMatching(lines, v -> v.startsWith("現在 "), "");
        String rainProbability = firstMatching(lines, v -> v.contains("降水確率"), "");
        String rainTiming = firstMatching(lines, v -> v.startsWith("雨は "), "");
        String advice = firstMatching(lines, v -> v.startsWith("🥵") || v.startsWith("👕") || v.startsWith("🧥")
                || v.startsWith("🌂") || v.startsWith("🧺") || v.startsWith("✨"), "");
        String source = valueAfter(lines, "取得元：", "Open-Meteo");
        String updated = valueAfter(lines, "取得時刻：", "");

        List<Map<String, Object>> headerItems = new ArrayList<>();
        headerItems.add(text(title, "xl", "bold", style.accent(), "start"));
        if (!area.isBlank()) headerItems.add(text(area, "xs", "regular", "#617184", "start"));

        List<Map<String, Object>> contents = new ArrayList<>();
        List<Map<String, Object>> main = new ArrayList<>();
        if (!condition.isBlank()) main.add(text(condition, "lg", "bold", "#243B53", "start"));
        if (!current.isBlank()) main.add(text(current, "md", "bold", "#334E68", "start"));
        if (!temperatures.isBlank()) main.add(text(temperatures, "sm", "regular", "#526D82", "start"));
        contents.add(groupBox("#F4F8FD", main));

        List<Map<String, Object>> rain = new ArrayList<>();
        if (!rainTiming.isBlank()) rain.add(text(rainTiming, "md", "bold", "#2E6FC4", "start"));
        if (!rainProbability.isBlank()) rain.add(text(rainProbability, "sm", "regular", "#526D82", "start"));
        if (!rain.isEmpty()) contents.add(groupBox("#EEF6FF", rain));
        if (!advice.isBlank()) contents.add(groupBox("#FFF8E8", List.of(
                text(advice, "sm", "bold", "#72551E", "start")
        )));

        contents.add(text("取得元：" + source + (updated.isBlank() ? "" : "　更新：" + updated),
                "xxs", "regular", "#8A96A6", "start"));
        contents.add(horizontal(List.of(
                button("関連", style.backMessage(), style.accent()),
                button("🏠 ホーム", "ホーム", "#8592A6")
        )));

        return FlexUi.bubble(
                vertical(style.headerBackground(), "12px", "sm", headerItems),
                vertical("#FCFDFE", "12px", "sm", contents)
        );
    }

    private Map<String, Object> resultBubble(Style style, String raw) {
        List<String> lines = raw.lines().map(String::stripTrailing).toList();
        String first = lines.stream().filter(v -> !v.isBlank() && !isDivider(v)).findFirst().orElse(style.title());

        List<Map<String, Object>> contents = new ArrayList<>();
        boolean skippedFirst = false;
        int visible = 0;
        for (String line : lines) {
            String value = line.strip();
            if (value.isBlank()) {
                if (!contents.isEmpty()) contents.add(FlexUi.separator());
                continue;
            }
            if (!skippedFirst && value.equals(first)) {
                skippedFirst = true;
                continue;
            }
            if (isDivider(value)) {
                if (!contents.isEmpty()) contents.add(FlexUi.separator());
                continue;
            }
            if (visible >= 35) {
                contents.add(text("ほかの情報は関連画面から確認してね", "xs", "regular", "#7A8798", "start"));
                break;
            }
            contents.add(lineCard(value));
            visible++;
        }
        if (contents.isEmpty()) contents.add(text("表示できる情報はまだないよ", "md", "regular", "#526D82", "center"));
        contents.add(button("関連メニュー", style.backMessage(), style.accent()));
        contents.add(button("🏠 ホーム", "ホーム", "#8592A6"));

        return FlexUi.bubble(
                vertical(style.headerBackground(), "14px", "md", List.of(
                        text(first, "xl", "bold", style.accent(), "start"),
                        text(style.subtitle(), "xs", "regular", "#617184", "start")
                )),
                vertical("#FCFDFE", "16px", "md", contents)
        );
    }

    private Map<String, Object> lineCard(String value) {
        String color = value.contains("期限切れ") || value.contains("削除") || value.contains("注意")
                ? "#B54752" : "#334E68";
        String weight = value.startsWith("【") || value.startsWith("■") || value.startsWith("□")
                || value.matches("^\\d+[.．].*") ? "bold" : "regular";
        return groupBox("#F5F8FC", List.of(text(value, "sm", weight, color, "start")));
    }

    private Map<String, Object> groupBox(String background, List<Map<String, Object>> contents) {
        return FlexUi.card(background, "12px", "xs", contents.isEmpty()
                ? List.of(text("情報を取得できなかったよ", "sm", "regular", "#526D82", "start"))
                : contents);
    }

    private Map<String, Object> quickReply(Style style) {
        return Map.of("items", List.of(
                FlexUi.quick("🏠 ホーム", "ホーム"),
                FlexUi.quick("関連メニュー", style.backMessage()),
                FlexUi.quick("操作メニュー", "操作メニュー")
        ));
    }

    private Map<String, Object> vertical(String background, String padding, String spacing,
                                         List<Map<String, Object>> contents) {
        return FlexUi.vertical(background, padding, spacing, contents);
    }

    private Map<String, Object> horizontal(List<Map<String, Object>> contents) {
        return FlexUi.horizontal(contents);
    }

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        return FlexUi.text(value, size, weight, color, align);
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>(FlexUi.button(label, message, color));
        button.put("flex", 1);
        return button;
    }

    private String firstMatching(List<String> lines, java.util.function.Predicate<String> predicate, String fallback) {
        return lines.stream().filter(predicate).findFirst().orElse(fallback);
    }

    private String valueAfter(List<String> lines, String prefix, String fallback) {
        return lines.stream().filter(v -> v.startsWith(prefix)).findFirst()
                .map(v -> v.substring(prefix.length()).strip()).orElse(fallback);
    }

    private boolean isDivider(String value) {
        return value.matches("^[━─ー_=\\-]{3,}$");
    }

    private record Style(String title, String subtitle, String accent,
                         String headerBackground, String backMessage) {}
}
