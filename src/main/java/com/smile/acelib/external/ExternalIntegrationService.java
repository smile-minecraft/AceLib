package com.smile.acelib.external;

/**
 * 外部插件整合服務對外 facade（canonical public API）。
 *
 * <p>提供外部插件整合狀態的查詢入口，後續插件不需要直接接觸
 * {@link ExternalPluginProbe} / registry / adapter 生命週期，改透過本介面
 * 查詢某個整合（例如 vault / luckperms / placeholderapi）目前的狀態與 reason。</p>
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
     * 取得當前模組狀態（{@code READY} / {@code FAILED} / {@code NOT_INITIALIZED}）。
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
}