package com.smile.acelib.external;

import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * 外部插件整合服務對外 facade（Supported API）。
 *
 * <p>提供外部插件整合狀態的查詢入口，以及經濟／權限／佔位符／建造查詢四類
 * 業務門面，後續插件不需要直接接觸 {@link ExternalPluginProbe} / registry /
 * adapter 生命週期或外部 API 型別，改透過本介面查詢與操作。</p>
 *
 * <p>四類業務皆只包裝外部提供者：不可用時回明確的 {@code UNAVAILABLE} 結果
 * （不得默認允許、不得視為成功）；提供者可被下游以 AceLib 自有 SPI 型別替換。</p>
 *
 * <h2>三態安全 facade（比照 {@code WorldService} / {@code GuiService}）</h2>
 * <ul>
 *   <li>未啟用（uninitialized）— {@link com.smile.acelib.AceLibApi#uninitialized()} 內建
 *       {@link #NOT_READY} unavailable facade；查詢一律回
 *       {@code INIT_FAILED} 結果，模組狀態為 {@code NOT_INITIALIZED}</li>
 *   <li>已啟用（ready）— 由 {@link com.smile.acelib.AceLibApi#ready(String, com.smile.acelib.platform.Platform, com.smile.acelib.platform.PlatformCapability,
 *       com.smile.acelib.world.WorldService, com.smile.acelib.gui.GuiService, ExternalIntegrationService, BooleanSupplier, Runnable)}
 *       傳入實際實作</li>
 *   <li>已停用（shutDown）— {@link com.smile.acelib.AceLibApi#shutDown(com.smile.acelib.world.WorldService, com.smile.acelib.gui.GuiService)}
 *       內建 {@link #SHUTDOWN} unavailable facade；模組狀態為 {@code FAILED}</li>
 * </ul>
 *
 * <p>後續插件可放心呼叫所有查詢方法，無需 null 判斷。</p>
 *
 * <h2>code 常數說明</h2>
 * <p>{@link #NOT_READY} / {@link #SHUTDOWN} 為 {@code ACELIB-EXT-*} 常數（見
 * {@link ExternalIntegrationErrorCodes}）；facade 簽章與語意不變。</p>
 *
 * @see IntegrationStatus
 * @see IntegrationProbeResult
 * @see ExternalIntegrationErrorCodes
 * @since 1.0.0
 */
public interface ExternalIntegrationService {

    /** 服務尚未啟用（uninitialized / bind 前）的 facade code（ACELIB-EXT-* 常數）。 */
    String NOT_READY = ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_NOT_READY;

    /** 服務已停用（onDisable / reload 失敗）的 facade code（ACELIB-EXT-* 常數）。 */
    String SHUTDOWN = ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_SHUTDOWN;

    /**
     * Unavailable factory：建立未啟用 / 已停用狀態下的可診斷 facade。
     *
     * <p>實作類別 {@code ExternalIntegrationServiceUnavailableImpl} 為
     * package-private（不暴露為 public API）；本方法為內部 wiring 與下游插件
     * 取得 unavailable 實例的唯一 public 入口，回傳型別為介面本身。
     * {@code code} 必須為 {@link #NOT_READY} 或 {@link #SHUTDOWN}，否則丟
     * {@link IllegalArgumentException}（不吞錯）。</p>
     *
     * @param code 狀態碼；不可為 null，且必須為 NOT_READY 或 SHUTDOWN
     * @return 新的 {@link ExternalIntegrationService} unavailable 實作實例；never null
     * @throws IllegalArgumentException 當 {@code code} 為 null 或不是 NOT_READY / SHUTDOWN
     */
    static ExternalIntegrationService forUnavailable(String code) {
        return new ExternalIntegrationServiceUnavailableImpl(code);
    }

    /**
     * 查詢指定外部整合目前的狀態。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → 回 {@link IntegrationStatus#INIT_FAILED} 結果，
     *       reason 說明服務不可用</li>
     *   <li>已啟用 → 回 {@link ExternalPluginProbe} / registry 判定的
     *       {@link IntegrationProbeResult}</li>
     * </ul>
     *
     * @param integrationId 整合識別字串（例如 {@code "vault"}）；不可為 null
     * @return 永不為 null 的 {@link IntegrationProbeResult}
     * @throws IllegalArgumentException 當 {@code integrationId} 為 null
     */
    IntegrationProbeResult getStatus(String integrationId);

    /**
     * 取得當前模組狀態（{@code AVAILABLE}／{@code DEGRADED}／{@code FAILED}／
     * {@code SHUTDOWN}／{@code NOT_INITIALIZED} 的聚合結果）。
     *
     * <p>用於診斷；不屬於穩定 public API。</p>
     */
    String getModuleStatus();

    /**
     * 停用服務並釋放資源（adapter 生命週期由後續實作負責）。
     *
     * <p>unavailable facade 為 no-op（冪等）。</p>
     */
    void shutdown();

    // ----- 外部整合門面：經濟（只包裝，不自製） -----

    /**
     * 查詢玩家經濟餘額。
     *
     * <p>提供者缺席／停用時回 {@code UNAVAILABLE}（餘額為 NaN，不等於 0）；
     * 提供者回失敗或呼叫拋例外時回 {@code FAILED}。呼叫端可在非同步執行緒呼叫，
     * 本門面不觸碰世界／實體狀態。</p>
     *
     * @param player 查詢對象；不可為 null
     * @return 永不為 null 的 {@link EconomyResult}
     * @throws IllegalArgumentException 當 {@code player} 為 null
     * @since 1.4.0
     */
    EconomyResult getBalance(OfflinePlayer player);

    /**
     * 從玩家帳戶扣款。
     *
     * <p>不可用語意同 {@link #getBalance(OfflinePlayer)}；金額必須為有限非負數。
     * AceLib 不做扣款去重或持久操作紀錄，重複呼叫的後果由提供者語意決定。</p>
     *
     * @param player 扣款對象；不可為 null
     * @param amount 金額；必須為有限非負數
     * @return 永不為 null 的 {@link EconomyResult}（成功時餘額為扣款後餘額）
     * @throws IllegalArgumentException 當 {@code player} 為 null 或金額不合法
     * @since 1.4.0
     */
    EconomyResult withdraw(OfflinePlayer player, double amount);

    /**
     * 向玩家帳戶入帳。
     *
     * <p>不可用語意同 {@link #getBalance(OfflinePlayer)}；金額必須為有限非負數。</p>
     *
     * @param player 入帳對象；不可為 null
     * @param amount 金額；必須為有限非負數
     * @return 永不為 null 的 {@link EconomyResult}（成功時餘額為入帳後餘額）
     * @throws IllegalArgumentException 當 {@code player} 為 null 或金額不合法
     * @since 1.4.0
     */
    EconomyResult deposit(OfflinePlayer player, double amount);

    /**
     * 替換經濟提供者（下游自有實作覆寫內建）。
     *
     * @param provider 新提供者；不可為 null
     * @throws IllegalArgumentException 當 {@code provider} 為 null
     * @throws IllegalStateException 當服務已停用
     * @since 1.4.0
     */
    void setEconomyProvider(EconomyProvider provider);

    /**
     * 清除下游覆寫，恢復內建經濟提供者（冪等；已停用時為 no-op）。
     *
     * @since 1.4.0
     */
    void clearEconomyProvider();

    // ----- 外部整合門面：權限（只包裝，不自製） -----

    /**
     * 查詢玩家主要群組、所屬群組與當前情境。
     *
     * <p>提供者缺席／停用時回 {@code UNAVAILABLE}；查無玩家或呼叫拋例外時回
     * {@code FAILED}（群組為空，不等於「無群組」，不得以空集合做授權判斷）。
     * 呼叫端可在非同步執行緒呼叫，本門面不觸碰世界／實體狀態。</p>
     *
     * @param playerId 玩家識別；不可為 null
     * @return 永不為 null 的 {@link PermissionResult}
     * @throws IllegalArgumentException 當 {@code playerId} 為 null
     * @since 1.4.0
     */
    PermissionResult getPermissionGroups(UUID playerId);

    /**
     * 替換權限提供者（下游自有實作覆寫內建）。
     *
     * @param provider 新提供者；不可為 null
     * @throws IllegalArgumentException 當 {@code provider} 為 null
     * @throws IllegalStateException 當服務已停用
     * @since 1.4.0
     */
    void setPermissionProvider(PermissionProvider provider);

    /**
     * 清除下游覆寫，恢復內建權限提供者（冪等；已停用時為 no-op）。
     *
     * @since 1.4.0
     */
    void clearPermissionProvider();

    // ----- 外部整合門面：佔位符（下游註冊自有佔位符） -----

    /**
     * 註冊一個下游自有佔位符。
     *
     * <p>提供者缺席／停用時回 {@code UNAVAILABLE}；識別重複或底層註冊被拒時回
     * {@code FAILED}。停用／reload 時門面負責清理全部註冊，不殘留。</p>
     *
     * @param identifier 佔位符識別；不可為 null／空白
     * @param handler 解析處理器；不可為 null
     * @return 永不為 null 的 {@link ExternalOperationResult}
     * @throws IllegalArgumentException 當識別或處理器不合法
     * @since 1.4.0
     */
    ExternalOperationResult registerPlaceholder(String identifier, PlaceholderHandler handler);

    /**
     * 取消註冊一個下游自有佔位符（不存在視為操作失敗，不拋例外）。
     *
     * @param identifier 佔位符識別；不可為 null／空白
     * @return 永不為 null 的 {@link ExternalOperationResult}
     * @throws IllegalArgumentException 當識別不合法
     * @since 1.4.0
     */
    ExternalOperationResult unregisterPlaceholder(String identifier);

    /**
     * 替換佔位符提供者（下游自有實作覆寫內建；被替換的內建註冊由門面清理）。
     *
     * @param provider 新提供者；不可為 null
     * @throws IllegalArgumentException 當 {@code provider} 為 null
     * @throws IllegalStateException 當服務已停用
     * @since 1.4.0
     */
    void setPlaceholderProvider(PlaceholderProvider provider);

    /**
     * 清除下游覆寫，恢復內建佔位符提供者（冪等；已停用時為 no-op）。
     *
     * @since 1.4.0
     */
    void clearPlaceholderProvider();

    // ----- 外部整合門面：建造查詢（通用 SPI，本期無內建 adapter） -----

    /**
     * 查詢玩家能否在指定位置建造。
     *
     * <p>無提供者（含本期：AceLib 不內建任何區域保護 adapter）或服務停用時回
     * {@code UNAVAILABLE} 且 {@code allowed()} 為 false；提供者呼叫拋例外時回
     * {@code FAILED} 且 {@code allowed()} 為 false。不可用不得被解讀為允許。
     * 查詢資料由呼叫端提供，本門面不觸碰世界／實體狀態。</p>
     *
     * @param playerId 玩家識別；不可為 null
     * @param worldName 世界名稱；不可為 null／空白
     * @param x 方塊 X 座標
     * @param y 方塊 Y 座標
     * @param z 方塊 Z 座標
     * @return 永不為 null 的 {@link BuildCheckResult}
     * @throws IllegalArgumentException 當識別或世界名稱不合法
     * @since 1.4.0
     */
    BuildCheckResult canBuild(UUID playerId, String worldName, int x, int y, int z);

    /**
     * 註冊建造查詢提供者（下游包裝自選的區域保護 plugin）。
     *
     * @param provider 新提供者；不可為 null
     * @throws IllegalArgumentException 當 {@code provider} 為 null
     * @throws IllegalStateException 當服務已停用
     * @since 1.4.0
     */
    void setBuildCheckProvider(BuildCheckProvider provider);

    /**
     * 清除建造查詢提供者，恢復為無提供者（冪等；已停用時為 no-op）。
     *
     * @since 1.4.0
     */
    void clearBuildCheckProvider();
}