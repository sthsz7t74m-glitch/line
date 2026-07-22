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
import java.util.ArrayList;
import java.util.Base64;
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

    private final ObjectMapper mapper;
    private final LineProperties props;
    private final WeatherCommandService weatherCommandService;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final RpgService rpgService;
    private final BenlyCommandService commandService;
    private final HttpClient client = HttpClient.newHttpClient();

    public UnifiedResultUiFilter(ObjectMapper mapper,
                                 LineProperties props,
                                 WeatherCommandService weatherCommandService,
                                 ExpenseService expenseService,
                                 HabitService habitService,
                                 RpgService rpgService,
                                 BenlyCommandService commandService) {
        this.mapper = mapper;
        this.props = props;
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
        CachedRequest wrapped = new CachedRequest(request, body);

        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode event : root.path("events")) {
                if (!"message".equals(event.path("type").asText())) continue;
                if (!"text".equals(event.path("message").path("type").asText())) continue;

                String input = normalize(event.path("message").path("text").asText());
                if (!COMMANDS.contains(input)) continue;
                if (!validSignature(body, request.getHeader("x-line-signature"))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String userId = event.path("source").path("userId").asText();
                if (!allowed(userId)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String result = resultFor(userId, input);
                if (result == null || result.startsWith("受け取ったよ：")) continue;

                Style style = styleFor(input);
                Map<String, Object> bubble = resultBubble(style, result);
                replyFlex(event.path("replyToken").asText(), style.title(), bubble, quickReply(style));
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
        if (weatherCommandService.isWeatherCommand(input)) {
            return weatherCommandService.handle(userId, input);
        }
        if (expenseService.supports(input)) {
            return expenseService.handle(userId, input);
        }
        if (habitService.supports(input)) {
            return habitService.handle(userId, input);
        }
        if (rpgService.supports(input)) {
            return rpgService.handle(userId, input);
        }
        return commandService.handle(userId, input);
    }

    private Style styleFor(String input) {
        if (weatherCommandService.isWeatherCommand(input)) {
            return new Style("天気", "対象地域と取得元も表示", "#3B82C4", "#E5F3FF", "予定メニュー");
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

    private Map<String, Object> resultBubble(Style style, String raw) {
        Map<String, Object> bubble = new LinkedHashMap<>();
        bubble.put("type", "bubble");
        bubble.put("size", "mega");

        List<String> lines = raw.lines().map(String::stripTrailing).toList();
        String first = lines.stream().filter(v -> !v.isBlank() && !isDivider(v)).findFirst().orElse(style.title());

        bubble.put("header", vertical(style.headerBackground(), List.of(
                text(first, "xl", "bold", style.accent(), "start"),
                text(style.subtitle(), "xs", "regular", "#617184", "start")
        )));

        List<Map<String, Object>> contents = new ArrayList<>();
        boolean skippedFirst = false;
        int visible = 0;
        for (String line : lines) {
            String value = line.strip();
            if (value.isBlank()) {
                if (!contents.isEmpty()) contents.add(separator());
                continue;
            }
            if (!skippedFirst && value.equals(first)) {
                skippedFirst = true;
                continue;
            }
            if (isDivider(value)) {
                if (!contents.isEmpty()) contents.add(separator());
                continue;
            }
            if (visible >= 35) {
                contents.add(text("ほかの情報は関連画面から確認してね", "xs", "regular", "#7A8798", "start"));
                break;
            }
            contents.add(lineCard(value, style));
            visible++;
        }
        if (contents.isEmpty()) {
            contents.add(text("表示できる情報はまだないよ", "md", "regular", "#526D82", "center"));
        }
        contents.add(button("関連メニュー", style.backMessage(), style.accent()));
        contents.add(button("🏠 ホーム", "ホーム", "#8592A6"));
        bubble.put("body", vertical("#FCFDFE", contents));
        return bubble;
    }

    private Map<String, Object> lineCard(String value, Style style) {
        String color = value.contains("期限切れ") || value.contains("削除") || value.contains("注意")
                ? "#B54752" : "#334E68";
        String weight = value.startsWith("【") || value.startsWith("■") || value.startsWith("□")
                || value.matches("^\\d+[.．].*") ? "bold" : "regular";
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "box");
        card.put("layout", "vertical");
        card.put("backgroundColor", "#F5F8FC");
        card.put("cornerRadius", "12px");
        card.put("paddingAll", "12px");
        card.put("contents", List.of(text(value, "sm", weight, color, "start")));
        return card;
    }

    private Map<String, Object> quickReply(Style style) {
        return Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("関連メニュー", style.backMessage()),
                quick("操作メニュー", "操作メニュー")
        ));
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

    private Map<String, Object> text(String value, String size, String weight, String color, String align) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", value);
        text.put("size", size);
        text.put("weight", weight);
        text.put("color", color);
        text.put("align", align);
        text.put("wrap", true);
        return text;
    }

    private Map<String, Object> button(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "primary");
        button.put("height", "sm");
        button.put("color", color);
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> separator() {
        return Map.of("type", "separator", "color", "#E2E8F0", "margin", "sm");
    }

    private Map<String, Object> quick(String label, String message) {
        return Map.of("type", "action", "action", Map.of(
                "type", "message", "label", label, "text", message
        ));
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble,
                           Map<String, Object> quickReply) throws Exception {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", altText);
        flex.put("contents", bubble);
        flex.put("quickReply", quickReply);
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
        if (result.statusCode() / 100 != 2) {
            throw new IllegalStateException("LINE API error: HTTP " + result.statusCode());
        }
    }

    private boolean isDivider(String value) {
        return value.matches("^[━─ー_=\\-]{3,}$");
    }

    private boolean allowed(String userId) {
        return props.ownerUserId() == null || props.ownerUserId().isBlank() || props.ownerUserId().equals(userId);
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

    private String normalize(String value) {
        return value == null ? "" : value.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Style(String title, String subtitle, String accent,
                         String headerBackground, String backMessage) {}

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
