package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RpgService {
    private final BenlyStore store;
    private final JdbcTemplate jdbc;

    public RpgService(BenlyStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        return text.equals("プロフィール") || text.equals("ステータス")
                || text.equals("レベル") || text.equals("経験値") || text.equals("称号")
                || text.equals("実績") || text.equals("実績一覧") || text.equals("バッジ");
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);
        return text.equals("実績") || text.equals("実績一覧") || text.equals("バッジ")
                ? achievements(userId) : profile(userId);
    }

    public String profile(String userId) {
        Stats stats = stats(userId);
        int exp = store.experience(userId);
        int level = level(exp);
        int progress = exp % 100;
        int remaining = 100 - progress;
        String gauge = "■".repeat(progress / 10) + "□".repeat(10 - progress / 10);
        int unlocked = achievementList(stats, exp).stream().mapToInt(a -> a.unlocked() ? 1 : 0).sum();

        return """
                ベンリー冒険者プロフィール
                ━━━━━━━━━━
                レベル %d
                称号「%s」
                累計経験値 %d
                %s  %d/100
                次のレベルまで %d EXP

                解除した実績 %d/%d
                タスク完了 %d件
                買い物完了 %d件
                登録した予定 %d件
                保存中のメモ %d件
                """.formatted(level, title(level), exp, gauge, progress, remaining,
                unlocked, achievementList(stats, exp).size(), stats.completedTasks(),
                stats.purchasedItems(), stats.schedules(), stats.memos()).strip();
    }

    public String achievements(String userId) {
        Stats stats = stats(userId);
        int exp = store.experience(userId);
        List<Achievement> achievements = achievementList(stats, exp);
        long unlocked = achievements.stream().filter(Achievement::unlocked).count();

        StringBuilder out = new StringBuilder("実績一覧  ")
                .append(unlocked).append("/").append(achievements.size()).append("\n\n");
        for (Achievement achievement : achievements) {
            out.append(achievement.unlocked() ? "【解除】" : "【未解除】")
                    .append(achievement.name()).append("\n")
                    .append("  ").append(achievement.description()).append("\n");
        }
        if (unlocked == achievements.size()) {
            out.append("\n全実績達成！かなり使い込んでるね。");
        } else {
            out.append("\n機能を使うほど、新しい実績が解除されるよ。");
        }
        return out.toString().stripTrailing();
    }

    public int level(int experience) {
        return Math.max(1, experience / 100 + 1);
    }

    public String title(int level) {
        if (level >= 20) return "伝説の万能秘書";
        if (level >= 15) return "暮らしの司令塔";
        if (level >= 10) return "頼れる相棒";
        if (level >= 7) return "段取りの達人";
        if (level >= 5) return "しっかり管理人";
        if (level >= 3) return "駆け出し秘書";
        return "はじめての冒険者";
    }

    private Stats stats(String userId) {
        return new Stats(
                count("select count(*) from memos where line_user_id=? and archived=false", userId),
                count("select count(*) from tasks where line_user_id=? and completed=true", userId),
                count("select count(*) from shopping_items where line_user_id=? and purchased=true", userId),
                count("select count(*) from schedules where line_user_id=?", userId)
        );
    }

    private int count(String sql, String userId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    private List<Achievement> achievementList(Stats stats, int exp) {
        List<Achievement> list = new ArrayList<>();
        list.add(new Achievement("最初の一歩", "いずれかの機能を使って経験値を獲得", exp >= 1));
        list.add(new Achievement("クエスト初心者", "タスクを1件完了", stats.completedTasks() >= 1));
        list.add(new Achievement("クエスト職人", "タスクを10件完了", stats.completedTasks() >= 10));
        list.add(new Achievement("クエストマスター", "タスクを50件完了", stats.completedTasks() >= 50));
        list.add(new Achievement("買い物上手", "買い物を5件完了", stats.purchasedItems() >= 5));
        list.add(new Achievement("買い物の達人", "買い物を30件完了", stats.purchasedItems() >= 30));
        list.add(new Achievement("予定のある暮らし", "予定を1件登録", stats.schedules() >= 1));
        list.add(new Achievement("段取り名人", "予定を30件登録", stats.schedules() >= 30));
        list.add(new Achievement("記録好き", "メモを10件保存", stats.memos() >= 10));
        list.add(new Achievement("知識の倉庫", "メモを100件保存", stats.memos() >= 100));
        list.add(new Achievement("成長中", "累計経験値500", exp >= 500));
        list.add(new Achievement("ベンリー熟練者", "累計経験値1000", exp >= 1000));
        return list;
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record Stats(int memos, int completedTasks, int purchasedItems, int schedules) {}
    private record Achievement(String name, String description, boolean unlocked) {}
}
