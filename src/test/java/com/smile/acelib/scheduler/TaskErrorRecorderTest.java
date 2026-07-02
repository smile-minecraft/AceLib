package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TaskErrorRecorder 單元測試。
 *
 * <p>對應 Plan §七 Phase 2「任務錯誤留下可追蹤紀錄」需求。
 * 純邏輯測試，不依賴 Bukkit / MockBukkit。</p>
 */
@DisplayName("TaskErrorRecorder")
class TaskErrorRecorderTest {

    @Nested
    @DisplayName("建構子與容量")
    class Constructor {

        @Test
        @DisplayName("預設容量為 100")
        void defaultCapacity_is100() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            assertEquals(100, r.getCapacity());
        }

        @Test
        @DisplayName("自訂容量")
        void customCapacity_accepted() {
            TaskErrorRecorder r = new TaskErrorRecorder(50);
            assertEquals(50, r.getCapacity());
        }

        @Test
        @DisplayName("容量 0 必須拋 IllegalArgumentException")
        void capacityZero_throws() {
            assertThrows(IllegalArgumentException.class, () -> new TaskErrorRecorder(0));
        }

        @Test
        @DisplayName("負容量必須拋 IllegalArgumentException")
        void negativeCapacity_throws() {
            assertThrows(IllegalArgumentException.class, () -> new TaskErrorRecorder(-1));
        }
    }

    @Nested
    @DisplayName("record 與容量淘汰")
    class RecordAndEvict {

        @Test
        @DisplayName("record 寫入後 getErrorCount 反映筆數")
        void record_incrementsCount() {
            TaskErrorRecorder r = new TaskErrorRecorder(5);
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
            r.record(TaskErrorRecord.cancelled(TaskType.LATER, "Y", "y"));
            assertEquals(2, r.getErrorCount());
        }

        @Test
        @DisplayName("容量超限時淘汰最舊紀錄（FIFO）")
        void record_evictsOldestWhenFull() {
            TaskErrorRecorder r = new TaskErrorRecorder(3);
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "C1", "1"));
            r.record(TaskErrorRecord.cancelled(TaskType.LATER, "C2", "2"));
            r.record(TaskErrorRecord.cancelled(TaskType.TIMER, "C3", "3"));
            r.record(TaskErrorRecord.cancelled(TaskType.ASYNC, "C4", "4"));
            // 容量 3，最舊的 C1 應被淘汰
            assertEquals(3, r.getErrorCount());
            assertFalse(r.contains("C1"), "C1 應被淘汰");
            assertTrue(r.contains("C2"));
            assertTrue(r.contains("C3"));
            assertTrue(r.contains("C4"));
        }

        @Test
        @DisplayName("record(null) 為 no-op")
        void recordNull_isNoOp() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(null);
            assertEquals(0, r.getErrorCount());
        }

        @Test
        @DisplayName("大量寫入後只保留最近 N 筆")
        void record_manyEntries_keepsLastN() {
            TaskErrorRecorder r = new TaskErrorRecorder(10);
            for (int i = 0; i < 100; i++) {
                r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "C" + i, "d" + i));
            }
            assertEquals(10, r.getErrorCount());
            // 最早的 C0~C89 都應被淘汰
            assertFalse(r.contains("C0"));
            assertFalse(r.contains("C89"));
            // C90~C99 仍保留
            assertTrue(r.contains("C99"));
            assertTrue(r.contains("C90"));
        }
    }

    @Nested
    @DisplayName("getRecentErrors")
    class GetRecent {

        @Test
        @DisplayName("max <= 0 回傳空清單")
        void getRecent_zeroOrNegative_returnsEmpty() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
            assertTrue(r.getRecentErrors(0).isEmpty());
            assertTrue(r.getRecentErrors(-5).isEmpty());
        }

        @Test
        @DisplayName("max >= size 回傳全部")
        void getRecent_largerThanSize_returnsAll() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "A", "a"));
            r.record(TaskErrorRecord.cancelled(TaskType.LATER, "B", "b"));
            List<TaskErrorRecord> all = r.getRecentErrors(100);
            assertEquals(2, all.size());
            // 時間順序：舊到新
            assertEquals("A", all.get(0).code());
            assertEquals("B", all.get(1).code());
        }

        @Test
        @DisplayName("max < size 回傳最後 N 筆")
        void getRecent_smallerThanSize_returnsLastN() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            for (int i = 0; i < 5; i++) {
                r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "C" + i, "d" + i));
            }
            List<TaskErrorRecord> last2 = r.getRecentErrors(2);
            assertEquals(2, last2.size());
            assertEquals("C3", last2.get(0).code());
            assertEquals("C4", last2.get(1).code());
        }

        @Test
        @DisplayName("回傳的清單不可變")
        void getRecent_returnsImmutable() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
            List<TaskErrorRecord> errors = r.getRecentErrors(10);
            assertThrows(UnsupportedOperationException.class,
                () -> errors.add(TaskErrorRecord.cancelled(TaskType.GLOBAL, "Y", "y")));
        }
    }

    @Nested
    @DisplayName("contains 查詢")
    class Contains {

        @Test
        @DisplayName("存在時回傳 true")
        void contains_existing_returnsTrue() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002", "x"));
            assertTrue(r.contains("ACELIB-SCHED-002"));
        }

        @Test
        @DisplayName("不存在時回傳 false")
        void contains_missing_returnsFalse() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002", "x"));
            assertFalse(r.contains("ACELIB-SCHED-999"));
        }

        @Test
        @DisplayName("null code 必須回傳 false，不丟例外")
        void contains_null_returnsFalse() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002", "x"));
            assertFalse(r.contains(null));
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("clear 後 getErrorCount 為 0，contains 為 false")
        void clear_resetsAll() {
            TaskErrorRecorder r = new TaskErrorRecorder();
            r.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
            r.record(TaskErrorRecord.cancelled(TaskType.LATER, "Y", "y"));
            assertEquals(2, r.getErrorCount());
            r.clear();
            assertEquals(0, r.getErrorCount());
            assertFalse(r.contains("X"));
        }
    }

    @Nested
    @DisplayName("TaskErrorRecord factory")
    class Factories {

        @Test
        @DisplayName("cancelled factory: cause 為 null")
        void cancelled_causeIsNull() {
            TaskErrorRecord r = TaskErrorRecord.cancelled(
                TaskType.GLOBAL, "X", "detail");
            assertNotNull(r);
            assertEquals(TaskType.GLOBAL, r.type());
            assertEquals("X", r.code());
            assertEquals("detail", r.detail());
            org.junit.jupiter.api.Assertions.assertNull(r.cause(),
                "cancelled factory 的 cause 應為 null");
        }

        @Test
        @DisplayName("threw factory: cause 為非 null")
        void threw_causePreserved() {
            Throwable t = new IllegalStateException("boom");
            TaskErrorRecord r = TaskErrorRecord.threw(
                TaskType.GLOBAL, "X", "detail", t);
            assertNotNull(r.cause());
            assertEquals(t, r.cause());
        }

        @Test
        @DisplayName("threw factory: null cause 會被替換為 sentinel")
        void threw_nullCause_replacedWithSentinel() {
            TaskErrorRecord r = TaskErrorRecord.threw(
                TaskType.GLOBAL, "X", "detail", null);
            assertNotNull(r.cause(), "threw factory 必須有 cause（即使是 sentinel）");
        }

        @Test
        @DisplayName("record compact constructor: null 必填欄位必須拋 NPE")
        void record_nullRequiredFields_throw() {
            assertThrows(NullPointerException.class,
                () -> new TaskErrorRecord(null, "X", "d", null, 0L));
            assertThrows(NullPointerException.class,
                () -> new TaskErrorRecord(TaskType.GLOBAL, null, "d", null, 0L));
            assertThrows(NullPointerException.class,
                () -> new TaskErrorRecord(TaskType.GLOBAL, "X", null, null, 0L));
        }
    }

    @Test
    @DisplayName("contentEquals 對相同內容回傳 true")
    void contentEquals_sameContent_true() {
        TaskErrorRecorder a = new TaskErrorRecorder();
        TaskErrorRecorder b = new TaskErrorRecorder();
        a.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
        b.record(TaskErrorRecord.cancelled(TaskType.GLOBAL, "X", "x"));
        assertTrue(a.contentEquals(b));
    }

    @Test
    @DisplayName("contentEquals 對 null 回傳 false")
    void contentEquals_null_false() {
        TaskErrorRecorder a = new TaskErrorRecorder();
        assertFalse(a.contentEquals(null));
    }
}