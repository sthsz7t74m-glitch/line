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

import static jp.ryozora.lineassistant.FlexUi.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 38)
public class CompactRemainingResultsFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of(
            "家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出",
            "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ",
            "今日の習慣", "習慣一覧", "習慣統計", "習慣の記録",
            "今日のダッシュボード", "今日のミッション", "今週ランキング",
            "自分のデータ", "統計", "買い物一覧"
    );

    private final LineWebhookSupport webhook;
    private final ExpenseService expenseService;
    private final HabitService habitService;
    private final RpgService rpgService;
    private final AiSecretaryService aiSecretaryService;
    private final BenlyCommandService commandService;

    public CompactRemainingResultsFilter(LineWebhookSupport webhook,
                                         ExpenseService expenseService,
                                         HabitService habitService,
                                         RpgService rpgService,
                                         AiSecretaryService aiSecretaryService,
                                         BenlyCommandService commandService) {
        this.webhook = webhook;
        this.expenseService = expenseService;
        this.habitService = habitService;
        this.rpgService = rpgService;
        this.aiSecretaryService = aiSecretaryService;
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
                Map<String, Object> flex = new LinkedHashMap<>(flexMessage(style.title(), resultBubble(style, result)));
                flex.put("quickReply", Map.of("items", List.of(
                        quick("🏠 ホーム", "ホーム"),
                        quick("関連", style.backMessage()),
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

    private String resultFor(String userId, String input) {
        if (expenseService.supports(input)) return expenseService.handle(userId, input);
        if (habitService.supports(input)) return habitService.handle(userId, input);
        if (rpgService.supports(input)) return rpgService.handle(userId, input);
        if (aiSecretaryService.supports(input)) return aiSecretaryService.handle(userId, input);
        return commandService.handle(userId, input);
    }

    private Style styleFor(String input) {
        if (Set.of("家計簿", "家計簿一覧", "支出一覧", "今日いくら", "今日の支出",
                "今月いくら", "今月の支出", "カテゴリ別", "支出カテゴリ").contains(input)) {
            return new Style("お金・家計簿", "#D88916", "#FFF2DE", "お金メニュー");
        }
        if (Set.of("今日の習慣", "習慣一覧", "習慣統計", "習慣の記録").contains(input)) {
            return new Style("習慣・成長", "#4F956D", "#E8F7EF", "成長メニュー");
        }
        if (Set.of("今日のミッション", "今週ランキング").contains(input)) {
            return new Style("成長記録", "#7957C7", "#EFE7FF", "成長メニュー");
        }
        if (input.equals("今日のダッシュボード")) {
            return new Style("今日のまとめ", "#2E6FC4", "#E5EFFF", "予定メニュー");
        }
        if (input.equals("買い物一覧")) {
            return new Style("買い物", "#D89A34", "#FFF3E1", "お金メニュー");
        }
        return new Style("ベンリー", "#526D82", "#F1F5F9", "ホーム");
    }

    private Map<String, Object> resultBubble(Style style, String raw) {
        List<String> lines = cleanLines(raw);
        String title = lines.isEmpty() ? style.title() : lines.get(0);
        List<String> contentLines = lines.size() <= 1 ? List.of() : lines.subList(1, lines.size());
        List<String> primary = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> meta = new ArrayList<>();

        for (String line : contentLines) {
            if (isInstruction(line)) meta.add(line);
            else if (isPrimary(line) && primary.size() < 4) primary.add(line);
            else if (details.size() < 12) details.add(line);
        }

        List<Map<String, Object>> body = new ArrayList<>();
        if (!primary.isEmpty()) {
            List<Map<String, Object>> main = new ArrayList<>();
            for (int i = 0; i < primary.size(); i++) {
                main.add(text(primary.get(i), i == 0 ? "lg" : "sm", i == 0 ? "bold" : "regular",
                        i == 0 ? "#243B53" : "#526D82"));
            }
            body.add(card(tint(style.headerColor()), "12px", "xs", main));
        }
        if (!details.isEmpty()) {
            List<Map<String, Object>> components = new ArrayList<>();
            for (String line : details.stream().limit(10).toList()) {
                boolean warning = line.contains("期限切れ") || line.contains("注意") || line.contains("未達成");
                boolean strong = line.startsWith("【") || line.startsWith("■") || line.startsWith("□")
                        || line.matches("^\\d+[.．].*");
                components.add(text(line, "xs", strong ? "bold" : "regular",
                        warning ? "#B54752" : "#526D82"));
            }
            body.add(card("#F6F8FB", "10px", "xs", components));
        }
        if (!meta.isEmpty()) {
            body.add(text(String.join("　", meta.stream().limit(2).toList()), "xxs", "regular", "#8A96A6"));
        }
        if (body.isEmpty()) body.add(text("表示できる情報はまだないよ", "sm", "regular", "#526D82"));
        body.add(horizontal(List.of(
                button("関連", style.backMessage(), style.accent()),
                button("🏠 ホーム", "ホーム", "#8592A6")
        )));
        return bubble(
                vertical(style.headerColor(), "12px", "xs", List.of(
                        text(title, "xl", "bold", style.accent()),
                        text(style.title(), "xxs", "regular", "#6A788B")
                )),
                vertical("#FCFDFE", "12px", "sm", body)
        );
    }

    private boolean isPrimary(String line) {
        return line.contains("合計") || line.contains("達成率") || line.contains("レベル")
                || line.contains("経験値") || line.matches(".*\\d+件.*")
                || line.contains("今日") || line.contains("今月") || line.contains("連続");
    }

    private boolean isInstruction(String line) {
        return line.startsWith("例：") || line.startsWith("削除：") || line.startsWith("編集：")
                || line.startsWith("完了：") || line.startsWith("延期：")
                || line.contains("のように送") || line.contains("で確認できる");
    }

    private List<String> cleanLines(String raw) {
        return raw == null ? List.of() : raw.lines().map(String::strip)
                .filter(v -> !v.isBlank())
                .filter(v -> !v.matches("^[━─ー_=\\-]{3,}$"))
                .toList();
    }

    private String tint(String headerColor) {
        return switch (headerColor) {
            case "#FFF2DE" -> "#FFF8EE";
            case "#E8F7EF" -> "#F2FAF6";
            case "#EFE7FF" -> "#F8F4FF";
            case "#E5EFFF" -> "#F1F6FF";
            case "#FFF3E1" -> "#FFF9F0";
            default -> "#F5F8FC";
        };
    }

    private record Style(String title, String accent, String headerColor, String backMessage) { }
}
