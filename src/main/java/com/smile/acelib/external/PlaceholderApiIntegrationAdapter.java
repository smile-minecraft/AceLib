package com.smile.acelib.external;

import org.bukkit.plugin.PluginManager;

/**
 * PlaceholderAPI 外部整合 adapter（reflection-only，Internal）。
 *
 * <p>以 {@link ExternalPluginProbe} 探測 PlaceholderAPI 的 marker API class、plugin 安裝 / 啟用
 * 狀態與版本；只有探測結果為 {@link IntegrationStatus#AVAILABLE} 時 {@code initialize()}
 * 才成功並進入 active 狀態，其餘狀態（marker 缺失、plugin 未啟用、版本不符）一律不得
 * active。</p>
 *
 * <p>本 adapter 不 import 任何 PlaceholderAPI API 類別；PlaceholderAPI marker / plugin 名稱 /
 * 最低版本皆以字串常數表示，完全透過 classpath 反射與 Bukkit {@link PluginManager} 探測，因此外部
 * PlaceholderAPI 類別不在 classpath 時仍可安全啟動。</p>
 *
 * <p>最低相容版本選擇保守值 {@code 2.11.0}：PlaceholderAPI 自 2.11.x 起即為廣泛部署且 API 相容的
 * 版本；低於此版本視為不支援，由 {@link ExternalPluginProbe} 回傳
 * {@link IntegrationStatus#VERSION_UNSUPPORTED}。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴。</p>
 *
 * @since 1.0.0
 */
public final class PlaceholderApiIntegrationAdapter extends AbstractIntegrationAdapter {

    /** PlaceholderAPI marker API class 的完整名稱（用於 classpath 反射探測）。 */
    private static final String PLACEHOLDERAPI_MARKER_FQCN = "me.clip.placeholderapi.PlaceholderAPI";

    /** Bukkit plugin 名稱（PluginManager 查詢鍵）。 */
    private static final String PLACEHOLDERAPI_PLUGIN_NAME = "PlaceholderAPI";

    /** 需求的最低 PlaceholderAPI 版本（保守相容值）。 */
    private static final String PLACEHOLDERAPI_MIN_VERSION = "2.11.0";

    private final ExternalPluginProbe probe;
    private final String markerFqcn;
    private final String pluginName;
    private final String requiredMinVersion;

    /**
     * 建構子：以固定的 PlaceholderAPI marker / plugin 名稱 / 最低版本建立 adapter。
     *
     * @param classLoader   用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager Bukkit plugin manager（安裝 / 啟用狀態查詢）；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    public PlaceholderApiIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager) {
        this(classLoader, pluginManager,
            PLACEHOLDERAPI_MARKER_FQCN, PLACEHOLDERAPI_PLUGIN_NAME, PLACEHOLDERAPI_MIN_VERSION);
    }

    /**
     * 完整建構子（package-private，供測試注入 marker / plugin / 版本以驅動各探測路徑）。
     *
     * @param classLoader         用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager       Bukkit plugin manager；不可為 null
     * @param markerFqcn          marker API class 完整名稱；不可為 null
     * @param pluginName          Bukkit plugin 名稱；不可為 null
     * @param requiredMinVersion  需求的最低版本字串；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    PlaceholderApiIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager,
                                     String markerFqcn, String pluginName, String requiredMinVersion) {
        super("placeholderapi");
        this.probe = new ExternalPluginProbe(classLoader, pluginManager);
        this.markerFqcn = markerFqcn;
        this.pluginName = pluginName;
        this.requiredMinVersion = requiredMinVersion;
    }

    @Override
    protected IntegrationProbeResult doInitialize() {
        IntegrationProbeResult result = probe.probe(markerFqcn, pluginName, requiredMinVersion);
        if (result.status() == IntegrationStatus.AVAILABLE) {
            return result;
        }
        throw new IntegrationLifecycleException(
            "PlaceholderAPI integration is not available: " + result.status() + " - " + result.reason(), null);
    }

    @Override
    protected void doShutdown() {
        // PlaceholderAPI 整合不持有需要顯式釋放的外部資源；reflection-only 探測無外部 API 呼叫。
    }
}
