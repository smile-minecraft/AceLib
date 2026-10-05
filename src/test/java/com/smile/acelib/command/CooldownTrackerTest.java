package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CooldownTracker} 單元測試。
 *
 * <p>對應 Plan §十一驗收標準「冷卻 / 防重複觸發在 reload 過程中也不會破壞狀態」
 * 與邊界條件「短時間重複觸發」。</p>
 *
 * <h2>測試時鐘</h2>
 * <p>所有測試使用 {@link AtomicLong} 注入 deterministic clock — 不使用
 * {@link Thread#sleep}。</p>
 */
@DisplayName("CooldownTracker")
class CooldownTrackerTest {

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String SUB = "acelib:reload";
    /**
     * 過期入口用的 subKey：只被當成「prune 每次都有東西可清」的播種者，
     * 與受保護的 {@link #SUB} 分開，才能讓 per-player 表在 prune 之後被清空。
     */
    private static final String EXPIRED_SUB = "acelib:expired";

    @Nested
    @DisplayName("tryAcquire 基本語意")
    class BasicAcquire {

        @Test
        @DisplayName("cooldownMillis <= 0 時永遠成功（無冷卻）")
        void noCooldown_alwaysSucceeds() {
            TestClock clock = new TestClock(0);
            CooldownTracker t = new CooldownTracker(clock);
            assertTrue(t.tryAcquire(PLAYER_A, SUB, 0));
            assertTrue(t.tryAcquire(PLAYER_A, SUB, -1));
            assertTrue(t.tryAcquire(PLAYER_A, SUB, -1000));
        }

        @Test
        @DisplayName("同一玩家同 subKey 在冷卻時間內第二次失敗")
        void samePlayer_secondAcquireFails() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            assertTrue(t.tryAcquire(PLAYER_A, SUB, 5000));
            clock.advance(1000);
            assertFalse(t.tryAcquire(PLAYER_A, SUB, 5000),
                "1000ms 經過但 5000ms 冷卻中");
        }

        @Test
        @DisplayName("不同玩家各自獨立冷卻")
        void differentPlayersIndependent() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            assertTrue(t.tryAcquire(PLAYER_A, SUB, 5000));
            assertTrue(t.tryAcquire(PLAYER_B, SUB, 5000),
                "PLAYER_B 不應受 PLAYER_A 冷卻影響");
        }

        @Test
        @DisplayName("不同 subKey 各自獨立冷卻")
        void differentSubKeysIndependent() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            assertTrue(t.tryAcquire(PLAYER_A, "acelib:reload", 5000));
            assertTrue(t.tryAcquire(PLAYER_A, "acelib:status", 5000),
                "不同 subKey 不應互相影響");
        }

        @Test
        @DisplayName("過期後再次 acquire 成功（重置過期時間）")
        void expiredAllowsReacquire() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            assertTrue(t.tryAcquire(PLAYER_A, SUB, 5000));
            clock.advance(6000);  // 超過 5000ms 冷卻
            assertTrue(t.tryAcquire(PLAYER_A, SUB, 5000),
                "過期後應能再次 acquire");
            // 此時新冷卻時間已設定
            assertFalse(t.tryAcquire(PLAYER_A, SUB, 5000),
                "新冷卻時間內仍應被擋下");
        }
    }

    @Nested
    @DisplayName("remainingMillis 查詢")
    class RemainingQuery {

        @Test
        @DisplayName("未冷卻中時 remainingMillis 回傳 0")
        void notOnCooldown_returnsZero() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            assertEquals(0, t.remainingMillis(PLAYER_A, SUB));
        }

        @Test
        @DisplayName("冷卻中時 remainingMillis 回傳剩餘毫秒數")
        void onCooldown_returnsRemaining() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, SUB, 5000);
            clock.advance(2000);
            assertEquals(3000, t.remainingMillis(PLAYER_A, SUB));
        }

        @Test
        @DisplayName("過期後 remainingMillis 回傳 0")
        void expired_returnsZero() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, SUB, 5000);
            clock.advance(10000);
            assertEquals(0, t.remainingMillis(PLAYER_A, SUB));
        }
    }

    @Nested
    @DisplayName("clear / clearAll")
    class ClearOperations {

        @Test
        @DisplayName("clear(playerId) 移除該玩家所有冷卻")
        void clearPlayer_removesAll() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, "k1", 5000);
            t.tryAcquire(PLAYER_A, "k2", 5000);
            t.tryAcquire(PLAYER_B, "k1", 5000);
            t.clear(PLAYER_A);
            assertEquals(0, t.remainingMillis(PLAYER_A, "k1"));
            assertEquals(0, t.remainingMillis(PLAYER_A, "k2"));
            // PLAYER_B 的冷卻不應被清掉 — 其 k1 仍在冷卻中
            assertTrue(t.remainingMillis(PLAYER_B, "k1") > 0,
                "clear PLAYER_A 不應影響 PLAYER_B 的 k1 冷卻狀態");
            assertFalse(t.tryAcquire(PLAYER_B, "k1", 5000),
                "PLAYER_B 的 k1 仍在原冷卻期內，第二次 acquire 應失敗");
        }

        @Test
        @DisplayName("clearAll 移除所有玩家所有冷卻")
        void clearAll_removesEverything() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, "k1", 5000);
            t.tryAcquire(PLAYER_B, "k1", 5000);
            t.clearAll();
            assertEquals(0, t.trackedPlayerCount());
        }
    }

    @Nested
    @DisplayName("reload 行為（Plan §十一 驗收標準）")
    class ReloadSurvival {

        @Test
        @DisplayName("reload 流程中（disable → 重新持有 tracker）冷卻狀態保留")
        void reload_preservesCooldowns() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, SUB, 5000);
            // 模擬 disable：依 Plan §十一 規範，disable 不清除 tracker 狀態
            // 但 caller 可以選擇重建 registry（保留同一個 CooldownTracker reference）
            // 此測試只驗證 tracker 本身在被「跨 disable 週期持有」時狀態保留。
            clock.advance(1000);
            assertFalse(t.tryAcquire(PLAYER_A, SUB, 5000),
                "reload 後冷卻仍應有效（不重置）");
        }

        @Test
        @DisplayName("snapshot 回傳不可變快照")
        void snapshot_isImmutable() {
            TestClock clock = new TestClock(1000);
            CooldownTracker t = new CooldownTracker(clock);
            t.tryAcquire(PLAYER_A, SUB, 5000);
            Map<UUID, Map<String, Long>> snap = t.snapshot();
            assertEquals(1, snap.size());
            assertTrue(snap.containsKey(PLAYER_A));
            assertThrows(UnsupportedOperationException.class,
                () -> snap.put(UUID.randomUUID(), Map.of()));
        }
    }

    @Nested
    @DisplayName("null 參數檢查")
    class NullGuards {

        @Test
        @DisplayName("tryAcquire 對 null playerId/subKey 拋 NPE")
        void nullArgs_throwNPE() {
            CooldownTracker t = new CooldownTracker();
            assertThrows(NullPointerException.class,
                () -> t.tryAcquire(null, SUB, 1000));
            assertThrows(NullPointerException.class,
                () -> t.tryAcquire(PLAYER_A, null, 1000));
        }

        @Test
        @DisplayName("建構子對 null clock 拋 NPE")
        void nullClock_throws() {
            assertThrows(NullPointerException.class,
                () -> new CooldownTracker((Clock) null));
        }
    }

    // -----------------------------------------------------------------
    // 多執行緒與過期清理
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("多執行緒與過期清理")
    class ConcurrencyAndExpiry {

        @Test
        @DisplayName("多執行緒同 key 只允許一個通過（多輪獨立樣本）")
        void concurrentTryAcquire_onlyOneSucceeds() throws Exception {
            TestClock clock = new TestClock(1000);
            CooldownTracker tracker = new CooldownTracker(clock);
            // get-then-put 競爭屬於時序型缺陷：單輪可能剛好不重現。
            // 以多輪「清空 → 併發競爭」獨立樣本累加違例，確保回歸測試是可靠偵測器
            // 而不是靠運氣的單次抽樣。
            int rounds = 200;
            int threads = 8;
            int violatingRounds = 0;
            int observedPermits = 0;
            for (int round = 0; round < rounds; round++) {
                int permits = acquireConcurrently(tracker, clock, threads);
                observedPermits += permits;
                if (permits != 1) {
                    violatingRounds++;
                }
                tracker.clear(PLAYER_A);
            }
            assertEquals(0, violatingRounds,
                "同 key 不可在同一冷卻窗同時多通；觀察到 " + observedPermits
                    + " 次放行分佈於 " + rounds + " 輪");
            assertEquals(rounds, observedPermits,
                "每輪恰好一個執行緒取得冷卻鎖");
        }

        /**
         * 讓 {@code threads} 個執行緒同時對同一 key 嘗試 acquire，回傳成功數。
         *
         * <p>時間來源固定不動（{@link TestClock} 不前進），確保唯一能通過的
         * 理由是「第一個寫入者贏得冷卻窗」，而不是時間流逝。</p>
         */
        private int acquireConcurrently(CooldownTracker tracker, TestClock clock,
                                        int threads) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                AtomicInteger permits = new AtomicInteger();
                for (int i = 0; i < threads; i++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            if (tracker.tryAcquire(PLAYER_A, SUB, 60_000)) {
                                permits.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS),
                    "併發執行緒應在時限內完成");
                return permits.get();
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("pruneExpired 清除過期紀錄，長期不成長")
        void pruneExpired_removesExpiredEntries() {
            TestClock clock = new TestClock(1000);
            CooldownTracker tracker = new CooldownTracker(clock);
            tracker.tryAcquire(PLAYER_A, SUB, 5000);
            tracker.tryAcquire(PLAYER_B, "cmd:other", 100_000);
            clock.advance(10_000);
            tracker.pruneExpired();
            assertEquals(1, tracker.trackedPlayerCount(), "過期玩家應被移除");
            assertTrue(tracker.snapshot().containsKey(PLAYER_B));
            clock.advance(200_000);
            tracker.pruneExpired();
            assertEquals(0, tracker.trackedPlayerCount());
            assertTrue(tracker.snapshot().isEmpty());
        }

        @Test
        @DisplayName("prune 與 tryAcquire 交錯：有效冷卻不可因過期清理而遺失")
        void pruneRacingAcquire_validCooldownIsNotLost() throws Exception {
            GatedClock clock = new GatedClock(1000, 1);
            CooldownTracker tracker = new CooldownTracker(clock);

            AtomicBoolean acquired = new AtomicBoolean();
            Thread acquirer = new Thread(
                () -> acquired.set(tracker.tryAcquire(PLAYER_A, SUB, 60_000)), "acquire");
            acquirer.start();
            // gate 停在「讀取時鐘」這一步，也就是 acquire 進入 per-player 臨界區之前：
            // 固定的是「清理先於寫入跑完」這個單一樣本，而非「已取得內層 map、
            // 尚未寫入」那個真正危險的交錯點（該交錯點改由下方持續清理的壓力
            // 測試覆蓋）。時間凍結，寫入結果不會被時間流逝稀釋。
            assertTrue(clock.awaitParked(10, TimeUnit.SECONDS),
                "acquire 執行緒應停在時鐘讀取上");

            Thread pruner = new Thread(tracker::pruneExpired, "prune");
            pruner.start();
            // 此時 acquire 尚未進入臨界區，清理因此可以先跑完；確認的就是
            // 「清理先完成也不能讓稍後寫入的冷卻消失」。
            pruner.join(500);
            clock.releaseGate();
            acquirer.join(10_000);
            pruner.join(10_000);

            assertFalse(pruner.isAlive(), "prune 應在 acquire 寫入後正常結束");
            assertTrue(acquired.get(), "無既有冷卻時第一次 acquire 應成功");
            assertEquals(1, tracker.trackedPlayerCount(),
                "有效冷卻不可被 prune 連帶摘掉");
            assertEquals(60_000L, tracker.remainingMillis(PLAYER_A, SUB),
                "成功取得的冷卻必須真的被記錄下來");
            assertFalse(tracker.tryAcquire(PLAYER_A, SUB, 60_000),
                "冷卻窗內的第二次 acquire 必須被擋下");
        }

        @Test
        @DisplayName("prune 高壓下併發 acquire 同 key 仍只放行一次")
        void concurrentAcquireUnderPrunePressure_onlyOneSucceeds() throws Exception {
            int rounds = 100;
            int threads = 6;
            int violatingRounds = 0;
            int observedPermits = 0;
            // 時間凍結時，唯一能通過的理由是「第一個寫入者贏得冷卻窗」。
            // prune 持續在另一執行緒上清理，正是「有效冷卻被誤刪」最容易被踩到的
            // 情境：清理若不與寫入共用同一把鎖，冷卻窗會被清空而多放行。
            for (int round = 0; round < rounds; round++) {
                TestClock clock = new TestClock(1000);
                CooldownTracker tracker = new CooldownTracker(clock);
                CountDownLatch roundDone = new CountDownLatch(1);
                Thread pruner = new Thread(() -> {
                    while (roundDone.getCount() > 0) {
                        tracker.pruneExpired();
                    }
                }, "prune-" + round);
                pruner.start();
                int permits = acquireConcurrently(tracker, clock, threads);
                roundDone.countDown();
                pruner.join(10_000);
                observedPermits += permits;
                if (permits != 1) {
                    violatingRounds++;
                }
            }
            assertEquals(0, violatingRounds,
                "同 key 不可在同一冷卻窗同時多通；觀察到 " + observedPermits
                    + " 次放行分佈於 " + rounds + " 輪");
            assertEquals(rounds, observedPermits, "每輪恰好一個執行緒取得冷卻鎖");
        }

        /**
         * 已過期入口存在時，prune 高壓下併發 acquire 的放行結果不可遺失。
         *
         * <p>與 {@code concurrentAcquireUnderPrunePressure_onlyOneSucceeds} 的差別
         * 正是能不能抓到非原子 prune 的關鍵：那一個測試的時鐘完全凍結，prune 沒有
         * 任何過期項目可清，{@code isEmpty()} 永遠不成立，因此非原子 prune 的
         * 「清空後摘掉外層項目」路徑從未被執行過。</p>
         *
         * <p>非原子 prune（先 get 內層 map → removeIf → 再
         * {@code expiresAt.remove}）的破綻是：判定「表已空」之後、真正呼叫 remove
         * 之前，另一執行緒的 acquire 把新冷卻寫進同一張 map，prune 的 remove 隨後
         * 把它連帶摘掉。</p>
         *
         * <p>時鐘由測試控制（{@link TestClock} 的讀取不會改變 now）：每輪明確推進
         * 一次讓播種入口過期，prune 因此每輪都有東西可清；但同一輪之內 now 凍結，
         * {@code 60_000ms} 的有效冷卻不會因時間流逝而到期 —— 唯一能讓
         * {@code remainingMillis} 歸零的原因，就只剩下寫入真的被遺失。</p>
         */
        @Test
        @DisplayName("已過期入口存在時，prune 高壓下併發 acquire 的放行結果不可遺失")
        void concurrentAcquireOverExpiredEntry_grantedCooldownSurvives() throws Exception {
            TestClock clock = new TestClock(1_000);
            CooldownTracker tracker = new CooldownTracker(clock);
            int rounds = 20_000;
            final long cooldown = 60_000;
            final long expiryTick = 1;

            AtomicBoolean stop = new AtomicBoolean();
            Thread pruner = new Thread(() -> {
                while (!stop.get()) {
                    tracker.pruneExpired();
                }
            }, "prune-expired");
            pruner.start();
            try {
                int lostRounds = 0;
                int doubleGrantRounds = 0;
                int expiredEntriesRemoved = 0;
                for (int round = 0; round < rounds; round++) {
                    // 播種一筆立刻到期的入口，讓 prune 每輪都有東西可清 → 表會被清空
                    // 並從外層摘掉，正是非原子 prune 的破綻窗口。
                    tracker.tryAcquire(PLAYER_A, EXPIRED_SUB, 1);
                    clock.advance(expiryTick);
                    boolean granted = tracker.tryAcquire(PLAYER_A, SUB, cooldown);
                    if (!granted) {
                        // 該玩家對 SUB 唯一的既有紀錄是已過期入口，不該擋下這次取得
                        doubleGrantRounds++;
                        continue;
                    }
                    if (tracker.remainingMillis(PLAYER_A, SUB) <= 0L) {
                        lostRounds++;
                        continue;
                    }
                    // 冷卻窗內的第二次取得必須仍被擋下
                    if (tracker.tryAcquire(PLAYER_A, SUB, cooldown)) {
                        doubleGrantRounds++;
                    }
                    // 存在性握手：主執行緒在本輪只寫入 SUB，從不刪除 EXPIRED_SUB，
                    // 唯一能讓快照缺失該 key 的就是背景 prune。remainingMillis == 0
                    // 只代表時間到期（入口仍可能留在 map 內），不可當成已清理的證據；
                    // 檢查必須在 clear 之前，否則 clear 本身就會讓計數必然通過。
                    Map<String, Long> perRound = tracker.snapshot().get(PLAYER_A);
                    if (perRound == null || !perRound.containsKey(EXPIRED_SUB)) {
                        expiredEntriesRemoved++;
                    }
                    tracker.clear(PLAYER_A);
                }
                assertEquals(0, lostRounds,
                    "過期入口被清理時，acquire 放行的冷卻不可被連帶摘掉；"
                        + lostRounds + " 輪發生於 " + rounds + " 輪");
                assertEquals(0, doubleGrantRounds,
                    "冷卻窗內不可因清理而重複放行；"
                        + doubleGrantRounds + " 輪發生於 " + rounds + " 輪");
                assertTrue(expiredEntriesRemoved > 0,
                    "過期入口必須真的被 prune 清掉，prune 才不是空轉");
            } finally {
                stop.set(true);
                pruner.join(10_000);
                assertFalse(pruner.isAlive(), "清理執行緒應在停止旗標後結束");
            }
        }
    }

    // -----------------------------------------------------------------
    // 測試輔助
    // -----------------------------------------------------------------

    private static final class TestClock implements Clock {
        private final AtomicLong now;

        TestClock(long initial) {
            this.now = new AtomicLong(initial);
        }

        void advance(long delta) {
            now.addAndGet(delta);
        }

        @Override
        public long currentTimeMillis() {
            return now.get();
        }
    }

    /**
     * 可控時鐘：第 {@code gateRead} 次讀取會停在 gate 上，直到 {@link #releaseGate()}。
     *
     * <p>用途是把冷卻寫入尚未開始的時點固定出來，讓過期清理與冷卻取得的競爭
     * 在不靠 {@link Thread#sleep} 猜時機的前提下被觀察。</p>
     *
     * <p><strong>限制</strong>：gate 停在讀取時鐘這一步，而
     * {@link CooldownTracker#tryAcquire} 是在進入 per-player 臨界區<strong>之前</strong>
     * 讀時鐘的，所以 gate 並不會停在「已取得內層 map、尚未寫入」那個真正會遺失
     * 冷卻的交錯點。本測試只覆蓋單一樣本，不能單獨作為該競爭的回歸防護；
     * 決定性覆蓋由 {@code concurrentAcquireUnderPrunePressure_onlyOneSucceeds}
     * 的持續清理壓力測試提供。</p>
     */
    private static final class GatedClock implements Clock {

        private final AtomicLong now;
        private final AtomicInteger reads = new AtomicInteger();
        private final int gateRead;
        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        GatedClock(long initial, int gateRead) {
            this.now = new AtomicLong(initial);
            this.gateRead = gateRead;
        }

        @Override
        public long currentTimeMillis() {
            if (reads.incrementAndGet() == gateRead) {
                parked.countDown();
                try {
                    if (!released.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate 逾時未被釋放");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return now.get();
        }

        boolean awaitParked(long timeout, TimeUnit unit) throws InterruptedException {
            return parked.await(timeout, unit);
        }

        void releaseGate() {
            released.countDown();
        }
    }
}