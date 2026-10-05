package com.smile.acelib.world;

import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
