package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.TaskOutcome;
import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 事件處理後延後操作與延後傳送的行為契約。
 *
 * <p>鎖定路線圖「事件處理完成後的操作」：取消移動事件後立刻傳送會被平台還原，
 * 但 API 仍回報成功；延後傳送排到之後的 tick、在玩家所在執行緒傳送，
 * 完成時確認玩家真的到達目的地，沒到就回報失敗。</p>
 *
 * <p>傳送後端以可控假實作注入：{@code REVERT} 模式模擬平台行為
 * （傳送呼叫回成功、玩家實際留在原地），讓「回成功但被還原」可重現；
 * 真實平台差異留給 Paper／Folia 實機探針驗證。</p>
 */
@DisplayName("事件處理後的延後操作與延後傳送")
class WorldDeferredOperationsTest {

    /** 傳送假後端的行為模式。 */
    enum TeleportMode {
        /** 真實移動玩家並回成功。 */
        MOVE,
        /** 回成功但不移動玩家（模擬事件處理把位置還原）。 */
        REVERT,
        /** 回 false（Bukkit 拒絕）。 */
        FALSE,
        /** 回異常完成的 future。 */
        THROW
    }

    /** 可控傳送後端：玩家解析走真實 PlayerMock，傳送行為可指定。 */
    static final class ScriptedBackend implements WorldBackend {
        final ServerMock server;
        final Map<UUID, PlayerMock> players = new HashMap<>();
        final Map<UUID, World> worlds = new HashMap<>();
        TeleportMode mode = TeleportMode.MOVE;
        /** 第一次傳送是否強制還原（之後恢復正常）；用於取消移動事件對照。 */
        boolean revertFirstTeleport;
        /** 傳送成功後額外偏移（容差邊界測試用，null 表示不偏移）。 */
        double[] successOffset;
        final AtomicInteger teleportCalls = new AtomicInteger();

        ScriptedBackend(ServerMock server) {
            this.server = server;
        }

        void addPlayer(PlayerMock player) {
            players.put(player.getUniqueId(), player);
            worlds.put(player.getWorld().getUID(), player.getWorld());
        }

        World mockWorld(UUID wid) {
            return worlds.computeIfAbsent(wid, id -> {
                World w = Mockito.mock(World.class);
                Mockito.when(w.getUID()).thenReturn(id);
                Mockito.when(w.getName()).thenReturn("mock-" + id);
                Mockito.when(w.isChunkLoaded(
                    Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
                return w;
            });
        }

        @Override
        public org.bukkit.Server server() {
            return server;
        }

        @Override
        public World resolveWorld(UUID worldId) {
            return worlds.get(worldId);
        }

        @Override
        public org.bukkit.entity.Entity resolveEntity(UUID entityId) {
            return players.get(entityId);
        }

        @Override
        public org.bukkit.entity.Player resolvePlayer(UUID playerId) {
            return players.get(playerId);
        }

        @Override
        public WorldBackendResult<String> readBlockAt(Location location) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED, "unused");
        }

