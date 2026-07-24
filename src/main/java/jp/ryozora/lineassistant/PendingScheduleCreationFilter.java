package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PendingScheduleCreationFilter extends OncePerRequestFilter {
    private static final Set<String> CANCEL = Set.of("キャンセル", "やめる", "取消", "取り消し", "ホーム");

    private final LineWebhookSupport webhook;
    private final PendingScheduleCreation pendingStore;
    private final NaturalLanguageService naturalLanguageService;
    private final AdvancedScheduleService scheduleService;

    public PendingScheduleCreationFilter(LineWebhookSupport webhook,
                                         PendingScheduleCreation pendingStore,
                                         NaturalLanguageService naturalLanguageService,
                                         AdvancedScheduleService scheduleService) {
        this.webhook = webhook;
        this.pendingStore = pendingStore;
        this.naturalLanguageService = naturalLanguageService;
        this.scheduleService = scheduleService;
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
                PendingScheduleCreation.Pending pending = pendingStore.get(event.userId());
                if (pending == null) continue;

                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String input = event.text().strip();
                if (CANCEL.contains(input)) {
                    pendingStore.clear(event.userId());
                    if (input.equals("ホーム")) {
                        chain.doFilter(wrapped, response);
                    } else {
                        webhook.reply(event.replyToken(), List.of(Map.of(
                                "type", "text", "text", "予定の入力待ちをキャンセルしたよ。")));
                        response.setStatus(HttpServletResponse.SC_OK);
                    }
                    return;
                }

                if (isNavigationOrCommand(input)) {
                    pendingStore.clear(event.userId());
                    chain.doFilter(wrapped, response);
                    return;
                }

                String combined = (pending.partial() + " " + input).strip();
                NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(combined);
                if (interpretation == null || interpretation.type() != NaturalLanguageService.Type.SCHEDULE) {
                    webhook.reply(event.replyToken(), List.of(retryMessage(pending.partial(), input)));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String result = scheduleService.handle(event.userId(), interpretation.command());
                if (result == null || result.isBlank()) {
                    webhook.reply(event.replyToken(), List.of(retryMessage(pending.partial(), input)));
                } else {
                    pendingStore.clear(event.userId());
                    webhook.reply(event.replyToken(), List.of(successMessage(result)));
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

    private boolean isNavigationOrCommand(String input) {
        return input.equals("予定一覧") || input.equals("カレンダー") || input.endsWith("メニュー")
                || input.startsWith("予定変更") || input.startsWith("予定削除")
                || input.startsWith("メモ") || input.startsWith("タスク")
                || input.startsWith("習慣") || input.startsWith("支出") || input.startsWith("買い物");
    }

    private Map<String, Object> retryMessage(String partial, String input) {
        return FlexUi.flexMessage("予定の入力を確認してね", FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "14px", "xs", List.of(
                        FlexUi.text("もう少し教えてね", "xl", "bold", "#C68A2B"),
                        FlexUi.text("予定の入力待ちは継続中", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#FFF9F0", "10px", "xs", List.of(
                                FlexUi.text("受け取り済み：" + partial, "sm", "bold", "#526D82"),
                                FlexUi.text("今回の入力：" + input, "sm", "regular", "#526D82"),
                                FlexUi.text("例：映画 / 明日18:00", "xs", "regular", "#718096")
                        )),
                        FlexUi.button("キャンセル", "キャンセル", "#8793A5")
                ))
        ));
    }

    private Map<String, Object> successMessage(String result) {
        List<Map<String, Object>> lines = result.lines().map(String::strip)
                .filter(value -> !value.isBlank()).limit(8)
                .map(value -> FlexUi.text(value, "sm", "regular", "#526D82")).toList();
        return FlexUi.flexMessage("予定を追加したよ", FlexUi.bubble(
                FlexUi.vertical("#DDEBFF", "14px", "xs", List.of(
                        FlexUi.text("✓ 予定を追加したよ", "xl", "bold", "#2E6FC4")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F5F8FD", "10px", "xs", lines),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("予定一覧", "予定一覧", "#4F7FC7"),
                                FlexUi.button("カレンダー", "カレンダー", "#6CA6E5")
                        )),
                        FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                ))
        ));
    }
}
