package com.smile.acelib.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EventErrorRecorder} 的單元測試 — 容量策略、查詢語意、
 * {@code contains}/{@code getRecentErrorsFor} 等便利方法。
 *
 * <p>對應 Plan §十二 Phase 7「事件錯誤可記錄並定位來源」。</p>
 */
@DisplayName("EventErrorRecorder")
class EventErrorRecorderTest {

    /**
     * 測試用的兩個 Event 子型別，用於驗證 {@code getRecentErrorsFor} 過濾語意。
     */
    private static final class AlphaEvent extends Event {
        @SuppressWarnings("unused")
        private static final HandlerList HANDLERS = new HandlerList();
        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static final class BetaEvent extends Event {
        @SuppressWarnings("unused")
        private static final HandlerList HANDLERS = new HandlerList();
        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private EventErrorRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new EventErrorRecorder();
    }

    @Nested
    @DisplayName("建構子")
    class Construction {

        @Test
        @DisplayName("預設建構子使用容量 100")
        void defaultCapacity_is100() {
            assertEquals(EventErrorRecorder.DEFAULT_CAPACITY, recorder.getCapacity());
            assertEquals(100, recorder.getCapacity());
        }

        @Test
        @DisplayName("自訂容量建構子必須接受 > 0 的值")
        void customCapacity_positive() {
            EventErrorRecorder r = new EventErrorRecorder(50);
            assertEquals(50, r.getCapacity());
        }

        @Test
        @DisplayName("容量 <= 0 必須拋 IllegalArgumentException")
        void capacity_zeroOrNegative_throws() {
            assertThrows(IllegalArgumentException.class, () -> new EventErrorRecorder(0));
            assertThrows(IllegalArgumentException.class, () -> new EventErrorRecorder(-1));
        }
    }

    @Nested
    @DisplayName("record / null 處理")
    class RecordNull {

        @Test
        @DisplayName("record(null) 為 no-op，不丟例外")
        void record_null_isNoop() {
            assertEquals(0, recorder.getErrorCount());
            recorder.record(null);
            assertEquals(0, recorder.getErrorCount(),
                "record(null) 必須為 no-op（避免上游 NPE）");
        }

        @Test
        @DisplayName("record(null) 之後仍可正常 record 非 null")
        void record_nullThenNonNull_works() {
            recorder.record(null);
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            assertEquals(1, recorder.getErrorCount());
        }
    }

    @Nested
    @DisplayName("FIFO 容量策略")
    class FifoCapacity {

        @Test
        @DisplayName("超出容量時最舊紀錄被淘汰（FIFO）")
        void overflow_evictsOldest() {
            EventErrorRecorder r = new EventErrorRecorder(3);
            r.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "first"));
            r.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "second"));
            r.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "third"));
            r.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "fourth"));

            assertEquals(3, r.getErrorCount());
            List<EventErrorRecord> errors = r.getRecentErrors(10);
            assertEquals("second", errors.get(0).detail());
            assertEquals("third", errors.get(1).detail());
            assertEquals("fourth", errors.get(2).detail(),
                "超過容量時最舊紀錄必須被淘汰");
        }
    }

    @Nested
    @DisplayName("getRecentErrors(int max)")
    class GetRecentErrors {

        @Test
        @DisplayName("max <= 0 回傳空清單")
        void nonPositive_returnsEmpty() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            assertTrue(recorder.getRecentErrors(0).isEmpty());
            assertTrue(recorder.getRecentErrors(-1).isEmpty());
        }

        @Test
        @DisplayName("max 大於總數回傳全部")
        void maxTooLarge_returnsAll() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            recorder.record(EventErrorRecord.cancelled(
                BetaEvent.class, "ACELIB-EVT-002", "y"));
            assertEquals(2, recorder.getRecentErrors(100).size());
        }

        @Test
        @DisplayName("回傳清單不可變")
        void returnedList_isImmutable() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            List<EventErrorRecord> errors = recorder.getRecentErrors(10);
            assertThrows(UnsupportedOperationException.class,
                () -> errors.add(EventErrorRecord.cancelled(
                    BetaEvent.class, "ACELIB-EVT-002", "y")));
        }
    }

    @Nested
    @DisplayName("contains")
    class Contains {

        @Test
        @DisplayName("曾 record 的 code 必須可被 contains 查到")
        void contains_found() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            assertTrue(recorder.contains("ACELIB-EVT-001"));
        }

        @Test
        @DisplayName("contains 對未記錄的 code 回傳 false")
        void contains_notFound() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            assertFalse(recorder.contains("ACELIB-EVT-002"));
        }

        @Test
        @DisplayName("contains(null) 回傳 false（不丟例外）")
        void contains_null_returnsFalse() {
            assertFalse(recorder.contains(null));
        }
    }

    @Nested
    @DisplayName("getRecentErrorsFor")
    class GetRecentErrorsFor {

        @Test
        @DisplayName("依 eventType 過濾最近 N 筆紀錄")
        void filtersByEventType() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "alpha-1"));
            recorder.record(EventErrorRecord.cancelled(
                BetaEvent.class, "ACELIB-EVT-002", "beta-1"));
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "alpha-2"));

            List<EventErrorRecord> alphas =
                recorder.getRecentErrorsFor(AlphaEvent.class, 10);
            assertEquals(2, alphas.size());
            assertEquals("alpha-1", alphas.get(0).detail());
            assertEquals("alpha-2", alphas.get(1).detail());
        }

        @Test
        @DisplayName("getRecentErrorsFor 對無匹配 eventType 回傳空清單")
        void noMatch_returnsEmpty() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            List<EventErrorRecord> betas =
                recorder.getRecentErrorsFor(BetaEvent.class, 10);
            assertNotNull(betas);
            assertTrue(betas.isEmpty());
        }

        @Test
        @DisplayName("getRecentErrorsFor(null eventType) → NullPointerException")
        void nullEventType_throws() {
            assertThrows(NullPointerException.class, () ->
                recorder.getRecentErrorsFor(null, 10));
        }

        @Test
        @DisplayName("getRecentErrorsFor 的 max 過濾只保留最後 N 筆")
        void maxFilter_keepsLastN() {
            for (int i = 0; i < 5; i++) {
                recorder.record(EventErrorRecord.cancelled(
                    AlphaEvent.class, "ACELIB-EVT-001", "alpha-" + i));
            }
            List<EventErrorRecord> last2 =
                recorder.getRecentErrorsFor(AlphaEvent.class, 2);
            assertEquals(2, last2.size());
            assertEquals("alpha-3", last2.get(0).detail());
            assertEquals("alpha-4", last2.get(1).detail());
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearOp {

        @Test
        @DisplayName("clear 後 getErrorCount 歸 0、contains 失效")
        void clear_resets() {
            recorder.record(EventErrorRecord.cancelled(
                AlphaEvent.class, "ACELIB-EVT-001", "x"));
            assertEquals(1, recorder.getErrorCount());
            assertSame(recorder, recorder);
            recorder.clear();
            assertEquals(0, recorder.getErrorCount());
            assertFalse(recorder.contains("ACELIB-EVT-001"));
        }
    }
}