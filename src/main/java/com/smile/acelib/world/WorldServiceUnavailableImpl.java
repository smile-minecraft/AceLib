package com.smile.acelib.world;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 未啟用 / 已停用狀態下的可診斷 facade（Internal）。
 *
 * <p>任何狀態下呼叫本類別的操作，都會回傳 {@code REJECTED} 與對應的
 * {@link WorldErrorCode#NOT_READY} 或 {@link WorldErrorCode#SHUTDOWN} 結果 —
 * <strong>永不為 null，絕不丟例外（除了 null inputs 的契約例外）</strong>。
 * 後續插件於 onEnable 之前或 plugin disable 之後呼叫
 * {@code AceLibApi.getWorldService()} 即取得此 instance。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link com.smile.acelib.AceLibApi} 取得 {@link WorldService} 介面。</p>
 *
 * @see WorldService
 * @since 1.0.0
 */
public final class WorldServiceUnavailableImpl implements WorldService {

    /** 標記本 facade 為「未啟用」或「已停用」。 */
    private final String code;

    public WorldServiceUnavailableImpl(String code) {
        if (!WorldErrorCode.NOT_READY.equals(code)
                && !WorldErrorCode.SHUTDOWN.equals(code)) {
            throw new IllegalArgumentException(
                "WorldServiceUnavailableImpl.code must be NOT_READY or SHUTDOWN, got: " + code);
        }
        this.code = code;
    }

    // ----- contract: null inputs throw IllegalArgumentException -----

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + WorldErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    // ----- block operations -----

    @Override
    public BlockResult readBlock(LocationSnapshot snapshot) {
        requireNonNull(snapshot, "snapshot");
        return BlockResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, snapshot);
    }

    @Override
    public BlockResult writeBlock(LocationSnapshot snapshot, String blockKey) {
        requireNonNull(snapshot, "snapshot");
        requireNonNull(blockKey, "blockKey");
        return BlockResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, snapshot);
    }

    // ----- entity / effect operations -----

    @Override
    public EntityResult spawnEntity(LocationSnapshot location, String entityTypeKey) {
        requireNonNull(location, "location");
        requireNonNull(entityTypeKey, "entityTypeKey");
        return EntityResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, location);
    }

    @Override
    public EntityResult removeEntity(EntityReference reference) {
        requireNonNull(reference, "reference");
        return EntityResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, null);
    }

    @Override
    public EntityResult playEffect(LocationSnapshot location, String effectKey) {
        requireNonNull(location, "location");
        requireNonNull(effectKey, "effectKey");
        return EntityResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, location);
    }

    // ----- query -----

    @Override
    public NearbyQueryResult findNearbyEntities(LocationSnapshot center,
                                                double radius,
                                                String entityTypeFilter) {
        requireNonNull(center, "center");
        requireNonNull(entityTypeFilter, "entityTypeFilter");
        return NearbyQueryResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, center);
    }

    @Override
    public NearbyQueryResult findNearbyPlayers(LocationSnapshot center, double radius) {
        requireNonNull(center, "center");
        return NearbyQueryResult.failure(WorldState.REJECTED, code,
            "world service is unavailable: " + code, center);
    }

    // ----- teleport (async) -----

    @Override
    public CompletionStage<TeleportResult> teleportPlayer(UUID playerId,
                                                          LocationSnapshot target,
                                                          boolean keepPassengers) {
        requireNonNull(playerId, "playerId");
        requireNonNull(target, "target");
        return CompletableFuture.completedFuture(
            TeleportResult.failure(WorldState.REJECTED, code,
                "world service is unavailable: " + code,
                playerId, target, keepPassengers));
    }

    @Override
    public CompletionStage<TeleportResult> teleportEntity(UUID entityId,
                                                          LocationSnapshot target,
                                                          boolean keepPassengers) {
        requireNonNull(entityId, "entityId");
        requireNonNull(target, "target");
        return CompletableFuture.completedFuture(
            TeleportResult.failure(WorldState.REJECTED, code,
                "world service is unavailable: " + code,
                entityId, target, keepPassengers));
    }

    // ----- lifecycle -----

    @Override
    public String getModuleStatus() {
        return code.equals(WorldErrorCode.SHUTDOWN) ? "FAILED" : "NOT_INITIALIZED";
    }

    @Override
    public void shutdown() {
        // no-op for unavailable facade: idempotent + 留 audit trail 只留於 status 字串
    }
}
