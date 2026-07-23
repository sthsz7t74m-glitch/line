package jp.ryozora.lineassistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static jp.ryozora.lineassistant.FlexUi.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class GlobalUxNavigationFilter extends OncePerRequestFilter {
    private static final Set<String> SYSTEM_COMMANDS = Set.of(
            "戻る", "← 戻る", "前へ戻る", "最近使った", "最近の操作", "地域設定", "天気地域設定"
    );
    private static final Set<String> TRACKABLE = Set.of(
            "予定一覧", "カレンダー", "今日のダッシュボード", "今日の天気",
            "メモ一覧", "タスク一覧", "家計簿", "支出一覧", "買い物一覧",
            "今日の習慣", "習慣一覧", "プロフィール", "実績一覧", "通知設定"
    );

    private final LineWebhookSupport line;
    private final NotificationStore notificationStore;
    private final Map<String, String> lastMenu = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> recent = new ConcurrentHashMap<>();

    public GlobalUxNavigationFilter(LineWebhookSupport line,
                                    NotificationStore notificationStore) {
        this.line = line;
        this.notificationStore = notificationStore;
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
                if (!line.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    if (SYSTEM_COMMANDS.contains(event.text())) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }
                    continue;
                }

                rememberContext(event.userId(), event.text());
                rememberRecent(event.userId(), event.text());
                if (!SYSTEM_COMMANDS.contains(event.text())) continue;

                if (isBack(event.text())) {
                    replyBack(event.replyToken(), event.userId());
                } else if (event.text().equals("最近使った") || event.text().equals("最近の操作")) {
                    replyFlex(event.replyToken(), "最近使った機能", recentBubble(event.userId()));
                } else {
                    replyFlex(event.replyToken(), "天気の地域設定", regionBubble(event.userId()));
                }
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        } catch (Exception ignored) {
            // Existing webhook pipeline remains the fallback.
        }

        try {
            chain.doFilter(wrapped, response);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    private boolean isBack(String input) {
        return input.equals("戻る") || input.equals("← 戻る") || input.equals("前へ戻る");
    }

    private void rememberContext(String userId, String input) {
        String menu = switch (input) {
            case "予定メニュー", "今日メニュー", "予定操作", "予定一覧", "カレンダー", "今日の天気" -> "予定メニュー";
            case "記録メニュー", "メモタスクメニュー", "メモタスク操作", "メモ一覧", "タスク一覧" -> "記録メニュー";
            case "お金メニュー", "家計メニュー", "お金買い物操作", "家計簿", "支出一覧", "買い物一覧" -> "お金メニュー";
            case "成長メニュー", "習慣メニュー", "習慣成長操作", "今日の習慣", "習慣一覧", "プロフィール", "実績一覧" -> "成長メニュー";
            default -> null;
        };
        if (menu != null) lastMenu.put(userId, menu);
    }

    private void rememberRecent(String userId, String input) {
        if (!TRACKABLE.contains(input)) return;
        Deque<String> history = recent.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.remove(input);
            history.addFirst(input);
            while (history.size() > 5) history.removeLast();
        }
    }

    private void replyBack(String replyToken, String userId) throws Exception {
        String destination = lastMenu.getOrDefault(userId, "ホーム");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", destination);
        message.put("quickReply", navigationQuickReply());
        line.reply(replyToken, List.of(message));
    }

    private Map<String, Object> recentBubble(String userId) {
        List<Map<String, Object>> body = new ArrayList<>();
        Deque<String> history = recent.get(userId);
        if (history == null || history.isEmpty()) {
            body.add(text("まだ利用履歴がないよ", "sm", "regular", "#718096"));
            body.add(text("機能を使うと、ここに最大5件表示されるよ", "xxs", "regular", "#98A1AE"));
        } else {
            synchronized (history) {
                for (String command : history) body.add(secondary(command, command));
            }
        }
        body.add(horizontal(List.of(
                secondary("操作メニュー", "操作メニュー"),
                secondary("🏠 ホーム", "ホーム")
        )));
        return pageBubble("最近使った機能", "よく使う操作へすぐ戻れるよ", "#526D82", "#EEF2F7", body);
    }

    private Map<String, Object> regionBubble(String userId) {
        NotificationStore.Settings settings = notificationStore.get(userId);
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(card("#F4F8FD", "12px", "xs", List.of(
                text(settings.area(), "xl", "bold", "#2E6FC4"),
                text("緯度 " + round(settings.latitude()) + " / 経度 " + round(settings.longitude()),
                        "xxs", "regular", "#7A8798")
        )));
        body.add(text("天気と雨通知はこの地域を基準に取得するよ", "xs", "regular", "#526D82"));
        body.add(card("#FAFBFD", "12px", "xs", List.of(
                text("取得元：Open-Meteo", "xxs", "regular", "#8A96A6"),
                text("地域を変えるときは『地域変更 府中市』のように送ってね", "xxs", "regular", "#8A96A6")
        )));
        body.add(horizontal(List.of(
                button("今日の天気", "今日の天気", "#4E85D1"),
                secondary("通知設定", "通知設定")
        )));
        body.add(secondary("🏠 ホーム", "ホーム"));
        return pageBubble("天気の地域設定", "現在の基準地域", "#2E6FC4", "#E5F3FF", body);
    }

    private Map<String, Object> pageBubble(String title, String subtitle, String accent,
                                           String headerColor, List<Map<String, Object>> body) {
        return bubble(
                vertical(headerColor, "12px", "xs", List.of(
                        text(title, "lg", "bold", accent),
                        text(subtitle, "xxs", "regular", "#6A788B")
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

    private Map<String, Object> navigationQuickReply() {
        return Map.of("items", List.of(
                quick("🏠 ホーム", "ホーム"),
                quick("最近使った", "最近使った"),
                quick("操作メニュー", "操作メニュー")
        ));
    }

    private void replyFlex(String replyToken, String altText, Map<String, Object> bubble) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>(flexMessage(altText, bubble));
        message.put("quickReply", navigationQuickReply());
        line.reply(replyToken, List.of(message));
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
