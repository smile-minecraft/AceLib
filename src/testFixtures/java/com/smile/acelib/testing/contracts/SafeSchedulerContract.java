package com.smile.acelib.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 排程服務契約（真實作與假實作共用）。
 *
 * <p>鎖定「現有」公開 {@link SafeScheduler} 語意：立即／延遲／週期執行、
 * 取消、玩家離線（SCHED-002）、實體退休（SCHED-003）、chunk 未載入
 * （SCHED-004）、停用（SCHED-006）。子類提供 harness（生產側用 MockBukkit
 * 真實作，假側用 {@code FakeSafeScheduler}）。</p>
 *
 * @since 1.4.0
 */
@DisplayName("排程服務契約（真／假共用）")
public abstract class SafeSchedulerContract {

    /** 可操控平台狀態的測試 harness（每測試全新）。 */
    protected interface Harness {
        SafeScheduler scheduler();

        /** 推進時間使延遲／週期任務到期（生產側 tick 推進，假側虛擬 tick）。 */
        void runPending();

        Player onlinePlayer();

        Player offlinePlayer();

        Entity liveEntity();

        Entity retiredEntity();

        Location loadedLocation();

        Location unloadedLocation();

        /** 停用排程器（模擬 plugin disable）。 */
        void disable();
    }

    /** 每個測試全新 harness。 */
    protected abstract Harness newHarness();

    private static boolean hasCode(List<TaskErrorRecord> errors, String code) {
        return errors.stream().anyMatch(e -> e.code().contains(code));
    }

    @Test
    @DisplayName("全域任務執行；延遲任務到期前不執行、到期後執行")
    void globalAndDelayed_execute() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask immediate = harness.scheduler().runGlobal(counter::incrementAndGet);
        assertTrue(!immediate.isCancelled());
        assertEquals(TaskType.GLOBAL, immediate.getType());

        ScheduledTask delayed = harness.scheduler().runLater(counter::incrementAndGet, 5L);
        assertTrue(!delayed.isCancelled());
        int beforePending = counter.get();
        harness.runPending();
        assertTrue(counter.get() > beforePending, "延遲任務到期後必須執行");
    }

    @Test
    @DisplayName("週期任務重複執行；取消後停止")
    void timer_repeatsUntilCancelled() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask timer = harness.scheduler().runTimer(counter::incrementAndGet, 1L, 1L);
        harness.runPending();
        assertTrue(counter.get() >= 2, "週期任務必須重複執行，實際: " + counter.get());

        timer.cancel();
        assertTrue(timer.isCancelled());
        int frozen = counter.get();
        harness.runPending();
        assertEquals(frozen, counter.get(), "取消後不得再執行");
    }

    @Test
    @DisplayName("取消待執行任務後即使推進時間也不執行")
    void cancel_preventsExecution() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = harness.scheduler().runLater(counter::incrementAndGet, 2L);
        task.cancel();
        assertTrue(task.isCancelled());
        harness.runPending();
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("在線玩家／存活實體／已載入位置任務正常執行")
    void scopedTasks_executeWhenAvailable() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask playerTask =
            harness.scheduler().runForPlayer(harness.onlinePlayer(), counter::incrementAndGet);
        assertTrue(!playerTask.isCancelled());
        assertEquals(TaskType.PLAYER, playerTask.getType());

        ScheduledTask entityTask =
            harness.scheduler().runForEntity(harness.liveEntity(), counter::incrementAndGet);
        assertTrue(!entityTask.isCancelled());

        ScheduledTask locationTask = harness.scheduler()
            .runAtLocation(harness.loadedLocation(), counter::incrementAndGet);
        assertTrue(!locationTask.isCancelled());

        harness.runPending();
        assertEquals(3, counter.get(), "在線玩家／存活實體／已載入位置任務都必須執行");
    }

    @Test
    @DisplayName("離線玩家任務回 cancelled 並記 SCHED-002")
    void offlinePlayer_recordsSched002() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = harness.scheduler()
            .runForPlayer(harness.offlinePlayer(), counter::incrementAndGet);
        assertTrue(task.isCancelled());
        harness.runPending();
        assertEquals(0, counter.get());
        assertTrue(hasCode(harness.scheduler().getRecorderErrors(10), "SCHED-002"),
            "離線玩家必須記 SCHED-002");
    }

    @Test
    @DisplayName("退休實體任務回 cancelled 並記 SCHED-003")
    void retiredEntity_recordsSched003() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = harness.scheduler()
            .runForEntity(harness.retiredEntity(), counter::incrementAndGet);
        assertTrue(task.isCancelled());
        harness.runPending();
        assertEquals(0, counter.get());
        List<TaskErrorRecord> errors = harness.scheduler().getRecorderErrors(10);
        assertTrue(hasCode(errors, "SCHED-003"), "退休實體必須記 SCHED-003");
        assertTrue(!hasCode(errors, "SCHED-005"), "退休實體不得誤記為 SCHED-005");
    }

    @Test
    @DisplayName("未載入 chunk 位置任務回 cancelled 並記 SCHED-004")
    void unloadedLocation_recordsSched004() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = harness.scheduler()
            .runAtLocation(harness.unloadedLocation(), counter::incrementAndGet);
        assertTrue(task.isCancelled());
        harness.runPending();
        assertEquals(0, counter.get());
        assertTrue(hasCode(harness.scheduler().getRecorderErrors(10), "SCHED-004"),
            "未載入 chunk 必須記 SCHED-004");
    }

    @Test
    @DisplayName("停用後派送一律 cancelled 並記 SCHED-006")
    void disabledScheduler_recordsSched006() {
        Harness harness = newHarness();
        harness.disable();
        AtomicInteger counter = new AtomicInteger();

        ScheduledTask task = harness.scheduler().runGlobal(counter::incrementAndGet);
        assertTrue(task.isCancelled());
        assertEquals(0, counter.get());
        assertTrue(hasCode(harness.scheduler().getRecorderErrors(10), "SCHED-006"),
            "停用後派送必須記 SCHED-006");
    }

    @Test
    @DisplayName("cancelAll 取消待執行任務")
    void cancelAll_cancelsPending() {
        Harness harness = newHarness();
        AtomicInteger counter = new AtomicInteger();

        harness.scheduler().runLater(counter::incrementAndGet, 2L);
        harness.scheduler().runLater(counter::incrementAndGet, 3L);
        harness.scheduler().cancelAll();
        harness.runPending();
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("邊界：null runnable 拋 NPE；負 delay 與非法 period 拋 IAE；錯誤查詢非正數回空")
    void invalidInputs_throw() {
        Harness harness = newHarness();
        SafeScheduler scheduler = harness.scheduler();
        assertThrows(NullPointerException.class, () -> scheduler.runGlobal(null));
        assertThrows(NullPointerException.class,
            () -> scheduler.runForPlayer(null, () -> { }));
        assertThrows(NullPointerException.class,
            () -> scheduler.runForEntity(null, () -> { }));
        assertThrows(NullPointerException.class,
            () -> scheduler.runAtLocation(null, () -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runLater(() -> { }, -1L));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runTimer(() -> { }, 0L, 0L));
        assertEquals(0, scheduler.getRecorderErrors(0).size());
        assertEquals(0, scheduler.getRecorderErrors(-5).size());
    }
}
