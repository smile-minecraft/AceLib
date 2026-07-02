package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecorder;
import java.util.concurrent.atomic.AtomicBoolean;
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
}