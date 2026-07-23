package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CompactPrefixInputFilter extends OncePerRequestFilter {
    private static final Pattern COMPACT_HABIT = Pattern.compile(
            "^習慣(.+?)(毎日|平日|土日|[月火水木金土日]{1,7})(?:(\\d{1,2}:\\d{2}))?$");

    private static final Set<String> RESERVED_MEMO = Set.of(
            "メモ一覧", "メモ検索", "メモ削除", "メモ編集", "メモお気に入り", "メモタグ", "メモ全削除", "メモ追加");
    private static final Set<String> RESERVED_TASK = Set.of(
            "タスク一覧", "タスク追加", "タスク完了", "タスク削除", "タスク延期");
    private static final Set<String> RESERVED_SHOPPING = Set.of(
            "買い物一覧", "買い物追加", "買い物完了", "買い物削除");
    private static final Set<String> RESERVED_HABIT = Set.of(
            "習慣一覧", "習慣追加", "習慣統計", "習慣の記録", "習慣達成", "習慣達成ID",
            "習慣取消", "習慣削除", "習慣編集", "習慣休止", "習慣再開");

    private final LineWebhookSupport webhook;
    private final BenlyCommandService commandService;
    private final HabitService habitService;

    public CompactPrefixInputFilter(LineWebhookSupport webhook,
                                    BenlyCommandService commandService,
                                    HabitService habitService) {
        this.webhook = webhook;
        this.commandService = commandService;
        this.habitService = habitService;
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
                NormalizedInput normalized = normalize(event.text());
                if (normalized == null) continue;

                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String result = normalized.kind() == Kind.HABIT
                        ? habitService.handle(event.userId(), normalized.command())
                        : commandService.handle(event.userId(), normalized.command());

                if (result == null || result.startsWith("受け取ったよ：") || result.contains("読み取れなかった")) {
                    webhook.reply(event.replyToken(), List.of(guideMessage(normalized.kind())));
                } else {
                    webhook.reply(event.replyToken(), List.of(successMessage(normalized.kind(), result)));
                }
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

    static NormalizedInput normalize(String raw) {
        if (raw == null) return null;
        String text = raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
        if (text.isBlank() || text.contains(" ")) return null;

        NormalizedInput memo = simplePrefix(text, "メモ", RESERVED_MEMO, Kind.MEMO);
        if (memo != null) return memo;

        NormalizedInput task = simplePrefix(text, "タスク", RESERVED_TASK, Kind.TASK);
        if (task != null) return task;

        NormalizedInput shopping = simplePrefix(text, "買い物", RESERVED_SHOPPING, Kind.SHOPPING);
        if (shopping != null) return shopping;

        if (startsWithReserved(text, RESERVED_HABIT)) return null;
        Matcher habit = COMPACT_HABIT.matcher(text);
        if (habit.matches()) {
            String command = "習慣 " + habit.group(1).strip() + " " + habit.group(2);
            if (habit.group(3) != null) command += " " + habit.group(3);
            return new NormalizedInput(Kind.HABIT, command);
        }
        if (text.startsWith("習慣") && text.length() > 2) {
            return new NormalizedInput(Kind.HABIT, "習慣 " + text.substring(2).strip());
        }
        return null;
    }

    private static NormalizedInput simplePrefix(String text, String prefix, Set<String> reserved, Kind kind) {
        if (!text.startsWith(prefix) || text.length() <= prefix.length() || startsWithReserved(text, reserved)) return null;
        return new NormalizedInput(kind, prefix + " " + text.substring(prefix.length()).strip());
    }

    private static boolean startsWithReserved(String text, Set<String> reserved) {
        return reserved.stream().anyMatch(text::startsWith);
    }

    private Map<String, Object> successMessage(Kind kind, String result) {
        UiStyle style = style(kind);
        List<Map<String, Object>> summary = result.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank() && !value.matches("^[━─ー_=\\-]{3,}$"))
                .limit(8)
                .map(value -> FlexUi.text(value, "sm", "regular", "#526D82"))
                .toList();
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical(style.header(), "12px", "xs", List.of(
                        FlexUi.text("✓ " + style.successTitle(), "lg", "bold", style.accent()),
                        FlexUi.text("空白なし入力で登録したよ", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F8FAFC", "10px", "xs", summary),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("一覧を見る", style.listCommand(), style.accent()),
                                FlexUi.button("もう1件", style.addCommand(), style.lightAccent())
                        )),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("← 戻る", style.backCommand(), "#8793A5"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        return FlexUi.flexMessage(style.successTitle(), bubble);
    }

    private Map<String, Object> guideMessage(Kind kind) {
        UiStyle style = style(kind);
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical(style.header(), "12px", "xs", List.of(
                        FlexUi.text(style.guideTitle(), "lg", "bold", style.accent()),
                        FlexUi.text("内容を続けて入力してね", "sm", "regular", "#526D82")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F8FAFC", "10px", "xs", style.examples().stream()
                                .map(value -> FlexUi.text(value, "sm", "bold", "#334E68"))
                                .toList()),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("← 戻る", style.backCommand(), "#8793A5"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        return FlexUi.flexMessage(style.guideTitle(), bubble);
    }

    private UiStyle style(Kind kind) {
        return switch (kind) {
            case MEMO -> new UiStyle("メモを保存したよ", "メモを追加", "#D989AD", "#FBEAF2", "#E4A5BF",
                    "メモ一覧", "メモ追加", "記録メニュー", List.of("メモ牛乳を買う", "メモ病院の電話番号"));
            case TASK -> new UiStyle("タスクを追加したよ", "タスクを追加", "#2E9B6B", "#E7F7F1", "#71C9B7",
                    "タスク一覧", "タスク追加", "記録メニュー", List.of("タスク資料を提出", "タスク田中さんへ電話"));
            case SHOPPING -> new UiStyle("買い物に追加したよ", "買い物を追加", "#C68A2B", "#FFF2DE", "#D9A44E",
                    "買い物一覧", "買い物追加", "お金メニュー", List.of("買い物ティッシュ", "買い物牛乳2本"));
            case HABIT -> new UiStyle("習慣を追加したよ", "習慣を追加", "#7656B8", "#EFEAF8", "#9780CD",
                    "習慣一覧", "習慣追加", "成長メニュー", List.of("習慣読書", "習慣筋トレ月水金", "習慣薬毎日21:00"));
        };
    }

    enum Kind { MEMO, TASK, SHOPPING, HABIT }
    record NormalizedInput(Kind kind, String command) { }
    private record UiStyle(String successTitle, String guideTitle, String accent, String header, String lightAccent,
                           String listCommand, String addCommand, String backCommand, List<String> examples) { }
}
