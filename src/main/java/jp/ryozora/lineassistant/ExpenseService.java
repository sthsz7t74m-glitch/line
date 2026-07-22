package jp.ryozora.lineassistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExpenseService {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Pattern NATURAL = Pattern.compile(
            "^(?:(今日|昨日|\\d{1,2}[/-]\\d{1,2})\\s*)?(.+?)[\\s　]*([\\d,]{1,12})\\s*(?:円|えん)$");
    private static final Pattern DATE_PREFIX = Pattern.compile(
            "^(今日|昨日|\\d{1,2}[/-]\\d{1,2})\\s+(.+)$");
    private static final Pattern AMOUNT_FIRST = Pattern.compile(
            "^([\\d,]{1,12})\\s*(?:円|えん)?\\s+(.+)$");
    private static final Pattern DESCRIPTION_FIRST = Pattern.compile(
            "^(.+?)\\s+([\\d,]{1,12})\\s*(?:円|えん)?$");
    private static final Pattern AMOUNT_ONLY = Pattern.compile(
            "^([\\d,]{1,12})\\s*(?:円|えん)?$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private static final List<String> CATEGORIES = List.of(
            "食費", "交通費", "日用品", "娯楽", "医療", "住居", "通信", "その他"
    );

    private final JdbcTemplate jdbc;
    private final BenlyStore store;

    public ExpenseService(JdbcTemplate jdbc, BenlyStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public boolean supports(String raw) {
        String text = normalize(raw);
        if (text.isBlank()) return false;
        if (text.equals("家計簿") || text.equals("家計簿一覧") || text.equals("支出一覧")
                || text.equals("今日いくら") || text.equals("今日の支出")
                || text.equals("今月いくら") || text.equals("今月の支出")
                || text.equals("カテゴリ別") || text.equals("支出カテゴリ")) return true;
        if (text.startsWith("支出 ") || text.startsWith("支出削除 ") || text.startsWith("支出編集 ")) return true;
        if (categoryFromQuery(text) != null) return true;
        if (text.contains("収入") || text.contains("給料")) return false;
        return NATURAL.matcher(text).matches();
    }

    public String handle(String userId, String raw) {
        String text = normalize(raw);
        store.ensureUser(userId);

        if (text.startsWith("支出削除 ")) return delete(userId, text.substring(5).strip());
        if (text.startsWith("支出編集 ")) return edit(userId, text.substring(5).strip());
        if (text.equals("家計簿一覧") || text.equals("支出一覧")) return list(userId);
        if (text.equals("今日いくら") || text.equals("今日の支出")) return todaySummary(userId);
        if (text.equals("カテゴリ別") || text.equals("支出カテゴリ")) return categorySummary(userId);

        String requestedCategory = categoryFromQuery(text);
        if (requestedCategory != null) return categoryDetail(userId, requestedCategory);

        if (text.equals("家計簿") || text.equals("今月いくら") || text.equals("今月の支出")) {
            return monthlySummary(userId);
        }
        return add(userId, text);
    }

    @Transactional
    String add(String userId, String raw) {
        ParsedExpense parsed = parseExpense(raw);
        if (parsed == null) {
            return "金額を読み取れなかったよ。例：昼1200円 / 支出 980 電車";
        }
        jdbc.update("""
                insert into expenses(line_user_id, amount, description, category, spent_on)
                values (?,?,?,?,?)
                """, userId, parsed.amount(), parsed.description(), parsed.category(), parsed.spentOn());

        long monthTotal = monthlyTotal(userId, YearMonth.from(parsed.spentOn()));
        return """
                家計簿に記録したよ。
                日付　%s
                内容　%s
                分類　%s
                金額　%s

                この月の合計　%s
                """.formatted(
                parsed.spentOn().format(DateTimeFormatter.ofPattern("M/d")),
                parsed.description(), parsed.category(), yen(parsed.amount()), yen(monthTotal)).strip();
    }

    String list(String userId) {
        List<ExpenseRow> rows = recentRows(userId);
        if (rows.isEmpty()) return "家計簿はまだ空っぽ。『昼1200円』のように送ると記録できるよ。";

        StringBuilder out = new StringBuilder("最近の支出\n");
        for (int i = 0; i < rows.size(); i++) {
            ExpenseRow row = rows.get(i);
            out.append(i + 1).append(".　")
                    .append(row.spentOn().format(DateTimeFormatter.ofPattern("M/d"))).append("　")
                    .append(row.category()).append("　")
                    .append(yen(row.amount())).append("　")
                    .append(row.description()).append("\n");
        }
        out.append("\n削除：支出削除 1\n編集：支出編集 1 1500 昼ごはん");
        return out.toString().stripTrailing();
    }

    @Transactional
    String delete(String userId, String value) {
        Integer number = parseNumber(value);
        if (number == null || number < 1) return "例：支出削除 2 のように送ってね。";
        List<ExpenseRow> rows = recentRows(userId);
        if (number > rows.size()) return "その番号の支出は見つからなかったよ。『支出一覧』で確認してね。";
        ExpenseRow row = rows.get(number - 1);
        int changed = jdbc.update("delete from expenses where id=? and line_user_id=?", row.id(), userId);
        return changed == 1
                ? "支出「" + row.description() + " " + yen(row.amount()) + "」を削除したよ。"
                : "支出を削除できなかったよ。";
    }

    @Transactional
    String edit(String userId, String value) {
        int space = value.indexOf(' ');
        if (space < 1) return "例：支出編集 2 1500 昼ごはん のように送ってね。";
        Integer number = parseNumber(value.substring(0, space));
        if (number == null || number < 1) return "支出番号を読み取れなかったよ。";
        List<ExpenseRow> rows = recentRows(userId);
        if (number > rows.size()) return "その番号の支出は見つからなかったよ。";

        ExpenseRow old = rows.get(number - 1);
        ParsedExpense parsed = parseExpense(value.substring(space + 1).strip());
        if (parsed == null) return "変更内容を読み取れなかったよ。例：支出編集 2 1500 昼ごはん";
        LocalDate spentOn = parsed.explicitDate() ? parsed.spentOn() : old.spentOn();

        jdbc.update("""
                update expenses
                set amount=?, description=?, category=?, spent_on=?, updated_at=current_timestamp
                where id=? and line_user_id=?
                """, parsed.amount(), parsed.description(), parsed.category(), spentOn, old.id(), userId);
        return "支出を変更したよ。\n"
                + spentOn.format(DateTimeFormatter.ofPattern("M/d")) + "　"
                + parsed.description() + "　" + yen(parsed.amount()) + "　［" + parsed.category() + "］";
    }

    String todaySummary(String userId) {
        LocalDate today = LocalDate.now(TOKYO);
        List<ExpenseRow> rows = rowsBetween(userId, today, today.plusDays(1), 30);
        if (rows.isEmpty()) return "今日の支出はまだ0円だよ。";
        long total = rows.stream().mapToLong(ExpenseRow::amount).sum();
        StringBuilder out = new StringBuilder("今日の支出　").append(yen(total)).append("\n\n");
        rows.stream().limit(10).forEach(row -> out.append("・")
                .append(row.description()).append("　").append(yen(row.amount())).append("\n"));
        if (rows.size() > 10) out.append("ほか").append(rows.size() - 10).append("件\n");
        return out.toString().stripTrailing();
    }

    String monthlySummary(String userId) {
        YearMonth month = YearMonth.now(TOKYO);
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        long total = totalBetween(userId, start, end, null);
        int count = countBetween(userId, start, end, null);
        List<CategoryTotal> categories = categoryTotals(userId, start, end);

        if (count == 0) return month.getMonthValue() + "月の支出はまだ0円だよ。";
        int elapsed = LocalDate.now(TOKYO).getDayOfMonth();
        long dailyAverage = elapsed == 0 ? total : total / elapsed;

        StringBuilder out = new StringBuilder()
                .append(month.getYear()).append("年").append(month.getMonthValue()).append("月の家計簿\n")
                .append("━━━━━━━━━━\n")
                .append("合計　").append(yen(total)).append("\n")
                .append("件数　").append(count).append("件\n")
                .append("1日平均　").append(yen(dailyAverage)).append("\n\n");
        categories.stream().limit(5).forEach(category -> out.append(category.category())
                .append("　").append(yen(category.total())).append("\n"));
        return out.toString().stripTrailing();
    }

    String categorySummary(String userId) {
        YearMonth month = YearMonth.now(TOKYO);
        List<CategoryTotal> categories = categoryTotals(userId, month.atDay(1), month.plusMonths(1).atDay(1));
        if (categories.isEmpty()) return "今月のカテゴリ別支出はまだないよ。";
        long total = categories.stream().mapToLong(CategoryTotal::total).sum();
        StringBuilder out = new StringBuilder("今月のカテゴリ別支出\n\n");
        categories.forEach(category -> out.append(category.category()).append("　")
                .append(yen(category.total())).append("\n"));
        return out.append("\n合計　").append(yen(total)).toString();
    }

    String categoryDetail(String userId, String category) {
        YearMonth month = YearMonth.now(TOKYO);
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        long total = totalBetween(userId, start, end, category);
        int count = countBetween(userId, start, end, category);
        return "今月の" + category + "は " + yen(total) + "（" + count + "件）だよ。";
    }

    private ParsedExpense parseExpense(String raw) {
        String text = normalize(raw);
        if (text.startsWith("支出 ")) text = text.substring(3).strip();
        if (text.isBlank()) return null;

        Matcher natural = NATURAL.matcher(text);
        if (natural.matches()) {
            DateValue date = parseDate(natural.group(1));
            String description = cleanDescription(natural.group(2));
            Integer amount = parseAmount(natural.group(3));
            return buildParsed(amount, description, date.date(), date.explicit());
        }

        DateValue date = new DateValue(LocalDate.now(TOKYO), false);
        Matcher datePrefix = DATE_PREFIX.matcher(text);
        if (datePrefix.matches()) {
            date = parseDate(datePrefix.group(1));
            text = datePrefix.group(2).strip();
        }

        Matcher amountFirst = AMOUNT_FIRST.matcher(text);
        if (amountFirst.matches()) {
            return buildParsed(parseAmount(amountFirst.group(1)), cleanDescription(amountFirst.group(2)),
                    date.date(), date.explicit());
        }
        Matcher descriptionFirst = DESCRIPTION_FIRST.matcher(text);
        if (descriptionFirst.matches()) {
            return buildParsed(parseAmount(descriptionFirst.group(2)), cleanDescription(descriptionFirst.group(1)),
                    date.date(), date.explicit());
        }
        Matcher amountOnly = AMOUNT_ONLY.matcher(text);
        if (amountOnly.matches()) {
            return buildParsed(parseAmount(amountOnly.group(1)), "支出", date.date(), date.explicit());
        }
        return null;
    }

    private ParsedExpense buildParsed(Integer amount, String description, LocalDate spentOn, boolean explicitDate) {
        if (amount == null || amount <= 0 || amount > 100_000_000) return null;
        if (description == null || description.isBlank()) description = "支出";
        if (description.length() > 500) description = description.substring(0, 500);
        return new ParsedExpense(amount, description, classify(description), spentOn, explicitDate);
    }

    private DateValue parseDate(String value) {
        LocalDate today = LocalDate.now(TOKYO);
        if (value == null || value.isBlank() || value.equals("今日")) {
            return new DateValue(today, value != null && !value.isBlank());
        }
        if (value.equals("昨日")) return new DateValue(today.minusDays(1), true);
        Matcher matcher = Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})$").matcher(value);
        if (!matcher.matches()) return new DateValue(today, false);
        try {
            LocalDate date = LocalDate.of(today.getYear(), Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
            if (date.isAfter(today)) date = date.minusYears(1);
            return new DateValue(date, true);
        } catch (DateTimeException e) {
            return new DateValue(today, false);
        }
    }

    private String classify(String description) {
        String text = description.toLowerCase(Locale.JAPANESE);
        if (containsAny(text, "朝", "昼", "夜", "ご飯", "ごはん", "ランチ", "弁当", "カフェ", "スタバ",
                "コンビニ", "スーパー", "食品", "食材", "飲み", "酒", "居酒屋", "レストラン")) return "食費";
        if (containsAny(text, "電車", "バス", "タクシー", "ガソリン", "駐車", "高速", "交通", "新幹線")) return "交通費";
        if (containsAny(text, "洗剤", "ティッシュ", "トイレットペーパー", "シャンプー", "日用品", "ドラッグストア")) return "日用品";
        if (containsAny(text, "映画", "ゲーム", "ライブ", "カラオケ", "本", "漫画", "遊び", "娯楽", "サブスク")) return "娯楽";
        if (containsAny(text, "病院", "歯医者", "薬", "診察", "医療", "眼科", "整骨")) return "医療";
        if (containsAny(text, "家賃", "住宅", "管理費", "電気", "ガス", "水道")) return "住居";
        if (containsAny(text, "携帯", "スマホ", "通信", "ネット", "wifi", "wi-fi")) return "通信";
        return "その他";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String categoryFromQuery(String text) {
        for (String category : CATEGORIES) {
            if (text.equals(category + "いくら") || text.equals(category + "はいくら")
                    || text.equals("今月の" + category) || text.equals(category + "の支出")) return category;
        }
        return null;
    }

    private List<ExpenseRow> recentRows(String userId) {
        return jdbc.query("""
                select id, amount, description, category, spent_on
                from expenses where line_user_id=?
                order by spent_on desc, id desc limit 30
                """, (rs, i) -> new ExpenseRow(rs.getLong("id"), rs.getInt("amount"),
                rs.getString("description"), rs.getString("category"), rs.getDate("spent_on").toLocalDate()), userId);
    }

    private List<ExpenseRow> rowsBetween(String userId, LocalDate start, LocalDate end, int limit) {
        return jdbc.query("""
                select id, amount, description, category, spent_on
                from expenses
                where line_user_id=? and spent_on>=? and spent_on<?
                order by spent_on desc, id desc limit ?
                """, (rs, i) -> new ExpenseRow(rs.getLong("id"), rs.getInt("amount"),
                rs.getString("description"), rs.getString("category"), rs.getDate("spent_on").toLocalDate()),
                userId, start, end, limit);
    }

    private List<CategoryTotal> categoryTotals(String userId, LocalDate start, LocalDate end) {
        return jdbc.query("""
                select category, coalesce(sum(amount),0) total
                from expenses
                where line_user_id=? and spent_on>=? and spent_on<?
                group by category order by total desc
                """, (rs, i) -> new CategoryTotal(rs.getString("category"), rs.getLong("total")),
                userId, start, end);
    }

    private long monthlyTotal(String userId, YearMonth month) {
        return totalBetween(userId, month.atDay(1), month.plusMonths(1).atDay(1), null);
    }

    private long totalBetween(String userId, LocalDate start, LocalDate end, String category) {
        Long value;
        if (category == null) {
            value = jdbc.queryForObject("""
                    select coalesce(sum(amount),0) from expenses
                    where line_user_id=? and spent_on>=? and spent_on<?
                    """, Long.class, userId, start, end);
        } else {
            value = jdbc.queryForObject("""
                    select coalesce(sum(amount),0) from expenses
                    where line_user_id=? and spent_on>=? and spent_on<? and category=?
                    """, Long.class, userId, start, end, category);
        }
        return value == null ? 0 : value;
    }

    private int countBetween(String userId, LocalDate start, LocalDate end, String category) {
        Integer value;
        if (category == null) {
            value = jdbc.queryForObject("""
                    select count(*) from expenses
                    where line_user_id=? and spent_on>=? and spent_on<?
                    """, Integer.class, userId, start, end);
        } else {
            value = jdbc.queryForObject("""
                    select count(*) from expenses
                    where line_user_id=? and spent_on>=? and spent_on<? and category=?
                    """, Integer.class, userId, start, end, category);
        }
        return value == null ? 0 : value;
    }

    private Integer parseAmount(String value) {
        if (value == null) return null;
        try {
            long amount = Long.parseLong(value.replace(",", ""));
            return amount > Integer.MAX_VALUE ? null : (int) amount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseNumber(String value) {
        Matcher matcher = NUMBER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String cleanDescription(String value) {
        if (value == null) return "支出";
        String result = value.strip().replaceAll("^[・:：]", "").strip();
        return result.isBlank() ? "支出" : result;
    }

    private String yen(long amount) {
        return String.format(Locale.JAPAN, "%,d円", amount);
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('　', ' ').replaceAll("[\\t\\n\\r ]+", " ").strip();
    }

    private record ParsedExpense(int amount, String description, String category,
                                 LocalDate spentOn, boolean explicitDate) {}
    private record DateValue(LocalDate date, boolean explicit) {}
    private record ExpenseRow(long id, int amount, String description, String category, LocalDate spentOn) {}
    private record CategoryTotal(String category, long total) {}
}
