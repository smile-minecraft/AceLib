package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 已退休實體回 null 分類與 disable/dispatch 並行生命週期回歸測試。
 *
 * <p>鎖定兩條生命週期約定：已退休 entity 派送回 null 必須記為
 * {@code ACELIB-SCHED-003} 並回安全 no-op（不得誤記為 {@code ACELIB-SCHED-005}）；
 * disable 插入派送與登記之間、且底層句柄尚未返回時，底層最終必須被取消、
 * 追蹤不得殘留、停用後回呼不得再產生效果。
 */
@DisplayName("SafeScheduler — 退休分類與停用並行")
class SafeSchedulerRetiredAndDisableRaceTest {

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

    @Test
    @DisplayName("已退休 entity 回 null：記 SCHED-003 並回安全 no-op，不留 tracked")
    void retiredEntityNullHandle_recordsSched003() {
        StubGlobalRegionScheduler global = new StubGlobalRegionScheduler(plugin);
        NullEntityScheduler nullEntities = new NullEntityScheduler(plugin);
        Function<org.bukkit.entity.Entity, EntityScheduler> resolver = e -> nullEntities;
        StubAsyncScheduler async = new StubAsyncScheduler(plugin);
        StubRegionScheduler region = new StubRegionScheduler(plugin);
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, resolver, async, region));

        var player = server.addPlayer();
        assertFalse(player.isDead(), "前置：測試用實體必須活著，否則走的是派送前檢查而非回 null 路徑");

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = scheduler.runForEntity(player, () -> ran.set(true));

        assertNotNull(task, "回 null 時仍須回傳 no-op task，不可回 null 或拋例外");
        assertTrue(task.isCancelled(), "已退休 entity 任務必須為 cancelled");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-003"),
            "已退休 entity 回 null 必須記為 ACELIB-SCHED-003");
        assertFalse(scheduler.getRecorder().contains("ACELIB-SCHED-005"),
            "已退休 entity 不得誤記為平台不支援 SCHED-005");
        assertEquals(0, scheduler.getTrackedTaskCount(), "no-op 不得留在 tracked");
        assertFalse(ran.get(), "runnable 不得執行");
        scheduler.onPluginDisable();
    }

    @Test
    @DisplayName("disable 插入派送/登記間且句柄尚未返回：底層最終必取消、無殘留、回呼無效果")
    void disableDuringDispatch_cancelsUnderlyingHandle() throws Exception {
        LatchBackend backend = new LatchBackend();
        SafeSchedulerImpl scheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            backend);

        AtomicBoolean userRan = new AtomicBoolean(false);
        AtomicReference<ScheduledTask> returned = new AtomicReference<>();
        AtomicReference<Throwable> dispatchError = new AtomicReference<>();
        Thread dispatchThread = new Thread(() -> {
            try {
                returned.set(scheduler.runGlobal(() -> userRan.set(true)));
            } catch (Throwable t) {
                dispatchError.set(t);
            }
        }, "dispatch-race");
        dispatchThread.start();

        assertTrue(backend.entered.await(5, TimeUnit.SECONDS), "backend 必須進入 dispatch 並阻塞");
        // 此時 backend 尚未返回句柄：在派送與登記之間插入停用。
        scheduler.onPluginDisable();
        backend.release.countDown();
        dispatchThread.join(5000);

        assertTrue(dispatchError.get() == null,
            "dispatch 不得拋例外，實際: " + dispatchError.get());
        assertNotNull(returned.get(), "即使停用插入，dispatch 仍須回傳 task");
        assertTrue(backend.handle.cancelled, "底層句柄最終必須被取消（handle 在 disable 後才返回的情況）");
        assertTrue(returned.get().isCancelled(), "回傳的 task 必須為 cancelled");
        assertEquals(0, scheduler.getTrackedTaskCount(), "停用後 tracked 不得殘留");

        // 模擬平台已排入的執行在停用後才送達：回呼不得再產生效果。
        Runnable captured = backend.capturedWrapped.get();
        assertNotNull(captured, "backend 必須捕獲 wrapped 回呼");
        captured.run();
        assertFalse(userRan.get(), "停用後回呼不得再產生效果");
    }

    // -----------------------------------------------------------------
    // Stub：回 null 的 entity scheduler（模擬已退休實體）
    // -----------------------------------------------------------------

    /** 無論哪種派送一律回 null，模擬實體已在派送當下退休。 */
    private static final class NullEntityScheduler implements EntityScheduler {
        private final Plugin owner;

        NullEntityScheduler(Plugin owner) {
            this.owner = owner;
        }

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delayTicks) {
            return false;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            Runnable retired) {
            return null;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            Runnable retired, long delayTicks) {
            return null;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            Runnable retired, long initialDelayTicks, long periodTicks) {
            return null;
        }
    }

    /** 最小 global stub：測試只走 entity 路徑，global 不應被用到。 */
    private static final class StubGlobalRegionScheduler implements GlobalRegionScheduler {
        private final Plugin owner;

        StubGlobalRegionScheduler(Plugin owner) {
            this.owner = owner;
        }

        @Override
        public void execute(Plugin plugin, Runnable run) {
            run.run();
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            long delayTicks) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public void cancelTasks(Plugin plugin) {
        }
    }

    private static final class StubAsyncScheduler implements AsyncScheduler {
        private final Plugin owner;

        StubAsyncScheduler(Plugin owner) {
            this.owner = owner;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runNow(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(
            Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public void cancelTasks(Plugin plugin) {
        }
    }

    private static final class StubRegionScheduler implements RegionScheduler {
        private final Plugin owner;

        StubRegionScheduler(Plugin owner) {
            this.owner = owner;
        }

        @Override
        public void execute(Plugin plugin, org.bukkit.World world, int chunkX, int chunkZ, Runnable run) {
            run.run();
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(
            Plugin plugin, org.bukkit.World world, int chunkX, int chunkZ,
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(
            Plugin plugin, org.bukkit.World world, int chunkX, int chunkZ,
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
            throw new UnsupportedOperationException("test routes entity only");
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(
            Plugin plugin, org.bukkit.World world, int chunkX, int chunkZ,
            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
            long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException("test routes entity only");
        }
    }

    // -----------------------------------------------------------------
    // Latch backend：dispatch 阻塞到停用插入後才返回真實句柄
    // -----------------------------------------------------------------

    /** 可記錄取消的句柄替身。 */
    private static final class RecordingHandle implements PlatformTaskHandle {
        volatile boolean cancelled;

        @Override
        public int taskId() {
            return 1;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 在 dispatch 內阻塞，讓測試能把停用插入派送與登記之間。 */
    private static final class LatchBackend implements SchedulerBackend {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Runnable> capturedWrapped = new AtomicReference<>();
        final RecordingHandle handle = new RecordingHandle();

        @Override
        public PlatformTaskHandle dispatch(TaskType type, Runnable wrapped, Runnable retired,
                                           Player player, Object entityOrLoc, long delayTicks,
                                           long periodTicks, boolean async) throws Exception {
            capturedWrapped.set(wrapped);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch backend timed out");
            }
            return handle;
        }
    }
}