        @Override
        public WorldBackendResult<Void> writeBlockAt(Location location, String blockKey) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED, "unused");
        }

        @Override
        public WorldBackendResult<org.bukkit.entity.Entity> spawnAt(
            Location location, String entityTypeKey) {
            return WorldBackendResult.failed(WorldErrorCode.INVALID_INPUT, "unused");
        }

        @Override
        public WorldBackendResult<Void> removeEntity(org.bukkit.entity.Entity entity) {
            return WorldBackendResult.ok(null, "unused");
        }

        @Override
        public WorldBackendResult<Void> playEffect(Location location, String effectKey) {
            return WorldBackendResult.ok(null, "unused");
        }

        @Override
        public List<org.bukkit.entity.Entity> findNearby(
            Location location, double radius, EntityType type) {
            return List.of();
        }

        @Override
        public List<org.bukkit.entity.Player> findNearbyPlayers(
            Location location, double radius) {
            return List.of();
        }

        @Override
        public CompletionStage<Boolean> teleportAsync(org.bukkit.entity.Entity subject,
                                                      Location target,
                                                      boolean keepPassengers) {
            teleportCalls.incrementAndGet();
            if (revertFirstTeleport) {
                revertFirstTeleport = false;
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
            return switch (mode) {
                case REVERT -> CompletableFuture.completedFuture(Boolean.TRUE);
                case FALSE -> CompletableFuture.completedFuture(Boolean.FALSE);
                case THROW -> CompletableFuture.failedFuture(
                    new RuntimeException("backend blew up"));
                case MOVE -> {
                    boolean ok = subject.teleport(target);
                    Location moved = subject.getLocation();
                    if (successOffset != null) {
                        moved.add(successOffset[0], successOffset[1], successOffset[2]);
                        subject.teleport(moved);
                    }
                    yield CompletableFuture.completedFuture(ok);
                }
            };
        }
    }

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeScheduler scheduler;
    private ScriptedBackend backend;
    private WorldServiceImpl service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        server.getPluginManager().enablePlugin(plugin);
        scheduler = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        backend = new ScriptedBackend(server);
        service = new WorldServiceImpl(backend, null);
    }

    @AfterEach
    void tearDown() {
        if (scheduler instanceof SafeSchedulerImpl impl && !impl.isDisabled()) {
            impl.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    private PlayerMock addPlayerAt(int x, int y, int z) {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(player.getWorld(), x, y, z));
        backend.addPlayer(player);
        return player;
    }

    private LocationSnapshot targetOf(PlayerMock player, int x, int y, int z) {
        return LocationSnapshot.of(player.getWorld().getUID(), x, y, z);
    }

    private void pumpUntilDone(CompletionStage<?> stage, String what)
        throws InterruptedException {
        CompletableFuture<?> future = stage.toCompletableFuture();
        long deadline = System.currentTimeMillis() + 10000L;
        while (!future.isDone()) {
            server.getScheduler().performTicks(1L);
            if (future.isDone()) {
                return;
            }
            Thread.sleep(20L);
            if (System.currentTimeMillis() > deadline) {
                fail(what + " 未在時限內完成");
            }
        }
    }

    private void pumpUntilDone(TaskTicket<?> ticket, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (!ticket.isDone()) {
            server.getScheduler().performTicks(1L);
            if (ticket.isDone()) {
                return;
            }
            Thread.sleep(20L);
            if (System.currentTimeMillis() > deadline) {
                fail(what + " 未在時限內完成");
            }
        }
    }

    // -----------------------------------------------------------------
    // 通用延後操作
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("通用延後操作")
    class DeferTests {

        @Test
        @DisplayName("派送當下不執行，下一個 tick 才在玩家執行緒執行並完成")
        void defer_runsOnNextTickNotSynchronously() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            AtomicInteger runs = new AtomicInteger();

            TaskTicket<String> ticket =
                service.deferForPlayer(player.getUniqueId(), () -> {
                    runs.incrementAndGet();
                    return "done";
                }, scheduler);

            assertNotNull(ticket);
            assertEquals(0, runs.get(), "延後操作在派送當下不得執行");
            assertFalse(ticket.isDone(), "派送當下動作尚未完成");

            pumpUntilDone(ticket, "延後操作");
            TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.COMPLETED, result.outcome());
            assertEquals("done", result.value());
            assertEquals(1, runs.get());
        }

        @Test
        @DisplayName("動作拋錯 → FAILED 終態並記 SCHED-001")
        void defer_throwingAction_settlesFailed() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            IllegalStateException boom = new IllegalStateException("boom");

            TaskTicket<String> ticket = service.deferForPlayer(player.getUniqueId(), () -> {
                throw boom;
            }, scheduler);
            pumpUntilDone(ticket, "拋錯延後操作");

            TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.FAILED, result.outcome());
            assertTrue(result.cause() == boom, "必須保留原始例外");
            assertNotNull(result.errorRecord());
            assertTrue(result.errorRecord().code().contains("SCHED-001"));
        }

        @Test
        @DisplayName("執行前顯式取消 → CANCELLED，動作不執行")
        void defer_explicitCancel_neverRuns() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            AtomicBoolean ran = new AtomicBoolean();

            TaskTicket<Void> ticket = service.deferForPlayer(player.getUniqueId(), () -> {
                ran.set(true);
                return null;
            }, scheduler);
            ticket.cancel();
            server.getScheduler().performTicks(3L);

            assertTrue(ticket.isDone());
            assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
            assertFalse(ran.get(), "取消後不得執行使用者程式");
        }

        @Test
        @DisplayName("離線玩家 → REJECTED，動作不執行")
        void defer_offlinePlayer_rejected() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            player.disconnect();
            AtomicBoolean ran = new AtomicBoolean();

            TaskTicket<String> ticket = service.deferForPlayer(player.getUniqueId(), () -> {
                ran.set(true);
                return "x";
            }, scheduler);

            assertTrue(ticket.isDone(), "拒派本身就是終態");
            assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());
            assertFalse(ran.get());
        }

        @Test
        @DisplayName("服務停用後新派送 → REJECTED + WORLD-002，動作不執行")
        void defer_shutdownService_rejected() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            service.shutdown();
            AtomicBoolean ran = new AtomicBoolean();

            TaskTicket<String> ticket = service.deferForPlayer(player.getUniqueId(), () -> {
                ran.set(true);
                return "x";
            }, scheduler);
            server.getScheduler().performTicks(3L);

            assertTrue(ticket.isDone());
            TaskResult<String> result = ticket.await(5L, TimeUnit.SECONDS);
            assertEquals(TaskOutcome.REJECTED, result.outcome());
            assertEquals(WorldErrorCode.SHUTDOWN, result.errorRecord().code());
            assertFalse(ran.get(), "停用後不得執行使用者程式");
        }

        @Test
        @DisplayName("退服：待執行延後操作自動取消，動作不執行")
        void defer_quitMidFlight_cancelled() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            AtomicBoolean ran = new AtomicBoolean();

            TaskTicket<String> ticket = service.deferForPlayer(player.getUniqueId(), () -> {
                ran.set(true);
                return "x";
            }, scheduler);
            assertFalse(ticket.isDone());
            player.disconnect();
            pumpUntilDone(ticket, "退服取消");

            assertEquals(TaskOutcome.CANCELLED, ticket.await(5L, TimeUnit.SECONDS).outcome());
            server.getScheduler().performTicks(3L);
            assertFalse(ran.get(), "退服後不得再執行使用者程式");
        }

        @Test
        @DisplayName("null 輸入一律丟 IllegalArgumentException 帶 INVALID_INPUT")
        void defer_nullInputs_throw() {
            UUID id = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class,
                () -> service.deferForPlayer(null, () -> "x", scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.deferForPlayer(id, null, scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.deferForPlayer(id, () -> "x", null));
        }
    }

    // -----------------------------------------------------------------
    // 延後傳送
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("延後傳送")
    class DeferredTeleportTests {

        @Test
        @DisplayName("下一 tick 傳送並確認到達 → SUCCESS，玩家真的在目的地")
        void deferred_success_arrives() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            LocationSnapshot target = targetOf(player, 100, 70, -200);

            CompletionStage<TeleportResult> stage =
                service.teleportPlayerDeferred(player.getUniqueId(), target, false, scheduler);
            pumpUntilDone(stage, "延後傳送");

            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.SUCCESS, result.state());
            Location actual = player.getLocation();
            assertEquals(100.0, actual.getX());
            assertEquals(70.0, actual.getY());
            assertEquals(-200.0, actual.getZ());
        }

        @Test
        @DisplayName("平台回成功但位置被還原 → FAILED + WORLD-018，診斷含期望與實際")
        void deferred_revertedButReportedSuccess_failed() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            backend.mode = TeleportMode.REVERT;
            LocationSnapshot target = targetOf(player, 100, 70, -200);

            CompletionStage<TeleportResult> stage =
                service.teleportPlayerDeferred(player.getUniqueId(), target, false, scheduler);
            pumpUntilDone(stage, "被還原的延後傳送");

            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_NOT_ARRIVED, result.errorCode());
            assertTrue(result.detail().contains("100"),
                "診斷必須含期望位置，實際=" + result.detail());
            assertTrue(result.detail().contains("0.0") || result.detail().contains(", 0,"),
                "診斷必須含實際位置，實際=" + result.detail());
        }

        @Test
        @DisplayName("對照：立即傳送在還原情境下謊報 SUCCESS（此為既有行為，不更動）")
        void immediate_liesWhenReverted_contrast() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            backend.mode = TeleportMode.REVERT;
            LocationSnapshot target = targetOf(player, 100, 70, -200);

            TeleportResult immediate = service.teleportPlayer(
                player.getUniqueId(), target, false).toCompletableFuture()
                .get(5L, TimeUnit.SECONDS);

            assertEquals(WorldState.SUCCESS, immediate.state(),
                "立即傳送回成功，但玩家實際未移動（此即待修的謊報）");
            assertEquals(0.0, player.getLocation().getX(), "玩家實際仍在原地");
        }

        @Test
        @DisplayName("取消移動事件後傳送：立即傳送被還原，延後傳送下一 tick 到達")
        void cancelledMoveEvent_immediateReverted_deferredArrives() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            backend.revertFirstTeleport = true;
            LocationSnapshot target = targetOf(player, 100, 70, -200);
            CompletableFuture<TeleportResult> immediateBox = new CompletableFuture<>();
            CompletableFuture<CompletionStage<TeleportResult>> deferredBox =
                new CompletableFuture<>();

            Listener cancelling = new Listener() {
                @EventHandler
                public void onMove(PlayerMoveEvent event) {
                    event.setCancelled(true);
                    // 事件處理內立即傳送：平台隨後把位置還原（假後端第一次呼叫不移動）。
                    service.teleportPlayer(player.getUniqueId(), target, false)
                        .whenComplete((result, failure) -> immediateBox.complete(result));
                    deferredBox.complete(service.teleportPlayerDeferred(
                        player.getUniqueId(), target, false, scheduler));
                }
            };
            server.getPluginManager().registerEvents(cancelling, plugin);
            try {
                server.getPluginManager().callEvent(new PlayerMoveEvent(
                    player, player.getLocation(),
                    new Location(player.getWorld(), 1, 64, 1)));
            } finally {
                PlayerMoveEvent.getHandlerList().unregister(cancelling);
            }

            TeleportResult immediate = immediateBox.get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.SUCCESS, immediate.state(), "立即傳送回報成功");
            assertEquals(0.0, player.getLocation().getX(), "但事件處理把位置還原");

            CompletionStage<TeleportResult> deferred = deferredBox.get(5L, TimeUnit.SECONDS);
            pumpUntilDone(deferred, "取消事件後的延後傳送");
            TeleportResult arrival = deferred.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.SUCCESS, arrival.state());
            assertEquals(100.0, player.getLocation().getX(), "延後傳送真的到達");
        }

        @Test
        @DisplayName("後端回 false → REJECTED + WORLD-014（原樣透出）")
        void deferred_backendFalse_rejected() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            backend.mode = TeleportMode.FALSE;

            CompletionStage<TeleportResult> stage = service.teleportPlayerDeferred(
                player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler);
            pumpUntilDone(stage, "被拒絕的延後傳送");
            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_REJECTED, result.errorCode());
        }

        @Test
        @DisplayName("後端拋例外 → FAILED + WORLD-015（原樣透出）")
        void deferred_backendThrows_failed() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            backend.mode = TeleportMode.THROW;

            CompletionStage<TeleportResult> stage = service.teleportPlayerDeferred(
                player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler);
            pumpUntilDone(stage, "拋錯的延後傳送");
            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(WorldState.FAILED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_EXCEPTION, result.errorCode());
        }

        @Test
        @DisplayName("跨世界：玩家仍在舊世界 → FAILED + WORLD-018")
        void deferred_crossWorld_failed() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            // 目標世界存在、座標相同，但玩家實際仍在原世界（還原模式）：
            // 世界不同即判定未到達，與座標無關。
            backend.mode = TeleportMode.REVERT;
            LocationSnapshot otherWorld =
                LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            backend.mockWorld(otherWorld.worldId());

            CompletionStage<TeleportResult> stage = service.teleportPlayerDeferred(
                player.getUniqueId(), otherWorld, false, scheduler);
            pumpUntilDone(stage, "跨世界延後傳送");

            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_NOT_ARRIVED, result.errorCode());
            assertTrue(result.detail().contains(otherWorld.worldIdString()),
                "診斷必須含期望世界，實際=" + result.detail());
        }

        @Test
        @DisplayName("容差邊界：恰 0.5 格算到達，超過則失敗")
        void deferred_toleranceBoundary() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            LocationSnapshot target = targetOf(player, 100, 70, -200);

            backend.successOffset = new double[] {0.5, 0.0, 0.0};
            CompletionStage<TeleportResult> atEdge = service.teleportPlayerDeferred(
                player.getUniqueId(), target, false, scheduler);
            pumpUntilDone(atEdge, "邊界容差傳送");
            assertEquals(WorldState.SUCCESS,
                atEdge.toCompletableFuture().get(5L, TimeUnit.SECONDS).state(),
                "恰 0.5 格應算到達");

            backend.successOffset = new double[] {0.51, 0.0, 0.0};
            CompletionStage<TeleportResult> beyond = service.teleportPlayerDeferred(
                player.getUniqueId(), target, false, scheduler);
            pumpUntilDone(beyond, "超容差傳送");
            TeleportResult beyondResult =
                beyond.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, beyondResult.state());
            assertEquals(WorldErrorCode.TELEPORT_NOT_ARRIVED, beyondResult.errorCode());
        }

        @Test
        @DisplayName("容差可覆寫：放寬後 1 格漂移算到達")
        void deferred_customTolerance_override() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            LocationSnapshot target = targetOf(player, 100, 70, -200);
            backend.successOffset = new double[] {1.0, 0.0, 0.0};

            CompletionStage<TeleportResult> relaxed = service.teleportPlayerDeferred(
                player.getUniqueId(), target, false, 2.0, scheduler);
            pumpUntilDone(relaxed, "放寬容差傳送");
            TeleportResult relaxedResult =
                relaxed.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.SUCCESS, relaxedResult.state(), "容差 2.0 應容忍 1 格漂移");

            CompletionStage<TeleportResult> strict = service.teleportPlayerDeferred(
                player.getUniqueId(), target, false, scheduler);
            pumpUntilDone(strict, "預設容差傳送");
            TeleportResult strictResult =
                strict.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, strictResult.state(),
                "同樣 1 格漂移在預設容差下必須失敗");
            assertEquals(WorldErrorCode.TELEPORT_NOT_ARRIVED, strictResult.errorCode());
        }

        @Test
        @DisplayName("退服：傳送中退服 → CANCELLED")
        void deferred_quitMidFlight_cancelled() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);

            CompletionStage<TeleportResult> stage = service.teleportPlayerDeferred(
                player.getUniqueId(), targetOf(player, 100, 70, -200), false, scheduler);
            player.disconnect();
            pumpUntilDone(stage, "退服中的延後傳送");

            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.CANCELLED, result.state());
        }

        @Test
        @DisplayName("排程器停用 → REJECTED")
        void deferred_schedulerDisabled_rejected() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            ((SafeSchedulerImpl) scheduler).onPluginDisable();

            TeleportResult result = service.teleportPlayerDeferred(
                    player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(WorldState.REJECTED, result.state());
        }

        @Test
        @DisplayName("服務停用後新派送 → REJECTED + WORLD-002")
        void deferred_shutdownService_rejected() throws Exception {
            PlayerMock player = addPlayerAt(0, 64, 0);
            service.shutdown();
            int calls = backend.teleportCalls.get();

            TeleportResult result = service.teleportPlayerDeferred(
                    player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);

            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.SHUTDOWN, result.errorCode());
            assertEquals(calls, backend.teleportCalls.get(), "停用後不得再呼叫後端傳送");
        }

        @Test
        @DisplayName("null 與非法容差一律丟 IllegalArgumentException 帶 INVALID_INPUT")
        void deferred_invalidInputs_throw() {
            UUID id = UUID.randomUUID();
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(null, target, false, scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(id, null, false, scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(id, target, false, null));
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(id, target, false, Double.NaN, scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(id, target, false, -0.1, scheduler));
            assertThrows(IllegalArgumentException.class,
                () -> service.teleportPlayerDeferred(
                    id, target, false, Double.POSITIVE_INFINITY, scheduler));
        }
    }

    // -----------------------------------------------------------------
    // 不可用 facade 的延後入口
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("不可用 facade")
    class UnavailableTests {

        @Test
        @DisplayName("NOT_READY 下延後操作 → REJECTED；延後傳送 → REJECTED + WORLD-001")
        void unavailable_notReady_rejected() throws Exception {
            WorldService unavailable =
                new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY);
            PlayerMock player = addPlayerAt(0, 64, 0);

            TaskTicket<String> ticket = unavailable.deferForPlayer(
                player.getUniqueId(), () -> "x", scheduler);
            assertTrue(ticket.isDone());
            assertEquals(TaskOutcome.REJECTED, ticket.await(5L, TimeUnit.SECONDS).outcome());

            TeleportResult result = unavailable.teleportPlayerDeferred(
                    player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
        }

        @Test
        @DisplayName("SHUTDOWN 下延後傳送 → REJECTED + WORLD-002")
        void unavailable_shutdown_rejected() throws Exception {
            WorldService unavailable =
                new WorldServiceUnavailableImpl(WorldErrorCode.SHUTDOWN);
            PlayerMock player = addPlayerAt(0, 64, 0);

            TeleportResult result = unavailable.teleportPlayerDeferred(
                    player.getUniqueId(), targetOf(player, 5, 64, 5), false, scheduler)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.SHUTDOWN, result.errorCode());
        }
    }

    // -----------------------------------------------------------------
    // 防禦：派送期未預期錯誤不得懸空 future 或漏減 in-flight
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("防禦性完成")
    class DefensiveTests {

        @Test
        @DisplayName("第一跳派送拋 Error → FAILED + OPERATION_FAILED，in-flight 歸零")
        void hopDispatchThrowsError_settlesFailed() throws Exception {
            WorldBackend mockBackend = Mockito.mock(WorldBackend.class);
            PlayerMock player = addPlayerAt(0, 64, 0);
            Mockito.when(mockBackend.resolvePlayer(player.getUniqueId())).thenReturn(player);
            Mockito.when(mockBackend.resolveWorld(player.getWorld().getUID()))
                .thenReturn(player.getWorld());
            SafeScheduler mockScheduler = Mockito.mock(SafeScheduler.class);
            TaskScope mockScope = Mockito.mock(TaskScope.class);
            Mockito.when(mockScheduler.scopeFor(player)).thenReturn(mockScope);
            Mockito.when(mockScope.supply(Mockito.any()))
                .thenThrow(new AssertionError("simulated dispatch failure"));
            WorldServiceImpl impl = new WorldServiceImpl(mockBackend, null);

            CompletionStage<TeleportResult> stage = impl.teleportPlayerDeferred(
                player.getUniqueId(), targetOf(player, 5, 64, 5), false, mockScheduler);

            TeleportResult result = stage.toCompletableFuture().get(5L, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, result.state());
            assertEquals(WorldErrorCode.OPERATION_FAILED, result.errorCode());
            assertEquals(0, impl.getInFlightCount(), "失敗路徑不得漏減 in-flight 計數");
        }
    }
}
