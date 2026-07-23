package jp.ryozora.lineassistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompactPrefixInputFilterTest {

    @Test
    void normalizesCompactMemoTaskShoppingAndHabitInputs() {
        assertEquals("メモ 牛乳を買う", CompactPrefixInputFilter.normalize("メモ牛乳を買う").command());
        assertEquals("タスク 資料を提出", CompactPrefixInputFilter.normalize("タスク資料を提出").command());
        assertEquals("買い物 ティッシュ", CompactPrefixInputFilter.normalize("買い物ティッシュ").command());
        assertEquals("習慣 読書", CompactPrefixInputFilter.normalize("習慣読書").command());
        assertEquals("習慣 筋トレ 月水金", CompactPrefixInputFilter.normalize("習慣筋トレ月水金").command());
        assertEquals("習慣 薬 毎日 21:00", CompactPrefixInputFilter.normalize("習慣薬毎日21:00").command());
    }

    @Test
    void leavesExistingSpacedCommandsToNormalHandlers() {
        assertNull(CompactPrefixInputFilter.normalize("メモ 牛乳を買う"));
        assertNull(CompactPrefixInputFilter.normalize("タスク 資料を提出"));
        assertNull(CompactPrefixInputFilter.normalize("習慣 読書 毎日"));
        assertNull(CompactPrefixInputFilter.normalize("買い物 ティッシュ"));
    }

    @Test
    void doesNotTurnManagementCommandsIntoNewItems() {
        assertNull(CompactPrefixInputFilter.normalize("メモ一覧"));
        assertNull(CompactPrefixInputFilter.normalize("メモ削除1"));
        assertNull(CompactPrefixInputFilter.normalize("タスク一覧"));
        assertNull(CompactPrefixInputFilter.normalize("タスク削除1"));
        assertNull(CompactPrefixInputFilter.normalize("買い物一覧"));
        assertNull(CompactPrefixInputFilter.normalize("習慣一覧"));
        assertNull(CompactPrefixInputFilter.normalize("習慣達成1"));
    }
}
