package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SafeScheduler 生命週期測試：disable / cancelAll / reload。
 *
 * <p>對應 Plan §七 Phase 2 邊界條件：</p>
 * <ul>
 *   <li>「週期任務執行中插件被停用」</li>
 *   <li>「重複取消」</li>
 *   <li>驗收標準 #4「插件停用後不留 AceLib 任務」</li>
 * </ul>
 */
@DisplayName("SafeScheduler — 生命週期")
class SafeSchedulerLifecycleTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER)
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isDisabled()) {
            scheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("onPluginDisable 後 isDisabled() 為 true")
    void onPluginDisable_marksScheduler() {
        assertFalse(scheduler.isDisabled(), "初始必須 not disabled");
        scheduler.onPluginDisable();
        assertTrue(scheduler.isDisabled(), "onPluginDisable 後必須 disabled");
    }

    @Test
    @DisplayName("onPluginDisable 後 runGlobal 仍不丟例外，但回傳 no-op 並記錄 ACELIB-SCHED-006")
    void onPluginDisable_subsequentRunReturnsNoOp() {
        scheduler.onPluginDisable();
        ScheduledTask t = scheduler.runGlobal(() -> {});
        assertNotNull(t);
        assertTrue(t.isCancelled(), "disabled 後的任務必須為 no-op");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-006"),
            "disabled 後的任務必須記錄 ACELIB-SCHED-006");
    }

    @Test
    @DisplayName("onPluginDisable 取消所有 tracked task（timer 也要停）")
    void onPluginDisable_cancelsAllTracked() {
        // 註冊多個 task
        ScheduledTask t1 = scheduler.runGlobal(() -> {});
        ScheduledTask t2 = scheduler.runTimer(() -> {}, 0L, 1L);
        ScheduledTask t3 = scheduler.runLater(() -> {}, 0L);

        assertTrue(scheduler.getTrackedTaskCount() >= 3,
            "應至少有 3 個 tracked task，實際: " + scheduler.getTrackedTaskCount());

        scheduler.onPluginDisable();

        // 全部 cancelled
        assertTrue(t1.isCancelled(), "t1 應被取消");
        assertTrue(t2.isCancelled(), "t2 應被取消");
        assertTrue(t3.isCancelled(), "t3 應被取消");
        // tracked 計數歸 0
        assertEquals(0, scheduler.getTrackedTaskCount(),
            "onPluginDisable 後 tracked 應清空，實際: " + scheduler.getTrackedTaskCount());
    }

    @Test
    @DisplayName("cancelAll 重複呼叫不丟例外")
    void cancelAll_idempotent() {
        scheduler.runGlobal(() -> {});
        scheduler.cancelAll();
        scheduler.cancelAll(); // 重複呼叫
        scheduler.cancelAll();
        assertEquals(0, scheduler.getTrackedTaskCount());
    }

    @Test
    @DisplayName("cancelAll 取消多個任務；對同個 task 重複 cancel 不丟例外")
    void cancelAll_cancelsEachTask() {
        ScheduledTask t1 = scheduler.runGlobal(() -> {});
        ScheduledTask t2 = scheduler.runLater(() -> {}, 0L);
        scheduler.cancelAll();
        assertTrue(t1.isCancelled());
        assertTrue(t2.isCancelled());
        // 重複 cancel 不丟例外
        t1.cancel();
        t2.cancel();
    }

    @Test
    @DisplayName("onPluginDisable 重複呼叫不丟例外")
    void onPluginDisable_idempotent() {
        scheduler.onPluginDisable();
        scheduler.onPluginDisable(); // 第二次必須 not throw
        assertTrue(scheduler.isDisabled());
    }

    @Test
    @DisplayName("disable 後 runTimer 仍記錄 ACELIB-SCHED-006，不丟例外")
    void onPluginDisable_runTimer_recordsError() {
        scheduler.onPluginDisable();
        ScheduledTask t = scheduler.runTimer(() -> {}, 0L, 1L);
        assertNotNull(t);
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-006"));
    }

    @Test
    @DisplayName("disable 後 runForPlayer 仍記錄 ACELIB-SCHED-006")
    void onPluginDisable_runForPlayer_recordsError() {
        scheduler.onPluginDisable();
        var player = server.addPlayer();
        ScheduledTask t = scheduler.runForPlayer(player, () -> {});
        assertTrue(t.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-006"));
    }

    @Test
    @DisplayName("recreate 流程：disable 後新建 SafeSchedulerImpl 應為 not disabled")
    void recreateScheduler_isFresh() {
        scheduler.onPluginDisable();
        assertTrue(scheduler.isDisabled());

        SafeSchedulerImpl fresh = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER)
        );
        assertFalse(fresh.isDisabled(), "新建的 scheduler 必須 not disabled");
        assertEquals(0, fresh.getTrackedTaskCount());

        // 可正常運作
        ScheduledTask t = fresh.runGlobal(() -> {});
        assertNotNull(t);
        assertFalse(t.isCancelled());
        fresh.onPluginDisable();
    }
}