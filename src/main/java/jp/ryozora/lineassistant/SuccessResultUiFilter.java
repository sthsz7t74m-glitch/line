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

import static jp.ryozora.lineassistant.FlexUi.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class SuccessResultUiFilter extends OncePerRequestFilter {
    private final LineWebhookSupport line;
    private final BenlyCommandService commandService;
    private final HabitService habitService;
    private final ExpenseService expenseService;
    private final AdvancedScheduleService scheduleService;

    public SuccessResultUiFilter(LineWebhookSupport line,
                                 BenlyCommandService commandService,
                                 HabitService habitService,
                                 ExpenseService expenseService,
                                 AdvancedScheduleService scheduleService) {
        this.line = line;
        this.commandService = commandService;
        this.habitService = habitService;
        this.expenseService = expenseService;
        this.scheduleService = scheduleService;
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
            for (LineWebhookSupport.TextEvent event : line.textEvents(body)) {
                Action action = actionFor(event.text());
                if (action == null) continue;
                if (!line.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String result = execute(event.userId(), event.text(), action);
                if (result == null || isFailure(result)) {
                    chain.doFilter(wrapped, response);
                    return;
                }
                Map<String, Object> message = new LinkedHashMap<>(flexMessage(action.title(), successBubble(action, result)));
                message.put("quickReply", quickReply(List.of(
                        quick("🏠 ホーム", "ホーム"),
                        quick("最近使った", "最近使った"),
                        quick("操作メニュー", "操作メニュー")
                )));
                line.reply(event.replyToken(), List.of(message));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Normal webhook processing remains the fallback.
        }
        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private Action actionFor(String input) {
        if (input.startsWith("メモ ") && input.length() > 3) {
            return new Action("メモを保存したよ", "#D989AD", "#FBEAF2", "メモ一覧", "メモ追加", "記録メニュー");
        }
        if (input.startsWith("タスク ") && input.length() > 4) {
            return new Action("タスクを追加したよ", "#2E9B6B", "#E7F7F1", "タスク一覧", "タスク追加", "記録メニュー");
        }
        if (input.startsWith("買い物 ") && input.length() > 4) {
            return new Action("買い物に追加したよ", "#D88916", "#FFF2DE", "買い物一覧", "買い物追加", "お金メニュー");
        }
        if (input.startsWith("習慣 ") && input.length() > 3) {
            return new Action("習慣を追加したよ", "#7957C7", "#EFE7FF", "習慣一覧", "習慣追加", "成長メニュー");
        }
        if (input.startsWith("支出 ") && input.length() > 3) {
            return new Action("支出を記録したよ", "#D88916", "#FFF2DE", "支出一覧", "支出追加", "お金メニュー");
        }
        if ((input.startsWith("予定 ") && input.length() > 3)
                || input.startsWith("毎日") || input.startsWith("毎週")
                || input.startsWith("毎月") || input.startsWith("平日") || input.startsWith("土日")) {
            return new Action("予定を追加したよ", "#2E6FC4", "#E5EFFF", "予定一覧", "予定追加", "予定メニュー");
        }
        return null;
    }

    private String execute(String userId, String input, Action action) {
        if (SetLike.isOneOf(action.listCommand(), "メモ一覧", "タスク一覧", "買い物一覧")) {
            return commandService.handle(userId, input);
        }
        if (action.listCommand().equals("習慣一覧")) return habitService.handle(userId, input);
        if (action.listCommand().equals("支出一覧")) return expenseService.handle(userId, input);
        return scheduleService.handle(userId, input);
    }

    private boolean isFailure(String result) {
        String value = result == null ? "" : result;
        return value.contains("読み取れなかった") || value.contains("書いてな")
                || value.contains("見つからなかった") || value.startsWith("受け取ったよ：")
                || value.contains("例：") && !value.contains("追加した") && !value.contains("記録した");
    }

    private Map<String, Object> successBubble(Action action, String result) {
        List<Map<String, Object>> summary = new ArrayList<>();
        int count = 0;
        for (String line : result.lines().map(String::strip).filter(v -> !v.isBlank()).toList()) {
            if (line.matches("^[━─ー_=\\-]{3,}$")) continue;
            if (count++ >= 8) break;
            summary.add(text(line, count == 1 ? "sm" : "xs", count == 1 ? "bold" : "regular",
                    count == 1 ? "#243B53" : "#526D82"));
        }
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(card("#F8FAFC", "10px", "xs", summary.isEmpty()
                ? List.of(text("登録内容を保存したよ", "sm", "bold", "#243B53")) : summary));
        body.add(horizontal(List.of(
                button("一覧を見る", action.listCommand(), action.accent()),
                secondary("もう1件追加", action.addCommand())
        )));
        body.add(horizontal(List.of(
                secondary("← 戻る", action.backCommand()),
                secondary("🏠 ホーム", "ホーム")
        )));
        return bubble(
                vertical(action.headerColor(), "12px", "xs", List.of(
                        text("✓ " + action.title(), "lg", "bold", action.accent()),
                        text("次の操作を選べるよ", "xxs", "regular", "#718096")
                )),
                vertical("#FCFDFE", "12px", "sm", body)
        );
    }

    private Map<String, Object> secondary(String label, String message) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("style", "secondary");
        button.put("height", "sm");
        button.put("flex", 1);
        button.put("adjustMode", "shrink-to-fit");
        button.put("action", Map.of("type", "message", "label", label, "text", message));
        return button;
    }

    private Map<String, Object> quickReply(List<Map<String, Object>> items) {
        return Map.of("items", items);
    }

    private record Action(String title, String accent, String headerColor,
                          String listCommand, String addCommand, String backCommand) { }

    private static final class SetLike {
        private SetLike() { }
        static boolean isOneOf(String value, String... candidates) {
            for (String candidate : candidates) if (candidate.equals(value)) return true;
            return false;
        }
    }
}
