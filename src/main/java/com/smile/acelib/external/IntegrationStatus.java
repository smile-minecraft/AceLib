package com.smile.acelib.external;

/**
 * 外部插件整合狀態模型。
 *
 * <p>五種狀態各自附帶管理員可理解的預設 reason；動態 reason（例如版本比較的
 * 目前 / 需求版本）由 {@link IntegrationProbeResult} 承載，null / 空白 reason
 * 由 factory 給定預設。</p>
 *
 * <h2>狀態語意</h2>
 * <ul>
 *   <li>{@link #AVAILABLE} — 外部插件已安裝、已啟用且版本符合需求</li>
 *   <li>{@link #NOT_INSTALLED} — marker API class 不在 classpath，或 plugin 不存在</li>
 *   <li>{@link #NOT_ENABLED} — plugin 已安裝但未啟用</li>
 *   <li>{@link #VERSION_UNSUPPORTED} — 已安裝啟用但版本低於需求（或無法比較）</li>
 *   <li>{@link #INIT_FAILED} — 偵測 / 初始化失敗（例如 sandbox 拒絕 classpath 探測）</li>
 * </ul>
 *
 * @see ExternalPluginProbe
 * @see IntegrationProbeResult
 * @since 1.0.0
 */
public enum IntegrationStatus {

    /** 外部插件已安裝、已啟用且版本符合需求。 */
    AVAILABLE("external plugin is available and ready to use"),

    /** marker API class 不在 classpath，或 plugin 不存在。 */
    NOT_INSTALLED("external plugin is not installed"),

    /** plugin 已安裝但未啟用。 */
    NOT_ENABLED("external plugin is installed but not enabled"),

    /** 已安裝啟用但版本低於需求（或版本字串無法比較）。 */
    VERSION_UNSUPPORTED("external plugin version is not supported"),

    /** 偵測 / 初始化失敗。 */
    INIT_FAILED("external plugin failed to initialize");

    /** 管理員可理解的預設 reason；永不為 null。 */
    private final String defaultReason;

    IntegrationStatus(String defaultReason) {
        this.defaultReason = defaultReason;
    }

    /**
     * 取得本狀態的管理員可理解預設 reason。
     *
     * @return 非空 reason 字串
     */
    public String getDefaultReason() {
        return defaultReason;
    }

    /** @return {@link #AVAILABLE} 常數 */
    public static IntegrationStatus available() {
        return AVAILABLE;
    }

    /** @return {@link #NOT_INSTALLED} 常數 */
    public static IntegrationStatus notInstalled() {
        return NOT_INSTALLED;
    }

    /** @return {@link #NOT_ENABLED} 常數 */
    public static IntegrationStatus notEnabled() {
        return NOT_ENABLED;
    }

    /** @return {@link #VERSION_UNSUPPORTED} 常數 */
    public static IntegrationStatus versionUnsupported() {
        return VERSION_UNSUPPORTED;
    }

    /** @return {@link #INIT_FAILED} 常數 */
    public static IntegrationStatus initFailed() {
        return INIT_FAILED;
    }
}