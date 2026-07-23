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
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CommandPaletteFilter extends OncePerRequestFilter {
    private final LineWebhookSupport webhook;

    public CommandPaletteFilter(LineWebhookSupport webhook) {
        this.webhook = webhook;
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
                if (!supports(input)) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                Map<String, Object> message = new LinkedHashMap<>(flexMessage(altText(input), paletteFor(input)));
                message.put("quickReply", Map.of("items", List.of(
                        quick("🏠 ホーム", "ホーム"),
                        quick("最近使った", "最近使った")
                )));
                webhook.reply(event.replyToken(), List.of(message));
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

    private boolean supports(String input) {
        return switch (input) {
            case "操作メニュー", "操作一覧", "コマンド一覧",
                 "予定操作", "メモタスク操作", "お金買い物操作", "習慣成長操作",
                 "予定追加ガイド", "メモ追加ガイド", "タスク追加ガイド",
                 "支出追加ガイド", "買い物追加ガイド", "習慣追加ガイド" -> true;
            default -> false;
        };
    }

    private String altText(String input) {
        return switch (input) {
            case "予定操作" -> "予定の操作";
            case "メモタスク操作" -> "メモとタスクの操作";
            case "お金買い物操作" -> "お金と買い物の操作";
            case "習慣成長操作" -> "習慣と成長の操作";
            case "予定追加ガイド" -> "予定を追加";
            case "メモ追加ガイド" -> "メモを追加";
            case "タスク追加ガイド" -> "タスクを追加";
            case "支出追加ガイド" -> "支出を記録";
            case "買い物追加ガイド" -> "買い物を追加";
            case "習慣追加ガイド" -> "習慣を追加";
            default -> "ベンリー操作メニュー";
        };
    }

    private Map<String, Object> paletteFor(String input) {
        return switch (input) {
            case "予定操作" -> schedulePalette();
            case "メモタスク操作" -> recordPalette();
            case "お金買い物操作" -> moneyPalette();
            case "習慣成長操作" -> growthPalette();
            case "予定追加ガイド" -> guide("予定を追加", "日時と内容を送ってね", "例：明日19時 歯医者", "予定操作");
            case "メモ追加ガイド" -> guide("メモを追加", "『メモ』に続けて内容を送ってね", "例：メモ 牛乳を買う", "メモタスク操作");
            case "タスク追加ガイド" -> guide("タスクを追加", "『タスク』に続けて内容を送ってね", "例：タスク 資料を提出", "メモタスク操作");
            case "支出追加ガイド" -> guide("支出を記録", "金額と内容を送ってね", "例：支出 1200 昼食", "お金買い物操作");
            case "買い物追加ガイド" -> guide("買い物を追加", "買う物を送ってね", "例：買い物 ティッシュ", "お金買い物操作");
            case "習慣追加ガイド" -> guide("習慣を追加", "続けたいことを送ってね", "例：習慣 読書 毎日", "習慣成長操作");
            default -> mainPalette();
        };
    }

    private Map<String, Object> mainPalette() {
        return palette("#EDE3FF", "操作メニュー", "やりたいことの種類を選んでね", List.of(
                row(button("今日・予定", "予定操作", "#2E6FC4"), button("メモ・タスク", "メモタスク操作", "#2E9B6B")),
                row(button("お金・買い物", "お金買い物操作", "#D88916"), button("習慣・成長", "習慣成長操作", "#7957C7")),
                row(button("通知設定", "通知設定", "#567EC7"), button("使い方", "ヘルプ", "#7F91B5")),
                button("🏠 ホーム", "ホーム", "#8E9CB3")
        ));
    }

    private Map<String, Object> schedulePalette() {
        return palette("#E7EFFA", "今日・予定", "確認・追加・設定をボタンで操作", List.of(
                row(button("今日まとめ", "今日のダッシュボード", "#4F7FC7"), button("予定一覧", "予定一覧", "#5E8BCB")),
                row(button("予定を追加", "予定追加ガイド", "#6F9BD5"), button("カレンダー", "カレンダー", "#7EA8DB")),
                row(button("今日の天気", "今日の天気", "#8BAFDC"), button("通知設定", "通知設定", "#98B6D9")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> recordPalette() {
        return palette("#E7F3EF", "メモ・タスク", "記録や完了操作をまとめたよ", List.of(
                row(button("メモ一覧", "メモ一覧", "#4F9F8A"), button("メモを追加", "メモ追加ガイド", "#5EAA96")),
                row(button("タスク一覧", "タスク一覧", "#6BB5A2"), button("タスクを追加", "タスク追加ガイド", "#78BEAC")),
                row(button("今日のタスク", "今日のタスク", "#86B8AC"), button("統計", "統計", "#91C1B5")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> moneyPalette() {
        return palette("#FAF0DF", "お金・買い物", "記録と確認をまとめたよ", List.of(
                row(button("家計簿", "家計簿", "#C68A2B"), button("支出を記録", "支出追加ガイド", "#D09538")),
                row(button("支出一覧", "支出一覧", "#D6A04A"), button("カテゴリ別", "カテゴリ別", "#DBAA59")),
                row(button("今日の支出", "今日いくら", "#E0B367"), button("今月の支出", "今月いくら", "#E3BA76")),
                row(button("買い物一覧", "買い物一覧", "#D6A04A"), button("買い物を追加", "買い物追加ガイド", "#DBAA59")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> growthPalette() {
        return palette("#EFEAF8", "習慣・成長", "続ける・確認する・振り返る", List.of(
                row(button("今日の習慣", "今日の習慣", "#7656B8"), button("習慣を追加", "習慣追加ガイド", "#8264BF")),
                row(button("習慣統計", "習慣統計", "#8D72C6"), button("ミッション", "今日のミッション", "#9780CD")),
                row(button("プロフィール", "プロフィール", "#A18DD3"), button("実績", "実績一覧", "#AA98D8")),
                row(button("今週の成績", "今週ランキング", "#9780CD"), button("全体統計", "統計", "#A18DD3")),
                button("← 操作メニュー", "操作メニュー", "#8E9CB3")
        ));
    }

    private Map<String, Object> guide(String title, String message, String example, String backMessage) {
        return palette("#F3F6FA", title, message, List.of(
                card("#F6F8FB", "12px", "xs", List.of(text(example, "md", "bold", "#334E68"))),
                row(secondary("← 戻る", backMessage), secondary("🏠 ホーム", "ホーム"))
        ));
    }

    private Map<String, Object> palette(String headerColor, String title, String subtitle,
                                        List<Map<String, Object>> bodyContents) {
        return bubble(
                vertical(headerColor, "14px", "sm", List.of(
                        text(title, "xl", "bold", "#243B53"),
                        text(subtitle, "sm", "regular", "#526D82")
                )),
                vertical("#FCFDFE", "14px", "sm", bodyContents)
        );
    }

    private Map<String, Object> row(Map<String, Object> left, Map<String, Object> right) {
        return horizontal(List.of(left, right));
    }

    private Map<String, Object> secondary(String label, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "button");
        result.put("style", "secondary");
        result.put("height", "sm");
        result.put("flex", 1);
        result.put("adjustMode", "shrink-to-fit");
        result.put("action", Map.of("type", "message", "label", label, "text", message));
        return result;
    }
}
