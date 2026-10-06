package com.smile.acelib.world;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.TaskTicket;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 世界操作安全 facade（Supported API）。
 *
 * <p>提供一組 Folia-safe 的世界操作入口，後續插件不需要直接接觸
 * {@code Bukkit.getWorld(uid)} / {@code World#getBlockAt(loc)} /
 * {@code Entity#teleport(loc)}（同步）等會破壞 Folia 執行緒假設的 API，
 * 改透過本介面取得 region-aware 操作結果。非同步傳送走
 * {@code Entity#teleportAsync} 平台 API，本介面只是把它包裝成可觀察的結果。</p>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>對外輸入僅接受 {@link LocationSnapshot} / {@link EntityReference} —
 *       不可傳入 {@code World} / {@code Location} / {@code Entity} / {@code Player}。</li>
 *   <li>每次呼叫於執行前重新驗證目標；失敗回對應 {@code ACELIB-WORLD-*} 結果，
 *       不丟例外給 caller（null 輸入除外，丟 {@link IllegalArgumentException}）。</li>
 *   <li>Teleport 為非同步：回 {@link CompletionStage} 等待實際 future 完成
 *       （success / false / exception / cancelled / partial）。</li>
 *   <li>模組於未啟用（{@link com.smile.acelib.AceLibApi#uninitialized()}）或停用後呼叫一律回
 *       {@code REJECTED + ACELIB-WORLD-001 / 002}；實作內部持有 in-flight
 *       handle 清單，shutdown 時拒絕新請求並取消既有。</li>
 * </ul>
 *
 * <h2>執行緒 / Folia 契約</h2>
 * <p>方塊與實體 mutate 操作必須在目標所屬 region context（Folia）或主執行緒
 * （Paper）內執行；本介面不承諾任意執行緒呼叫皆安全。實作層透過既有
 * 安全排程 API 安排 region 派送。</p>
 *
 * @see WorldErrorCode
 * @see WorldResult
 * @since 1.0.0
 */
public interface WorldService {

    /**
     * 延後傳送的預設到達容差（格）。
     *
     * <p>到達確認逐軸比較：實際座標與目標每軸誤差皆在此範圍內才算到達。
     * 傳送設定精確座標，被事件還原的位移通常遠大於此值，因此 0.5 格容忍平台微調，
     * 但不會把「仍在原處」誤判為到達。呼叫端可以傳入自訂容差覆寫。</p>
     */
    double DEFAULT_ARRIVAL_TOLERANCE = 0.5;

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
    // Deferred operations (after event handling)
    // -----------------------------------------------------------------

    /**
     * 通用延後操作：排到事件處理後的 tick，在玩家當下所在執行緒執行。
     *
     * <p>適用於「取消移動事件後要做事」這類情境：直接在事件處理內操作玩家，
     * 可能被後續的事件處理還原；經由本方法派送的動作會排到事件處理結束之後，
     * 在玩家當下所在的執行緒執行（Folia 走玩家 entity scheduler，
     * Paper 走主執行緒）。</p>
     *
     * <p>派送經由呼叫端傳入的 {@link SafeScheduler} 建立玩家作用域
     * （{@code scheduler.scopeFor(player)}），終態語意與該作用域一致：</p>
     * <ul>
     *   <li>完成（{@code COMPLETED}）— 動作已執行，值經終態攜回；</li>
     *   <li>失敗（{@code FAILED}）— 動作內拋錯（記 {@code ACELIB-SCHED-001}）；</li>
     *   <li>取消（{@code CANCELLED}）— 派送後玩家退服、作用域關閉或顯式取消，
     *       退服後保證不執行使用者程式；</li>
     *   <li>拒派（{@code REJECTED}）— 派送當下玩家已離線
     *       （{@code ACELIB-SCHED-002}）、服務已停用
     *       （{@code ACELIB-WORLD-002}），動作從未執行。</li>
     * </ul>
     *
     * <p>呼叫端以自己的 plugin 建立 scheduler
     * （{@code AceLibScheduler.create(this, api.getPlatform(),
     * api.getPlatformCapability())}），生命週期自行管理；本服務不會接管
     * 呼叫端的 scheduler，也不會在停用時取消已接受派送的動作
     * （停用後的新派送一律拒派）。動作內不可阻塞等待
     * （不可在 region 執行緒上 {@code await}），需要等待請在呼叫端自己的執行緒
     * 使用票據的 {@code await}。</p>
     *
     * @param playerId 玩家 UUID；不可為 null
     * @param action 延後執行的動作；不可為 null（在玩家所在執行緒執行一次）
     * @param scheduler 派送用的排程器（呼叫端擁有）；不可為 null
     * @param <T> 完成時攜回的值型別
     * @return 可觀察終態的票據；永不為 null
     * @throws IllegalArgumentException 任一參數為 null（帶
     * {@code ACELIB-WORLD-007}）
     * @since 1.4.0
     */
    <T> TaskTicket<T> deferForPlayer(UUID playerId,
                                     Supplier<T> action,
                                     SafeScheduler scheduler);

    /**
     * 延後傳送：排到事件處理後的 tick，在玩家所在執行緒傳送並確認到達。
     *
     * <p>與 {@link #teleportPlayer(UUID, LocationSnapshot, boolean)} 的差別有二：
     * 傳送本身延後到事件處理結束之後執行
     * （避開「取消移動事件後立即傳送被還原」），且完成時確認玩家真的到達目的地。
     * 容差採用 {@link #DEFAULT_ARRIVAL_TOLERANCE}。</p>
     *
     * <p>到達確認在傳送呼叫返回後、同一玩家執行緒內立即讀取實際位置：
     * 先比世界（UUID 相等才算同世界，不同即未到達），同世界再逐軸比座標。
     * 平台回報成功、但玩家實際不在目的地時，回報 {@code FAILED +
     * ACELIB-WORLD-018}，診斷含期望與實際位置。晚於確認時點才發生的第三方還原
     * 不保證偵測到。</p>
     *
     * <p>傳送被拒、拋錯、取消的結果原樣透出（{@code REJECTED / FAILED /
     * CANCELLED} 與原錯誤碼）；服務停用、玩家離線、世界不存在、派送被拒等
     * 失敗路徑與立即傳送一致，只是發生在延後派送的節點上。</p>
     *
     * @param playerId 玩家 UUID；不可為 null
     * @param target 目標位置；不可為 null
     * @param keepPassengers 是否保留乘客
     * @param scheduler 派送用的排程器（呼叫端擁有）；不可為 null
     * @return 對應 {@link CompletionStage}；never null，future 必定完成
     * @throws IllegalArgumentException 任一參數為 null（帶
     * {@code ACELIB-WORLD-007}）
     * @since 1.4.0
     */
    CompletionStage<TeleportResult> teleportPlayerDeferred(UUID playerId,
                                                           LocationSnapshot target,
                                                           boolean keepPassengers,
                                                           SafeScheduler scheduler);

    /**
     * 延後傳送（自訂容差）。
     *
     * <p>語意同 {@link #teleportPlayerDeferred(UUID, LocationSnapshot, boolean,
     * SafeScheduler)}，到達確認的每軸容差改為呼叫端指定值。</p>
     *
     * @param playerId 玩家 UUID；不可為 null
     * @param target 目標位置；不可為 null
     * @param keepPassengers 是否保留乘客
     * @param tolerance 每軸容差（格）；必須為有限非負數，
     * 非法值（NaN、無限、負數）丟 {@link IllegalArgumentException}（帶
     * {@code ACELIB-WORLD-007}）
     * @param scheduler 派送用的排程器（呼叫端擁有）；不可為 null
     * @return 對應 {@link CompletionStage}；never null，future 必定完成
     * @throws IllegalArgumentException 任一參數為 null 或容差非法
     * @since 1.4.0
     */
    CompletionStage<TeleportResult> teleportPlayerDeferred(UUID playerId,
                                                           LocationSnapshot target,
                                                           boolean keepPassengers,
                                                           double tolerance,
                                                           SafeScheduler scheduler);

    // -----------------------------------------------------------------
    // Lifecycle (test seam)
    // -----------------------------------------------------------------

    /**
     * 取得當前模組狀態（READY / DEGRADED / FAILED / NOT_INITIALIZED）。
     *
     * @return 永遠不為 null 的診斷快照（以 {@code String} 形式供測試使用，
     *         <strong>不屬於穩定 public API</strong>；後續插件用於診斷查詢）
     * @since 1.0.0
     */
    String getModuleStatus();

    /**
     * 取消所有 in-flight handle 並標記 stopped。測試 seam；正常 reload/disable
     * 不應直接呼叫。
     *
     * @since 1.0.0
     */
    void shutdown();
}
