package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link SafeExecutor} 派送測試。
 *
 * <p>對應 Plan §八 Phase 3：透過 SafeExecutor 統一處理 executeAsync / executeOnRegion，
 * 自動選擇正確的 scheduler（Folia 用 entity/region scheduler，Paper 用 main thread），
 * 並主動攔截 mutate 操作於錯誤上下文（CTX-001 / CTX-002）。</p>
 */
@DisplayName("SafeExecutor")
class SafeExecutorTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeSchedulerImpl scheduler;
    private TaskErrorRecorder recorder;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new com.smile.acelib.platform.PlatformDetector(getClass().getClassLoader()));
        recorder = new TaskErrorRecorder();
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
    // executeAsync
    // -----------------------------------------------------------------

    @Test
    @DisplayName("executeAsync: READ_ONLY 操作應直接派送到 runAsync")
    void executeAsync_readOnly_dispatches() {
        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = SafeExecutor.executeAsync(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            OperationType.READ_ONLY,
            () -> ran.set(true)
        );
        assertNotNull(task);
        assertEquals(com.smile.acelib.scheduler.TaskType.ASYNC, task.getType());
    }

    @Test
    @DisplayName("executeAsync: 在主執行緒嘗試 mutate → 拋 ContextException（CTX-002）")
    void executeAsync_mutateFromMainThread_throws() {
        // READ_ONLY 之外的 mutate 操作都應被攔截
        ContextException ex = assertThrows(ContextException.class, () ->
            SafeExecutor.executeAsync(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                OperationType.WORLD_MUTATE,
                () -> {}
            )
        );
        assertEquals("ACELIB-CTX-002", ex.getCode());
    }

    @Test
    @DisplayName("executeAsync: null runnable 必須拋 NPE")
    void executeAsync_nullRunnable_throws() {
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.executeAsync(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                OperationType.READ_ONLY,
                null
            )
        );
    }

    @Test
    @DisplayName("executeAsync: null plugin 必須拋 NPE")
    void executeAsync_nullPlugin_throws() {
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.executeAsync(
                null, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                OperationType.READ_ONLY,
                () -> {}
            )
        );
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Player)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("executeOnRegion(Player): mutate 操作應透過 player scheduler 派送")
    void executeOnRegion_player_mutateDispatched() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        ScheduledTask task = SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            player,
            () -> {}
        );
        assertNotNull(task);
        // Paper 環境下對玩家操作會走 PLAYER task type
        assertEquals(com.smile.acelib.scheduler.TaskType.PLAYER, task.getType());
    }

    @Test
    @DisplayName("executeOnRegion(Player): READ_ONLY 也應允許")
    void executeOnRegion_player_readOnlyAllowed() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        ScheduledTask task = assertDoesNotThrow(() ->
            SafeExecutor.executeOnRegion(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                player,
                () -> {},
                OperationType.READ_ONLY
            )
        );
        assertNotNull(task);
    }

    @Test
    @DisplayName("executeOnRegion(Player): null player 必須拋 NPE")
    void executeOnRegion_nullPlayer_throws() {
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.executeOnRegion(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                (org.bukkit.entity.Player) null,
                () -> {}
            )
        );
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Entity)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("executeOnRegion(Entity): 活實體 mutate 應派送")
    void executeOnRegion_entity_mutateDispatched() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        ScheduledTask task = SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            (org.bukkit.entity.Entity) player,
            () -> {}
        );
        assertNotNull(task);
        assertEquals(com.smile.acelib.scheduler.TaskType.ENTITY, task.getType());
    }

    @Test
    @DisplayName("executeOnRegion(Entity): null entity 必須拋 NPE")
    void executeOnRegion_nullEntity_throws() {
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.executeOnRegion(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                (org.bukkit.entity.Entity) null,
                () -> {}
            )
        );
    }

    // -----------------------------------------------------------------
    // executeOnRegion(Location)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("executeOnRegion(Location): mutate 操作應派送")
    void executeOnRegion_location_mutateDispatched() {
        org.mockbukkit.mockbukkit.world.WorldMock world = server.addSimpleWorld("flat");
        org.bukkit.Location loc = new org.bukkit.Location(world, 0, 64, 0);
        ScheduledTask task = SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            loc,
            () -> {}
        );
        assertNotNull(task);
        assertEquals(com.smile.acelib.scheduler.TaskType.LOCATION, task.getType());
    }

    @Test
    @DisplayName("executeOnRegion(Location): null location 必須拋 NPE")
    void executeOnRegion_nullLocation_throws() {
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.executeOnRegion(
                plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                (org.bukkit.Location) null,
                () -> {}
            )
        );
    }

    // -----------------------------------------------------------------
    // 平台不支援時的降級
    // -----------------------------------------------------------------

    @Test
    @DisplayName("UNKNOWN 平台下 mutate 操作應被拒絕（CTX-004）")
    void unknownPlatform_rejected() {
        ContextException ex = assertThrows(ContextException.class, () ->
            SafeExecutor.executeAsync(
                plugin, Platform.UNKNOWN,
                PlatformCapability.forPlatform(Platform.UNKNOWN),
                OperationType.WORLD_MUTATE,
                () -> {}
            )
        );
        assertEquals("ACELIB-CTX-004", ex.getCode());
    }

    @Test
    @DisplayName("DebugMode 開啟時 executeAsync 不拋例外（除錯模式可輸出診斷）")
    void debugMode_enabled_doesNotThrow() {
        // 暫時啟用除錯模式
        boolean prev = DebugMode.isEnabled();
        try {
            DebugMode.setEnabled(true);
            assertDoesNotThrow(() -> {
                // READ_ONLY 在主執行緒是合法的，不應被攔截
                SafeExecutor.executeAsync(
                    plugin, Platform.PAPER,
                    PlatformCapability.forPlatform(Platform.PAPER),
                    OperationType.READ_ONLY,
                    () -> {}
                );
            });
        } finally {
            DebugMode.setEnabled(prev);
        }
    }

    // -----------------------------------------------------------------
    // 共享 scheduler（與 plugin 生命週期一致）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("共享：兩次派送累積進 plugin 共享 scheduler 的 recorder（同一 instance）")
    void shared_twoDispatches_accumulateInPluginScheduler() {
        SafeSchedulerImpl shared = plugin.getSchedulerForDiagnostics();
        assertNotNull(shared, "onEnable 後 plugin 應持有共享 scheduler");
        int before = shared.getRecorder().getErrorCount();

        org.mockbukkit.mockbukkit.entity.PlayerMock first = server.addPlayer();
        first.disconnect();
        org.mockbukkit.mockbukkit.entity.PlayerMock second = server.addPlayer();
        second.disconnect();
        SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            first,
            () -> {}
        );
        SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            second,
            () -> {}
        );

        assertEquals(before + 2, shared.getRecorder().getErrorCount(),
            "兩次派送應累積進同一個共享 scheduler，而不是每次自建實例");
        assertTrue(shared.getRecorder().contains("ACELIB-SCHED-002"));
    }

    @Test
    @DisplayName("共享：併發派送累積進同一個共享 scheduler")
    void shared_concurrentDispatches_accumulateInPluginScheduler() throws Exception {
        SafeSchedulerImpl shared = plugin.getSchedulerForDiagnostics();
        assertNotNull(shared);
        int threads = 16;
        List<org.mockbukkit.mockbukkit.entity.PlayerMock> players = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
            player.disconnect();
            players.add(player);
        }
        int before = shared.getRecorder().getErrorCount();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (org.mockbukkit.mockbukkit.entity.PlayerMock player
                    : players) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    SafeExecutor.executeOnRegion(
                        plugin, Platform.PAPER,
                        PlatformCapability.forPlatform(Platform.PAPER),
                        player,
                        () -> {}
                    );
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(before + threads, shared.getRecorder().getErrorCount(),
            "併發派送應全部累積進同一個共享 scheduler");
    }

    @Test
    @DisplayName("停用：plugin disable 後派送回 cancelled no-op，不執行且記 SCHED-006")
    void disabledPlugin_dispatchReturnsCancelledNoOp() throws Exception {
        plugin.onDisable();
        assertTrue(plugin.getSchedulerForDiagnostics().isDisabled());

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = SafeExecutor.executeAsync(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            OperationType.READ_ONLY,
            () -> ran.set(true)
        );
        assertNotNull(task);
        assertTrue(task.isCancelled(), "disable 後應回 cancelled no-op");
        // 給舊行為（若仍自建可派送實例）一個 settle 機會
        Thread.sleep(300);
        assertEquals(false, ran.get(), "disable 後 runnable 不可執行");
        assertTrue(plugin.getSchedulerForDiagnostics().getRecorder()
            .contains("ACELIB-SCHED-006"), "應留下 ACELIB-SCHED-006 紀錄");
    }

    @Test
    @DisplayName("診斷：經 SafeExecutor 的錯誤會進 DiagnosticsService sink")
    void diagnosticsSink_receivesSafeExecutorErrors() {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.disconnect();
        SafeExecutor.executeOnRegion(
            plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            player,
            () -> {}
        );

        var snapshot = plugin.getDiagnosticsService().buildSnapshot();
        assertTrue(snapshot.recentErrors().stream()
                .anyMatch(line -> line.code().equals("ACELIB-SCHED-002")),
            "SafeExecutor 派送的 SCHED-002 應出現在 diagnostics 快照");
    }

    @Test
    @DisplayName("非 AceLibPlugin：已停用 plugin 的派送回 cancelled 且不執行")
    void nonAceLibPlugin_disabledPlugin_dispatchCancelled() throws Exception {
        JavaPlugin other = org.mockito.Mockito.mock(JavaPlugin.class);
        // Mockito 預設 boolean 回 false，等同已停用；此處顯式寫出語意
        org.mockito.Mockito.when(other.isEnabled()).thenReturn(false);

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = SafeExecutor.executeAsync(
            other, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            OperationType.READ_ONLY,
            () -> ran.set(true)
        );
        assertNotNull(task);
        assertTrue(task.isCancelled(), "非 AceLib 停用 plugin 應回 cancelled");
        Thread.sleep(300);
        assertEquals(false, ran.get(), "停用 plugin 的 runnable 不可執行");
    }

    // -----------------------------------------------------------------
    // 共享身份與邊界（package-private resolver）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("身份：AceLibPlugin 解析一律回傳 canonical 共享實例")
    void resolveScheduler_aceLibPlugin_returnsCanonical() {
        PlatformCapability capability = PlatformCapability.forPlatform(Platform.PAPER);
        assertSame(plugin.getSchedulerForDiagnostics(),
            SafeExecutor.resolveScheduler(plugin, Platform.PAPER, capability));
        assertSame(plugin.getSchedulerForDiagnostics(),
            SafeExecutor.resolveScheduler(plugin, Platform.PAPER, capability));
    }

    @Test
    @DisplayName("身份：併發解析取到同一實例")
    void resolveScheduler_concurrent_sameInstance() throws Exception {
        PlatformCapability capability = PlatformCapability.forPlatform(Platform.PAPER);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SafeSchedulerImpl>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return SafeExecutor.resolveScheduler(
                        plugin, Platform.PAPER, capability);
                }));
            }
            start.countDown();
            SafeSchedulerImpl first = futures.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<SafeSchedulerImpl> future : futures) {
                assertSame(first, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("非 AceLibPlugin：啟用共用、停用同實例 SCHED-006、重啟重建")
    void resolveScheduler_nonAceLib_lifecycle() {
        PlatformCapability capability = PlatformCapability.forPlatform(Platform.PAPER);
        JavaPlugin other = org.mockito.Mockito.mock(JavaPlugin.class);
        org.mockito.Mockito.when(other.isEnabled()).thenReturn(true);

        SafeSchedulerImpl first =
            SafeExecutor.resolveScheduler(other, Platform.PAPER, capability);
        assertNotNull(first);
        assertFalse(first.isDisabled());
        assertSame(first,
            SafeExecutor.resolveScheduler(other, Platform.PAPER, capability));

        // 停用：同一快取實例轉 disabled，派送記 SCHED-006
        org.mockito.Mockito.when(other.isEnabled()).thenReturn(false);
        assertSame(first,
            SafeExecutor.resolveScheduler(other, Platform.PAPER, capability));
        assertTrue(first.isDisabled());
        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = SafeExecutor.executeAsync(
            other, Platform.PAPER, capability, OperationType.READ_ONLY,
            () -> ran.set(true));
        assertTrue(task.isCancelled());
        assertEquals(false, ran.get());
        assertTrue(first.getRecorder().contains("ACELIB-SCHED-006"));

        // 重啟：舊快取已 disabled，必須重建而不殘留舊狀態
        org.mockito.Mockito.when(other.isEnabled()).thenReturn(true);
        SafeExecutor.resolveScheduler(other, Platform.PAPER, capability);
        SafeSchedulerImpl rebuilt =
            SafeExecutor.resolveScheduler(other, Platform.PAPER, capability);
        // 注意：第一次重建呼叫回傳 fresh（未 disabled），第二次仍取到它
        assertFalse(rebuilt.isDisabled());
        assertSame(rebuilt,
            SafeExecutor.resolveScheduler(other, Platform.PAPER, capability));
    }

    @Test
    @DisplayName("邊界：profile 更換時重建快取實例")
    void resolveScheduler_profileMismatch_rebuilds() {
        JavaPlugin other = org.mockito.Mockito.mock(JavaPlugin.class);
        org.mockito.Mockito.when(other.isEnabled()).thenReturn(true);

        SafeSchedulerImpl paper = SafeExecutor.resolveScheduler(
            other, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        SafeSchedulerImpl unknown = SafeExecutor.resolveScheduler(
            other, Platform.UNKNOWN, PlatformCapability.forPlatform(Platform.UNKNOWN));
        assertNotSame(paper, unknown);
        // 同 profile 再次解析仍取到同一實例
        assertSame(unknown, SafeExecutor.resolveScheduler(
            other, Platform.UNKNOWN, PlatformCapability.forPlatform(Platform.UNKNOWN)));
    }

    @Test
    @DisplayName("邊界：resolveScheduler null 參數拋 NPE")
    void resolveScheduler_nullArgs_throwNpe() {
        PlatformCapability capability = PlatformCapability.forPlatform(Platform.PAPER);
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.resolveScheduler(null, Platform.PAPER, capability));
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.resolveScheduler(plugin, null, capability));
        assertThrows(NullPointerException.class, () ->
            SafeExecutor.resolveScheduler(plugin, Platform.PAPER, null));
    }
}