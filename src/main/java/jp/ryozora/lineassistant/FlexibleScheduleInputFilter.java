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
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class FlexibleScheduleInputFilter extends OncePerRequestFilter {
    private static final Set<String> RESERVED = Set.of(
            "予定", "予定一覧", "今後の予定", "予定追加", "予定メニュー", "今日の予定"
    );

    private final LineWebhookSupport webhook;
    private final NaturalLanguageService naturalLanguageService;
    private final AdvancedScheduleService scheduleService;

    public FlexibleScheduleInputFilter(LineWebhookSupport webhook,
                                       NaturalLanguageService naturalLanguageService,
                                       AdvancedScheduleService scheduleService) {
        this.webhook = webhook;
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
                String input = normalize(event.text());
                if (!isFlexibleScheduleInput(input)) continue;

                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String value = input.substring("予定".length()).strip();
                NaturalLanguageService.Interpretation interpretation = naturalLanguageService.interpret(value);
                if (interpretation == null || interpretation.type() != NaturalLanguageService.Type.SCHEDULE) {
                    webhook.reply(event.replyToken(), List.of(guideMessage()));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                String result = scheduleService.handle(event.userId(), interpretation.command());
                if (result == null || result.isBlank()) {
                    webhook.reply(event.replyToken(), List.of(guideMessage()));
                } else {
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

    static boolean isFlexibleScheduleInput(String input) {
        if (input == null || input.isBlank() || RESERVED.contains(input)) return false;
        if (!input.startsWith("予定")) return false;
        return !input.startsWith("予定一覧")
                && !input.startsWith("予定変更")
                && !input.startsWith("予定削除")
                && !input.startsWith("予定全削除")
                && !input.startsWith("予定変更案内")
                && !input.startsWith("予定削除選択")
                && !input.startsWith("予定削除実行");
    }

    private Map<String, Object> successMessage(String result) {
        List<Map<String, Object>> lines = result.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .limit(8)
                .map(value -> FlexUi.text(value, "sm", "regular", "#526D82"))
                .toList();
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

    private Map<String, Object> guideMessage() {
        return FlexUi.flexMessage("予定の入力方法", FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "14px", "xs", List.of(
                        FlexUi.text("予定を読み取れなかったよ", "xl", "bold", "#C68A2B")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#FFF9F0", "10px", "xs", List.of(
                                FlexUi.text("予定 明日 18:00 映画", "sm", "bold", "#334E68"),
                                FlexUi.text("予定明日18:00映画", "sm", "bold", "#334E68"),
                                FlexUi.text("予定 7/30 昼 病院", "sm", "bold", "#334E68"),
                                FlexUi.text("予定 18:00 買い物", "sm", "bold", "#334E68")
                        )),
                        FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                ))
        ));
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }
}
