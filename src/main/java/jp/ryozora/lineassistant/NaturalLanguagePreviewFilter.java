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
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class NaturalLanguagePreviewFilter extends OncePerRequestFilter {
    private final LineWebhookSupport webhook;
    private final NaturalLanguageService naturalLanguage;

    public NaturalLanguagePreviewFilter(LineWebhookSupport webhook, NaturalLanguageService naturalLanguage) {
        this.webhook = webhook;
        this.naturalLanguage = naturalLanguage;
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
                if (!input.equals("解釈") && !input.startsWith("解釈 ")
                        && !input.equals("プレビュー") && !input.startsWith("プレビュー ")) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String value = input.startsWith("プレビュー")
                        ? input.substring("プレビュー".length()).strip()
                        : input.substring("解釈".length()).strip();
                webhook.reply(event.replyToken(), List.of(value.isBlank()
                        ? guideMessage() : previewMessage(value)));
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

    private Map<String, Object> previewMessage(String original) {
        NaturalLanguageService.Interpretation interpretation = naturalLanguage.interpret(original);
        if (interpretation == null) return notUnderstoodMessage(original);

        List<Map<String, Object>> details = new ArrayList<>();
        String accent;
        String header;
        switch (interpretation.type()) {
            case SCHEDULE -> {
                accent = "#4F7FC7";
                header = "予定として解釈";
                String value = interpretation.command().substring("予定 ".length());
                String[] parts = value.split(" ", 3);
                if (parts.length >= 3) {
                    details.add(row("日付", parts[0]));
                    details.add(row("時刻", parts[1]));
                    details.add(row("内容", parts[2]));
                } else {
                    details.add(row("解釈結果", value));
                }
            }
            case SHOPPING -> {
                accent = "#C68A2B";
                header = "買い物として解釈";
                details.add(row("買うもの", interpretation.command().substring("買い物 ".length())));
            }
            case TASK -> {
                accent = "#4F9F8A";
                header = "タスクとして解釈";
                details.add(row("やること", interpretation.command().substring("タスク ".length())));
            }
            default -> throw new IllegalStateException("Unsupported interpretation");
        }

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.card("#F7F9FC", "12px", "xs", details));
        body.add(FlexUi.text("元の入力：" + original, "xxs", "regular", "#8A96A6"));
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("この内容で登録", interpretation.command(), accent),
                FlexUi.button("やめる", "ホーム", "#8793A5")
        )));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical(tint(interpretation.type()), "14px", "xs", List.of(
                        FlexUi.text("解釈プレビュー", "xl", "bold", accent),
                        FlexUi.text(header, "sm", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
        return FlexUi.flexMessage("解釈プレビュー", bubble);
    }

    private Map<String, Object> row(String label, String value) {
        return FlexUi.horizontal(List.of(
                FlexUi.text(label, "sm", "bold", "#526D82"),
                FlexUi.text(value, "sm", "regular", "#334E68", "end")
        ));
    }

    private String tint(NaturalLanguageService.Type type) {
        return switch (type) {
            case SCHEDULE -> "#E7EFFA";
            case SHOPPING -> "#FAF0DF";
            case TASK -> "#E7F3EF";
        };
    }

    private Map<String, Object> notUnderstoodMessage(String original) {
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "14px", "xs", List.of(
                        FlexUi.text("解釈できなかったよ", "xl", "bold", "#C68A2B"),
                        FlexUi.text("別の言い方で試してみてね", "sm", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#FFF9F0", "10px", "xs", List.of(
                                FlexUi.text("入力：" + original, "sm", "regular", "#526D82"),
                                FlexUi.text("例：解釈 明日昼 病院", "xs", "bold", "#334E68"),
                                FlexUi.text("例：解釈 牛乳が切れそう", "xs", "bold", "#334E68")
                        )),
                        FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                ))
        );
        return FlexUi.flexMessage("解釈できなかったよ", bubble);
    }

    private Map<String, Object> guideMessage() {
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#EEF2F7", "14px", "xs", List.of(
                        FlexUi.text("解釈プレビュー", "xl", "bold", "#526D82"),
                        FlexUi.text("登録前にベンリーの解釈を確認できるよ", "sm", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#F7F9FC", "10px", "xs", List.of(
                                FlexUi.text("解釈 明日昼 病院", "sm", "bold", "#334E68"),
                                FlexUi.text("解釈 ティッシュが切れそう", "sm", "bold", "#334E68"),
                                FlexUi.text("解釈 資料を忘れない", "sm", "bold", "#334E68")
                        )),
                        FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                ))
        );
        return FlexUi.flexMessage("解釈プレビューの使い方", bubble);
    }
}
