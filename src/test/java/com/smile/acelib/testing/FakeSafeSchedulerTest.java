package com.smile.acelib.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 可控排程 Red 錨點：下游以虛擬 tick 驅動延遲／週期任務，不再依賴平台排程器。
 *
 * <p>在 {@code FakeSafeScheduler} 交付前，本檔連編譯都過不了；
 * Green 後鎖定：同步立即執行、虛擬 tick 驅動延遲與週期、取消語意、停用後
 * {@code ACELIB-SCHED-006}、錯誤查詢形狀。</p>
 */
@DisplayName("FakeSafeScheduler — 可控排程 Red")
class FakeSafeSchedulerTest {

    private static JavaPlugin owner() {
        return Mockito.mock(JavaPlugin.class);
    }

    @Test
    @DisplayName("全域與非同步任務同步執行；延遲任務需 advanceTicks 才執行")
    void delayedTasks_runOnVirtualTicks() {
        FakeClock clock = new FakeClock();
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), clock);
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask immediate = scheduler.runGlobal(counter::incrementAndGet);
        assertFalse(immediate.isCancelled());
        assertEquals(1, counter.get());

        ScheduledTask delayed = scheduler.runLater(counter::incrementAndGet, 5L);
        assertFalse(delayed.isCancelled());
        scheduler.advanceTicks(4L);
        assertEquals(1, counter.get());
        scheduler.advanceTicks(1L);
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("取消後即使推進 tick 也不執行；cancelAll 取消全部待執行")
    void cancel_preventsExecution() {
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), new FakeClock());
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = scheduler.runLater(counter::incrementAndGet, 2L);
        task.cancel();
        assertTrue(task.isCancelled());
        scheduler.advanceTicks(10L);
        assertEquals(0, counter.get());

        scheduler.runLater(counter::incrementAndGet, 1L);
        scheduler.runLater(counter::incrementAndGet, 1L);
        scheduler.cancelAll();
        scheduler.advanceTicks(10L);
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("停用後派送一律 no-op 並記 ACELIB-SCHED-006")
    void disabledScheduler_recordsSched006() {
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), new FakeClock());
        scheduler.disable();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = scheduler.runGlobal(counter::incrementAndGet);
        assertTrue(task.isCancelled());
        assertEquals(0, counter.get());

        List<TaskErrorRecord> errors = scheduler.getRecorderErrors(10);
        assertTrue(errors.stream().anyMatch(e -> e.code().contains("SCHED-006")),
            "停用後派送必須記 SCHED-006，實際: " + errors);
        assertEquals(TaskType.GLOBAL, task.getType());
    }

    @Test
    @DisplayName("邊界：null runnable 拋 NPE；負 delay 拋 IAE")
    void rejectsInvalidInput() {
        SafeScheduler scheduler = new FakeSafeScheduler(owner(), new FakeClock());
        assertThrows(NullPointerException.class, () -> scheduler.runGlobal(null));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runLater(() -> { }, -1L));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runTimer(() -> { }, 0L, 0L));
    }

    @Test
    @DisplayName("退休實體任務回 cancelled 並記 SCHED-003；runnable 不執行")
    void retiredEntity_recordsSched003() {
        FakeClock clock = new FakeClock();
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), clock);
        AtomicInteger counter = new AtomicInteger();

        org.bukkit.entity.Entity entity = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
        org.mockito.Mockito.when(entity.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(entity.isDead()).thenReturn(false);
        org.mockito.Mockito.when(entity.isValid()).thenReturn(true);
        ScheduledTask live = scheduler.runForEntity(entity, counter::incrementAndGet);
        assertFalse(live.isCancelled());

        scheduler.retire(entity);
        ScheduledTask retired =
            scheduler.runForEntity(entity, counter::incrementAndGet);
        assertTrue(retired.isCancelled());
        scheduler.advanceTicks(10L);
        assertEquals(1, counter.get());

        List<TaskErrorRecord> errors = scheduler.getRecorderErrors(10);
        assertTrue(errors.stream().anyMatch(e -> e.code().contains("SCHED-003")),
            "退休實體必須記 SCHED-003，實際: " + errors);

        scheduler.revive(entity);
        scheduler.runForEntity(entity, counter::incrementAndGet);
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("chunk 未載入位置任務回 cancelled 並記 SCHED-004；覆寫後可執行")
    void unloadedChunk_recordsSched004() {
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), new FakeClock());
        AtomicInteger counter = new AtomicInteger();

        org.bukkit.Location location = new org.bukkit.Location(null, 8.0, 64.0, 8.0);
        ScheduledTask rejected =
            scheduler.runAtLocation(location, counter::incrementAndGet);
        assertTrue(rejected.isCancelled());
        assertTrue(scheduler.getRecorderErrors(10).stream()
            .anyMatch(e -> e.code().contains("SCHED-004")));

        scheduler.setChunkLoaded(location, true);
        ScheduledTask accepted =
            scheduler.runAtLocation(location, counter::incrementAndGet);
        assertFalse(accepted.isCancelled());
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("離線玩家任務回 cancelled 並記 SCHED-002")
    void offlinePlayer_recordsSched002() {
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), new FakeClock());
        AtomicInteger counter = new AtomicInteger();

        org.bukkit.entity.Player player =
            org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(uuid);
        org.mockito.Mockito.when(player.isOnline()).thenReturn(true);
        scheduler.runForPlayer(player, counter::incrementAndGet);
        assertEquals(1, counter.get());

        scheduler.markPlayerOffline(uuid);
        ScheduledTask rejected =
            scheduler.runForPlayer(player, counter::incrementAndGet);
        assertTrue(rejected.isCancelled());
        assertTrue(scheduler.getRecorderErrors(10).stream()
            .anyMatch(e -> e.code().contains("SCHED-002")));
        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("週期任務按虛擬 tick 重複執行；任務拋錯記 SCHED-001 且不中斷後續")
    void timer_repeatsAndErrorsRecorded() {
        FakeClock clock = new FakeClock();
        FakeSafeScheduler scheduler = new FakeSafeScheduler(owner(), clock);
        AtomicInteger counter = new AtomicInteger();

        scheduler.runTimer(counter::incrementAndGet, 1L, 2L);
        scheduler.advanceTicks(5L);
        assertEquals(3, counter.get(), "tick1／3／5 各執行一次");
        assertEquals(250L, clock.currentTimeMillis());

        scheduler.runGlobal(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(scheduler.getRecorderErrors(10).stream()
            .anyMatch(e -> e.code().contains("SCHED-001")));

        scheduler.cancelAll();
        assertEquals(0, scheduler.pendingTaskCount());
    }
}
