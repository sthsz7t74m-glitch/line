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
                || text.equals("家計簿ヘルプ") || text.equals("家計簿使い方")
                || text.equals("習慣ヘルプ") || text.equals("習慣使い方")
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
            case "家計簿ヘルプ", "家計簿使い方" -> expenseHelp();
            case "習慣ヘルプ", "習慣使い方" -> habitHelp();
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

                ■ 標準の予定通知
                1か月前（早く登録した予定）
                1週間前（早く登録した予定）
                前日
                1時間前
                5分前
                予定時刻ちょうど

                ■ 通知を個別指定
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

                予定は「その時間に行うこと」、
                タスクは「期限までに終えること」に使うよ。

                ■ 追加
                タスク 部屋を片づける
                タスク 部屋の掃除 優先度高
                明日までに資料作成
                金曜18時までにメール返信
                タスク 明日18時 資料提出 優先度高

                ■ 確認
                タスク一覧
                今日のタスク
                期限切れ
                今週のタスク

                ■ 完了・延期
                完了 1
                タスク延期 1 明日
                タスク延期 1 1時間後
                タスク延期 1 3日後

                ■ 変更・削除
                タスク変更 2 明日18時 資料提出 優先度高
                優先度変更 2 低
                タスク削除 2

                ■ 繰り返しタスク
                繰り返しタスク 毎週月曜9時 ゴミ出し確認
                毎週月曜9時までに ゴミ出し確認
                繰り返しタスク 平日18時 日報提出

                期限付きタスクは標準で前日・1時間前・期限時刻に通知するよ。
                通知カードから完了・1時間後・明日への延期を選べるよ。
                完了すると経験値+10。
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

    private String expenseHelp() {
        return """
                【家計簿の使い方】

                ■ 一言で記録
                昼1200円
                スタバ650円
                昨日 スーパー3,480円
                支出 980 電車

                ■ 確認
                家計簿
                今日いくら
                今月いくら
                カテゴリ別
                食費いくら
                支出一覧

                ■ 修正・削除
                支出編集 1 1500 昼ごはん
                支出削除 1

                食費・交通費・日用品・娯楽・医療・住居・通信へ自動分類するよ。
                """.strip();
    }

    private String habitHelp() {
        return """
                【習慣トラッカーの使い方】

                ■ 登録
                習慣 筋トレ
                習慣 薬 毎日 21:00
                習慣 読書 平日 22:00
                習慣 ゴミ出し 火金 7:00

                曜日は「毎日」「平日」「土日」または
                「月水金」のように指定できるよ。
                時刻を付けると、その時間に通知するよ。

                ■ 今日の確認・達成
                今日の習慣
                習慣達成 1
                習慣取消 1

                ■ 管理
                習慣一覧
                習慣編集 1 読書 月水金 22:00
                習慣休止 1
                習慣再開 1
                習慣削除 1

                ■ 記録
                習慣統計

                達成すると経験値+5。30日達成率と連続日数も確認できるよ。
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

                予定は標準で次の時間に通知するよ。
                ・前日
                ・1時間前
                ・5分前
                ・予定時刻ちょうど

                十分早く登録した予定には、さらに
                ・1か月前
                ・1週間前
                の通知も追加されるよ。

                締切付きタスクは標準で
                ・前日
                ・1時間前
                ・期限時刻
                に通知するよ。
                タスク通知カードから完了・1時間後・明日への延期ができるよ。

                習慣は登録時に時刻を付けると、その時刻に通知するよ。
                通知カードの「できた」から直接達成できるよ。

                予定通知カードから
                「あと5分」「あと10分」「あと30分」「明日にする」
                を選べるよ。

                予定ごとの通知変更例
                通知変更 2 1日前と30分前
                通知変更 2 通知なし
                """.strip();
    }

    private String otherHelp() {
        return """
                【その他の機能】

                今日のミッション
                統計
                カレンダー
                プロフィール
                実績一覧
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
                家計簿ヘルプ
                習慣ヘルプ
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
