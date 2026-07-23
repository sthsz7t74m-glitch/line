package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class EnhancedMenuFilter extends OncePerRequestFilter {
    private static final Set<String> MENU_COMMANDS = Set.of(
            "ホーム", "ベンリー", "トップ", "メニュー",
            "予定メニュー", "今日メニュー", "今日・予定", "今日と予定", "予定",
            "記録メニュー", "メモタスクメニュー", "メモ・タスク", "メモとタスク", "記録",
            "お金メニュー", "家計メニュー", "お金・買い物", "お金と買い物", "お金",
            "成長メニュー", "習慣メニュー", "習慣・成長", "習慣と成長", "成長"
    );

    private static final String BLUE = "#4F7FC7";
    private static final String GREEN = "#4F9F8A";
    private static final String ORANGE = "#C68A2B";
    private static final String PURPLE = "#7656B8";
    private static final String GRAY = "#8793A5";

    private final LineWebhookSupport webhook;

    public EnhancedMenuFilter(LineWebhookSupport webhook) {
        this.webhook = webhook;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);

        try {
            for (LineWebhookSupport.TextEvent event : webhook.textEvents(body)) {
                if (!MENU_COMMANDS.contains(event.text())) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                webhook.reply(event.replyToken(), List.of(menuMessage(canonical(event.text()))));
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // 既存Webhook処理をフォールバックとして残す。
        }
        chain.doFilter(wrapped, response);
    }

    private String canonical(String input) {
        if (Set.of("予定メニュー", "今日メニュー", "今日・予定", "今日と予定", "予定").contains(input)) return "SCHEDULE";
        if (Set.of("記録メニュー", "メモタスクメニュー", "メモ・タスク", "メモとタスク", "記録").contains(input)) return "RECORD";
        if (Set.of("お金メニュー", "家計メニュー", "お金・買い物", "お金と買い物", "お金").contains(input)) return "MONEY";
        if (Set.of("成長メニュー", "習慣メニュー", "習慣・成長", "習慣と成長", "成長").contains(input)) return "GROWTH";
        return "HOME";
    }

    private Map<String, Object> menuMessage(String type) {
        Map<String, Object> message = new LinkedHashMap<>(FlexUi.flexMessage(altText(type), bubble(type)));
        message.put("quickReply", quickReply());
        return message;
    }

    private String altText(String type) {
        return switch (type) {
            case "SCHEDULE" -> "ホーム ＞ 今日・予定";
            case "RECORD" -> "ホーム ＞ メモ・タスク";
            case "MONEY" -> "ホーム ＞ お金・買い物";
            case "GROWTH" -> "ホーム ＞ 習慣・成長";
            default -> "ベンリー ホーム";
        };
    }

    private Map<String, Object> bubble(String type) {
        return switch (type) {
            case "SCHEDULE" -> categoryBubble("🏠 ホーム ＞ 今日・予定", "予定と今日の確認", BLUE, "#E7EFFA", List.of(
                    row(action("今日まとめ", "今日のダッシュボード", BLUE), action("予定一覧", "予定一覧", "#5E8BCB")),
                    row(action("カレンダー", "カレンダー", "#6F9BD5"), action("予定追加", "予定追加", "#7EA8DB")),
                    row(action("今日の天気", "今日の天気", "#8BAFDC"), action("通知設定", "通知設定", "#98B6D9"))
            ));
            case "RECORD" -> categoryBubble("🏠 ホーム ＞ メモ・タスク", "メモとやること", GREEN, "#E7F3EF", List.of(
                    row(action("タスク一覧", "タスク一覧", GREEN), action("タスク追加", "タスク追加", "#5EAA96")),
                    row(action("メモ一覧", "メモ一覧", "#6BB5A2"), action("メモ追加", "メモ追加", "#78BEAC")),
                    row(action("自分のデータ", "自分のデータ", "#86B8AC"), action("統計", "統計", "#91C1B5"))
            ));
            case "MONEY" -> categoryBubble("🏠 ホーム ＞ お金・買い物", "支出と買い物", ORANGE, "#FAF0DF", List.of(
                    row(action("家計簿", "家計簿", ORANGE), action("買い物一覧", "買い物一覧", "#D09538")),
                    row(action("今日の支出", "今日いくら", "#D6A04A"), action("今月の支出", "今月いくら", "#DBAA59")),
                    row(action("カテゴリ別", "カテゴリ別", "#E0B367"), action("支出一覧", "支出一覧", "#E3BA76"))
            ));
            case "GROWTH" -> categoryBubble("🏠 ホーム ＞ 習慣・成長", "習慣と成長", PURPLE, "#EFEAF8", List.of(
                    row(action("今日の習慣", "今日の習慣", PURPLE), action("習慣追加", "習慣追加", "#8264BF")),
                    row(action("ミッション", "今日のミッション", "#8D72C6"), action("プロフィール", "プロフィール", "#9780CD")),
                    row(action("実績", "実績一覧", "#A18DD3"), action("今週成績", "今週ランキング", "#AA98D8"))
            ));
            default -> homeBubble();
        };
    }

    private Map<String, Object> homeBubble() {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.text("⭐ よく使う", "sm", "bold", "#586174"));
        body.add(row(action("今日まとめ", "今日のダッシュボード", "#627A9C"), action("タスク一覧", "タスク一覧", "#7187A5")));
        body.add(action("買い物一覧", "買い物一覧", "#8193AB"));
        body.add(Map.of("type", "separator", "margin", "sm", "color", "#E5E8EE"));
        body.add(FlexUi.text("カテゴリ", "sm", "bold", "#586174"));
        body.add(row(action("今日・予定", "予定メニュー", BLUE), action("メモ・タスク", "記録メニュー", GREEN)));
        body.add(row(action("お金・買い物", "お金メニュー", ORANGE), action("習慣・成長", "成長メニュー", PURPLE)));
        body.add(row(action("通知", "通知設定", "#7187A5"), action("使い方", "ヘルプ", GRAY)));
        return FlexUi.bubble(
                header("ベンリー", "使いたい機能を選んでね", "#4F5870", "#EEF1F6"),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body)
        );
    }

    private Map<String, Object> categoryBubble(String title, String subtitle, String accent,
                                               String background, List<Map<String, Object>> rows) {
        List<Map<String, Object>> body = new ArrayList<>(rows);
        body.add(action("🏠 ホーム", "ホーム", GRAY));
        return FlexUi.bubble(header(title, subtitle, accent, background),
                FlexUi.vertical("#FCFDFE", "12px", "sm", body));
    }

    private Map<String, Object> header(String title, String subtitle, String accent, String background) {
        return FlexUi.vertical(background, "12px", "xs", List.of(
                FlexUi.text(title, "lg", "bold", accent),
                FlexUi.text(subtitle, "xs", "regular", "#6F7B8D")
        ));
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        return FlexUi.horizontal(List.of(left, right));
    }

    private Map<String, Object> action(String label, String message, String color) {
        Map<String, Object> button = new LinkedHashMap<>(FlexUi.button(label, message, color));
        button.put("flex", 1);
        return button;
    }

    private Map<String, Object> quickReply() {
        return Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"), quick("今日", "今日のダッシュボード"),
                quick("タスク", "タスク一覧"), quick("買い物", "買い物一覧"), quick("通知", "通知設定")
        ));
    }

    private Map<String, Object> quick(String label, String text) {
        return Map.of("type", "action", "action", Map.of("type", "message", "label", label, "text", text));
    }
}
