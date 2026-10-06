package com.smile.acelib.world;

import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskOutcome;
import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import com.smile.acelib.scheduler.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * 預設 {@link WorldService} 實作（Internal）。
 *
 * <p>設計要點：</p>
 * <ul>
 *   <li>所有輸入以 {@link LocationSnapshot} / {@link EntityReference} 為單位；
 *       每次操作內部即時解析 Bukkit 物件，<strong>不長期保存 mutable reference</strong>。</li>
 *   <li>每次操作前置驗證 region owner / world 有效 / chunk 已載入 / entity 存在；
 *       失敗回對應 {@code ACELIB-WORLD-*} 結果而非丟例外。</li>
 *   <li>Teleport 以 {@link CompletionStage} 暴露非同步結果；不把 future 視為
 *       立即完成。</li>
 *   <li>{@link #shutdown()} 標記 stopped 並取消所有 in-flight handle；shutdown 後
 *       新請求立刻回 {@code ACELIB-WORLD-002 SHUTDOWN}。</li>
 *   <li>模組狀態透過既有 {@link DiagnosticsService#registerModuleState} 註冊。</li>
 * </ul>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link com.smile.acelib.AceLibApi} 取得 {@link WorldService} 介面。</p>
 *
 * @since 1.0.0
 */
public final class WorldServiceImpl implements WorldService {

    /** Diagnostics 模組名稱（用於 registerModuleState 與 buildReport）。 */
    static final String MODULE_NAME = "world";

    /**
     * 排程錯誤碼字面值（scheduler package 未公開常數；延後派送的拒派紀錄沿用
     * {@code ACELIB-SCHED-*} 語意，字面值與生產／假排程器一致）。
     */
    private static final String SCHED_PLAYER_OFFLINE = "ACELIB-SCHED-002";
    private static final String SCHED_PLATFORM_UNSUPPORTED = "ACELIB-SCHED-005";

    private final WorldBackend backend;
    private final DiagnosticsService diagnostics;
    private final Platform platform;
    /** AtomicBoolean: started → shutting down 後改為 false。 */
    private final AtomicBoolean running = new AtomicBoolean(true);
    /** In-flight teleport handle 計數（shutdown 時等於 0 才算 fully drained）。 */
    private final AtomicInteger inFlightTeleports = new AtomicInteger(0);

    public WorldServiceImpl(WorldBackend backend, DiagnosticsService diagnostics) {
        this(backend, diagnostics,
            new PlatformDetector(WorldServiceImpl.class.getClassLoader()).detect());
    }

    /**
     * 測試 seam：顯式指定平台，避免測試依賴 classpath 探測結果。
     *
     * <p>package-private，非公開 API；下游僅能使用雙參數公開建構子。</p>
     */
    WorldServiceImpl(WorldBackend backend, DiagnosticsService diagnostics, Platform platform) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.diagnostics = diagnostics; // 可為 null；tests 用
        this.platform = Objects.requireNonNull(platform, "platform");
        if (diagnostics != null) {
            diagnostics.registerModuleState(MODULE_NAME,
                com.smile.acelib.diagnostics.ModuleState.ready(MODULE_NAME,
                    "world service bound to " + backend.server().getName()));
        }
    }

    /**
     * 把後端的失敗結果翻譯成對外 {@link WorldState}。
     *
     * <p>區分「請求被安全地拒絕」與「執行期失敗」：{@link WorldState#REJECTED}
     * 代表請求在動手前就被擋下（輸入不合法、目標不可解析、平台或執行緒不安全、
     * 功能尚未實作），呼叫端可據此修正後重試；{@link WorldState#FAILED} 代表已進入
     * 執行期卻失敗。</p>
     *
     * <p>未列出的代碼一律視為 {@link WorldState#FAILED}（保守降級），因此後端新增
     * 代碼時不會被誤標成「安全拒絕」。</p>
     *
     * @param errorCode 後端回傳的錯誤代碼
     * @return REJECTED 或 FAILED
     */
    private static WorldState stateForBackendFailure(String errorCode) {
        boolean rejected = WorldErrorCode.INVALID_INPUT.equals(errorCode)
            || WorldErrorCode.WORLD_NOT_FOUND.equals(errorCode)
            || WorldErrorCode.CHUNK_UNLOADED.equals(errorCode)
            || WorldErrorCode.ENTITY_GONE.equals(errorCode)
            || WorldErrorCode.PLAYER_OFFLINE.equals(errorCode)
            || WorldErrorCode.CONTEXT_UNSAFE.equals(errorCode)
            || WorldErrorCode.PLATFORM_UNSUPPORTED.equals(errorCode)
            || WorldErrorCode.EFFECT_REJECTED.equals(errorCode)
            || WorldErrorCode.TELEPORT_REJECTED.equals(errorCode)
            || WorldErrorCode.NEARBY_QUERY_FAILED.equals(errorCode)
            || WorldErrorCode.BLOCK_OPERATION_FAILED.equals(errorCode)
            || WorldErrorCode.NOT_READY.equals(errorCode)
            || WorldErrorCode.SHUTDOWN.equals(errorCode);
        return rejected ? WorldState.REJECTED : WorldState.FAILED;
    }

    // -----------------------------------------------------------------
    // Contract: null inputs throw IllegalArgumentException
    // -----------------------------------------------------------------

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + WorldErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    private static String requireStringNonEmpty(String s, String name) {
        requireNonNull(s, name);
        if (s.isEmpty()) {
            throw new IllegalArgumentException(
                "[" + WorldErrorCode.INVALID_INPUT + "] " + name + " must not be empty");
        }
        return s;
    }

    private static double requirePositiveRadius(double r) {
        if (!(r > 0) || Double.isNaN(r) || Double.isInfinite(r)) {
            throw new IllegalArgumentException(
                "[" + WorldErrorCode.INVALID_INPUT + "] radius must be > 0 (was " + r + ")");
        }
        return r;
    }

    /** 把 LocationSnapshot 解析為 Bukkit Location，不持有 reference。 */
    private Location resolveLocation(LocationSnapshot snapshot) {
        World w = backend.resolveWorld(snapshot.worldId());
        if (w == null) {
            return null;
        }
        // Folia-friendly: 直接組出 location，不要求 chunk 必須已載入（讀時才檢查）
        return new Location(w, snapshot.blockX(), snapshot.blockY(), snapshot.blockZ(),
            snapshot.yaw(), snapshot.pitch());
    }

    // -----------------------------------------------------------------
    // Block operations
    // -----------------------------------------------------------------

    @Override
    public BlockResult readBlock(LocationSnapshot snapshot) {
        if (!running.get()) {
            return BlockResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", snapshot);
        }
        requireNonNull(snapshot, "snapshot");
        Location loc = resolveLocation(snapshot);
        if (loc == null || loc.getWorld() == null) {
            return BlockResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + snapshot.worldIdString(), snapshot);
        }
        WorldBackendResult<String> r = backend.readBlockAt(loc);
        if (!r.isOk()) {
            return BlockResult.failure(WorldState.REJECTED, r.errorCode(), r.detail(), snapshot);
        }
        return BlockResult.success(snapshot, r.value());
    }

    @Override
    public BlockResult writeBlock(LocationSnapshot snapshot, String blockKey) {
        if (!running.get()) {
            return BlockResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", snapshot);
        }
        requireNonNull(snapshot, "snapshot");
        requireStringNonEmpty(blockKey, "blockKey");
        Location loc = resolveLocation(snapshot);
        if (loc == null || loc.getWorld() == null) {
            return BlockResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + snapshot.worldIdString(), snapshot);
        }
        WorldBackendResult<Void> r = backend.writeBlockAt(loc, blockKey);
        if (!r.isOk()) {
            return BlockResult.failure(WorldState.REJECTED, r.errorCode(), r.detail(), snapshot);
        }
        return BlockResult.success(snapshot, blockKey.toUpperCase(Locale.ROOT));
    }

    // -----------------------------------------------------------------
    // Entity / effect operations
    // -----------------------------------------------------------------

    @Override
    public EntityResult spawnEntity(LocationSnapshot location, String entityTypeKey) {
        if (!running.get()) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", location);
        }
        requireNonNull(location, "location");
        requireStringNonEmpty(entityTypeKey, "entityTypeKey");
        Location loc = resolveLocation(location);
        if (loc == null || loc.getWorld() == null) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + location.worldIdString(), location);
        }
        WorldBackendResult<Entity> r = backend.spawnAt(loc, entityTypeKey);
        if (!r.isOk()) {
            return EntityResult.failure(WorldState.REJECTED, r.errorCode(), r.detail(), location);
        }
        Entity spawned = r.value();
        EntityReference ref = EntityReference.of(spawned.getUniqueId(),
            spawned.getWorld().getUID(),
            spawned.getType().name());
        return EntityResult.success(ref, location);
    }

    @Override
    public EntityResult removeEntity(EntityReference reference) {
        if (!running.get()) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", null);
        }
        requireNonNull(reference, "reference");
        Entity entity = backend.resolveEntity(reference.entityId());
        if (entity == null) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.ENTITY_GONE,
                "entity " + reference.entityId() + " is not present", null);
        }
        WorldBackendResult<Void> r = backend.removeEntity(entity);
        if (!r.isOk()) {
            return EntityResult.failure(WorldState.REJECTED, r.errorCode(), r.detail(), null);
        }
        return EntityResult.successWithoutReference(null);
    }

    @Override
    public EntityResult playEffect(LocationSnapshot location, String effectKey) {
        if (!running.get()) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", location);
        }
        requireNonNull(location, "location");
        requireStringNonEmpty(effectKey, "effectKey");
        Location loc = resolveLocation(location);
        if (loc == null || loc.getWorld() == null) {
            return EntityResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + location.worldIdString(), location);
        }
        WorldBackendResult<Void> r = backend.playEffect(loc, effectKey);
        if (!r.isOk()) {
            // 後端回失敗就照實回失敗：未實作的效果不得退化成 successWithoutReference
            // 宣告已播放，否則呼叫端會以為效果真的播過。
            return EntityResult.failure(
                stateForBackendFailure(r.errorCode()), r.errorCode(), r.detail(), location);
        }
        return EntityResult.successWithoutReference(location);
    }

    // -----------------------------------------------------------------
    // Query operations
    // -----------------------------------------------------------------

    @Override
    public NearbyQueryResult findNearbyEntities(LocationSnapshot center,
                                                double radius,
                                                String entityTypeFilter) {
        if (!running.get()) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", center);
        }
        requireNonNull(center, "center");
        requirePositiveRadius(radius);
        requireStringNonEmpty(entityTypeFilter, "entityTypeFilter");
        Location loc = resolveLocation(center);
        if (loc == null || loc.getWorld() == null) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + center.worldIdString(), center);
        }
        EntityType type;
        try {
            type = EntityType.valueOf(entityTypeFilter.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.INVALID_INPUT,
                "unknown entity type key: " + entityTypeFilter, center);
        }
        if (backend instanceof BukkitWorldBackend builtin) {
            WorldBackendResult<List<Entity>> r = builtin.queryNearby(loc, radius, type);
            if (!r.isOk()) {
                // 後端拒絕（例如 Folia 無法證明 owner-safe）時保留原始錯誤碼，
                // 不得以空清單假裝「查過且沒有命中」。
                return NearbyQueryResult.failure(
                    stateForBackendFailure(r.errorCode()), r.errorCode(), r.detail(), center);
            }
            List<EntityReference> refs = new ArrayList<>(r.value().size());
            for (Entity e : r.value()) {
                refs.add(EntityReference.of(e.getUniqueId(),
                    e.getWorld().getUID(),
                    e.getType().name()));
            }
            return NearbyQueryResult.success(center, refs);
        }
        // 未知外部實作：Folia 上未證 owner-safe，不得呼叫其 List 方法，直接拒絕。
        if (platform == Platform.FOLIA) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.CONTEXT_UNSAFE,
                "findNearby cannot prove owner-safe region ownership on Folia;"
                    + " legacy backend not called",
                center);
        }
        // Paper 上按原 List SPI 委派；呼叫端不得宣稱為已證 bounded。
        List<Entity> hits = backend.findNearby(loc, radius, type);
        if (hits == null) {
            return NearbyQueryResult.failure(WorldState.FAILED, WorldErrorCode.NEARBY_QUERY_FAILED,
                "legacy backend returned null list", center);
        }
        List<EntityReference> refs = new ArrayList<>(hits.size());
        for (Entity e : hits) {
            refs.add(EntityReference.of(e.getUniqueId(),
                e.getWorld().getUID(),
                e.getType().name()));
        }
        return NearbyQueryResult.success(center, refs);
    }

    @Override
    public NearbyQueryResult findNearbyPlayers(LocationSnapshot center, double radius) {
        if (!running.get()) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                "world service is shutdown", center);
        }
        requireNonNull(center, "center");
        requirePositiveRadius(radius);
        Location loc = resolveLocation(center);
        if (loc == null || loc.getWorld() == null) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                "world not found: " + center.worldIdString(), center);
        }
        if (backend instanceof BukkitWorldBackend builtin) {
            WorldBackendResult<List<Player>> r = builtin.queryNearbyPlayers(loc, radius);
            if (!r.isOk()) {
                return NearbyQueryResult.failure(
                    stateForBackendFailure(r.errorCode()), r.errorCode(), r.detail(), center);
            }
            List<EntityReference> refs = new ArrayList<>(r.value().size());
            for (Player p : r.value()) {
                refs.add(EntityReference.of(p.getUniqueId(),
                    p.getWorld().getUID(),
                    p.getType().name()));
            }
            return NearbyQueryResult.success(center, refs);
        }
        if (platform == Platform.FOLIA) {
            return NearbyQueryResult.failure(WorldState.REJECTED, WorldErrorCode.CONTEXT_UNSAFE,
                "findNearbyPlayers cannot prove owner-safe region ownership on Folia;"
                    + " legacy backend not called",
                center);
        }
        List<Player> hits = backend.findNearbyPlayers(loc, radius);
        if (hits == null) {
            return NearbyQueryResult.failure(WorldState.FAILED, WorldErrorCode.NEARBY_QUERY_FAILED,
                "legacy backend returned null list", center);
        }
        List<EntityReference> refs = new ArrayList<>(hits.size());
        for (Player p : hits) {
            refs.add(EntityReference.of(p.getUniqueId(),
                p.getWorld().getUID(),
                p.getType().name()));
        }
        return NearbyQueryResult.success(center, refs);
    }

    // -----------------------------------------------------------------
    // Teleport operations (async)
    // -----------------------------------------------------------------

    @Override
    public CompletionStage<TeleportResult> teleportPlayer(UUID playerId,
                                                          LocationSnapshot target,
                                                          boolean keepPassengers) {
        if (!running.get()) {
            requireNonNull(playerId, "playerId");
            requireNonNull(target, "target");
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                    "world service is shutdown",
                    playerId, target, keepPassengers));
        }
        requireNonNull(playerId, "playerId");
        requireNonNull(target, "target");
        Player player = backend.resolvePlayer(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.PLAYER_OFFLINE,
                    "player " + playerId + " is offline",
                    playerId, target, keepPassengers));
        }
        Location loc = resolveLocation(target);
        if (loc == null || loc.getWorld() == null) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                    "world not found: " + target.worldIdString(),
                    playerId, target, keepPassengers));
        }
        return doTeleport(player, loc, playerId, target, keepPassengers);
    }

    @Override
    public CompletionStage<TeleportResult> teleportEntity(UUID entityId,
                                                          LocationSnapshot target,
                                                          boolean keepPassengers) {
        if (!running.get()) {
            requireNonNull(entityId, "entityId");
            requireNonNull(target, "target");
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                    "world service is shutdown",
                    entityId, target, keepPassengers));
        }
        requireNonNull(entityId, "entityId");
        requireNonNull(target, "target");
        Entity entity = backend.resolveEntity(entityId);
        if (entity == null) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.ENTITY_GONE,
                    "entity " + entityId + " is not present",
                    entityId, target, keepPassengers));
        }
        Location loc = resolveLocation(target);
        if (loc == null || loc.getWorld() == null) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                    "world not found: " + target.worldIdString(),
                    entityId, target, keepPassengers));
        }
        return doTeleport(entity, loc, entityId, target, keepPassengers);
    }

    private CompletionStage<TeleportResult> doTeleport(Entity subject,
                                                       Location targetLocation,
                                                       UUID subjectId,
                                                       LocationSnapshot targetSnapshot,
                                                       boolean keepPassengers) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                TeleportResult.cancelled(subjectId, targetSnapshot, keepPassengers));
        }
        inFlightTeleports.incrementAndGet();
        CompletableFuture<TeleportResult> resultFuture = new CompletableFuture<>();
        CompletionStage<Boolean> raw = backend.teleportAsync(subject, targetLocation, keepPassengers);
        raw.whenComplete((ok, err) -> {
            inFlightTeleports.decrementAndGet();
            if (!running.get()) {
                // shutdown 已觸發：在完成路徑統一替換為 CANCELLED
                resultFuture.complete(
                    TeleportResult.cancelled(subjectId, targetSnapshot, keepPassengers));
                return;
            }
            if (err != null) {
                resultFuture.complete(
                    TeleportResult.failure(WorldState.FAILED, WorldErrorCode.TELEPORT_EXCEPTION,
                        "teleport threw: " + err.getClass().getSimpleName()
                            + ": " + err.getMessage(),
                        subjectId, targetSnapshot, keepPassengers));
            } else if (Boolean.TRUE.equals(ok)) {
                resultFuture.complete(
                    TeleportResult.success(subjectId, targetSnapshot, keepPassengers));
            } else {
                resultFuture.complete(
                    TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.TELEPORT_REJECTED,
                        "teleport rejected by Bukkit (returned false)",
                        subjectId, targetSnapshot, keepPassengers));
            }
        });
        return resultFuture;
    }

    // -----------------------------------------------------------------
    // Deferred operations (after event handling)
    // -----------------------------------------------------------------

    /**
     * 派送期共用的玩家解析：離線或不存在即為拒派。
     *
     * @return 在線玩家；離線或不存在時為 null
     */
    private Player resolveOnlinePlayer(UUID playerId) {
        Player player = backend.resolvePlayer(playerId);
        return (player != null && player.isOnline()) ? player : null;
    }

    @Override
    public <T> TaskTicket<T> deferForPlayer(UUID playerId,
                                            Supplier<T> action,
                                            SafeScheduler scheduler) {
        requireNonNull(playerId, "playerId");
        requireNonNull(action, "action");
        requireNonNull(scheduler, "scheduler");
        Player player = resolveOnlinePlayer(playerId);
        if (player == null) {
            return TerminalTaskTicket.rejected(TaskType.PLAYER,
                TaskErrorRecord.cancelled(TaskType.PLAYER, SCHED_PLAYER_OFFLINE,
                    "player " + playerId + " is offline"),
                null);
        }
        TaskScope scope;
        try {
            scope = scheduler.scopeFor(player);
        } catch (RuntimeException scopeFailure) {
            return TerminalTaskTicket.rejected(TaskType.PLAYER,
                TaskErrorRecord.cancelled(TaskType.PLAYER, SCHED_PLATFORM_UNSUPPORTED,
                    "deferred dispatch refused: scope creation failed: " + scopeFailure),
                null);
        }
        if (!running.get()) {
            return TerminalTaskTicket.rejected(TaskType.PLAYER,
                TaskErrorRecord.cancelled(TaskType.PLAYER, WorldErrorCode.SHUTDOWN,
                    "world service is shutdown"),
                scope.plugin());
        }
        return scope.supply(action);
    }

    @Override
    public CompletionStage<TeleportResult> teleportPlayerDeferred(
        UUID playerId, LocationSnapshot target, boolean keepPassengers,
        SafeScheduler scheduler) {
        return teleportPlayerDeferred(playerId, target, keepPassengers,
            WorldService.DEFAULT_ARRIVAL_TOLERANCE, scheduler);
    }

    @Override
    public CompletionStage<TeleportResult> teleportPlayerDeferred(
        UUID playerId, LocationSnapshot target, boolean keepPassengers,
        double tolerance, SafeScheduler scheduler) {
        requireNonNull(playerId, "playerId");
        requireNonNull(target, "target");
        requireNonNull(scheduler, "scheduler");
        requireValidTolerance(tolerance);
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.SHUTDOWN,
                    "world service is shutdown",
                    playerId, target, keepPassengers));
        }
        Player player = resolveOnlinePlayer(playerId);
        if (player == null) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.PLAYER_OFFLINE,
                    "player " + playerId + " is offline",
                    playerId, target, keepPassengers));
        }
        Location expected = resolveLocation(target);
        if (expected == null || expected.getWorld() == null) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED, WorldErrorCode.WORLD_NOT_FOUND,
                    "world not found: " + target.worldIdString(),
                    playerId, target, keepPassengers));
        }
        TaskScope scope;
        try {
            scope = scheduler.scopeFor(player);
        } catch (RuntimeException scopeFailure) {
            return CompletableFuture.completedFuture(
                TeleportResult.failure(WorldState.REJECTED,
                    WorldErrorCode.DEFERRED_UNAVAILABLE,
                    "deferred dispatch refused: scope creation failed: " + scopeFailure,
                    playerId, target, keepPassengers));
        }
        inFlightTeleports.incrementAndGet();
        CompletableFuture<TeleportResult> outcome = new CompletableFuture<>();
        outcome.whenComplete((ignored, ignoredFailure) -> inFlightTeleports.decrementAndGet());
        TaskTicket<CompletionStage<TeleportResult>> hop;
        try {
            hop = scope.supply(() -> teleportPlayer(playerId, target, keepPassengers));
        } catch (Throwable dispatchFailure) {
            // 最後防線：派送期未預期錯誤不得把例外丟給呼叫端，
            // 也不得留下永不完成的 future 與 in-flight 計數。
            completeDefensive(outcome, playerId, target, keepPassengers,
                dispatchFailure, "dispatch");
            return outcome;
        }
        hop.stage().whenComplete((hopResult, hopFailure) -> {
            try {
                completeHop(outcome, scope, playerId, target, keepPassengers,
                    tolerance, hopResult);
            } catch (Throwable callbackFailure) {
                completeDefensive(outcome, playerId, target, keepPassengers,
                    callbackFailure, "teleport completion");
            }
        });
        return outcome;
    }

    /**
     * 第一跳完成後的串接（第一跳票據 → 內層傳送 → 到達確認派送）。
     *
     * <p>抽成獨立方法，讓外層回呼的防禦性 {@code try/catch} 只包一層；
     * 本方法內仍以明確分支處理所有預期終態，防禦分支只接未預期的拋錯。</p>
     */
    private void completeHop(CompletableFuture<TeleportResult> outcome,
                             TaskScope scope,
                             UUID playerId,
                             LocationSnapshot target,
                             boolean keepPassengers,
                             double tolerance,
                             TaskResult<CompletionStage<TeleportResult>> hopResult) {
        if (hopResult == null || hopResult.outcome() != TaskOutcome.COMPLETED) {
            outcome.complete(mapScopeTerminal(hopResult, playerId, target, keepPassengers));
            return;
        }
        CompletionStage<TeleportResult> inner = hopResult.value();
        if (inner == null) {
            outcome.complete(
                TeleportResult.failure(WorldState.FAILED, WorldErrorCode.OPERATION_FAILED,
                    "deferred teleport dispatch returned null stage",
                    playerId, target, keepPassengers));
            return;
        }
        inner.whenComplete((teleported, teleportFailure) -> {
            try {
                completeTeleport(outcome, scope, playerId, target, keepPassengers,
                    tolerance, teleported, teleportFailure);
            } catch (Throwable callbackFailure) {
                completeDefensive(outcome, playerId, target, keepPassengers,
                    callbackFailure, "teleport completion");
            }
        });
    }

    /**
     * 內層傳送完成後的串接（結果透出 → 到達確認派送 → 確認完成）。
     */
    private void completeTeleport(CompletableFuture<TeleportResult> outcome,
                                  TaskScope scope,
                                  UUID playerId,
                                  LocationSnapshot target,
                                  boolean keepPassengers,
                                  double tolerance,
                                  TeleportResult teleported,
                                  Throwable teleportFailure) {
        if (teleportFailure != null) {
            outcome.complete(
                TeleportResult.failure(WorldState.FAILED,
                    WorldErrorCode.TELEPORT_EXCEPTION,
                    "teleport threw: " + teleportFailure.getClass().getSimpleName()
                        + ": " + teleportFailure.getMessage(),
                    playerId, target, keepPassengers));
            return;
        }
        if (teleported == null) {
            outcome.complete(
                TeleportResult.failure(WorldState.FAILED,
                    WorldErrorCode.OPERATION_FAILED,
                    "teleport completed with null result",
                    playerId, target, keepPassengers));
            return;
        }
        if (teleported.state() != WorldState.SUCCESS) {
            outcome.complete(teleported);
            return;
        }
        if (!running.get()) {
            outcome.complete(
                TeleportResult.cancelled(playerId, target, keepPassengers));
            return;
        }
        TaskTicket<TeleportResult> verify;
        try {
            verify = scope.supply(
                () -> verifyArrival(playerId, target, tolerance, keepPassengers));
        } catch (Throwable verifyDispatchFailure) {
            completeDefensive(outcome, playerId, target, keepPassengers,
                verifyDispatchFailure, "arrival-check dispatch");
            return;
        }
        verify.stage().whenComplete((verifyResult, verifyFailure) -> {
            try {
                if (verifyResult == null) {
                    outcome.complete(
                        TeleportResult.failure(WorldState.FAILED,
                            WorldErrorCode.OPERATION_FAILED,
                            "arrival check completed without a result",
                            playerId, target, keepPassengers));
                } else if (verifyResult.outcome() == TaskOutcome.COMPLETED) {
                    TeleportResult arrival = verifyResult.value();
                    outcome.complete(arrival != null ? arrival
                        : TeleportResult.failure(WorldState.FAILED,
                            WorldErrorCode.OPERATION_FAILED,
                            "arrival check completed with null result",
                            playerId, target, keepPassengers));
                } else {
                    outcome.complete(
                        mapScopeTerminal(verifyResult, playerId, target, keepPassengers));
                }
            } catch (Throwable callbackFailure) {
                completeDefensive(outcome, playerId, target, keepPassengers,
                    callbackFailure, "arrival-check completion");
            }
        });
    }

    /**
     * 最後防線：串接中任何未預期錯誤都讓 {@code outcome} 以
     * {@code FAILED + ACELIB-WORLD-010} 完成。
     *
     * <p>in-flight 計數掛在 {@code outcome} 完成上遞減，完成即歸零，不另處理。
     * 本方法自身不拋錯（訊息只取例外類別名，不呼叫 {@code toString}）。</p>
     */
    private static void completeDefensive(CompletableFuture<TeleportResult> outcome,
                                          UUID subjectId,
                                          LocationSnapshot target,
                                          boolean keepPassengers,
                                          Throwable failure,
                                          String phase) {
        String name;
        try {
            name = failure == null ? "<null>" : failure.getClass().getName();
        } catch (Throwable ignored) {
            name = "<unprintable>";
        }
        outcome.complete(
            TeleportResult.failure(WorldState.FAILED, WorldErrorCode.OPERATION_FAILED,
                "deferred " + phase + " failed unexpectedly (" + name
                    + "); keep this detail for the AceLib maintainer",
                subjectId, target, keepPassengers));
    }

    /**
     * 容差合法性：有限非負數。
     */
    private static void requireValidTolerance(double tolerance) {
        if (Double.isNaN(tolerance) || Double.isInfinite(tolerance) || tolerance < 0) {
            throw new IllegalArgumentException(
                "[" + WorldErrorCode.INVALID_INPUT + "] tolerance must be a finite"
                    + " non-negative number (was " + tolerance + ")");
        }
    }

    /**
     * 作用域終態轉傳送結果：取消沿用取消，拒派／失敗保留原始錯誤碼與訊息。
     */
    private static TeleportResult mapScopeTerminal(TaskResult<?> terminal,
                                                   UUID subjectId,
                                                   LocationSnapshot target,
                                                   boolean keepPassengers) {
        if (terminal == null) {
            return TeleportResult.failure(WorldState.FAILED, WorldErrorCode.OPERATION_FAILED,
                "deferred dispatch completed without a result",
                subjectId, target, keepPassengers);
        }
        return switch (terminal.outcome()) {
            case COMPLETED -> TeleportResult.failure(WorldState.FAILED,
                WorldErrorCode.OPERATION_FAILED,
                "deferred dispatch completed without a teleport result",
                subjectId, target, keepPassengers);
            case CANCELLED -> TeleportResult.cancelled(subjectId, target, keepPassengers);
            case REJECTED -> {
                TaskErrorRecord record = terminal.errorRecord();
                yield TeleportResult.failure(WorldState.REJECTED,
                    record == null ? WorldErrorCode.OPERATION_FAILED : record.code(),
                    "deferred dispatch refused: "
                        + (record == null ? "no record" : record.detail()),
                    subjectId, target, keepPassengers);
            }
            case FAILED -> {
                TaskErrorRecord record = terminal.errorRecord();
                yield TeleportResult.failure(WorldState.FAILED,
                    record == null ? WorldErrorCode.OPERATION_FAILED : record.code(),
                    "deferred dispatch failed: "
                        + (record == null ? "no record" : record.detail()),
                    subjectId, target, keepPassengers);
            }
        };
    }

    /**
     * 到達確認（必須在玩家所在執行緒執行）。
     *
     * <p>先比世界（UUID 相等才算同世界），同世界再逐軸比座標；
     * 平台回報成功但實際未到達時回 {@code FAILED + ACELIB-WORLD-018}，
     * 診斷含期望與實際位置。</p>
     */
    private TeleportResult verifyArrival(UUID playerId,
                                         LocationSnapshot target,
                                         double tolerance,
                                         boolean keepPassengers) {
        Player current;
        try {
            current = backend.resolvePlayer(playerId);
        } catch (RuntimeException resolveFailure) {
            return TeleportResult.failure(WorldState.FAILED, WorldErrorCode.OPERATION_FAILED,
                "arrival check could not resolve player " + playerId + ": " + resolveFailure,
                playerId, target, keepPassengers);
        }
        if (current == null || !current.isOnline()) {
            return TeleportResult.failure(WorldState.FAILED, WorldErrorCode.PLAYER_OFFLINE,
                "player " + playerId + " went offline before the arrival check;"
                    + " expected=" + describeTarget(target)
                    + "; the teleport outcome can no longer be confirmed",
                playerId, target, keepPassengers);
        }
        Location actual;
        try {
            actual = current.getLocation();
        } catch (RuntimeException locationFailure) {
            return TeleportResult.failure(WorldState.FAILED, WorldErrorCode.OPERATION_FAILED,
                "arrival check could not read location of player " + playerId + ": "
                    + locationFailure,
                playerId, target, keepPassengers);
        }
        if (actual == null || actual.getWorld() == null
            || !target.worldId().equals(actual.getWorld().getUID())) {
            return TeleportResult.failure(WorldState.FAILED,
                WorldErrorCode.TELEPORT_NOT_ARRIVED,
                "teleport reported success but the player did not arrive:"
                    + " expected=" + describeTarget(target)
                    + " actual=" + describeActual(actual)
                    + " tolerance=" + tolerance
                    + "; the position may have been restored by a later event handler;"
                    + " wait for event handling to finish and retry teleportPlayerDeferred",
                playerId, target, keepPassengers);
        }
        double dx = Math.abs(actual.getX() - target.blockX());
        double dy = Math.abs(actual.getY() - target.blockY());
        double dz = Math.abs(actual.getZ() - target.blockZ());
        if (dx <= tolerance && dy <= tolerance && dz <= tolerance) {
            return TeleportResult.success(playerId, target, keepPassengers);
        }
        return TeleportResult.failure(WorldState.FAILED,
            WorldErrorCode.TELEPORT_NOT_ARRIVED,
            "teleport reported success but the player did not arrive:"
                + " expected=" + describeTarget(target)
                + " actual=" + describeActual(actual)
                + " tolerance=" + tolerance
                + "; the position may have been restored by a later event handler;"
                + " wait for event handling to finish and retry teleportPlayerDeferred",
            playerId, target, keepPassengers);
    }

    private static String describeTarget(LocationSnapshot target) {
        return "world=" + target.worldIdString()
            + " x=" + target.blockX() + " y=" + target.blockY() + " z=" + target.blockZ();
    }

    private static String describeActual(Location actual) {
        if (actual == null) {
            return "<null location>";
        }
        World world = actual.getWorld();
        String worldPart = world == null
            ? "<null world>"
            : world.getName() + "/" + world.getUID();
        return "world=" + worldPart
            + " x=" + actual.getX() + " y=" + actual.getY() + " z=" + actual.getZ();
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Override
    public String getModuleStatus() {
        return running.get() ? "READY" : "FAILED";
    }

    /**
     * 標記 stopped 並拒絕新請求；不主動中斷已 in-flight 的 teleport future —
     * future 會在下個完成點自動回 CANCELLED。
     */
    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return; // idempotent
        }
        if (diagnostics != null) {
            diagnostics.registerModuleState(MODULE_NAME,
                com.smile.acelib.diagnostics.ModuleState.failed(MODULE_NAME,
                    "world service shutdown",
                    WorldErrorCode.SHUTDOWN));
        }
    }

    /** 是否仍處於 running 狀態（測試 seam）。 */
    boolean isRunning() {
        return running.get();
    }

    /** 當前 in-flight teleport handle 數量（測試 seam）。 */
    int getInFlightCount() {
        return inFlightTeleports.get();
    }

    /** 便利方法：取得 diagnostics report（測試 seam）。 */
    DiagnosticReport getDiagnosticsReport() {
        return diagnostics == null ? null : diagnostics.buildReport();
    }
}
