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
 * 低階 Bukkit adapter（SPI）：把 {@link WorldService} 對 facade 的 UUID 介面，
 * 解析為實際的 Bukkit 物件做 mutate。
 *
 * <p>本介面要求「不被 long-lived 持有的 entity/player reference」 —
 * 因此每次操作即時解析、不快取任何 Bukkit 物件 reference。</p>
 *
 * <p>兩個 method 都回 {@code true} 表示成功；{@code false} + {@link WorldBackendResult}
 * 提供失敗語意。</p>
 *
 * <h2>實作者責任</h2>
 * <ul>
 *   <li>每次操作即時解析目標，不得長期保存 Bukkit 物件 reference</li>
 *   <li>失敗時回傳對應 {@code ACELIB-WORLD-*} 錯誤代碼的
 *       {@link WorldBackendResult#failed(String, String)}，不丟例外</li>
 *   <li>teleport 委派給 Bukkit 非同步 API；環境不支援時 fallback 為同步結果</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface WorldBackend {

    /**
     * 解析 world by UUID。
     *
     * @param worldId 目標世界 UUID；不可為 null
     * @return 對應的 {@link World}；若不存在回傳 null
     */
    World resolveWorld(UUID worldId);

    /**
     * 解析 entity by UUID（含 player）。
     *
     * @param entityId 目標 entity UUID；不可為 null
     * @return 對應的 {@link Entity}；若不存在回傳 null
     */
    Entity resolveEntity(UUID entityId);

    /**
     * 解析 player by UUID。
     *
     * @param playerId 目標 player UUID；不可為 null
     * @return 對應的 {@link Player}；若不存在回傳 null
     */
    Player resolvePlayer(UUID playerId);

    /**
     * 讀取指定位置的 block key。
     *
     * @param location 目標位置；不可為 null
     * @return 成功攜帶 block key；world/chunk 無法解析回 {@link WorldBackendResult#failed(String, String)}
     */
    WorldBackendResult<String> readBlockAt(Location location);

    /**
     * 將指定位置方塊寫成 {@code blockKey}。
     *
     * @param location 目標位置；不可為 null
     * @param blockKey 方塊材質 key；不可為 null
     * @return 成功回 ok；blockKey 對應的材質不存在時回 {@link WorldBackendResult#failed(String, String)}
     */
    WorldBackendResult<Void> writeBlockAt(Location location, String blockKey);

    /**
     * 在指定位置生成 entity。
     *
     * @param location      目標位置；不可為 null
     * @param entityTypeKey Bukkit EntityType 列舉名；不可為 null
     * @return 成功攜帶生成實體；typeKey 不合法回 {@link WorldBackendResult#failed(String, String)}
     */
    WorldBackendResult<Entity> spawnAt(Location location, String entityTypeKey);

    /**
     * 移除指定 entity。
     *
     * @param entity 目標實體；不可為 null
     * @return 成功回 ok；entity 不存在或死亡回 {@link WorldBackendResult#failed(String, String)}
     */
    WorldBackendResult<Void> removeEntity(Entity entity);

    /**
     * 在指定位置播放效果。
     *
     * @param location  目標位置；不可為 null
     * @param effectKey 效果 key；不可為 null
     * @return 成功回 ok（表示已接受播放請求）
     */
    WorldBackendResult<Void> playEffect(Location location, String effectKey);

    /**
     * 查詢指定位置 + 半徑 + type 的實體。
     *
     * @param location 查詢中心；不可為 null
     * @param radius   半徑（> 0）
     * @param type     過濾的 EntityType；不可為 null
     * @return 命中實體清單；world 不存在時為空清單
     */
    List<Entity> findNearby(Location location, double radius, EntityType type);

    /**
     * 查詢指定位置 + 半徑內玩家。
     *
     * @param location 查詢中心；不可為 null
     * @param radius   半徑（> 0）
     * @return 命中玩家清單；world 不存在時為空清單
     */
    List<Player> findNearbyPlayers(Location location, double radius);

    /**
     * 非同步傳送 subjectEntity 至 targetLocation。
     *
     * @param subjectEntity   被傳送的實體；不可為 null
     * @param targetLocation  目標位置；不可為 null
     * @param keepPassengers  keepPassengers 與 Bukkit 語意一致
     * @return future 完成時以 true 視為成功，false 視為 ACELIB-WORLD-014，
     *         exception 視為 ACELIB-WORLD-015
     */
    CompletionStage<Boolean> teleportAsync(Entity subjectEntity,
                                           Location targetLocation,
                                           boolean keepPassengers);

    /**
     * 取得當前 Bukkit {@link Server}。
     *
     * @return 當前 Server；測試 seam
     */
    Server server();
}
