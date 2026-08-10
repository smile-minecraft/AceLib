package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.Clock;
import java.util.Map;
import java.util.UUID;
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
}