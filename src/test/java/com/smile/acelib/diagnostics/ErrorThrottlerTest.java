package com.smile.acelib.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ErrorThrottler 單元測試。
 *
 * <p>對應 Plan §十九 Phase 14「同一錯誤大量發生時不無限洗版」需求。
 * 測試全程使用 {@link FakeClock} 注入時間，<strong>禁止 sleep</strong>。</p>
 */
@DisplayName("ErrorThrottler — 確定性節流")
class ErrorThrottlerTest {

    /**
     * 測試用 deterministic clock。
     * 呼叫 {@link #advance(long)} 推進時間，不依賴系統時鐘。
     */
    private static final class FakeClock implements Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public long currentTimeMillis() {
            return millis.get();
        }

        void advance(long deltaMs) {
            millis.addAndGet(deltaMs);
        }
    }

    private static Clock fakeClock() {
        return new FakeClock();
    }

    @Nested
    @DisplayName("基本節流行為")
    class BasicBehavior {

        @Test
        @DisplayName("新代碼第一次嘗試必須 ALLOWED")
        void firstOccurrence_allowed() {
            ErrorThrottler t = new ErrorThrottler(fakeClock());
            ThrottleDecision d = t.tryRecord("ACELIB-SCHED-001", "detail");
            assertEquals(ThrottleDecision.Kind.ALLOWED, d.kind());
        }

        @Test
        @DisplayName("視窗內同代碼重複 → SUPPRESSED（duplicate suppression 需 max=1）")
        void duplicateInWindow_suppressed() {
            FakeClock clock = new FakeClock();
            // 【fixture 修正說明】原 fixture 使用 max=5 卻斷言第二次 SUPPRESSED，
            // 與 ErrorThrottler 建構子 Javadoc「maxPerWindow 視窗內最多 ALLOWED 次數」
            // 的字面契約矛盾（max=5 應放行前 5 次）。
            // 「視窗內重複即抑制」屬於 duplicate suppression 語意，必須以 max=1 表達。
            // 對應修正：fixture 改用 max=1，斷言維持「第二次 SUPPRESSED」。
            ErrorThrottler t = new ErrorThrottler(clock, 1, 1_000L);
            t.tryRecord("ACELIB-SCHED-001", "x");
            clock.advance(100L);
            ThrottleDecision d = t.tryRecord("ACELIB-SCHED-001", "x");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED, d.kind(),
                "duplicate suppression（max=1）：視窗內（1s 內）第二次必須被抑制");
        }

        @Test
        @DisplayName("不同代碼各自獨立計數")
        void differentCodes_independent() {
            ErrorThrottler t = new ErrorThrottler(fakeClock(), 5, 1_000L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "a").kind());
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-CFG-003", "b").kind(),
                "不同 code 不應互相干擾");
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-DBG-001", "c").kind());
        }

        @Test
        @DisplayName("視窗外同代碼 → ALLOWED")
        void afterWindowReset_allowed() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 1_000L);
            t.tryRecord("ACELIB-SCHED-001", "x");
            clock.advance(2_000L); // 跨越 window
            ThrottleDecision d = t.tryRecord("ACELIB-SCHED-001", "x");
            assertEquals(ThrottleDecision.Kind.ALLOWED, d.kind(),
                "視窗外（≥ windowMs）必須視為新事件");
        }
    }

    @Nested
    @DisplayName("視窗內上限 maxPerWindow")
    class MaxPerWindow {

        @Test
        @DisplayName("maxPerWindow=3，視窗內第 1~3 次 ALLOWED，第 4 次起 SUPPRESSED")
        void withinWindowRespectsMax() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 3, 10_000L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind());
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind());
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind());
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind(),
                "達 maxPerWindow 後必須 SUPPRESSED");
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind(),
                "仍 SUPPRESSED");
        }

        @Test
        @DisplayName("maxPerWindow 邊界值為 1")
        void maxOne_meansOnlyFirstAllowed() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind());
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "x").kind());
        }

        @Test
        @DisplayName("maxPerWindow=DEFAULT（=5）：視窗內前 5 次 ALLOWED，第 6 次起 SUPPRESSED")
        void maxFiveDefault_firstFiveAllowed() {
            FakeClock clock = new FakeClock();
            // DEFAULT_MAX_PER_WINDOW=5 為通用節流語意；視窗內前 5 次都應放行。
            ErrorThrottler t = new ErrorThrottler(clock);
            for (int i = 1; i <= 5; i++) {
                clock.advance(50L);
                assertEquals(ThrottleDecision.Kind.ALLOWED,
                    t.tryRecord("ACELIB-SCHED-001", "msg-" + i).kind(),
                    "第 " + i + " 次（在 maxPerWindow=5 內）必須 ALLOWED");
            }
            clock.advance(50L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "msg-6").kind(),
                "第 6 次（超過 maxPerWindow=5）必須 SUPPRESSED");
            clock.advance(50L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "msg-7").kind(),
                "後續皆 SUPPRESSED");
        }

        @Test
        @DisplayName("視窗跨越後計數重置：跨窗後第 1 次重新 ALLOWED（累計保留）")
        void windowCross_resetsAllowedCount() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 2, 1_000L);
            // 第 1 次視窗：2 次 ALLOWED
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "a").kind());
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "b").kind());
            clock.advance(100L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACELIB-SCHED-001", "c").kind());
            // 跨越視窗
            clock.advance(2_000L);
            // 新視窗第 1 次：ALLOWED
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("ACELIB-SCHED-001", "d").kind(),
                "跨窗後當前視窗計數重置，重新 ALLOWED");
            // 但跨視窗累計保留
            assertEquals(3, t.getAllowedCount("ACELIB-SCHED-001"),
                "累計 allowed 跨視窗繼續累加");
            assertEquals(1, t.getSuppressedCount("ACELIB-SCHED-001"),
                "累計 suppressed 跨視窗繼續累加");
        }
    }

    @Nested
    @DisplayName("語意差異：duplicate suppression vs 通用節流")
    class SemanticDistinction {

        @Test
        @DisplayName("max=1（duplicate suppression）vs max=N（通用節流）行為差異")
        void duplicateSuppressionVsGenericThrottle() {
            FakeClock clock = new FakeClock();
            // duplicate suppression：視窗內只放行第 1 次
            ErrorThrottler dup = new ErrorThrottler(clock, 1, 1_000L);
            // 通用節流：視窗內前 5 次都放行
            ErrorThrottler gen = new ErrorThrottler(clock, 5, 1_000L);

            dup.tryRecord("ACELIB-CFG-001", "dup-1");
            gen.tryRecord("ACELIB-CFG-002", "gen-1");

            clock.advance(100L);

            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                dup.tryRecord("ACELIB-CFG-001", "dup-2").kind(),
                "duplicate suppression（max=1）：視窗內第二次 SUPPRESSED");
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                gen.tryRecord("ACELIB-CFG-002", "gen-2").kind(),
                "通用節流（max=5）：視窗內第二次仍 ALLOWED");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                dup.tryRecord("ACELIB-CFG-001", "dup-3").kind(),
                "duplicate suppression（max=1）：後續仍 SUPPRESSED");
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                gen.tryRecord("ACELIB-CFG-002", "gen-3").kind(),
                "通用節流（max=5）：第 3 次仍 ALLOWED（仍在 5 以內）");
        }
    }

    @Nested
    @DisplayName("建構子參數驗證")
    class ConstructorValidation {

        @Test
        @DisplayName("null clock 拋 NullPointerException")
        void nullClock_throws() {
            assertThrows(NullPointerException.class,
                () -> new ErrorThrottler(null, 5, 1_000L));
        }

        @Test
        @DisplayName("maxPerWindow <= 0 拋 IllegalArgumentException")
        void nonPositiveMax_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> new ErrorThrottler(fakeClock(), 0, 1_000L));
            assertThrows(IllegalArgumentException.class,
                () -> new ErrorThrottler(fakeClock(), -1, 1_000L));
        }

        @Test
        @DisplayName("windowMs <= 0 拋 IllegalArgumentException")
        void nonPositiveWindow_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> new ErrorThrottler(fakeClock(), 5, 0L));
            assertThrows(IllegalArgumentException.class,
                () -> new ErrorThrottler(fakeClock(), 5, -1L));
        }

        @Test
        @DisplayName("null code 拋 NullPointerException")
        void nullCode_throws() {
            assertThrows(NullPointerException.class,
                () -> new ErrorThrottler(fakeClock()).tryRecord(null, "x"));
        }

        @Test
        @DisplayName("空白 detail 仍可記錄（不拋例外）")
        void blankDetail_accepted() {
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                new ErrorThrottler(fakeClock()).tryRecord("ACELIB-DBG-001", "").kind());
        }
    }

    @Nested
    @DisplayName("統計查詢")
    class Statistics {

        @Test
        @DisplayName("getSuppressedCount 在多次抑制後遞增")
        void suppressedCount_increments() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            assertEquals(0, t.getSuppressedCount("ACELIB-SCHED-001"));
            t.tryRecord("ACELIB-SCHED-001", "x");
            t.tryRecord("ACELIB-SCHED-001", "x");
            t.tryRecord("ACELIB-SCHED-001", "x");
            assertEquals(2, t.getSuppressedCount("ACELIB-SCHED-001"));
        }

        @Test
        @DisplayName("getAllowedCount 在多次允許後遞增")
        void allowedCount_increments() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 1_000L);
            t.tryRecord("ACELIB-SCHED-001", "a");
            clock.advance(2_000L);
            t.tryRecord("ACELIB-SCHED-001", "b");
            assertEquals(2, t.getAllowedCount("ACELIB-SCHED-001"));
        }

        @Test
        @DisplayName("reset() 清空所有計數與視窗")
        void reset_clearsAll() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            t.tryRecord("ACELIB-SCHED-001", "x");
            t.tryRecord("ACELIB-SCHED-001", "x");
            assertEquals(1, t.getSuppressedCount("ACELIB-SCHED-001"));
            t.reset();
            assertEquals(0, t.getSuppressedCount("ACELIB-SCHED-001"));
            assertEquals(0, t.getAllowedCount("ACELIB-SCHED-001"));
        }

        @Test
        @DisplayName("trackedKeys 只列曾被 tryRecord 過的代碼")
        void trackedKeys_listsKnownCodes() {
            ErrorThrottler t = new ErrorThrottler(fakeClock());
            t.tryRecord("ACELIB-SCHED-001", "a");
            t.tryRecord("ACELIB-CFG-003", "b");
            assertTrue(t.trackedKeys().contains("ACELIB-SCHED-001"));
            assertTrue(t.trackedKeys().contains("ACELIB-CFG-003"));
            assertFalse(t.trackedKeys().contains("ACELIB-DBG-001"));
        }
    }

    @Nested
    @DisplayName("ThrottleDecision 結構")
    class ThrottleDecisionShape {

        @Test
        @DisplayName("ALLOWED 攜帶 detail")
        void allowedCarriesDetail() {
            ThrottleDecision d = new ErrorThrottler(fakeClock())
                .tryRecord("ACELIB-SCHED-001", "player offline");
            assertEquals("player offline", d.detail());
            assertEquals("ACELIB-SCHED-001", d.code());
        }

        @Test
        @DisplayName("SUPPRESSED 攜帶最近一次 detail（供報告輸出）")
        void suppressedCarriesDetail() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            t.tryRecord("ACELIB-SCHED-001", "first");
            ThrottleDecision d = t.tryRecord("ACELIB-SCHED-001", "ignored");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED, d.kind());
            assertEquals("ACELIB-SCHED-001", d.code());
            // 抑制時仍應能保留最近一次 detail，避免上層丟失訊息
            assertEquals("first", d.detail());
        }
    }
}
