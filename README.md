# My LINE Assistant v0.1.0

自分専用のLINE公式アカウントBotです。

## 機能

- `ヘルプ`：コマンド一覧
- `メモ 牛乳を買う`：メモ追加
- `メモ一覧`：メモ表示
- `メモ全削除`：メモ削除
- `アプリ`：自作アプリ一覧
- `時刻`：日本時間を返信
- Webhook署名検証
- `LINE_OWNER_USER_ID`設定時は自分以外へ応答しない

現在のメモはメモリ保存なので、再起動や再デプロイで消えます。

## LINE側の準備

1. LINE公式アカウントを作成
2. Messaging APIを有効化
3. Channel secretを確認
4. Channel access tokenを発行
5. 応答メッセージをオフ
6. Webhookをオン

## Renderへの公開

1. このフォルダをGitHubへアップロード
2. Renderで `New` → `Blueprint`
3. リポジトリを選択
4. 環境変数を設定
   - `LINE_CHANNEL_SECRET`
   - `LINE_CHANNEL_ACCESS_TOKEN`
   - `LINE_OWNER_USER_ID`
5. デプロイURLの末尾に `/line/webhook` を付ける
6. LINE DevelopersのWebhook URLに設定して「検証」
7. Webhook利用をオン

例:

```text
https://my-line-assistant.onrender.com/line/webhook
```

## ローカル起動

Java 21とMavenを用意して以下を実行します。

```bash
mvn spring-boot:run
```

## 次に追加する候補

- PostgreSQLでメモ永続化
- リッチメニュー
- Googleカレンダー連携
- Twitch配信開始通知
- GitHubデプロイ通知
- 自作アプリURL管理
- 定時プッシュ通知
