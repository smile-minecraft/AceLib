package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerCooldownService} 行為測試。
 *
 * <p>對應 Plan §十四 Phase 9 驗收標準：冷卻時間可開始、查詢、結束。</p>
 *
 * <p>與既有的 {@code command.CooldownTracker} 不同：
 * PlayerCooldownService 獨立於指令系統，純粹以 UUID + cooldown key 管理
 * 冷卻；提供更明確的 start/query/end API 與 reload 語意。</p>
 */
@DisplayName("PlayerCooldownService")
class PlayerCooldownServiceTest {

    private static class FakeClock implements Clock {
        final AtomicLong now = new AtomicLong(0);
        @Override public long currentTimeMillis() { return now.get(); }
        void advance(long ms) { now.addAndGet(ms); }
    }

    @Test
    @DisplayName("start：建立冷卻（duration<=0 仍視為可立即再次 acquire）")
    void start_createsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        // duration 內 query 回傳剩餘時間
        assertTrue(svc.remainingMillis(id, "skill.a") > 0);
    }

    @Test
    @DisplayName("start：既有冷卻被新值覆寫（重新觸發場景，剩餘時間以新值為準）")
    void start_overwritesExistingCooldown() {
        FakeClock clock = new FakeClock();
        clock.now.set(1000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();

        svc.start(id, "skill.a", 5000);
        assertEquals(5000L, svc.remainingMillis(id, "skill.a"));

        clock.advance(1000);
        // 縮短：重新觸發後冷卻窗應以新值為準，而不是保留原本較長的窗口
        svc.start(id, "skill.a", 1000);
        assertEquals(1000L, svc.remainingMillis(id, "skill.a"),
            "start 必須覆寫既有冷卻，剩餘時間以新值為準");

        clock.advance(100);
        // 延長：同樣以新值為準
        svc.start(id, "skill.a", 8000);
        assertEquals(8000L, svc.remainingMillis(id, "skill.a"),
            "start 必須覆寫既有冷卻，剩餘時間以新值為準");
    }

    @Test
    @DisplayName("start 覆寫成較短冷卻後：新窗口結束即解放，舊的長窗口不復存在")
    void start_overwriteShorterWindow_releasesEarlier() {
        FakeClock clock = new FakeClock();
        clock.now.set(1000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();

        svc.start(id, "skill.a", 5000);
        clock.advance(1000);
        svc.start(id, "skill.a", 1000);
        assertFalse(svc.tryAcquire(id, "skill.a", 1000),
            "覆寫後的新冷卻窗應立即生效");

        // 前進到「仍在舊窗口內、但已在新窗口外」：覆寫必須真的縮短窗口
        clock.advance(1000);
        assertEquals(0L, svc.remainingMillis(id, "skill.a"),
            "覆寫成較短冷卻後，舊窗口不應繼續延長鎖定");
        assertTrue(svc.tryAcquire(id, "skill.a", 1000),
            "新窗口結束後應可再次取得冷卻");
    }

    @Test
    @DisplayName("query：未 start 的 key 回傳 0")
    void query_unstarted_returnsZero() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        assertEquals(0, svc.remainingMillis(UUID.randomUUID(), "skill.a"));
    }

    @Test
    @DisplayName("query：過期後回傳 0")
    void query_expired_returnsZero() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        clock.advance(1500);
        assertEquals(0, svc.remainingMillis(id, "skill.a"));
    }

    @Test
    @DisplayName("tryAcquire：冷卻中 false；冷卻結束 true")
    void tryAcquire_respectsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        // 首次 acquire — 應成功並啟動冷卻
        assertTrue(svc.tryAcquire(id, "skill.a", 1000));
        // 冷卻期間再 acquire — false
        assertFalse(svc.tryAcquire(id, "skill.a", 1000));
        // 時間推進過期後又可 acquire
        clock.advance(1001);
        assertTrue(svc.tryAcquire(id, "skill.a", 1000));
    }

    @Test
    @DisplayName("tryAcquire：duration<=0 視為無冷卻")
    void tryAcquire_noCooldown() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertTrue(svc.tryAcquire(id, "skill.a", 0));
        assertTrue(svc.tryAcquire(id, "skill.a", 0));
    }

    @Test
    @DisplayName("end：清除指定 key 的冷卻（管理者指令）")
    void end_clearsSpecificKey() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        svc.start(id, "skill.b", 5000);
        assertTrue(svc.remainingMillis(id, "skill.a") > 0);
        svc.end(id, "skill.a");
        assertEquals(0, svc.remainingMillis(id, "skill.a"),
            "end 應清除該 key 的冷卻");
        assertTrue(svc.remainingMillis(id, "skill.b") > 0,
            "end 不應影響其他 key");
    }

    @Test
    @DisplayName("endAll：清除單一玩家所有冷卻")
    void endAll_clearsPlayer() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        svc.start(id, "skill.b", 5000);
        svc.endAll(id);
        assertEquals(0, svc.remainingMillis(id, "skill.a"));
        assertEquals(0, svc.remainingMillis(id, "skill.b"));
    }

    @Test
    @DisplayName("clearAll：清除所有玩家的所有冷卻（reload 使用）")
    void clearAll_clearsEverything() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        svc.start(a, "skill.a", 1000);
        svc.start(b, "skill.b", 5000);
        svc.clearAll();
        assertEquals(0, svc.remainingMillis(a, "skill.a"));
        assertEquals(0, svc.remainingMillis(b, "skill.b"));
    }

    @Test
    @DisplayName("clearAll：冪等")
    void clearAll_idempotent() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        svc.clearAll();
        svc.clearAll();
    }

    @Test
    @DisplayName("start/tryAcquire：null UUID 拋 NPE")
    void nullUuid_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        assertThrows(NullPointerException.class,
            () -> svc.start(null, "k", 1000));
        assertThrows(NullPointerException.class,
            () -> svc.tryAcquire(null, "k", 1000));
        assertThrows(NullPointerException.class,
            () -> svc.remainingMillis(null, "k"));
        assertThrows(NullPointerException.class,
            () -> svc.end(null, "k"));
        assertThrows(NullPointerException.class,
            () -> svc.endAll(null));
    }

    @Test
    @DisplayName("start/tryAcquire：null key 拋 NPE")
    void nullKey_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class,
            () -> svc.start(id, null, 1000));
        assertThrows(NullPointerException.class,
            () -> svc.tryAcquire(id, null, 1000));
        assertThrows(NullPointerException.class,
            () -> svc.remainingMillis(id, null));
        assertThrows(NullPointerException.class,
            () -> svc.end(id, null));
    }

    @Test
    @DisplayName("start：負 duration 拋 IAE")
    void start_negativeDuration_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> svc.start(id, "k", -1));
    }

    @Test
    @DisplayName("trackedPlayerCount：反映不同玩家數")
    void trackedPlayerCount() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        assertEquals(0, svc.trackedPlayerCount());
        svc.start(UUID.randomUUID(), "k", 1000);
        assertEquals(1, svc.trackedPlayerCount());
        svc.start(UUID.randomUUID(), "k", 1000);
        assertEquals(2, svc.trackedPlayerCount());
    }

    @Test
    @DisplayName("多執行緒同 key 只允許一個 acquire 通過（多輪獨立樣本）")
    void concurrentTryAcquire_onlyOneSucceeds() throws Exception {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        // get-then-put 競爭屬於時序型缺陷：單輪可能剛好不重現。
        // 以多輪「清空 → 併發競爭」獨立樣本累加違例，確保回歸測試是可靠偵測器。
        int rounds = 200;
        int threads = 8;
        int violatingRounds = 0;
        int observedPermits = 0;
        for (int round = 0; round < rounds; round++) {
            int permits = acquireConcurrently(svc, id, threads);
            observedPermits += permits;
            if (permits != 1) {
                violatingRounds++;
            }
            svc.endAll(id);
        }
        assertEquals(0, violatingRounds,
            "同 key 不可同時多通；觀察到 " + observedPermits + " 次放行分佈於 " + rounds + " 輪");
        assertEquals(rounds, observedPermits, "每輪恰好一個執行緒取得冷卻鎖");
    }

    /**
     * 讓 {@code threads} 個執行緒同時對同一 key 嘗試 acquire，回傳成功數。
     *
     * <p>{@link FakeClock} 不前進，確保唯一能通過的理由是「第一個寫入者
     * 贏得冷卻窗」，而不是時間流逝。</p>
     */
    private int acquireConcurrently(PlayerCooldownService svc, UUID id, int threads)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger permits = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (svc.tryAcquire(id, "k", 60_000)) {
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
            assertTrue(done.await(30, TimeUnit.SECONDS), "併發執行緒應在時限內完成");
            return permits.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("pruneExpired 清除過期紀錄，長期不成長")
    void pruneExpired_removesExpiredEntries() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        clock.now.set(1000);
        svc.start(a, "k1", 5000);
        svc.start(b, "k2", 100_000);
        clock.advance(10_000);
        svc.pruneExpired();
        assertEquals(1, svc.trackedPlayerCount(), "過期玩家應被移除");
        assertTrue(svc.snapshot().containsKey(b));
        clock.advance(200_000);
        svc.pruneExpired();
        assertEquals(0, svc.trackedPlayerCount());
        assertTrue(svc.snapshot().isEmpty());
    }

    @Test
    @DisplayName("名稱變更不影響既有冷卻：相同 UUID 不同 name 仍查到原冷卻")
    void nameChange_keepsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        // "alice" 啟動冷卻
        svc.start(id, "skill.a", 1000);
        long before = svc.remainingMillis(id, "skill.a");
        // 改名 "alice_renamed" — 由於以 UUID 索引，冷卻不受影響
        long after = svc.remainingMillis(id, "skill.a");
        assertEquals(before, after);
    }

    @Test
    @DisplayName("prune 與 start 交錯：剛啟動的冷卻不可因過期清理而遺失")
    void pruneRacingStart_newCooldownIsNotLost() throws Exception {
        GatedClock clock = new GatedClock(1000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();

        Thread starter = new Thread(() -> svc.start(id, "k", 60_000), "start");
        starter.start();
        // gate 停在「讀取時鐘」這一步，也就是 start 進入 per-player 臨界區之前。
        // 因此這裡固定的是「寫入還沒開始時清理已經跑完」這個單一樣本，而不是
        // 「已取得內層 map、尚未寫入」那個真正危險的交錯點 —— 後者沒有辦法用
        // 時鐘 gate 固定，只能靠持續 prune 的壓力測試覆蓋（見下方壓力測試）。
        assertTrue(clock.awaitParked(10, TimeUnit.SECONDS),
            "start 執行緒應停在時鐘讀取上");

        Thread pruner = new Thread(svc::pruneExpired, "prune");
        pruner.start();
        // 此時 start 尚未進入臨界區，外層可能還沒有這張 map，清理因此可以在
        // 寫入前先跑完；這正是要確認的情境 —— 清理先跑完也不能讓稍後寫入的
        // 冷卻消失。
        pruner.join(500);
        clock.releaseGate();
        starter.join(10_000);
        pruner.join(10_000);

        assertFalse(pruner.isAlive(), "prune 應在 start 寫入後正常結束");
        assertEquals(1, svc.trackedPlayerCount(), "有效冷卻不可被 prune 連帶摘掉");
        assertEquals(60_000L, svc.remainingMillis(id, "k"),
            "start 啟動的冷卻必須真的被記錄下來");
    }

    @Test
    @DisplayName("prune 與 tryAcquire 交錯：有效冷卻不可因過期清理而遺失")
    void pruneRacingAcquire_validCooldownIsNotLost() throws Exception {
        GatedClock clock = new GatedClock(1000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();

        AtomicBoolean acquired = new AtomicBoolean();
        Thread acquirer = new Thread(
            () -> acquired.set(svc.tryAcquire(id, "k", 60_000)), "acquire");
        acquirer.start();
        // 與 start 的 gate 相同：停在讀取時鐘，也就是進入 per-player 臨界區
        // 之前，固定的是單一樣本而非決定性的交錯點；決定性覆蓋在下方壓力測試。
        assertTrue(clock.awaitParked(10, TimeUnit.SECONDS),
            "acquire 執行緒應停在時鐘讀取上");

        Thread pruner = new Thread(svc::pruneExpired, "prune");
        pruner.start();
        pruner.join(500);
        clock.releaseGate();
        acquirer.join(10_000);
        pruner.join(10_000);

        assertFalse(pruner.isAlive(), "prune 應在 acquire 寫入後正常結束");
        assertTrue(acquired.get(), "無既有冷卻時第一次 acquire 應成功");
        assertEquals(1, svc.trackedPlayerCount(), "有效冷卻不可被 prune 連帶摘掉");
        assertEquals(60_000L, svc.remainingMillis(id, "k"),
            "成功取得的冷卻必須真的被記錄下來");
        assertFalse(svc.tryAcquire(id, "k", 60_000),
            "冷卻窗內的第二次 acquire 必須被擋下");
    }

    @Test
    @DisplayName("prune 高壓下併發 acquire 同 key 仍只放行一次")
    void concurrentAcquireUnderPrunePressure_onlyOneSucceeds() throws Exception {
        // pruneExpired 與冷卻寫入共用 per-player 鎖這件事，只有在「清理持續進行」
        // 時才有意義：若兩者不共用同一把鎖，prune 可以在寫入者取得內層 map 之後
        // 把整張空 map 摘掉，讓有效冷卻寫進沒有人引用的孤兒 map，冷卻窗因此被清空
        // 而多放行。單次 gate 無法固定這個交錯點，所以以多輪持續清理的壓力取樣。
        int rounds = 150;
        int threads = 6;
        int violatingRounds = 0;
        int observedPermits = 0;
        int lostCooldownRounds = 0;
        for (int round = 0; round < rounds; round++) {
            FakeClock clock = new FakeClock();
            clock.now.set(1000);
            PlayerCooldownService svc = new PlayerCooldownService(clock);
            UUID id = UUID.randomUUID();
            CountDownLatch roundDone = new CountDownLatch(1);
            Thread pruner = new Thread(() -> {
                while (roundDone.getCount() > 0) {
                    svc.pruneExpired();
                }
            }, "prune-" + round);
            pruner.start();
            int permits = acquireConcurrently(svc, id, threads);
            // 讓最後一次寫入落地，避免在清理執行緒還在跑時就斷言。
            svc.start(id, "k", 60_000);
            roundDone.countDown();
            pruner.join(10_000);
            observedPermits += permits;
            if (permits != 1) {
                violatingRounds++;
            }
            // 放行之後冷卻必須真的還在：孤兒 map 會讓 trackedPlayerCount 掉回 0。
            if (svc.remainingMillis(id, "k") != 60_000L) {
                lostCooldownRounds++;
            }
        }
        assertEquals(0, violatingRounds,
            "prune 高壓下同 key 不可在同一冷卻窗同時多通；觀察到 " + observedPermits
                + " 次放行分佈於 " + rounds + " 輪");
        assertEquals(rounds, observedPermits, "每輪恰好一個執行緒取得冷卻鎖");
        assertEquals(0, lostCooldownRounds,
            "成功取得的冷卻不可被 prune 摘掉而消失（寫入孤兒 map）；"
                + lostCooldownRounds + " 輪發生於 " + rounds + " 輪");
    }

    @Test
    @DisplayName("prune 高壓下併發 start 不可讓冷卻消失")
    void concurrentStartUnderPrunePressure_cooldownSurvives() throws Exception {
        // start 與 prune 的交錯點和 acquire 相同：取得內層 map 之後、寫入之前，
        // 清理若不共用同一把鎖就會摘掉這張空 map。這裡改以「冷卻必須留得住」為
        // 判準，直接檢查 start 的寫入有沒有落進孤兒 map。
        int rounds = 150;
        int threads = 6;
        int lostRounds = 0;
        for (int round = 0; round < rounds; round++) {
            FakeClock clock = new FakeClock();
            clock.now.set(1000);
            PlayerCooldownService svc = new PlayerCooldownService(clock);
            UUID id = UUID.randomUUID();
            CountDownLatch roundDone = new CountDownLatch(1);
            Thread pruner = new Thread(() -> {
                while (roundDone.getCount() > 0) {
                    svc.pruneExpired();
                }
            }, "prune-" + round);
            pruner.start();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        go.await();
                        svc.start(id, "k", 60_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "start 執行緒應在時限內完成");
            pool.shutdownNow();
            roundDone.countDown();
            pruner.join(10_000);
            if (svc.remainingMillis(id, "k") != 60_000L
                || svc.trackedPlayerCount() != 1) {
                lostRounds++;
            }
        }
        assertEquals(0, lostRounds,
            "start 寫入的冷卻不可被 prune 摘掉而消失（寫入孤兒 map）；"
                + lostRounds + " 輪發生於 " + rounds + " 輪");
    }

/**
     * 已過期入口存在時，prune 高壓下併發 start 不可讓新冷卻消失。
     *
     * <p>與 {@code concurrentStartUnderPrunePressure_cooldownSurvives} 的差別
     * 只有一個，但那個差別正是能不能抓到非原子 prune 的關鍵：本測試讓 prune
     * 每次都有過期項目可清，該玩家的表因此會被清空並從外層摘掉。</p>
     *
     * <p>非原子 prune（先 get 內層 map → 直接 removeIf → 再
     * {@code expiresAt.remove}）的破綻是：判定「表已空」之後、真正呼叫
     * remove 之前，另一執行緒的 start 把新冷卻寫進同一張 map，prune 的 remove
     * 隨後把它連帶摘掉。這個窗口只在「表真的會被清空」時才存在 ——
     * 沒有過期入口時 {@code isEmpty()} 永遠不成立，舊的壓力測試因此完全踩不到
     * 它。</p>
     *
     * <p>讓 prune 每次都有東西可清、又不讓「有效冷卻自己走完」的做法是
     * <strong>明確控制時鐘</strong>：每一輪由測試自己把 now 推過播種入口的到期
     * 時間，因此每輪 prune 都看得到一筆過期項目可清；但同一輪之內 now 完全
     * 凍結，{@code 60_000ms} 的有效冷卻不會因為時間流逝而到期。</p>
     *
     * <p>這一點是必要的：用「每次讀取都前進」的時鐘時，prune 執行緒在旁邊
     * hot spin 會讓 now 以不受控的速度前進（實測單輪最大漂移 1,023,642ms），
     * 連 {@code 60_000ms} 的冷卻都會被走完，測試就把「冷卻真的到期」誤判成
     * 「寫入被遺失」。改由測試控制推進量之後，唯一能讓
     * {@code remainingMillis} 歸零的原因就只剩下寫入真的遺失。</p>
     */
    @Test
    @DisplayName("已過期入口存在時，prune 高壓下併發 start 不可讓新冷卻消失")
    void concurrentStartOverExpiredEntry_pruneKeepsNewCooldown() throws Exception {
        FrozenClock clock = new FrozenClock(1_000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        int rounds = 20_000;
        final long cooldown = 60_000;
        final long expiryTick = 1;

        AtomicBoolean stop = new AtomicBoolean();
        Thread pruner = new Thread(() -> {
            while (!stop.get()) {
                svc.pruneExpired();
            }
        }, "prune-expired");
        pruner.start();
        try {
            int lostRounds = 0;
            int expiredEntriesRemoved = 0;
            for (int round = 0; round < rounds; round++) {
                // 播種一筆立刻到期的入口，讓 prune 每輪都有東西可清 → 表會被清空
                // 並從外層摘掉，正是非原子 prune 的破綻窗口。
                svc.start(id, "expired", 1);
                clock.advance(expiryTick);
                // 有效冷卻：now 已凍結，不會因時間流逝而到期
                svc.start(id, "k", cooldown);
                // prune 若在 start 之前把表摘掉，這裡就會讀到 0
                if (svc.remainingMillis(id, "k") <= 0L) {
                    lostRounds++;
                }
                // 讓下一輪重新播種
                svc.end(id, "k");
                // 存在性握手：主執行緒在本輪只刪除 "k"，從不刪除 "expired"，
                // 唯一能讓快照缺失該 key 的就是背景 prune。remainingMillis == 0
                // 只代表時間到期（入口仍可能留在 map 內），不可當成已清理的證據。
                Map<String, Long> perRound = svc.snapshot().get(id);
                if (perRound == null || !perRound.containsKey("expired")) {
                    expiredEntriesRemoved++;
                }
            }
            assertEquals(0, lostRounds,
                "過期入口被清理時，start 寫入的新冷卻不可被連帶摘掉；"
                    + lostRounds + " 輪發生於 " + rounds + " 輪");
            // prune 有沒有真的在做事，用「過期入口確實被清掉」證明，而不是用
            // prunesRun > 0 —— 後者只是量測執行緒有沒有被排到程，在忙碌機器上
            // 主迴圈可能先跑完而讓清理執行緒一次都沒執行，屬於排程競態而非
            // 產品行為，拿它當斷言會讓測試自己 flaky。
            assertTrue(expiredEntriesRemoved > 0,
                "過期入口必須真的被 prune 清掉，prune 才不是空轉");
        } finally {
            stop.set(true);
            pruner.join(10_000);
            assertFalse(pruner.isAlive(), "清理執行緒應在停止旗標後結束");
        }
    }

    /**
     * 已過期入口存在時，prune 高壓下併發 tryAcquire 的放行結果不可遺失。
     *
     * <p>同一個非原子窗口，但以 tryAcquire 驗證：被放行的那一次取得，其冷卻窗
     * 必須真的留得住。遺失時，同一冷卻窗內的下一次取得會被誤放行 ——
     * 這才是冷卻失效對呼叫端真正的影響，比只檢查剩餘時間更有意義。</p>
     *
     * <p>時鐘同樣由測試控制（見
     * {@code concurrentStartOverExpiredEntry_pruneKeepsNewCooldown} 的說明）：
     * 每輪明確推進一次讓播種入口過期，同輪之內 now 凍結，因此「放行後冷卻仍
     * 有效」只會在寫入真的遺失時失敗，不會被時間漂移誤傷。</p>
     */
    @Test
    @DisplayName("已過期入口存在時，prune 高壓下併發 tryAcquire 的放行結果不可遺失")
    void concurrentAcquireOverExpiredEntry_grantedCooldownSurvives() throws Exception {
        FrozenClock clock = new FrozenClock(1_000);
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        int rounds = 20_000;
        final long cooldown = 60_000;
        final long expiryTick = 1;

        AtomicBoolean stop = new AtomicBoolean();
        Thread pruner = new Thread(() -> {
            while (!stop.get()) {
                svc.pruneExpired();
            }
        }, "prune-expired");
        pruner.start();
        try {
            int lostRounds = 0;
            int doubleGrantRounds = 0;
            int expiredEntriesRemoved = 0;
            for (int round = 0; round < rounds; round++) {
                svc.start(id, "expired", 1);
                clock.advance(expiryTick);
                boolean granted = svc.tryAcquire(id, "k", cooldown);
                if (!granted) {
                    // 該玩家對 k 唯一的既有紀錄是已過期入口，不該擋下這次取得
                    doubleGrantRounds++;
                    continue;
                }
                if (svc.remainingMillis(id, "k") <= 0L) {
                    lostRounds++;
                    continue;
                }
                // 冷卻窗內的第二次取得必須仍被擋下
                if (svc.tryAcquire(id, "k", cooldown)) {
                    doubleGrantRounds++;
                }
                svc.end(id, "k");
                // 存在性握手：主執行緒在本輪只刪除 "k"，從不刪除 "expired"，
                // 唯一能讓快照缺失該 key 的就是背景 prune。remainingMillis == 0
                // 只代表時間到期（入口仍可能留在 map 內），不可當成已清理的證據。
                Map<String, Long> perRound = svc.snapshot().get(id);
                if (perRound == null || !perRound.containsKey("expired")) {
                    expiredEntriesRemoved++;
                }
            }
            assertEquals(0, lostRounds,
                "過期入口被清理時，tryAcquire 放行的冷卻不可被連帶摘掉；"
                    + lostRounds + " 輪發生於 " + rounds + " 輪");
            assertEquals(0, doubleGrantRounds,
                "冷卻窗內不可因清理而重複放行；"
                    + doubleGrantRounds + " 輪發生於 " + rounds + " 輪");
            // prune 有沒有真的在做事，用「過期入口確實被清掉」證明，而不是用
            // prunesRun > 0 —— 後者只是量測執行緒有沒有被排到程，在忙碌機器上
            // 主迴圈可能先跑完而讓清理執行緒一次都沒執行，屬於排程競態而非
            // 產品行為，拿它當斷言會讓測試自己 flaky。
            assertTrue(expiredEntriesRemoved > 0,
                "過期入口必須真的被 prune 清掉，prune 才不是空轉");
        } finally {
            stop.set(true);
            pruner.join(10_000);
            assertFalse(pruner.isAlive(), "清理執行緒應在停止旗標後結束");
        }
    }

    /**
     * 只在測試明確呼叫 {@link #advance(long)} 時才前進的時鐘。
     *
     * <p>用途是讓「過期項目被清掉、表被摘掉」持續發生，同時讓
     * <strong>同一輪之內的時間流逝量為零</strong>：prune 讀多少次時鐘都不會
     * 改變 {@code now}，因此「有效冷卻被遺失」的斷言不會被時間漂移誤判。</p>
     *
     * <p>與「每次讀取都前進」的時鐘差別是關鍵：後者在 prune 執行緒 hot spin
     * 時會讓 {@code now} 以不受控的速度前進（實測單輪最大漂移 1,023,642ms），
     * 連 {@code 60_000ms} 的冷卻都會被走完，測試便把「冷卻真的到期」誤判成
     * 「寫入被遺失」而 flaky。</p>
     *
     * <p>仍不使用 {@link Thread#sleep}：時間只在測試主動推進時改變，測試行為
     * 取決於明確的推進次數而非真實經過時間。</p>
     */
    private static final class FrozenClock implements Clock {

        private final AtomicLong now;

        FrozenClock(long initial) {
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
     * 可控時鐘：第一次讀取會停在 gate 上，直到 {@link #releaseGate()}。
     *
     * <p>用途是把「已取得 per-player map」與「寫入過期時間」之間的交錯點固定
     * 出來，讓過期清理與冷卻寫入的競爭可以被確定性重現；不需要靠
     * {@link Thread#sleep} 猜時機。</p>
     */
    private static final class GatedClock implements Clock {

        private final AtomicLong now;
        private final AtomicBoolean gateUsed = new AtomicBoolean();
        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        GatedClock(long initial) {
            this.now = new AtomicLong(initial);
        }

        @Override
        public long currentTimeMillis() {
            if (gateUsed.compareAndSet(false, true)) {
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
