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
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public class RichInputGuideFilter extends OncePerRequestFilter {
    private final LineWebhookSupport webhook;

    public RichInputGuideFilter(LineWebhookSupport webhook) {
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
                Guide guide = guideFor(event.text());
                if (guide == null) continue;
                if (!webhook.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                webhook.reply(event.replyToken(), List.of(flexMessage(guide.title(), guideBubble(guide))));
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

    private Guide guideFor(String input) {
        return switch (input) {
            case "予定追加ガイド" -> new Guide(
                    "予定を追加", "日時・繰り返し・通知を文章で指定できるよ", "#2E6FC4", "予定操作", "予定一覧",
                    List.of(
                            new Example("基本", "明日19時 歯医者"),
                            new Example("日付指定", "8/1 20時 会議"),
                            new Example("毎日", "毎日7時 朝の散歩"),
                            new Example("平日", "平日9時 朝会"),
                            new Example("曜日指定", "毎週火曜20時 ジム"),
                            new Example("通知指定", "明日19時 歯医者 30分前と10分前")
                    ));
            case "メモ追加ガイド" -> new Guide(
                    "メモを追加", "残したい内容を『メモ』に続けて送ってね", "#D98AAA", "メモタスク操作", "メモ一覧",
                    List.of(
                            new Example("短いメモ", "メモ 牛乳を買う"),
                            new Example("連絡先", "メモ 病院 03-1234-5678"),
                            new Example("覚え書き", "メモ 会議では予算案を確認する"),
                            new Example("あとで検索", "メモ パスワード変更は金曜日")
                    ));
            case "タスク追加ガイド" -> new Guide(
                    "タスクを追加", "やることを『タスク』に続けて送ってね", "#2E9B6B", "メモタスク操作", "タスク一覧",
                    List.of(
                            new Example("基本", "タスク 資料を提出"),
                            new Example("具体的に", "タスク 山田さんへ見積書を送る"),
                            new Example("買い物系", "タスク クリーニングを受け取る"),
                            new Example("連絡系", "タスク 病院へ予約の電話をする")
                    ));
            case "支出追加ガイド" -> new Guide(
                    "支出を記録", "内容・金額・日付はいろいろな順番で入力できるよ", "#D88916", "お金買い物操作", "支出一覧",
                    List.of(
                            new Example("内容＋金額", "昼食 1200円"),
                            new Example("金額＋内容", "支出 980 電車"),
                            new Example("今日指定", "今日 コンビニ 650円"),
                            new Example("昨日指定", "昨日 日用品 2300円"),
                            new Example("日付指定", "7/20 病院 3000円")
                    ));
            case "買い物追加ガイド" -> new Guide(
                    "買い物を追加", "買うものを『買い物』に続けて送ってね", "#E5A855", "お金買い物操作", "買い物一覧",
                    List.of(
                            new Example("1品", "買い物 ティッシュ"),
                            new Example("数量つき", "買い物 牛乳 2本"),
                            new Example("詳しく", "買い物 単3電池 8本入り"),
                            new Example("用途つき", "買い物 会議用の水 6本")
                    ));
            case "習慣追加ガイド" -> new Guide(
                    "習慣を追加", "曜日・頻度・通知時刻を指定できるよ", "#7957C7", "習慣成長操作", "今日の習慣",
                    List.of(
                            new Example("毎日", "習慣 読書 毎日"),
                            new Example("平日", "習慣 ストレッチ 平日"),
                            new Example("曜日指定", "習慣 筋トレ 月水金"),
                            new Example("曜日＋通知", "習慣 ゴミ出し 火土 8:00"),
                            new Example("毎日＋通知", "習慣 薬 毎日 21:00"),
                            new Example("通知なし", "習慣 日記 毎日")
                    ));
            default -> null;
        };
    }

    private Map<String, Object> guideBubble(Guide guide) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(text("入力例", "sm", "bold", "#526D82"));
        for (Example example : guide.examples()) {
            body.add(card("#F5F7FA", "12px", "sm", List.of(
                    text(example.label(), "xs", "bold", "#7B8794"),
                    text(example.command(), "md", "bold", "#334E68")
            )));
        }
        body.add(text("上の例を参考に、内容を入力して送信してね", "sm", "regular", "#526D82"));
        body.add(horizontal(List.of(
                secondary("← 戻る", guide.backMessage()),
                button("一覧を見る", guide.listMessage(), guide.accent())
        )));
        body.add(secondary("🏠 ホーム", "ホーム"));

        return bubble(
                vertical("#F3F6FA", "14px", "sm", List.of(
                        text(guide.title(), "xl", "bold", "#243B53"),
                        text(guide.instruction(), "sm", "regular", "#526D82")
                )),
                vertical("#FCFDFE", "14px", "sm", body)
        );
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

    private record Guide(String title, String instruction, String accent, String backMessage,
                         String listMessage, List<Example> examples) { }
    private record Example(String label, String command) { }
}
