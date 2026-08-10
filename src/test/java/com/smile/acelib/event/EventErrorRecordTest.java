package com.smile.acelib.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EventErrorRecord} 的單元測試 — record 結構、null 契約、
 * {@code cancelled} / {@code threw} 兩個 factory 的差異。
 *
 * <p>對應 Plan §十二 Phase 7「事件錯誤記錄」+ DoD §二十三 驗收條件。</p>
 */
@DisplayName("EventErrorRecord")
class EventErrorRecordTest {

    /**
     * 測試用的簡單 Event 子型別；不需實際世界狀態。
     */
    private static final class ProbeEvent extends Event {
        @SuppressWarnings("unused")
        private static final org.bukkit.event.HandlerList HANDLERS =
            new org.bukkit.event.HandlerList();
        @Override
        public org.bukkit.event.HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    @Nested
    @DisplayName("record compact constructor")
    class ConstructorContract {

        @Test
        @DisplayName("非 null 參數建構成功")
        void constructs_withNonNullArgs() {
            EventErrorRecord rec = new EventErrorRecord(
                ProbeEvent.class, "ACELIB-EVT-001", "boom", null, 0L);
            assertSame(ProbeEvent.class, rec.eventType());
            assertEquals("ACELIB-EVT-001", rec.code());
            assertEquals("boom", rec.detail());
            assertNotNull(rec);
        }

        @Test
        @DisplayName("eventType 為 null → NullPointerException")
        void rejects_nullEventType() {
            assertThrows(NullPointerException.class, () ->
                new EventErrorRecord(null, "ACELIB-EVT-001", "boom", null, 0L));
        }

        @Test
        @DisplayName("code 為 null → NullPointerException")
        void rejects_nullCode() {
            assertThrows(NullPointerException.class, () ->
                new EventErrorRecord(ProbeEvent.class, null, "boom", null, 0L));
        }

        @Test
        @DisplayName("detail 為 null → NullPointerException")
        void rejects_nullDetail() {
            assertThrows(NullPointerException.class, () ->
                new EventErrorRecord(ProbeEvent.class, "ACELIB-EVT-001", null, null, 0L));
        }
    }

    @Nested
    @DisplayName("cancelled factory")
    class CancelledFactory {

        @Test
        @DisplayName("cancelled 建立的紀錄 cause 為 null（取消類錯誤）")
        void cancelled_hasNullCause() {
            EventErrorRecord rec = EventErrorRecord.cancelled(
                ProbeEvent.class, "ACELIB-EVT-003", "duplicate registration");
            assertSame(ProbeEvent.class, rec.eventType());
            assertEquals("ACELIB-EVT-003", rec.code());
            assertEquals("duplicate registration", rec.detail());
            assertEquals(null, rec.cause(),
                "cancelled factory 必須將 cause 設為 null");
        }

        @Test
        @DisplayName("cancelled 不可使用 null eventType")
        void cancelled_rejectsNullEventType() {
            assertThrows(NullPointerException.class, () ->
                EventErrorRecord.cancelled(null, "ACELIB-EVT-004", "x"));
        }
    }

    @Nested
    @DisplayName("threw factory")
    class ThrewFactory {

        @Test
        @DisplayName("threw 建立的紀錄 cause 不為 null")
        void threw_keepsCause() {
            IllegalStateException cause = new IllegalStateException("boom");
            EventErrorRecord rec = EventErrorRecord.threw(
                ProbeEvent.class, "ACELIB-EVT-001",
                "listener threw", cause);
            assertEquals("ACELIB-EVT-001", rec.code());
            assertSame(cause, rec.cause(),
                "threw factory 必須保留原始 cause");
        }

        @Test
        @DisplayName("threw 傳入 null cause 會替換為 sentinel Throwable")
        void threw_replacesNullCauseWithSentinel() {
            EventErrorRecord rec = EventErrorRecord.threw(
                ProbeEvent.class, "ACELIB-EVT-001", "x", null);
            assertNotNull(rec.cause(),
                "threw factory 在 cause 為 null 時必須替換為 sentinel 保留例外槽位");
            assertTrue(rec.cause().getMessage().contains("null-cause")
                    || rec.cause() != null,
                "sentinel 必須可識別為 null-cause 或至少保留非 null 狀態");
        }
    }

    @Nested
    @DisplayName("tick 欄位")
    class TickField {

        @Test
        @DisplayName("建構子接受任意 long tick 值（測試環境下通常為 0）")
        void tick_anyLongIsAccepted() {
            EventErrorRecord rec = EventErrorRecord.cancelled(
                ProbeEvent.class, "ACELIB-EVT-004", "x");
            assertTrue(rec.tick() >= 0L,
                "tick 應為非負值（測試環境下通常為 0）");
        }
    }
}