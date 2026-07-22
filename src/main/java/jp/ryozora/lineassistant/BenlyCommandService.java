package jp.ryozora.lineassistant;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class BenlyCommandService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter SCHEDULE_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_OUTPUT = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final BenlyStore store;

    public BenlyCommandService(BenlyStore store) {
        this.store = store;
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);

        String helpResponse = featureHelp(text);
        if (helpResponse != null) return helpResponse;

        if (text.startsWith("メモ ")) {
            String content = text.substring(3).strip();
            if (content.isBlank()) return "メモの内容も書いてなー！";
            store.addMemo(userId, content);
            return "メモしたよ！\n" + content + "\n\n『メモ一覧』で番号を確認できるよ。";
        }
        if (text.equals("メモ") || text.equals("メモ一覧")) {
            return formatItems("メモ一覧", store.listMemos(userId), "メモはまだないよ！");
        }
        if (text.startsWith("メモ検索 ")) {
            String keyword = text.substring(5).strip();
            if (keyword.isBlank()) return "例：メモ検索 牛乳 のように送ってね！";
            return formatMemoSearch(userId, keyword);
        }
        if (text.startsWith("メモ削除 ")) {
            Long number = parseId(text.substring(5));
            if (number == null) return "例：メモ削除 3 のように送ってね！";
            Long id = memoIdByNumber(userId, number);
            if (id == null) return "その番号のメモは見つからんかった！\n『メモ一覧』で番号を確認してね。";
            return store.deleteMemo(userId, id)
                    ? "メモ No." + number + " を削除したよ！\n残りの番号は自動で詰め直したで。"
                    : "そのメモは見つからんかった！";
        }
        if (text.startsWith("メモ編集 ")) {
            IdAndText input = parseIdAndText(text.substring(5));
            if (input == null) return "例：メモ編集 3 牛乳を2本買う のように送ってね！";
            Long id = memoIdByNumber(userId, input.id());
            if (id == null) return "その番号のメモは見つからんかった！\n『メモ一覧』で番号を確認してね。";
            return store.editMemo(userId, id, input.text())
                    ? "メモ No." + input.id() + " を書き直したよ！\n" + input.text()
                    : "そのメモは見つからんかった！";
        }
        if (text.startsWith("お気に入り ") || text.startsWith("メモお気に入り ")) {
            String value = text.startsWith("メモお気に入り ") ? text.substring(8) : text.substring(6);
            Long number = parseId(value);
            if (number == null) return "例：お気に入り 3 のように送ってね！";
            Long id = memoIdByNumber(userId, number);
            if (id == null) return "その番号のメモは見つからんかった！\n『メモ一覧』で番号を確認してね。";
            Boolean favorite = store.toggleMemoFavorite(userId, id);
            if (favorite == null) return "そのメモは見つからんかった！";
            return favorite ? "メモ No." + number + " をお気に入りにしたよ！★\n一覧の上へ移動するで。"
                    : "メモ No." + number + " のお気に入りを外したよ！";
        }
        if (text.startsWith("タグ ") || text.startsWith("メモタグ ")) {
            String value = text.startsWith("メモタグ ") ? text.substring(5) : text.substring(3);
            IdAndText input = parseIdAndText(value);
            if (input == null) return "例：タグ 3 買い物,重要 のように送ってね！";
            Long id = memoIdByNumber(userId, input.id());
            if (id == null) return "その番号のメモは見つからんかった！\n『メモ一覧』で番号を確認してね。";
            String tags = input.text().replace("＃", "").replace("#", "").replace("、", ",");
            return store.setMemoTags(userId, id, tags)
                    ? "メモ No." + input.id() + " にタグを付けたよ！\n#" + tags.replace(",", " #")
                    : "そのメモは見つからんかった！";
        }
        if (text.equals("メモ全削除")) {
            int count = store.clearMemos(userId);
            return count == 0 ? "消すメモはなかったよ！" : "メモを" + count + "件、全部片づけたよ！";
        }
        if (text.startsWith("タスク ")) {
            String title = text.substring(4).strip();
            if (title.isBlank()) return "タスクの内容も書いてなー！";
            long id = store.addTask(userId, title);
            return "クエスト追加！\n#" + id + " " + title;
        }
        if (text.equals("タスク") || text.equals("タスク一覧")) {
            return formatItems("未完了タスク", store.listTasks(userId), "未完了タスクはゼロ！すっきりやなー！");
        }
        if (text.startsWith("完了 ")) {
            Long id = parseId(text.substring(3));
            if (id == null) return "例：完了 3 のように送ってね！";
            return store.completeTask(userId, id)
                    ? "ナイス！タスク #" + id + " 完了！\n経験値 +10"
                    : "その未完了タスクは見つからんかった！";
        }
        if (text.startsWith("買い物 ")) {
            String name = text.substring(4).strip();
            if (name.isBlank()) return "買うものも書いてなー！";
            long id = store.addShoppingItem(userId, name);
            return "買い物リストに追加！\n#" + id + " " + name;
        }
        if (text.equals("買い物") || text.equals("買い物一覧")) {
            return formatItems("買い物リスト", store.listShoppingItems(userId), "買い物リストは空っぽ！");
        }
        if (text.startsWith("購入 ")) {
            Long id = parseId(text.substring(3));
            if (id == null) return "例：購入 2 のように送ってね！";
            return store.purchaseShoppingItem(userId, id)
                    ? "お買い上げ完了！ #" + id + "\n経験値 +5"
                    : "その未購入アイテムは見つからんかった！";
        }
        if (text.startsWith("予定 ")) {
            return addSchedule(userId, text.substring(3).strip());
        }
        if (text.equals("予定") || text.equals("今日の予定")) {
            return todaySchedules(userId);
        }
        if (text.equals("経験値") || text.equals("レベル")) {
            int exp = store.experience(userId);
            return "いまのベンリー経験値は " + exp + "！\nレベル " + (exp / 100 + 1) + " やでー！";
        }
        if (text.equals("アプリ") || text.equals("ゲーム")) {
            return "自作アプリ一覧\n・宴会ゲーム集：準備中\n・国旗当てゲーム：準備中\n・エコ検定：準備中";
        }
        if (text.equals("時刻") || text.equals("時間")) {
            return ZonedDateTime.now(TOKYO).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        }
        return "受け取ったよ：『" + text + "』\n\n『ヘルプ』で機能別メニューが見られるよ！";
    }

    private Long memoIdByNumber(String userId, long number) {
        if (number < 1 || number > Integer.MAX_VALUE) return null;
        List<BenlyStore.Item> items = store.listMemos(userId);
        int index = (int) number - 1;
        return index < items.size() ? items.get(index).id() : null;
    }

    private String formatMemoSearch(String userId, String keyword) {
        List<BenlyStore.Item> all = store.listMemos(userId);
        List<BenlyStore.Item> matches = store.searchMemos(userId, keyword);
        if (matches.isEmpty()) return "見つからんかった！";
        StringBuilder out = new StringBuilder("『").append(keyword).append("』の検索結果\n");
        for (BenlyStore.Item match : matches) {
            int index = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id() == match.id()) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) out.append("No.").append(index + 1).append(" ").append(match.text()).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private String featureHelp(String text) {
        return switch (text) {
            case "ヘルプ", "使い方", "コマンド", "メニュー" -> mainHelp();
            case "メモヘルプ", "メモ使い方" -> memoHelp();
            case "タスクヘルプ", "タスク使い方" -> taskHelp();
            case "買い物ヘルプ", "買い物使い方" -> shoppingHelp();
            case "予定ヘルプ", "予定使い方" -> scheduleHelp();
            case "その他ヘルプ", "基本ヘルプ" -> utilityHelp();
            default -> text.equalsIgnoreCase("help") ? mainHelp() : null;
        };
    }

    private String mainHelp() {
        return """
                ベンリー 機能メニュー

                📝 メモ
                「メモヘルプ」

                ✅ タスク
                「タスクヘルプ」

                🛒 買い物
                「買い物ヘルプ」

                📅 予定
                「予定ヘルプ」

                ⚙️ その他
                「その他ヘルプ」

                下のボタンからも選べるよ！
                """.strip();
    }

    private String memoHelp() {
        return """
                📝 メモのコマンド

                【追加・確認】
                ・メモ 牛乳を買う
                ・メモ一覧
                ・メモ検索 牛乳

                【整理】
                ・メモ編集 1 牛乳を2本買う
                ・お気に入り 1
                ・タグ 1 買い物,重要

                【削除】
                ・メモ削除 1
                ・メモ全削除

                ※番号はメモ一覧に表示される No.1、No.2…を使ってね。
                """.strip();
    }

    private String taskHelp() {
        return """
                ✅ タスクのコマンド

                【追加・確認】
                ・タスク 部屋を片づける
                ・タスク一覧

                【完了】
                ・完了 1
                """.strip();
    }

    private String shoppingHelp() {
        return """
                🛒 買い物のコマンド

                【追加・確認】
                ・買い物 卵
                ・買い物一覧

                【購入済みにする】
                ・購入 1
                """.strip();
    }

    private String scheduleHelp() {
        return """
                📅 予定のコマンド

                【登録】
                ・予定 2026-07-23 19:00 歯医者

                【確認】
                ・今日の予定
                """.strip();
    }

    private String utilityHelp() {
        return """
                ⚙️ その他のコマンド

                ・経験値
                ・時刻
                ・アプリ
                ・ヘルプ
                """.strip();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u3000', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private String addSchedule(String userId, String input) {
        if (input.length() < 18) return "予定はこの形で送ってね！\n予定 2026-07-23 19:00 歯医者";
        try {
            LocalDateTime local = LocalDateTime.parse(input.substring(0, 16), SCHEDULE_INPUT);
            String title = input.substring(16).strip();
            if (title.isBlank()) return "予定名も書いてなー！";
            long id = store.addSchedule(userId, title, local.atZone(TOKYO).toOffsetDateTime());
            return "予定を登録したよ！\n#" + id + " " + local.format(TIME_OUTPUT) + " " + title;
        } catch (DateTimeParseException e) {
            return "日時を読み取れんかった！\n例：予定 2026-07-23 19:00 歯医者";
        }
    }

    private String todaySchedules(String userId) {
        List<BenlyStore.ScheduleItem> items = store.listTodaySchedules(userId, ZoneOffset.ofHours(9));
        if (items.isEmpty()) return "今日の予定はなし！のびのびいこー！";
        StringBuilder out = new StringBuilder("今日の予定\n");
        for (BenlyStore.ScheduleItem item : items) {
            out.append("・").append(item.startsAt().format(DateTimeFormatter.ofPattern("HH:mm")))
                    .append(" ").append(item.title()).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private String formatItems(String title, List<BenlyStore.Item> items, String emptyText) {
        if (items.isEmpty()) return emptyText;
        StringBuilder out = new StringBuilder(title).append("\n");
        for (int i = 0; i < items.size(); i++) {
            out.append("No.").append(i + 1).append(" ").append(items.get(i).text()).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value.strip().replace("#", "").replace("No.", "").replace("no.", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private IdAndText parseIdAndText(String value) {
        String stripped = value.strip();
        int space = stripped.indexOf(' ');
        if (space <= 0 || space == stripped.length() - 1) return null;
        Long id = parseId(stripped.substring(0, space));
        String content = stripped.substring(space + 1).strip();
        if (id == null || content.isBlank()) return null;
        return new IdAndText(id, content);
    }

    private record IdAndText(long id, String text) {}
}
