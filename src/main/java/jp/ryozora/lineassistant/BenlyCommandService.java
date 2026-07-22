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

        if (text.equals("ヘルプ") || text.equalsIgnoreCase("help") || text.equals("使い方")) {
            return help();
        }
        if (text.startsWith("メモ ")) {
            String content = text.substring(3).strip();
            if (content.isBlank()) return "メモの内容も書いてなー！";
            long id = store.addMemo(userId, content);
            return "メモしたよ！\n#" + id + " " + content;
        }
        if (text.equals("メモ") || text.equals("メモ一覧")) {
            return formatItems("メモ一覧", store.listMemos(userId), "メモはまだないよ！");
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
        return "受け取ったよ：『" + text + "』\n\n『ヘルプ』でコマンド一覧が見られるよ！";
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw
                .replace('\u3000', ' ')
                .replaceAll("[\\t\\n\\r ]+", " ")
                .strip();
    }

    private String addSchedule(String userId, String input) {
        if (input.length() < 18) {
            return "予定はこの形で送ってね！\n予定 2026-07-23 19:00 歯医者";
        }
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
            out.append("・")
                    .append(item.startsAt().format(DateTimeFormatter.ofPattern("HH:mm")))
                    .append(" ")
                    .append(item.title())
                    .append("\n");
        }
        return out.toString().stripTrailing();
    }

    private String formatItems(String title, List<BenlyStore.Item> items, String emptyText) {
        if (items.isEmpty()) return emptyText;
        StringBuilder out = new StringBuilder(title).append("\n");
        for (BenlyStore.Item item : items) {
            out.append("#").append(item.id()).append(" ").append(item.text()).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value.strip().replace("#", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String help() {
        return """
                ベンリーで使えるコマンド
                ・メモ 牛乳を買う
                ・メモ一覧
                ・タスク 部屋を片づける
                ・タスク一覧
                ・完了 1
                ・買い物 卵
                ・買い物一覧
                ・購入 1
                ・予定 2026-07-23 19:00 歯医者
                ・今日の予定
                ・経験値
                ・時刻
                """.strip();
    }
}
