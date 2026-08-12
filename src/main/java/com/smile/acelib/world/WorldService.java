package com.smile.acelib.world;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * WorldService 安全 facade（Plan §十五 §十九 Phase 10 對外 canonical API）。
 *
 * <p>提供一組 Folia-safe 的世界操作入口，後續插件不需要直接接觸
 * {@code Bukkit.getWorld(uid)} / {@code World#getBlockAt(loc)} /
 * {@code Entity#teleportAsync(loc)} 等會破壞 Folia 執行緒假設的 API，
 * 改透過本介面取得 region-aware 操作結果。</p>
 *
 * <h2>設計原則（Plan §二十一未完成階段共同交付契約）</h2>
 * <ul>
 *   <li>對外輸入僅接受 {@link LocationSnapshot} / {@link EntityReference} —
 *       不可傳入 {@code World} / {@code Location} / {@code Entity} / {@code Player}。</li>
 *   <li>每次呼叫於執行前重新驗證目標；失敗回對應 {@code ACELIB-WORLD-*} 結果，
 *       不丟例外給 caller。</li>
 *   <li>Teleport 為非同步：回 {@link CompletionStage} 等待實際 future 完成
 *       （success / false / exception / cancelled / partial）。</li>
 *   <li>模組於未啟用（{@link AceLibApi#uninitialized()}）或停用後呼叫一律回
 *       {@code REJECTED + ACELIB-WORLD-001 / 002}；實作內部持有 in-flight
 *       handle 清單，shutdown 時拒絕新請求並取消既有。</li>
 * </ul>
 *
 * @since Phase 10 (Plan §十五 §十九 §二十一)
 */
public interface WorldService {

    // -----------------------------------------------------------------
    // Block operations
    // -----------------------------------------------------------------

    /**
     * 讀取指定位置方塊的材質 key。
     *
     * @param snapshot 目標位置；不可為 null
     * @return 對應 {@link BlockResult}；never null
     */
    BlockResult readBlock(LocationSnapshot snapshot);

    /**
     * 寫入指定位置方塊。
     *
     * @param snapshot 目標位置；不可為 null
     * @param blockKey 方塊材質 key（如 {@code "STONE"}）；不可為 null / 空字串
     * @return 對應 {@link BlockResult}；never null
     */
    BlockResult writeBlock(LocationSnapshot snapshot, String blockKey);

    // -----------------------------------------------------------------
    // Entity / effect operations
    // -----------------------------------------------------------------

    /**
     * 在指定位置生成實體（同步；可能由 {@code SafeScheduler.runAtLocation} 保護）。
     *
     * @param location     生成位置；不可為 null
     * @param entityTypeKey Bukkit {@code EntityType} 列舉名（如 {@code "ZOMBIE"}）；
     *                      不可為 null
     * @return 對應 {@link EntityResult}；never null
     */
    EntityResult spawnEntity(LocationSnapshot location, String entityTypeKey);

    /**
     * 移除指定實體。
     *
     * @param reference 目標實體參考；不可為 null
     * @return 對應 {@link EntityResult}；never null
     */
    EntityResult removeEntity(EntityReference reference);

    /**
     * 在指定位置播放音效／粒子效果。
     *
     * @param location  目標位置；不可為 null
     * @param effectKey 效果 key（如 {@code "EXPLOSION"}）；不可為 null
     * @return 對應 {@link EntityResult}；never null
     */
    EntityResult playEffect(LocationSnapshot location, String effectKey);

    // -----------------------------------------------------------------
    // Query operations
    // -----------------------------------------------------------------

    /**
     * 查詢指定中心 + 半徑內符合 entity type 的實體。
     *
     * @param center           查詢中心；不可為 null
     * @param radius           半徑（必須 &gt; 0）
     * @param entityTypeFilter EntityType 列舉名過濾；不可為 null
     * @return 對應 {@link NearbyQueryResult}；never null
     */
    NearbyQueryResult findNearbyEntities(LocationSnapshot center,
                                         double radius,
                                         String entityTypeFilter);

    /**
     * 查詢指定中心 + 半徑內玩家。
     *
     * @param center 查詢中心；不可為 null
     * @param radius 半徑（必須 &gt; 0）
     * @return 對應 {@link NearbyQueryResult}；never null
     */
    NearbyQueryResult findNearbyPlayers(LocationSnapshot center, double radius);

    // -----------------------------------------------------------------
    // Teleport (async)
    // -----------------------------------------------------------------

    /**
     * 傳送玩家（依 UUID）。非同步；future 會攜帶最終 {@link TeleportResult}。
     *
     * @param playerId       玩家 UUID；不可為 null
     * @param target         目標位置；不可為 null
     * @param keepPassengers 是否保留乘客
     * @return 對應 {@link CompletionStage}；never null，future 必定完成
     *         （SUCCESS/REJECTED/FAILED/CANCELLED/PARTIAL/TeleportException）
     */
    CompletionStage<TeleportResult> teleportPlayer(UUID playerId,
                                                   LocationSnapshot target,
                                                   boolean keepPassengers);

    /**
     * 傳送實體（依 UUID）。非同步；future 會攜帶最終 {@link TeleportResult}。
     *
     * @param entityId       實體 UUID；不可為 null
     * @param target         目標位置；不可為 null
     * @param keepPassengers 是否保留乘客
     * @return 對應 {@link CompletionStage}；never null，future 必定完成
     */
    CompletionStage<TeleportResult> teleportEntity(UUID entityId,
                                                   LocationSnapshot target,
                                                   boolean keepPassengers);

    // -----------------------------------------------------------------
    // Lifecycle (test seam)
    // -----------------------------------------------------------------

    /**
     * 取得當前模組狀態（READY / DEGRADED / FAILED / NOT_INITIALIZED）。
     *
     * @return 永遠不為 null 的診斷快照（以 {@code String} 形式供測試使用，
     *         <strong>不屬於穩定 public API</strong>；後續插件用於診斷查詢）
     * @since Phase 10 (Plan §二十一未完成階段共同交付契約)
     */
    String getModuleStatus();

    /**
     * 取消所有 in-flight handle 並標記 stopped。測試 seam；正常 reload/disable
     * 不應直接呼叫。
     *
     * @since Phase 10 (Plan §二十一未完成階段共同交付契約)
     */
    void shutdown();
}
