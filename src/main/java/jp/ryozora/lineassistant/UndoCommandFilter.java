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
public class UndoCommandFilter extends OncePerRequestFilter {
    private static final Set<String> COMMANDS = Set.of("元に戻す", "取り消す", "直前の変更を戻す", "↩ 元に戻す");

    private final LineWebhookSupport webhook;
    private final UndoHistoryService undoHistoryService;

    public UndoCommandFilter(LineWebhookSupport webhook, UndoHistoryService undoHistoryService) {
        this.webhook = webhook;
        this.undoHistoryService = undoHistoryService;
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

                String result = undoHistoryService.undoLatest(event.userId());
                webhook.reply(event.replyToken(), List.of(resultMessage(result)));
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

    private Map<String, Object> resultMessage(String result) {
        boolean success = result.startsWith("✓");
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical(success ? "#E7F7F1" : "#FFF2DE", "12px", "xs", List.of(
                        FlexUi.text(success ? "元に戻したよ" : "元に戻せなかったよ",
                                "lg", "bold", success ? "#2E9B6B" : "#C68A2B"),
                        FlexUi.text("直近30分の編集が対象", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card(success ? "#F7FBFA" : "#FFF9F0", "10px", "xs", List.of(
                                FlexUi.text(result, "sm", "regular", "#526D82")
                        )),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("最近使った", "最近使った", "#667EA8"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        return FlexUi.flexMessage("元に戻す", bubble);
    }
}
