package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UtilityContextReleaseFilter extends OncePerRequestFilter {
    private final LineWebhookSupport webhook;
    private final PendingInputContext pendingInput;

    public UtilityContextReleaseFilter(LineWebhookSupport webhook, PendingInputContext pendingInput) {
        this.webhook = webhook;
        this.pendingInput = pendingInput;
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
                if (input.equals("検索") || input.startsWith("検索 ")
                        || input.equals("全体検索") || input.startsWith("全体検索 ")
                        || input.equals("解釈") || input.startsWith("解釈 ")
                        || input.equals("プレビュー") || input.startsWith("プレビュー ")) {
                    pendingInput.clear(event.userId());
                    break;
                }
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
}
