package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompactExpenseInputFilterTest {

    @Test
    void normalizesCompactPrefixedExpense() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出454昼食"))
                .isEqualTo("支出 454 昼食");
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出記録1,200ランチ"))
                .isEqualTo("支出 1,200 ランチ");
    }

    @Test
    void normalizesCompactAmountFirstExpense() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("454円昼食"))
                .isEqualTo("支出 454 昼食");
    }

    @Test
    void normalizesCompactDescriptionFirstExpense() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("昼食454円"))
                .isEqualTo("支出 昼食 454円");
    }

    @Test
    void keepsAlreadySpacedExpense() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出 454 昼食"))
                .isEqualTo("支出 454 昼食");
    }

    @Test
    void blocksMalformedExpenseFromFallingThroughToScheduleParser() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出昼食"))
                .isEqualTo("支出");
    }

    @Test
    void ignoresNonExpenseCommandsAndManagementCommands() {
        assertThat(CompactExpenseInputFilter.normalizeExpense("明日昼 会議")).isNull();
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出一覧")).isNull();
        assertThat(CompactExpenseInputFilter.normalizeExpense("支出削除 1")).isNull();
    }
}
