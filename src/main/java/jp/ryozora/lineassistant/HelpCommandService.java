package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

@Service
public class HelpCommandService {
    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.equals("予定ヘルプ") || text.equals("予定使い方")
                || text.equals("メモヘルプ") || text.equals("メモ使い方")
                || text.equals("タスクヘルプ") || text.equals("タスク使い方")
                || text.equals("買い物ヘルプ") || text.equals("買い物使い方")
                || text.equals("天気ヘルプ") || text.equals("天気使い方")
                || text.equals("通知ヘルプ") || text.equals("通知使い方")
                || text.equals("その他ヘルプ") || text.equals("基本ヘルプ")
                || text.equals("コマンド一覧");
    }

    public String handle(String raw) {
        String text = normalize(raw);
        return switch (text) {
            case "予定ヘルプ", "予定使い方" -> scheduleHelp();
            case "メモヘルプ", "メモ使い方" -> memoHelp();
            case "タスクヘルプ", "タスク使い方" -> taskHelp();
            case "買い物ヘルプ", "買い物使い方" -> shoppingHelp();
            case "天気ヘルプ", "天気使い方" -> weatherHelp();
            case "通知ヘルプ", "通知使い方" -> notificationHelp();
            case "その他ヘルプ", "基本ヘルプ" -> otherHelp();
            case "コマンド一覧" -> allCommands();
            default -> null;
        };
    }

    private String scheduleHelp() {
        return """
                【予定の使い方】

                ■ 予定を追加
                明日19時 歯医者
                あさって午前10時 会議

                ■ 繰り返し予定
                毎日8時 薬
                毎週月曜19時 ジム
                毎月25日9時 支払い
                平日7時30分 出勤
                土日10時 散歩

                ■ 終了日を指定
                毎週月曜19時 ジム 終了2026/12/31

                ■ 通知を指定
                明日19時 歯医者 30分前と10分前
                毎週月曜19時 ジム 1日前と2時間前
                通知なしも指定できるよ。

                ■ 確認・変更
                予定一覧
                予定変更 2 2026-08-01 20:00 会議
                通知変更 2 30分前と10分前
                繰り返し変更 2 毎週火曜20時 ジム

                ■ 削除や変更の範囲
                予定削除 2 この回だけ
                予定削除 2 この回以降
                予定削除 2 全部
                """.strip();
    }

    private String memoHelp() {
        return """
                【メモの使い方】

                メモ 牛乳を買う
                メモ一覧
                メモ検索 牛乳
                メモ編集 1 牛乳を2本買う
                お気に入り 1
                タグ 1 買い物,重要
                メモ削除 1
                メモ全削除

                番号は「メモ一覧」に表示される番号を使ってね。
                """.strip();
    }

    private String taskHelp() {
        return """
                【タスクの使い方】

                タスク 部屋を片づける
                タスク一覧
                完了 1

                完了すると経験値が増えるよ。
                """.strip();
    }

    private String shoppingHelp() {
        return """
                【買い物の使い方】

                買い物 卵
                買い物一覧
                購入 1

                自然な文章でも追加できるよ。
                牛乳ほしい
                卵が必要
                シャンプー切れそう
                ティッシュ残り少ない
                """.strip();
    }

    private String weatherHelp() {
        return """
                【天気の聞き方】

                今日の天気
                明日の天気
                雨降る？
                傘いる？

                通知設定から朝のお知らせや雨のお知らせも切り替えられるよ。
                """.strip();
    }

    private String notificationHelp() {
        return """
                【通知の使い方】

                通知設定
                通知切替 朝
                通知切替 雨
                通知切替 予定
                通知切替 タスク
                通知切替 夜

                予定通知では、通知カードのボタンから
                「あと10分」「あと30分」「明日にする」
                を選べるよ。

                予定ごとの通知変更例
                通知変更 2 1日前と30分前
                通知変更 2 通知なし
                """.strip();
    }

    private String otherHelp() {
        return """
                【その他の機能】

                経験値
                レベル
                時刻
                自分のデータ
                プライバシー
                ホーム
                ヘルプ
                コマンド一覧
                """.strip();
    }

    private String allCommands() {
        return """
                【主なコマンド一覧】

                予定ヘルプ
                メモヘルプ
                タスクヘルプ
                買い物ヘルプ
                天気ヘルプ
                通知ヘルプ
                その他ヘルプ

                詳しい例は各ヘルプを開いて確認してね。
                """.strip();
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }
}
