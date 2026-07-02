package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SafeScheduler 基本排程測試（對應 Plan §七 Phase 2 測試需求 + 邊界條件）。
 *
 * <p>覆蓋六種核心任務類型：global / async / later / timer / player / entity / location
 * （含 basic + 邊界條件：null runnable、負 delay、玩家離線、實體死亡等）。</p>
 */
@DisplayName("SafeScheduler — 基本排程")
class SafeSchedulerTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        // 使用 test classloader 啟用，確保 platform = PAPER
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        // PAPER 路徑：MockBukkit 預設 platform
        scheduler = new SafeSchedulerImpl(
            plugin,
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER)
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isDisabled()) {
            scheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // runGlobal
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runGlobal: runnable 應在下一個 tick 被執行")
    void runGlobal_executesRunnable() {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runGlobal(counter::incrementAndGet);
        assertNotNull(task);
        assertSame(plugin, task.getPlugin());
        assertEquals(TaskType.GLOBAL, task.getType());
        // 推進 tick 讓 task 執行
        server.getScheduler().performTicks(1L);
        assertEquals(1, counter.get(), "runGlobal 必須在下一個 tick 執行");
    }

    @Test
    @DisplayName("runGlobal: null runnable 必須拋 NPE")
    void runGlobal_nullRunnable_throws() {
        assertThrows(NullPointerException.class, () -> scheduler.runGlobal(null));
    }

    @Test
    @DisplayName("runGlobal: 取消後任務不再執行")
    void runGlobal_cancelPreventsExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runGlobal(counter::incrementAndGet);
        task.cancel();
        assertTrue(task.isCancelled());
        server.getScheduler().performTicks(1L);
        assertEquals(0, counter.get(), "取消後不應被執行");
    }

    // -----------------------------------------------------------------
    // runAsync
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runAsync: runnable 應被排入 async 排程")
    void runAsync_schedules() {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runAsync(counter::incrementAndGet);
        assertNotNull(task);
        assertEquals(TaskType.ASYNC, task.getType());
        // MockBukkit 環境下 performTicks 會排入 async pool；驗 task 已建立即可
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("runAsync: null runnable 必須拋 NPE")
    void runAsync_nullRunnable_throws() {
        assertThrows(NullPointerException.class, () -> scheduler.runAsync(null));
    }

    // -----------------------------------------------------------------
    // runLater
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runLater: 負數延遲必須拋 IllegalArgumentException")
    void runLater_negativeDelay_throws() {
        AtomicInteger counter = new AtomicInteger(0);
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runLater(counter::incrementAndGet, -1L));
    }

    @Test
    @DisplayName("runLater: 正常延遲 0 應在下一個 tick 執行")
    void runLater_zeroDelay_executesNextTick() {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runLater(counter::incrementAndGet, 0L);
        assertNotNull(task);
        assertEquals(TaskType.LATER, task.getType());
        server.getScheduler().performTicks(1L);
        assertEquals(1, counter.get(), "delay=0 應等同 runGlobal");
    }

    @Test
    @DisplayName("runLater: 延遲 2 tick 時，第 1 tick 不執行，第 2 tick 執行")
    void runLater_delaysExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.runLater(counter::incrementAndGet, 2L);
        server.getScheduler().performTicks(1L);
        assertEquals(0, counter.get(), "第 1 tick 不應執行");
        server.getScheduler().performTicks(1L);
        assertEquals(1, counter.get(), "第 2 tick 應執行");
    }

    @Test
    @DisplayName("runLater: null runnable 必須拋 NPE")
    void runLater_nullRunnable_throws() {
        assertThrows(NullPointerException.class, () -> scheduler.runLater(null, 0L));
    }

    // -----------------------------------------------------------------
    // runTimer
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runTimer: 週期 1 tick 應連續執行多次")
    void runTimer_repeatsExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runTimer(counter::incrementAndGet, 0L, 1L);
        assertNotNull(task);
        assertEquals(TaskType.TIMER, task.getType());
        server.getScheduler().performTicks(3L);
        // 至少執行 3 次（首次 + 兩個週期）
        assertTrue(counter.get() >= 3, "runTimer 週期 1 至少跑 3 次，實際: " + counter.get());
        task.cancel();
    }

    @Test
    @DisplayName("runTimer: 週期 0 必須拋 IllegalArgumentException")
    void runTimer_zeroPeriod_throws() {
        AtomicInteger counter = new AtomicInteger(0);
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runTimer(counter::incrementAndGet, 0L, 0L));
    }

    @Test
    @DisplayName("runTimer: 負 delay 必須拋 IllegalArgumentException")
    void runTimer_negativeDelay_throws() {
        AtomicInteger counter = new AtomicInteger(0);
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.runTimer(counter::incrementAndGet, -1L, 1L));
    }

    // -----------------------------------------------------------------
    // runForPlayer / runForPlayerLater
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runForPlayer: 在線玩家應成功派送")
    void runForPlayer_onlinePlayer_succeeds() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runForPlayer(player, counter::incrementAndGet);
        assertNotNull(task);
        assertEquals(TaskType.PLAYER, task.getType());
        assertFalse(task.isCancelled(), "在線玩家應成功派送");
    }

    @Test
    @DisplayName("runForPlayer: 離線玩家應回傳 cancelled no-op 並記錄 ACELIB-SCHED-002")
    void runForPlayer_offlinePlayer_recordsError() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.disconnect();
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runForPlayer(player, counter::incrementAndGet);
        assertNotNull(task);
        assertTrue(task.isCancelled(), "離線玩家應回傳已 cancelled task");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-002"),
            "必須記錄 PLAYER_OFFLINE 錯誤");
    }

    @Test
    @DisplayName("runForPlayer: null player 必須拋 NPE")
    void runForPlayer_nullPlayer_throws() {
        assertThrows(NullPointerException.class,
            () -> scheduler.runForPlayer(null, () -> {}));
    }

    @Test
    @DisplayName("runForPlayerLater: 在線玩家 + 正常 delay 應派送")
    void runForPlayerLater_onlinePlayer_succeeds() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runForPlayerLater(player, counter::incrementAndGet, 5L);
        assertNotNull(task);
        assertEquals(TaskType.PLAYER_LATER, task.getType());
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("runForPlayerLater: 離線玩家應回傳 cancelled no-op")
    void runForPlayerLater_offlinePlayer_recordsError() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.disconnect();
        ScheduledTask task = scheduler.runForPlayerLater(player, () -> {}, 1L);
        assertTrue(task.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-002"));
    }

    // -----------------------------------------------------------------
    // runForEntity
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runForEntity: 活實體應成功派送")
    void runForEntity_liveEntity_succeeds() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runForEntity(player, counter::incrementAndGet);
        assertNotNull(task);
        assertEquals(TaskType.ENTITY, task.getType());
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("runForEntity: null entity 必須拋 NPE")
    void runForEntity_nullEntity_throws() {
        assertThrows(NullPointerException.class,
            () -> scheduler.runForEntity(null, () -> {}));
    }

    // -----------------------------------------------------------------
    // runAtLocation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runAtLocation: 已載入 chunk 應成功派送")
    void runAtLocation_loadedChunk_succeeds() {
        org.mockbukkit.mockbukkit.world.WorldMock world = server.addSimpleWorld("flat");
        org.bukkit.Location loc = new org.bukkit.Location(world, 0.0, 64.0, 0.0);
        AtomicInteger counter = new AtomicInteger(0);
        ScheduledTask task = scheduler.runAtLocation(loc, counter::incrementAndGet);
        assertNotNull(task);
        assertEquals(TaskType.LOCATION, task.getType());
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("runAtLocation: null location 必須拋 NPE")
    void runAtLocation_nullLocation_throws() {
        assertThrows(NullPointerException.class,
            () -> scheduler.runAtLocation(null, () -> {}));
    }

    // -----------------------------------------------------------------
    // recorder 與查詢
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getRecorderErrors(0) 應回傳空清單")
    void getRecorderErrors_zero_returnsEmpty() {
        scheduler.runGlobal(() -> { throw new RuntimeException("boom"); });
        server.getScheduler().performTicks(1L);
        // 任務內拋錯會被記錄；用 0 限制時回空
        assertNotNull(scheduler.getRecorderErrors(0));
        assertEquals(0, scheduler.getRecorderErrors(0).size());
    }

    @Test
    @DisplayName("getRecorderErrors(10) 應回傳最多 10 筆")
    void getRecorderErrors_limitRespected() {
        for (int i = 0; i < 15; i++) {
            scheduler.runGlobal(() -> { throw new RuntimeException("e" + System.nanoTime()); });
        }
        server.getScheduler().performTicks(1L);
        var errors = scheduler.getRecorderErrors(10);
        assertTrue(errors.size() <= 10, "getRecorderErrors(10) 最多 10 筆，實際: " + errors.size());
    }

    @Test
    @DisplayName("scheduler 註冊 task 後 tracked 計數增加")
    void trackedCount_increasesAfterSchedule() {
        int before = scheduler.getTrackedTaskCount();
        ScheduledTask t1 = scheduler.runGlobal(() -> {});
        ScheduledTask t2 = scheduler.runLater(() -> {}, 0L);
        assertEquals(before + 2, scheduler.getTrackedTaskCount());
        t1.cancel();
        t2.cancel();
    }
}