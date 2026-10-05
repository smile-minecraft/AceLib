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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Folia 路徑的真實取消轉發與 delay 正規化測試。
 *
 * <p>MockBukkit v26.1.2 的 Folia mock（{@code FoliaGlobalRegionScheduler} 等）
 * 內部以 {@code PaperScheduledTask} 當回傳值，而該類別的
 * {@code cancel()} / {@code getExecutionState()} 會拋
 * {@code UnimplementedOperationException}，無法用來驗證「取消真的轉發到
 * 底層句柄」。因此本類別改以注入可控的 scheduler stub（實作真正的
 * {@link GlobalRegionScheduler} 等 Folia 介面）來驗證轉發行為，
 * 這正是 {@link FoliaSchedulerBackend} 需要保留真實句柄的原因。</p>
 *
 * <p>MockBukkit 的真 Folia scheduler 仍另外用來驗證「派送不失敗、
 * runnable 有執行」，兩者互補。</p>
 */
@DisplayName("SafeScheduler — Folia 真實句柄取消與 delay 正規化")
class FoliaSchedulerCancellationTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private StubGlobalRegionScheduler global;
    private StubEntityScheduler entityScheduler;
    private StubAsyncScheduler asyncScheduler;
    private StubRegionScheduler regionScheduler;
    private SafeSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        global = new StubGlobalRegionScheduler();
        entityScheduler = new StubEntityScheduler();
        asyncScheduler = new StubAsyncScheduler();
        regionScheduler = new StubRegionScheduler();
        scheduler = new SafeSchedulerImpl(
            plugin,
            Platform.FOLIA,
            PlatformCapability.forPlatform(Platform.FOLIA),
            new FoliaSchedulerBackend(plugin, global, e -> entityScheduler,
                asyncScheduler, regionScheduler));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null && !scheduler.isDisabled()) {
            scheduler.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 真實句柄取消轉發
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Folia runGlobal: cancel() 必須轉發到底層 ScheduledTask.cancel()")
    void runGlobal_cancelForwardsToRealHandle() {
        ScheduledTask task = scheduler.runGlobal(() -> {});
        assertNotNull(task);
        StubFoliaHandle handle = global.lastHandle();
        assertFalse(handle.cancelled, "初始不得為已取消");
        task.cancel();
        assertTrue(handle.cancelled, "cancel() 必須真的轉發到 Folia 句柄");
        assertTrue(task.isCancelled(), "isCancelled 必須反映底層句柄狀態");
    }

    @Test
    @DisplayName("Folia runLater / runTimer: cancel() 同樣轉發到底層句柄")
    void runLaterAndTimer_cancelForwardsToRealHandle() {
        scheduler.runLater(() -> {}, 5L).cancel();
        scheduler.runTimer(() -> {}, 1L, 20L).cancel();
        assertEquals(2, global.handles.size(), "應建立兩個真實句柄");
        assertTrue(global.handles.get(0).cancelled, "runLater 句柄必須被取消");
        assertTrue(global.handles.get(1).cancelled, "runTimer 句柄必須被取消");
    }

    @Test
    @DisplayName("Folia runForPlayer / runForEntity: cancel() 轉發到 entity scheduler 句柄")
    void entityTasks_cancelForwardsToRealHandle() {
        var player = server.addPlayer();
        scheduler.runForPlayer(player, () -> {}).cancel();
        scheduler.runForPlayerLater(player, () -> {}, 3L).cancel();
        scheduler.runForEntity(player, () -> {}).cancel();
        assertEquals(3, entityScheduler.handles.size(), "應建立三個 entity 句柄");
        entityScheduler.handles.forEach(h ->
            assertTrue(h.cancelled, "entity 句柄必須被取消: " + h));
    }

    @Test
    @DisplayName("Folia runAtLocation: cancel() 轉發到 region scheduler 句柄")
    void runAtLocation_cancelForwardsToRealHandle() {
        WorldMock world = server.addSimpleWorld("flat");
        world.loadChunk(0, 0);
        Location loc = new Location(world, 0.5, 64.0, 0.5);
        scheduler.runAtLocation(loc, () -> {}).cancel();
        assertEquals(1, regionScheduler.handles.size());
        assertTrue(regionScheduler.handles.get(0).cancelled, "region 句柄必須被取消");
    }

    @Test
    @DisplayName("Folia runAsync: cancel() 轉發到 async scheduler 句柄")
    void runAsync_cancelForwardsToRealHandle() {
        scheduler.runAsync(() -> {}).cancel();
        assertEquals(1, asyncScheduler.handles.size());
        assertTrue(asyncScheduler.handles.get(0).cancelled, "async 句柄必須被取消");
    }

    @Test
    @DisplayName("cancelAll 必須把所有 Folia 句柄都取消")
    void cancelAll_forwardsToAllRealHandles() {
        scheduler.runGlobal(() -> {});
        scheduler.runLater(() -> {}, 2L);
        scheduler.runTimer(() -> {}, 0L, 1L);
        scheduler.cancelAll();
        assertEquals(3, global.handles.size());
        global.handles.forEach(h ->
            assertTrue(h.cancelled, "cancelAll 必須取消所有句柄: " + h));
    }

    @Test
    @DisplayName("onPluginDisable 必須把所有 Folia 句柄都取消")
    void pluginDisable_forwardsToAllRealHandles() {
        scheduler.runGlobal(() -> {});
        scheduler.runLater(() -> {}, 2L);
        scheduler.onPluginDisable();
        global.handles.forEach(h ->
            assertTrue(h.cancelled, "plugin disable 必須取消所有句柄: " + h));
        assertEquals(0, scheduler.getTrackedTaskCount());
    }

    @Test
    @DisplayName("isCancelled 反映底層句柄，不使用本地旗標假造狀態")
    void isCancelled_reflectsUnderlyingHandle() {
        ScheduledTask task = scheduler.runGlobal(() -> {});
        assertFalse(task.isCancelled(), "尚未取消時必須為 false");
        // 從外部（例如實體退役）直接取消底層句柄，wrapper 必須立刻反映
        global.lastHandle().cancelled = true;
        assertTrue(task.isCancelled(), "isCancelled 必須即時反映底層句柄狀態");
    }

    @Test
    @DisplayName("任務識別碼在同一 dispatcher 內唯一")
    void taskIds_uniquePerDispatcher() {
        for (int i = 0; i < 20; i++) {
            scheduler.runGlobal(() -> {});
        }
        long distinct = global.handles.stream().map(h -> h.id).distinct().count();
        assertEquals(global.handles.size(), distinct,
            "同一 dispatcher 內的任務 id 必須唯一，實際: " + global.handles.size()
                + " 個句柄只有 " + distinct + " 個不同 id");
    }

    @Test
    @DisplayName("實體退役（retired）後任務不得留在 tracked")
    void retiredEntityTask_removedFromTracked() {
        int before = scheduler.getTrackedTaskCount();
        var player = server.addPlayer();
        scheduler.runForEntity(player, () -> {});
        assertEquals(before + 1, scheduler.getTrackedTaskCount());
        entityScheduler.retireLast();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "實體退役等同任務結束，必須解除追蹤");
    }

    @Test
    @DisplayName("Folia 一次性任務：region tick 前被追蹤、執行後解除追蹤")
    void oneShotGlobal_removedFromTrackedAfterRegionTick() {
        int before = scheduler.getTrackedTaskCount();
        AtomicBoolean executed = new AtomicBoolean(false);
        scheduler.runGlobal(() -> executed.set(true));
        assertEquals(before + 1, scheduler.getTrackedTaskCount(),
            "尚未在 region 執行前必須被追蹤，否則 cancelAll 取消不到它");
        assertFalse(executed.get(), "consumer 不得在 dispatch 內同步執行");
        global.runPending();
        assertTrue(executed.get(), "region tick 必須執行 runnable");
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "一次性任務執行完成後不得留在 tracked");
    }

    @Test
    @DisplayName("Folia 週期任務在重複執行期間維持被追蹤，取消後才移除")
    void repeatingTask_staysTrackedWhileRunning() {
        int before = scheduler.getTrackedTaskCount();
        int[] runs = {0};
        ScheduledTask timer = scheduler.runTimer(() -> runs[0]++, 0L, 1L);
        global.runPending();
        global.runPending();
        assertTrue(runs[0] >= 2, "timer 應已重複執行，實際: " + runs[0]);
        assertEquals(before + 1, scheduler.getTrackedTaskCount(),
            "週期任務執行期間仍需被追蹤（否則 disable 無法取消它）");
        timer.cancel();
        assertEquals(before, scheduler.getTrackedTaskCount(), "取消後必須移除");
    }

    @Test
    @DisplayName("Folia 一次性任務被取消後解除追蹤，且 region tick 不再執行 runnable")
    void oneShot_cancelled_removedFromTrackedAndNotExecuted() {
        int before = scheduler.getTrackedTaskCount();
        AtomicBoolean executed = new AtomicBoolean(false);
        ScheduledTask task = scheduler.runGlobal(() -> executed.set(true));
        assertEquals(before + 1, scheduler.getTrackedTaskCount());
        task.cancel();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "取消後不得留在 tracked（否則 cancelAll 會重複處理）");
        global.runPending();
        assertFalse(executed.get(), "已取消的底層任務不得再執行");
    }

    @Test
    @DisplayName("Folia entity 任務在 region tick 後解除追蹤")
    void oneShotEntity_removedFromTrackedAfterRegionTick() {
        int before = scheduler.getTrackedTaskCount();
        var player = server.addPlayer();
        scheduler.runForEntity(player, () -> {});
        assertEquals(before + 1, scheduler.getTrackedTaskCount());
        entityScheduler.runPending();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "entity 一次性任務執行完成後不得留在 tracked");
    }

    @Test
    @DisplayName("Folia 長跑：200 個一次性任務跑完後 tracked 不累積")
    void longRun_doesNotAccumulate() {
        int before = scheduler.getTrackedTaskCount();
        for (int i = 0; i < 200; i++) {
            scheduler.runGlobal(() -> {});
        }
        assertEquals(before + 200, scheduler.getTrackedTaskCount());
        global.runPending();
        assertEquals(before, scheduler.getTrackedTaskCount(),
            "200 個一次性任務跑完後 tracked 不得殘留");
    }

    // -----------------------------------------------------------------
    // delay 0 正規化
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runTimer delay=0 在 Folia 被正規化為 1 tick（不得原樣傳 0）")
    void timerZeroDelay_normalizedToOneTick() {
        scheduler.runTimer(() -> {}, 0L, 20L);
        assertEquals(1, global.calls.size());
        DispatchCall call = global.calls.get(0);
        assertEquals("runAtFixedRate", call.method, "delay=0 + period>0 應走 fixed-rate 路徑");
        assertEquals(1L, call.initialDelay,
            "Folia runAtFixedRate 要求 initialDelayTicks >= 1，0 必須被正規化");
        assertEquals(20L, call.period);
    }

    @Test
    @DisplayName("runTimer delay>0 的 initialDelay 不得被改動")
    void timerPositiveDelay_preserved() {
        scheduler.runTimer(() -> {}, 40L, 5L);
        DispatchCall call = global.calls.get(0);
        assertEquals(40L, call.initialDelay, "已合法的 initialDelay 不得被改寫");
        assertEquals(5L, call.period);
    }

    @Test
    @DisplayName("runLater delay=0 走 next-tick 路徑（不得誤用需要 >=1 tick 的 API）")
    void laterZeroDelay_usesRunNotRunDelayed() {
        scheduler.runLater(() -> {}, 0L);
        assertEquals("run", global.calls.get(0).method,
            "delay=0 的 runLater 應使用 next-tick 排程，不得傳 0 給 runDelayed");
    }

    @Test
    @DisplayName("runForPlayerLater delay=0 不得傳 0 給 EntityScheduler.runDelayed")
    void entityLaterZeroDelay_usesRunNotRunDelayed() {
        var player = server.addPlayer();
        scheduler.runForPlayerLater(player, () -> {}, 0L);
        assertEquals("run", entityScheduler.lastCall().method,
            "delay=0 的 player-later 應走 EntityScheduler.run");
        scheduler.runForPlayerLater(player, () -> {}, 7L);
        assertEquals("runDelayed", entityScheduler.lastCall().method);
        assertEquals(7L, entityScheduler.lastCall().delay);
    }

    @Test
    @DisplayName("entity 週期語意不因分支改變：entity 不支援 period 時不得靜默丟棄")
    void entityPeriod_semanticsUnchanged() {
        var player = server.addPlayer();
        // runForEntity 沒有 period 參數，故 entity 路徑只會是 run / runDelayed。
        // 這裡鎖住「entity 派送不會誤走 fixed-rate」這條既有語意。
        scheduler.runForEntity(player, () -> {});
        assertEquals("run", entityScheduler.lastCall().method);
        assertTrue(entityScheduler.calls.stream()
                .noneMatch(c -> c.method.equals("runAtFixedRate")),
            "entity 派送不得被當成週期任務");
    }

    // -----------------------------------------------------------------
    // runAtLocation 不得載入 chunk
    // -----------------------------------------------------------------

    @Test
    @DisplayName("runAtLocation 在 chunk 已載入時派送，且不觸發任何 chunk 載入 API")
    void runAtLocation_dispatchesWithoutLoadingChunk() {
        WorldMock world = server.addSimpleWorld("flat");
        world.loadChunk(0, 0);
        int loadedBefore = world.getLoadedChunks().length;
        Location loc = new Location(world, 0.5, 64.0, 0.5);
        scheduler.runAtLocation(loc, () -> {});
        assertEquals(1, regionScheduler.calls.size(), "必須真的派送到 region scheduler");
        assertEquals(loadedBefore, world.getLoadedChunks().length,
            "runAtLocation 不得為了檢查 chunk 而多載入任何 chunk");
    }

    @Test
    @DisplayName("chunk 未載入時 fail-closed：記錄 SCHED-004 且不派送")
    void runAtLocation_unloadedChunk_failsClosed() {
        WorldMock world = server.addSimpleWorld("barren");
        Location loc = new Location(world, 5000.0, 64.0, 5000.0); // 未載入的 chunk
        AtomicBoolean executed = new AtomicBoolean(false);
        ScheduledTask task = scheduler.runAtLocation(loc, () -> executed.set(true));
        assertTrue(task.isCancelled(), "chunk 未載入必須回 cancelled task");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-004"),
            "必須記錄 ACELIB-SCHED-004");
        assertEquals(0, regionScheduler.calls.size(), "fail-closed 時不得派送");
        assertFalse(executed.get(), "不得執行 runnable");
    }

    @Test
    @DisplayName("chunk 檢查只讀 isChunkLoaded，不得以 getChunk 觸發載入副作用")
    void runAtLocation_chunkCheckIsSideEffectFree() {
        World world = org.mockito.Mockito.mock(World.class);
        org.mockito.Mockito.when(world.isChunkLoaded(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        Location loc = new Location(world, 0.0, 64.0, 0.0);
        ScheduledTask task = scheduler.runAtLocation(loc, () -> {});
        assertTrue(task.isCancelled());
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-004"));
        org.mockito.Mockito.verify(world, org.mockito.Mockito.never())
            .getChunkAt(org.mockito.ArgumentMatchers.any(Location.class));
        org.mockito.Mockito.verify(world, org.mockito.Mockito.never())
            .getChunkAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("world 為 null 的 location 必須 fail-closed 而非 NPE")
    void runAtLocation_nullWorld_failsClosed() {
        Location loc = new Location(null, 0.0, 64.0, 0.0);
        ScheduledTask task = scheduler.runAtLocation(loc, () -> {});
        assertTrue(task.isCancelled(), "world=null 必須回 cancelled task");
        assertTrue(scheduler.getRecorder().contains("ACELIB-SCHED-004"));
    }

    // -----------------------------------------------------------------
    // 真實 MockBukkit Folia scheduler 的派送整合
    // -----------------------------------------------------------------

    @Test
    @DisplayName("以 MockBukkit 真實 Folia scheduler 派送時，runnable 仍會執行且不記錄 SCHED-005")
    void mockBukkitRealFoliaScheduler_dispatchSucceeds() {
        SafeSchedulerImpl real = new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        assertTrue(real.getBackend() instanceof FoliaSchedulerBackend);
        AtomicBoolean executed = new AtomicBoolean(false);
        ScheduledTask task = real.runGlobal(() -> executed.set(true));
        assertNotNull(task);
        assertFalse(task.isCancelled(), "MockBukkit Folia 派送應成功");
        server.getScheduler().performTicks(1L);
        assertTrue(executed.get(), "Folia 派送的 runnable 應執行");
        assertFalse(real.getRecorder().contains("ACELIB-SCHED-005"),
            "不應記錄平台不支援");
        real.onPluginDisable();
    }

    // -----------------------------------------------------------------
    // Stub：實作真正的 Folia 介面，記錄呼叫與句柄狀態
    // -----------------------------------------------------------------

    /** 記錄一次 Folia 排程呼叫的參數。 */
    record DispatchCall(String method, long initialDelay, long period, long delay) {
    }

    /** 一次已派送、等待 region tick 執行的任務。 */
    record PendingTask(StubFoliaHandle handle,
                       Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                       boolean repeating) {
    }

    /** 可控的真實 Folia 句柄：cancel() / isCancelled() 真的會改變狀態。 */
    static final class StubFoliaHandle implements io.papermc.paper.threadedregions.scheduler.ScheduledTask {
        private final Plugin owner;
        final int id;
        volatile boolean cancelled;

        StubFoliaHandle(Plugin owner, int id) {
            this.owner = owner;
            this.id = id;
        }

        @Override
        public Plugin getOwningPlugin() {
            return owner;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        public CancelledState cancel() {
            if (cancelled) {
                return CancelledState.CANCELLED_ALREADY;
            }
            cancelled = true;
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }

    /** 依 backend 提供的 id 產生器遞增，方便驗證唯一性。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 受控的 global region scheduler stub。
     *
     * <p>依<strong>真 Folia 語意</strong>建模：{@code run} / {@code runDelayed} /
     * {@code runAtFixedRate} 只把 consumer 排進 {@link #pending}，由測試呼叫
     * {@link #runPending()} 才執行。過去這個 stub 在 dispatch 內部就同步呼叫
     * consumer，等於讓「一次性任務在派送瞬間跑完」，與真 Folia（下一個 tick
     * 在 region 執行）不符，連帶讓任務追蹤的斷言失去意義。</p>
     */
    private static final class StubGlobalRegionScheduler implements GlobalRegionScheduler {
        final List<StubFoliaHandle> handles = new ArrayList<>();
        final List<DispatchCall> calls = new ArrayList<>();
        private final List<PendingTask> pending = new ArrayList<>();

        StubFoliaHandle lastHandle() {
            return handles.get(handles.size() - 1);
        }

        /**
         * 執行所有已派送但尚未執行的 consumer，模擬 region tick。
         *
         * <p>已取消的句柄不會執行（真 Folia 亦然），固定頻率任務會重新排入
         * pending 以模擬下一個 period。</p>
         */
        void runPending() {
            List<PendingTask> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (PendingTask p : snapshot) {
                if (!p.handle().cancelled) {
                    p.task().accept(p.handle());
                }
                if (p.repeating() && !p.handle().cancelled) {
                    pending.add(p);
                }
            }
        }

        @Override
        public void execute(Plugin plugin, Runnable run) {
            run.run();
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("run", -1L, -1L, -1L));
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runDelayed", -1L, -1L, delayTicks));
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                            long initialDelayTicks, long periodTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runAtFixedRate", initialDelayTicks, periodTicks, -1L));
            pending.add(new PendingTask(h, task, true));
            return h;
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            handles.forEach(h -> h.cancel());
        }
    }

    /**
     * 受控的 entity scheduler stub。
     *
     * <p>依真 Folia 語意建模：consumer 不在 dispatch 內同步執行，而是排進
     * {@link #pending}，由 {@link #runPending()}（實體仍有效）觸發；實體在執行前
     * 被移除則由 {@link #retireLast()} 觸發 retired callback——兩者互斥，
     * 正是 Folia 的語意。</p>
     */
    private static final class StubEntityScheduler implements EntityScheduler {
        final List<StubFoliaHandle> handles = new ArrayList<>();
        final List<DispatchCall> calls = new ArrayList<>();
        private final List<PendingTask> pending = new ArrayList<>();
        Runnable lastRetired;

        DispatchCall lastCall() {
            return calls.get(calls.size() - 1);
        }

        /** 模擬實體仍有效時的 region tick：執行所有待執行 consumer。 */
        void runPending() {
            List<PendingTask> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (PendingTask p : snapshot) {
                if (!p.handle().cancelled) {
                    p.task().accept(p.handle());
                }
                if (p.repeating() && !p.handle().cancelled) {
                    pending.add(p);
                }
            }
        }

        /** 模擬實體在執行前被移除：不執行 consumer，改觸發 retired callback。 */
        void retireLast() {
            pending.clear();
            if (lastRetired != null) {
                lastRetired.run();
            }
        }

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delayTicks) {
            return true;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, Runnable retired) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("run", -1L, -1L, -1L));
            lastRetired = retired;
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                        Runnable retired, long delayTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runDelayed", -1L, -1L, delayTicks));
            lastRetired = retired;
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                            Runnable retired, long initialDelayTicks, long periodTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runAtFixedRate", initialDelayTicks, periodTicks, -1L));
            lastRetired = retired;
            pending.add(new PendingTask(h, task, false));
            return h;
        }
    }

    private static final class StubAsyncScheduler implements AsyncScheduler {
        final List<StubFoliaHandle> handles = new ArrayList<>();

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runNow(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            task.accept(h);
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                        long delay, TimeUnit unit) {
            return runNow(plugin, task);
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(Plugin plugin, Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                            long initialDelay, long period, TimeUnit unit) {
            return runNow(plugin, task);
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            handles.forEach(h -> h.cancel());
        }
    }

    /**
     * 受控的 region scheduler stub：consumer 排進 {@link #pending}，由
     * {@link #runPending()} 觸發，與真 Folia「下一個 tick 在該 region 執行」一致。
     */
    private static final class StubRegionScheduler implements RegionScheduler {
        final List<StubFoliaHandle> handles = new ArrayList<>();
        final List<DispatchCall> calls = new ArrayList<>();
        private final List<PendingTask> pending = new ArrayList<>();

        /** 模擬 region tick；已取消的句柄不會執行，固定頻率任務會重新排入。 */
        void runPending() {
            List<PendingTask> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (PendingTask p : snapshot) {
                if (!p.handle().cancelled) {
                    p.task().accept(p.handle());
                }
                if (p.repeating() && !p.handle().cancelled) {
                    pending.add(p);
                }
            }
        }

        @Override
        public void execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable run) {
            run.run();
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask run(Plugin plugin, World world, int chunkX, int chunkZ,
                                 Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("run", -1L, -1L, -1L));
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runDelayed(Plugin plugin, World world, int chunkX, int chunkZ,
                                        Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task, long delayTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runDelayed", -1L, -1L, delayTicks));
            pending.add(new PendingTask(h, task, false));
            return h;
        }

        @Override
        public io.papermc.paper.threadedregions.scheduler.ScheduledTask runAtFixedRate(Plugin plugin, World world, int chunkX, int chunkZ,
                                            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task,
                                            long initialDelayTicks, long periodTicks) {
            StubFoliaHandle h = new StubFoliaHandle(plugin, SEQ.incrementAndGet());
            handles.add(h);
            calls.add(new DispatchCall("runAtFixedRate", initialDelayTicks, periodTicks, -1L));
            pending.add(new PendingTask(h, task, true));
            return h;
        }
    }

    @Test
    @DisplayName("entity stub 的 retired callback 不得為 null（Folia 需要它才能解除追蹤）")
    void entityStub_retiredCallbackProvided() {
        var player = server.addPlayer();
        scheduler.runForEntity(player, () -> {});
        assertNotNull(entityScheduler.lastRetired,
            "backend 必須提供 retired callback，否則實體退役時任務會留在 tracked");
    }

    @Test
    @DisplayName("Folia handle 的 owner 必須是派送方 plugin")
    void handleOwner_isDispatchingPlugin() {
        scheduler.runGlobal(() -> {});
        assertEquals(plugin, global.lastHandle().getOwningPlugin());
    }

    @Test
    @DisplayName("Entity 相關任務不得誤用 GlobalRegionScheduler")
    void entityTasks_doNotUseGlobalScheduler() {
        var player = server.addPlayer();
        scheduler.runForEntity(player, () -> {});
        assertEquals(0, global.handles.size(),
            "entity 任務必須走 EntityScheduler，不得退回 global region");
    }

    @Test
    @DisplayName("Location 任務不得誤用 GlobalRegionScheduler 或 EntityScheduler")
    void locationTasks_useRegionSchedulerOnly() {
        WorldMock world = server.addSimpleWorld("w");
        world.loadChunk(0, 0);
        scheduler.runAtLocation(new Location(world, 1.5, 64.0, 1.5), () -> {});
        assertEquals(1, regionScheduler.handles.size());
        assertEquals(0, global.handles.size());
        assertEquals(0, entityScheduler.handles.size());
    }

    @Test
    @DisplayName("Folia 任務的 creationTick 仍可取得（診斷資訊不得因改用真實句柄而遺失）")
    void creationTick_stillAvailable() {
        ScheduledTask task = scheduler.runGlobal(() -> {});
        assertTrue(task.getCreationTick() >= 0L, "creationTick 必須可取得且非負");
        assertEquals(plugin, task.getPlugin());
    }

    @Test
    @DisplayName("Folia 句柄已自行取消後，重複 cancel 仍冪等不丟例外")
    void doubleCancel_idempotent() {
        ScheduledTask task = scheduler.runGlobal(() -> {});
        global.lastHandle().cancel();
        task.cancel();
        task.cancel();
        assertTrue(task.isCancelled());
        assertNotNull(task.getType());
    }

    @Test
    @DisplayName("Entity 任務句柄被外部取消後，isCancelled 立即反映（Folia 語意）")
    void entityHandle_externalCancelReflected() {
        var player = server.addPlayer();
        ScheduledTask task = scheduler.runForEntity(player, () -> {});
        assertFalse(task.isCancelled());
        entityScheduler.handles.get(0).cancel();
        assertTrue(task.isCancelled());
    }
}
