package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * SchedulerBackend seam 測試（對應 backend 選擇與 fail-closed 契約）。
 *
 * <p>鎖定三件事：</p>
 * <ul>
 *   <li><strong>選擇只依 capability profile</strong>：PAPER → PaperSchedulerBackend、
 *       FOLIA → FoliaSchedulerBackend、UNKNOWN（兩者皆 false）→ 無 backend（null），
 *       全程無版本字串 switch。</li>
 *   <li><strong>capability missing rejection</strong>：UNKNOWN 下 runnable 不執行、
 *       回傳 cancelled task 並記錄 ACELIB-SCHED-005。</li>
 *   <li><strong>backend 派發失敗 fail-closed</strong>：backend 拋錯時，scheduler 回傳
 *       cancelled task 並記錄 SCHED-005，<strong>絕不</strong>退到 Folia-unsafe 的
 *       global scheduler 去執行 runnable。</li>
 * </ul>
 *
 * <p>注意：MockBukkit v26.1.2 已內含部分 Folia API（見 GuiFoliaPathTest 註解），
 * 因此 FOLIA capability 在 MockBukkit 下可能成功 enqueue，無法穩定重現
 * 「classpath 缺 Folia API」的天然失敗。故 fail-closed 契約改以強制注入一個
 * 必定拋錯的 backend（package-private 4-arg 建構子）來決定性驗證。</p>
 */
@DisplayName("SafeScheduler — SchedulerBackend 選擇與 fail-closed")
class SchedulerBackendSelectionTest {

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 選擇只依 capability profile（無版本字串 switch）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("PAPER capability → 選擇 PaperSchedulerBackend")
    void paperCapability_selectsPaperBackend() {
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        assertTrue(scheduler.getBackend() instanceof PaperSchedulerBackend,
            "PAPER 必須選擇 PaperSchedulerBackend");
        scheduler.onPluginDisable();
    }

    @Test
    @DisplayName("FOLIA capability → 選擇 FoliaSchedulerBackend")
    void foliaCapability_selectsFoliaBackend() {
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        assertTrue(scheduler.getBackend() instanceof FoliaSchedulerBackend,
            "FOLIA 必須選擇 FoliaSchedulerBackend");
        scheduler.onPluginDisable();
    }

    @Test
    @DisplayName("UNKNOWN capability → backend 為 null（regionScheduling 與 globalScheduler 皆 false）")
    void unknownCapability_noBackend() {
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.UNKNOWN, PlatformCapability.forPlatform(Platform.UNKNOWN));
        assertTrue(scheduler.getBackend() == null,
            "UNKNOWN 必須無 backend（兩者皆 false，無版本字串 switch）");
        scheduler.onPluginDisable();
    }

    // -----------------------------------------------------------------
    // capability missing rejection：不執行 runnable，記錄 SCHED-005
    // -----------------------------------------------------------------

    @Test
    @DisplayName("UNKNOWN capability：runGlobal 回 cancelled + SCHED-005，runnable 不執行")
    void unknownCapability_runGlobal_rejected() {
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.UNKNOWN, PlatformCapability.forPlatform(Platform.UNKNOWN));
        AtomicBoolean executed = new AtomicBoolean(false);
        ScheduledTask task = scheduler.runGlobal(() -> executed.set(true));
        assertNotNull(task);
        assertTrue(task.isCancelled(), "UNKNOWN 必須回 cancelled task");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-005"),
            "必須記錄 SCHED-005");
        assertFalse(executed.get(), "UNKNOWN 不得執行 runnable（無 fallback）");
        scheduler.onPluginDisable();
    }

    // -----------------------------------------------------------------
    // backend 派發失敗 fail-closed：不得退到 global scheduler
    // -----------------------------------------------------------------

    @Test
    @DisplayName("backend 派發失敗：回 cancelled + SCHED-005，runnable 不執行（無 global fallback）")
    void backendDispatchFailure_failClosed() {
        SchedulerBackend failing = new SchedulerBackend() {
            @Override
            public BukkitTask dispatch(TaskType type, Runnable wrapped, Player player,
                                        Object entityOrLoc, long delayTicks, long periodTicks,
                                        boolean async) throws Exception {
                throw new IllegalStateException("simulated backend failure");
            }
        };
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA), failing);
        AtomicBoolean executed = new AtomicBoolean(false);
        ScheduledTask task = scheduler.runGlobal(() -> executed.set(true));
        assertNotNull(task);
        assertTrue(task.isCancelled(), "backend 失敗必須回 cancelled task");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-005"),
            "backend 失敗必須記錄 SCHED-005");
        assertFalse(executed.get(),
            "backend 失敗不得退到 global scheduler 執行 runnable");
        scheduler.onPluginDisable();
    }

    // -----------------------------------------------------------------
    // PAPER backend 委派：所有路徑都透過 backend 執行 runnable
    // -----------------------------------------------------------------

    @Test
    @DisplayName("PAPER backend 委派：8 種任務路徑皆執行 runnable")
    void paperBackend_delegatesAllPaths() throws InterruptedException {
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));

        AtomicBoolean g = new AtomicBoolean();
        scheduler.runGlobal(() -> g.set(true));
        server.getScheduler().performTicks(1L);
        assertTrue(g.get(), "global 必須透過 backend 執行");

        AtomicBoolean a = new AtomicBoolean();
        scheduler.runAsync(() -> a.set(true));
        // MockBukkit 的 async task 不經 performTicks 驅動，改以輪詢等待 async 執行緒完成
        for (int i = 0; i < 50 && !a.get(); i++) {
            Thread.sleep(20L);
        }
        assertTrue(a.get(), "async 必須透過 backend 執行");

        AtomicBoolean l = new AtomicBoolean();
        scheduler.runLater(() -> l.set(true), 0L);
        server.getScheduler().performTicks(1L);
        assertTrue(l.get(), "later 必須透過 backend 執行");

        AtomicBoolean t = new AtomicBoolean();
        scheduler.runTimer(() -> t.set(true), 0L, 1L);
        server.getScheduler().performTicks(2L);
        assertTrue(t.get(), "timer 必須透過 backend 執行");

        var player = server.addPlayer();
        AtomicBoolean p = new AtomicBoolean();
        scheduler.runForPlayer(player, () -> p.set(true));
        server.getScheduler().performTicks(1L);
        assertTrue(p.get(), "player 必須透過 backend 執行");

        AtomicBoolean e = new AtomicBoolean();
        scheduler.runForEntity(player, () -> e.set(true));
        server.getScheduler().performTicks(1L);
        assertTrue(e.get(), "entity 必須透過 backend 執行");

        var world = server.addSimpleWorld("flat");
        var loc = new org.bukkit.Location(world, 0.0, 64.0, 0.0);
        AtomicBoolean lo = new AtomicBoolean();
        scheduler.runAtLocation(loc, () -> lo.set(true));
        server.getScheduler().performTicks(1L);
        assertTrue(lo.get(), "location 必須透過 backend 執行");

        scheduler.onPluginDisable();
    }
}
