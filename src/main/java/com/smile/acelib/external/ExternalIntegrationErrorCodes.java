package com.smile.acelib.external;

/**
 * 外部整合錯誤代碼常數（{@code ACELIB-EXT-*} 格式）。
 *
 * <p>錯誤代碼格式為 {@code ACELIB-<AREA>-<CODE>}，本類別 AREA=EXT。
 * 涵蓋整合初始化失敗、版本不支援、未安裝/未啟用與清理失敗；
 * 另含 facade 層級的服務未啟用 / 已停用代碼，供
 * {@link ExternalIntegrationService#NOT_READY} / {@link ExternalIntegrationService#SHUTDOWN} 使用。</p>
 *
 * <p>所有常數皆為 {@code ACELIB-EXT-<三位數字>} 形式，便於日誌與診斷報告聚合。</p>
 */
public final class ExternalIntegrationErrorCodes {

    private ExternalIntegrationErrorCodes() {
        // utility class
    }

    /** 整合初始化失敗。 */
    public static final String ACELIB_EXT_INIT_FAILED = "ACELIB-EXT-001";

    /** 外部插件版本不支援（低於需求或無法比較）。 */
    public static final String ACELIB_EXT_VERSION_UNSUPPORTED = "ACELIB-EXT-002";

    /** 外部插件未安裝或未啟用。 */
    public static final String ACELIB_EXT_NOT_INSTALLED_OR_ENABLED = "ACELIB-EXT-003";

    /** 整合資源清理失敗。 */
    public static final String ACELIB_EXT_CLEANUP_FAILED = "ACELIB-EXT-004";

    /** 整合服務尚未啟用（facade {@code NOT_READY}）。 */
    public static final String ACELIB_EXT_SERVICE_NOT_READY = "ACELIB-EXT-005";

    /** 整合服務已停用（facade {@code SHUTDOWN}）。 */
    public static final String ACELIB_EXT_SERVICE_SHUTDOWN = "ACELIB-EXT-006";
}
