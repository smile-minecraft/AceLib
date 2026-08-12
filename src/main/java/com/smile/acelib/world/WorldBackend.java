package com.smile.acelib.world;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;

/**
 * 低階 Bukkit adapter：把 {@link WorldService} 對 facade 的 UUID 介面，
 * 解析為實際的 Bukkit 物件做 mutate。Plan §二十一要求模組自行持有
 * 「不被 long-lived 持有的 entity/player reference」 — 因此本介面每次操作
 * 即時解析、不快取任何 Bukkit 物件 reference。
 *
 * <p>兩個 method 都回 {@code true} 表示成功；{@code false} + {@link WorldBackendResult}
 * 提供失敗語意。</p>
 *
 * @since Phase 10 (Plan §十九 §二十一)
 */
public interface WorldBackend {

    /**
     * 解析 world by UUID；若不存在回傳 null。
     */
    World resolveWorld(UUID worldId);

    /**
     * 解析 entity by UUID（含 player）；若不存在回傳 null。
     */
    Entity resolveEntity(UUID entityId);

    /**
     * 解析 player by UUID；若不存在回傳 null。
     */
    Player resolvePlayer(UUID playerId);

    /**
     * 讀取指定位置的 block key。若 world/chunk 無法解析回 {@link WorldBackendResult#failed(String, String)}。
     */
    WorldBackendResult<String> readBlockAt(Location location);

    /**
     * 將指定位置方塊寫成 {@code blockKey}；blockKey 對應的材質不存在時回
     * {@link WorldBackendResult#failed(String, String)}。
     */
    WorldBackendResult<Void> writeBlockAt(Location location, String blockKey);

    /**
     * 在指定位置生成 entity，回傳生成實體；若 typeKey 不合法回
     * {@link WorldBackendResult#failed(String, String)}。
     */
    WorldBackendResult<Entity> spawnAt(Location location, String entityTypeKey);

    /**
     * 移除指定 entity；若 entity 不存在或死亡回
     * {@link WorldBackendResult#failed(String, String)}。
     */
    WorldBackendResult<Void> removeEntity(Entity entity);

    /**
     * 在指定位置播放效果；回 PlayResult 標示是否成功。
     */
    WorldBackendResult<Void> playEffect(Location location, String effectKey);

    /**
     * 查詢指定位置 + 半徑 + type 的實體。
     */
    List<Entity> findNearby(Location location, double radius, EntityType type);

    /**
     * 查詢指定位置 + 半徑內玩家。
     */
    List<Player> findNearbyPlayers(Location location, double radius);

    /**
     * 非同步傳送 subjectEntity 至 targetLocation，keepPassengers 與 Bukkit 語意一致。
     * 回 future 完成時以 true 視為成功，false 視為 ACELIB-WORLD-014，
     * exception 視為 ACELIB-WORLD-015。
     */
    CompletionStage<Boolean> teleportAsync(Entity subjectEntity,
                                           Location targetLocation,
                                           boolean keepPassengers);

    /**
     * 取得當前 Bukkit {@link Server}（測試 seam）。
     */
    Server server();
}
