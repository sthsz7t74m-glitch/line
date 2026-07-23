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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CompactExpenseInputFilter extends OncePerRequestFilter {
    private static final Pattern COMPACT_PREFIX = Pattern.compile("^支出(?:記録)?([\\d,]+)(?:円|えん)?(.+)$");
    private static final Pattern AMOUNT_FIRST_COMPACT = Pattern.compile("^([\\d,]+)(?:円|えん)(.+)$");
    private static final Pattern DESCRIPTION_FIRST_WITH_YEN = Pattern.compile("^(.+?)([\\d,]+)(?:円|えん)$");

    private final LineWebhookSupport webhook;
    private final ExpenseService expenseService;

    public CompactExpenseInputFilter(LineWebhookSupport webhook, ExpenseService expenseService) {
        this.webhook = webhook;
        this.expenseService = expenseService;
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
                String normalized = normalizeExpense(event.text());
                if (normalized == null) continue;

                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String result = expenseService.handle(event.userId(), normalized);
                if (result == null || result.contains("読み取れなかった")) {
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

    static String normalizeExpense(String raw) {
        if (raw == null) return null;
        String text = raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
        if (text.isBlank()) return null;

        if (text.equals("支出") || text.equals("支出記録") || text.equals("支出追加")) {
            return "支出";
        }
        if (text.startsWith("支出削除") || text.startsWith("支出編集")
                || text.equals("支出一覧") || text.equals("支出カテゴリ")) {
            return null;
        }
        if (text.startsWith("支出 ")) return text;

        Matcher prefixed = COMPACT_PREFIX.matcher(text);
        if (prefixed.matches()) {
            return "支出 " + prefixed.group(1) + " " + prefixed.group(2).strip();
        }

        Matcher amountFirst = AMOUNT_FIRST_COMPACT.matcher(text);
        if (amountFirst.matches()) {
            return "支出 " + amountFirst.group(1) + " " + amountFirst.group(2).strip();
        }

        Matcher descriptionFirst = DESCRIPTION_FIRST_WITH_YEN.matcher(text);
        if (descriptionFirst.matches()) {
            return "支出 " + descriptionFirst.group(1).strip() + " " + descriptionFirst.group(2) + "円";
        }

        // 「支出」で始まる入力は予定解析へ流さず、支出ガイドを返す。
        if (text.startsWith("支出")) return "支出";
        return null;
    }

    private Map<String, Object> successMessage(String result) {
        List<Map<String, Object>> summary = result.lines()
                .map(String::strip)
                .filter(value -> !value.isBlank() && !value.matches("^[━─ー_=\\-]{3,}$"))
                .limit(8)
                .map(value -> FlexUi.text(value, "sm", "regular", "#526D82"))
                .toList();

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "12px", "xs", List.of(
                        FlexUi.text("✓ 支出を記録したよ", "lg", "bold", "#C68A2B"),
                        FlexUi.text("家計簿に保存済み", "xxs", "regular", "#718096")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#FFF9F0", "10px", "xs", summary),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("支出一覧", "支出一覧", "#C68A2B"),
                                FlexUi.button("もう1件", "支出追加", "#D9A44E")
                        )),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("← お金メニュー", "お金メニュー", "#8793A5"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        Map<String, Object> flex = new LinkedHashMap<>(FlexUi.flexMessage("支出を記録したよ", bubble));
        flex.put("quickReply", Map.of("items", List.of(
                FlexUi.quick("支出一覧", "支出一覧"),
                FlexUi.quick("家計簿", "家計簿"),
                FlexUi.quick("🏠 ホーム", "ホーム")
        )));
        return flex;
    }

    private Map<String, Object> guideMessage() {
        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#FFF2DE", "12px", "xs", List.of(
                        FlexUi.text("支出を記録", "lg", "bold", "#C68A2B"),
                        FlexUi.text("金額と内容を入力してね", "sm", "regular", "#526D82")
                )),
                FlexUi.vertical("#FCFDFE", "12px", "sm", List.of(
                        FlexUi.card("#FFF9F0", "10px", "xs", List.of(
                                FlexUi.text("支出454昼食", "sm", "bold", "#334E68"),
                                FlexUi.text("454円昼食", "sm", "bold", "#334E68"),
                                FlexUi.text("昼食454円", "sm", "bold", "#334E68")
                        )),
                        FlexUi.horizontal(List.of(
                                FlexUi.button("← お金メニュー", "お金メニュー", "#8793A5"),
                                FlexUi.button("🏠 ホーム", "ホーム", "#8793A5")
                        ))
                ))
        );
        return FlexUi.flexMessage("支出の入力方法", bubble);
    }
}
