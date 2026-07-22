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
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IncompleteCommandGuideFilter extends OncePerRequestFilter {
    private final LineWebhookSupport line;

    public IncompleteCommandGuideFilter(LineWebhookSupport line) {
        this.line = line;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/line/webhook".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        try {
            for (LineWebhookSupport.TextEvent event : line.textEvents(body)) {
                Guide guide = guideFor(event.text());
                if (guide == null) continue;
                if (!line.isAuthorized(body, request.getHeader("x-line-signature"), event.userId())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                line.reply(event.replyToken(), List.of(guideMessage(guide)));
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

    private Guide guideFor(String input) {
        return switch (input) {
            case "タスク", "タスク追加" -> new Guide("タスクを追加", "やることを入力して送信してね", List.of(
                    "基本：タスク 資料を提出", "買い物：タスク 牛乳を買う", "連絡：タスク 田中さんへ電話"
            ), "メモタスク操作", "入力した文章がそのままタスク名になるよ");
            case "メモ", "メモ追加" -> new Guide("メモを追加", "残したい内容を入力して送信してね", List.of(
                    "短いメモ：メモ 牛乳を買う", "連絡先：メモ 山田さん 090-xxxx-xxxx", "覚え書き：メモ 次回は資料を3部持参"
            ), "メモタスク操作", "あとから編集・お気に入り・タグ付けもできるよ");
            case "習慣", "習慣追加" -> new Guide("習慣を追加", "頻度や通知時刻も指定できるよ", List.of(
                    "毎日：習慣 読書 毎日", "平日：習慣 ストレッチ 平日", "曜日指定：習慣 筋トレ 月水金",
                    "曜日＋通知：習慣 ゴミ出し 火土 8:00", "毎日＋通知：習慣 薬 毎日 21:00"
            ), "習慣成長操作", "曜日は『月水金』のように続けて書けるよ");
            case "支出", "支出追加", "支出記録" -> new Guide("支出を記録", "金額・内容・日付を入力できるよ", List.of(
                    "内容＋金額：昼食 1200円", "金額＋内容：支出 980 電車", "今日指定：今日 コンビニ 650円",
                    "昨日指定：昨日 スーパー 3200円", "日付指定：7/20 本 1800円"
            ), "お金買い物操作", "分類は内容から自動で判定するよ");
            case "買い物", "買い物追加" -> new Guide("買い物を追加", "買うものを入力して送信してね", List.of(
                    "1品：買い物 ティッシュ", "数量つき：買い物 牛乳 2本", "詳しく：買い物 単4電池 8本入り"
            ), "お金買い物操作", "購入後は一覧から完了にできるよ");
            case "予定追加", "明日19時" -> new Guide("予定を追加", "単発・繰り返しに対応しているよ", List.of(
                    "相対日時：明日19時 歯医者", "日付指定：8/1 20時 会議", "毎日：毎日7時 薬",
                    "平日：平日8時 朝会", "曜日指定：毎週火曜20時 ジム", "毎月：毎月25日18時 支払い"
            ), "予定操作", "『30分前』のように通知タイミングも追加できるよ");
            case "予定変更" -> new Guide("予定を変更", "予定一覧から対象の変更を押してね", List.of(
                    "単発予定はその予定だけ変更", "繰り返し予定は対象範囲を選択", "番号を覚える必要はないよ"
            ), "予定一覧", "一覧カードから操作するのが一番簡単だよ");
            case "予定削除" -> new Guide("予定を削除", "予定一覧から対象の削除を押してね", List.of(
                    "単発：この予定だけ", "繰り返し：この回だけ", "繰り返し：この回以降", "繰り返し：シリーズ全部"
            ), "予定一覧", "全予定削除は一覧の一番下にあるよ");
            default -> null;
        };
    }

    private Map<String, Object> guideMessage(Guide guide) {
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(FlexUi.text("入力例", "sm", "bold", "#526D82"));
        for (String example : guide.examples()) {
            Map<String, Object> box = FlexUi.vertical("#F6F8FB", "10px", "xs", List.of(
                    FlexUi.text(example, "sm", "bold", "#334E68")
            ));
            box.put("cornerRadius", "10px");
            body.add(box);
        }
        body.add(FlexUi.text(guide.note(), "xs", "regular", "#6B7C93"));
        body.add(FlexUi.horizontal(List.of(
                FlexUi.button("← 戻る", guide.backMessage(), "#8E9CB3"),
                FlexUi.button("🏠 ホーム", "ホーム", "#8E9CB3")
        )));

        Map<String, Object> bubble = FlexUi.bubble(
                FlexUi.vertical("#F3F6FA", "14px", "xs", List.of(
                        FlexUi.text(guide.title(), "xl", "bold", "#243B53"),
                        FlexUi.text(guide.instruction(), "sm", "regular", "#526D82")
                )),
                FlexUi.vertical("#FCFDFE", "14px", "sm", body)
        );
        return FlexUi.flexMessage(guide.title(), bubble);
    }

    private record Guide(String title, String instruction, List<String> examples,
                         String backMessage, String note) {}
}
