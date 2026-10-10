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

        /**
         * 直接設定時間（含倒退，用於邊界測試）。
         *
         * <p>production 的 {@link Clock#system()} 不會倒退；此方法只模擬
         * 「時鐘回撥」邊界，驗證 {@code evictIdleCode} 不拋錯且行為合理。</p>
         */
        void setMillis(long value) {
            millis.set(value);
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

    @Nested
    @DisplayName("evictIdleCode 閒置淘汰")
    class EvictIdle {

        @Test
        @DisplayName("只移除閒置 code，仍活躍窗口與其 allowed／suppressed 統計保留")
        void evictsOnlyIdle_activeWindowAndStatsIntact() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            t.tryRecord("IDLE-CODE", "idle");
            clock.advance(500L);
            // 活躍 code：1 次 ALLOWED + 1 次 SUPPRESSED
            t.tryRecord("ACTIVE-CODE", "first");
            t.tryRecord("ACTIVE-CODE", "second");
            clock.advance(600L); // 此時 IDLE 已閒置 1100ms，ACTIVE 閒置 600ms

            int removed = t.evictIdleCode(1_000L);

            assertEquals(1, removed, "只應移除 1 個閒置 code");
            assertFalse(t.trackedKeys().contains("IDLE-CODE"), "閒置 code 必須被移除");
            assertTrue(t.trackedKeys().contains("ACTIVE-CODE"), "活躍 code 必須保留");
            assertEquals(1, t.getAllowedCount("ACTIVE-CODE"), "活躍窗口 allowed 累計必須保留");
            assertEquals(1, t.getSuppressedCount("ACTIVE-CODE"), "活躍窗口 suppressed 累計必須保留");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACTIVE-CODE", "third").kind(),
                "活躍窗口的視窗內語意必須延續（max=1 下仍 SUPPRESSED）");
        }

        @Test
        @DisplayName("無閒置 code 時回傳 0，不破壞任何統計")
        void nothingIdle_returnsZero() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 10_000L);
            t.tryRecord("ACELIB-SCHED-001", "x");
            assertEquals(0, t.evictIdleCode(60_000L));
            assertEquals(1, t.getAllowedCount("ACELIB-SCHED-001"));
        }

        @Test
        @DisplayName("空表淘汰回傳 0")
        void emptyTable_returnsZero() {
            assertEquals(0, new ErrorThrottler(fakeClock()).evictIdleCode(1_000L));
        }

        @Test
        @DisplayName("idleMillis <= 0 拋 IllegalArgumentException")
        void nonPositiveIdle_throws() {
            ErrorThrottler t = new ErrorThrottler(fakeClock());
            t.tryRecord("ACELIB-SCHED-001", "x");
            assertThrows(IllegalArgumentException.class, () -> t.evictIdleCode(0L));
            assertThrows(IllegalArgumentException.class, () -> t.evictIdleCode(-1L));
            assertTrue(t.trackedKeys().contains("ACELIB-SCHED-001"),
                "非法參數必須被拒絕，不得順手清掉任何窗口");
        }

        @Test
        @DisplayName("並行 record＋evict 不拋例外、不留下損毀狀態")
        void concurrentRecordAndEvict_noException() throws Exception {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 10_000L);
            int threads = 8;
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.Future<?>[] futures = new java.util.concurrent.Future[threads];
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int n = 0; n < 200; n++) {
                        t.tryRecord("CODE-" + (id % 4), "d" + n);
                        if (n % 10 == 0) {
                            t.evictIdleCode(Long.MAX_VALUE / 2);
                        }
                    }
                });
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            // 4 個 code 都在「剛活動過」的狀態下不得被超大 idle 門檻淘汰
            // （此斷言只確認結構完好，不斷言精確計數以免 flaky）
            assertEquals(4, t.trackedKeys().size());
        }

        @Test
        @DisplayName("活躍保護：早首次、晚再現、門檻介於兩者之間→保留（區分 lastSeenMs 與 startMs）")
        void activeWindowProtected_discriminatesLastSeenVsStart() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 1, 10_000L);
            // t=0 首次出現（ALLOWED）
            t.tryRecord("ACTIVE-CODE", "first");
            // t=900 再次出現：視窗內、max=1 → SUPPRESSED，但刷新最後出現時間
            clock.advance(900L);
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACTIVE-CODE", "second").kind());
            // t=1100 淘汰，門檻 1000 介於「距首次 1100」與「距再現 200」之間
            clock.advance(200L);

            int removed = t.evictIdleCode(1_000L);

            // lastSeenMs 基準：閒置僅 200ms → 保留。若誤用 startMs（閒置 1100ms）
            // 會被误删，此斷言能真正保護設計。
            assertEquals(0, removed, "活躍 code 不得被淘汰");
            assertTrue(t.trackedKeys().contains("ACTIVE-CODE"));
            assertEquals(1, t.getAllowedCount("ACTIVE-CODE"), "allowed 累計必須完好");
            assertEquals(1, t.getSuppressedCount("ACTIVE-CODE"), "suppressed 累計必須完好");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED,
                t.tryRecord("ACTIVE-CODE", "third").kind(),
                "同一視窗語意必須延續（max=1 下仍 SUPPRESSED）");
        }

        @Test
        @DisplayName("確實淘汰的並行案例：閒置 code 被清、活躍統計連續、被淘汰者下次視為新事件")
        void concurrentEvict_removesIdle_activeStatsContinuous() throws Exception {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 10_000L);
            // 種子：A、B 之後保持活躍，C、D 之後不再出現
            t.tryRecord("CODE-A", "a1");
            t.tryRecord("CODE-A", "a2");
            t.tryRecord("CODE-B", "b1");
            t.tryRecord("CODE-C", "c1");
            t.tryRecord("CODE-D", "d1");
            clock.advance(5_000L); // 全部閒置 5000ms（仍在 10s 視窗內）
            // 主執行緒先暖機：A、B 的最後出現時間刷新為「現在」，之後時鐘凍結，
            // 並行淘汰不可能误删 A、B（now - lastSeen 恆為 0）；C、D 維持 5000ms
            // 閒置，必被淘汰。不依賴 sleep，全程 deterministic。
            t.tryRecord("CODE-A", "warm");
            t.tryRecord("CODE-B", "warm");
            int allowedABefore = t.getAllowedCount("CODE-A");
            assertEquals(3, allowedABefore);

            // 並行階段：recorders 持續記錄 A、B，evictors 以 1000ms 門檻淘汰
            java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (String code : new String[]{"CODE-A", "CODE-B"}) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    for (int n = 0; n < 300; n++) {
                        t.tryRecord(code, "x");
                    }
                    return null;
                }));
            }
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    int removed = 0;
                    for (int n = 0; n < 300; n++) {
                        removed += t.evictIdleCode(1_000L);
                    }
                    return removed;
                }));
            }
            start.countDown();
            int totalRemoved = 0;
            for (java.util.concurrent.Future<?> f : futures) {
                Object r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (r instanceof Integer) {
                    totalRemoved += (Integer) r;
                }
            }
            pool.shutdownNow();

            assertTrue(totalRemoved >= 2, "C、D 必須在並行淘汰中被移除，實際移除: " + totalRemoved);
            assertFalse(t.trackedKeys().contains("CODE-C"), "閒置 C 必須被淘汰");
            assertFalse(t.trackedKeys().contains("CODE-D"), "閒置 D 必須被淘汰");
            assertTrue(t.trackedKeys().contains("CODE-A"), "活躍 A 必須保留");
            assertTrue(t.trackedKeys().contains("CODE-B"), "活躍 B 必須保留");
            assertTrue(t.getAllowedCount("CODE-A") > allowedABefore,
                "活躍 A 的 allowed 累計必須連續累加（視窗 max=5，未跨窗）");
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("CODE-C", "comeback").kind(),
                "被淘汰的 C 之後首次記錄必須視為新事件（ALLOWED）");
        }

        @Test
        @DisplayName("邊界：閒置恰好等於門檻時淘汰（釘住 >= 語意）")
        void idleExactlyAtThreshold_evicts() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 60_000L);
            t.tryRecord("EDGE-CODE", "x");
            clock.advance(1_000L); // 閒置恰好 1000ms

            int removed = t.evictIdleCode(1_000L);

            assertEquals(1, removed, "閒置 == 門檻（now - lastSeen >= idleMillis）必須淘汰");
            assertFalse(t.trackedKeys().contains("EDGE-CODE"));
        }

        @Test
        @DisplayName("邊界：閒置差 1ms 不到門檻時保留")
        void idleOneMsBelowThreshold_retained() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 60_000L);
            t.tryRecord("EDGE-CODE", "x");
            clock.advance(999L);

            assertEquals(0, t.evictIdleCode(1_000L));
            assertTrue(t.trackedKeys().contains("EDGE-CODE"));
            assertEquals(1, t.getAllowedCount("EDGE-CODE"));
        }

        @Test
        @DisplayName("邊界：時鐘倒退時不拋錯，窗口保留且仍可記錄")
        void clockGoesBackward_noException_windowRetained() {
            FakeClock clock = new FakeClock();
            ErrorThrottler t = new ErrorThrottler(clock, 5, 10_000L);
            t.tryRecord("BACK-CODE", "x");
            long seen = clock.currentTimeMillis();
            clock.setMillis(seen - 5_000L); // 時鐘回撥 5 秒

            int removed = t.evictIdleCode(1_000L); // now - lastSeen 為負，不得拋錯

            assertEquals(0, removed, "時鐘倒退（負閒置）不得误删窗口");
            assertTrue(t.trackedKeys().contains("BACK-CODE"));
            assertEquals(ThrottleDecision.Kind.ALLOWED,
                t.tryRecord("BACK-CODE", "y").kind(),
                "倒退後仍可正常記錄（視窗語意：負間隔 < windowMs，視為視窗內）");
        }

        /**
         * 並行起跑柵欄：中斷時恢復中斷旗標並以 unchecked 形式失敗，
         * 避免測試執行緒靜默通過。
         */
        private static void awaitStart(java.util.concurrent.CountDownLatch start) {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test start gate interrupted", e);
            }
        }
    }
}
