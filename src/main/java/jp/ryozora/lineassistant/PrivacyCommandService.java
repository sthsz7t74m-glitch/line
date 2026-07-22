package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

@Service
public class PrivacyCommandService {
    public static final int MAX_MESSAGE_LENGTH = 1000;
    private final BenlyStore store;
    private final AiSecretaryService secretaryService;

    public PrivacyCommandService(BenlyStore store, AiSecretaryService secretaryService) {
        this.store = store;
        this.secretaryService = secretaryService;
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);

        if (text.length() > MAX_MESSAGE_LENGTH) {
            return "入力が長すぎるよ！1回のメッセージは1000文字以内にしてね。";
        }

        if (secretaryService.supports(text)) {
            return secretaryService.handle(userId, text);
        }

        return switch (text) {
            case "プライバシー", "個人情報", "データ取扱い" -> privacyPolicy();
            case "自分のデータ", "データ確認" -> dataSummary(userId);
            case "全データ削除", "アカウント削除" -> "本当に全データを削除する場合は、\n『全データ削除 実行』\nと送ってね。\n\nこの操作は取り消せません。";
            case "全データ削除 実行", "アカウント削除 実行" -> deleteAll(userId);
            default -> null;
        };
    }

    private String dataSummary(String userId) {
        BenlyStore.DataSummary s = store.dataSummary(userId);
        return """
                ベンリーに保存されているデータ
                ・メモ：%d件
                ・タスク：%d件
                ・買い物：%d件
                ・予定：%d件
                ・経験値履歴：%d件

                内容そのものはここでは表示しません。
                『全データ削除』で削除手続きに進めます。
                """.formatted(s.memos(), s.tasks(), s.shoppingItems(), s.schedules(), s.experienceLogs()).strip();
    }

    private String deleteAll(String userId) {
        store.deleteAllUserData(userId);
        return "保存されていたメモ・タスク・買い物・予定・設定・経験値履歴をすべて削除しました。";
    }

    private String privacyPolicy() {
        return """
                ベンリーの個人情報・データ取扱い

                【保存する情報】
                ・LINE上の内部ユーザー識別子
                ・入力したメモ、タスク、買い物、予定
                ・通知設定、地域設定、経験値履歴

                【利用目的】
                ベンリーの各機能と通知を提供するためだけに使います。

                【保存先】
                Render上のアプリとSupabase PostgreSQLを利用します。

                【しないこと】
                ・広告目的での利用
                ・第三者への販売
                ・入力内容のAI学習への提供

                【削除】
                『自分のデータ』で件数を確認できます。
                『全データ削除』で削除できます。

                住所の詳細や現在地履歴など、不要な情報は原則保存しません。
                """.strip();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }
}
