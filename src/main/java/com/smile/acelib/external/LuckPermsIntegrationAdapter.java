package com.smile.acelib.external;

import org.bukkit.plugin.PluginManager;

/**
 * LuckPerms 外部整合 adapter（reflection-only）。
 *
 * <p>以 {@link ExternalPluginProbe} 探測 LuckPerms 的 marker API class、plugin 安裝 / 啟用
 * 狀態與版本；只有探測結果為 {@link IntegrationStatus#AVAILABLE} 時 {@code initialize()}
 * 才成功並進入 active 狀態，其餘狀態（marker 缺失、plugin 未啟用、版本不符）一律不得
 * active。</p>
 *
 * <p>本 adapter 不 import 任何 LuckPerms API 類別；LuckPerms marker / plugin 名稱 / 最低版本皆以
 * 字串常數表示，完全透過 classpath 反射與 Bukkit {@link PluginManager} 探測，因此外部
 * LuckPerms 類別不在 classpath 時仍可安全啟動。</p>
 *
 * <p>最低相容版本選擇保守值 {@code 5.4.0}：LuckPerms 自 5.x 起即提供穩定的
 * {@code net.luckperms.api.LuckPerms} marker 介面，5.4.0 為廣泛部署且 API 相容的版本；
 * 低於此版本視為不支援，由 {@link ExternalPluginProbe} 回傳 {@link IntegrationStatus#VERSION_UNSUPPORTED}。</p>
 */
public final class LuckPermsIntegrationAdapter extends AbstractIntegrationAdapter {

    /** LuckPerms marker API class 的完整名稱（用於 classpath 反射探測）。 */
    private static final String LUCKPERMS_MARKER_FQCN = "net.luckperms.api.LuckPerms";

    /** Bukkit plugin 名稱（PluginManager 查詢鍵）。 */
    private static final String LUCKPERMS_PLUGIN_NAME = "LuckPerms";

    /** 需求的最低 LuckPerms 版本（保守相容值）。 */
    private static final String LUCKPERMS_MIN_VERSION = "5.4.0";

    private final ExternalPluginProbe probe;
    private final String markerFqcn;
    private final String pluginName;
    private final String requiredMinVersion;

    /**
     * 建構子：以固定的 LuckPerms marker / plugin 名稱 / 最低版本建立 adapter。
     *
     * @param classLoader   用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager Bukkit plugin manager（安裝 / 啟用狀態查詢）；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    public LuckPermsIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager) {
        this(classLoader, pluginManager, LUCKPERMS_MARKER_FQCN, LUCKPERMS_PLUGIN_NAME, LUCKPERMS_MIN_VERSION);
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
    LuckPermsIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager,
                                String markerFqcn, String pluginName, String requiredMinVersion) {
        super("luckperms");
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
            "LuckPerms integration is not available: " + result.status() + " - " + result.reason(), null);
    }

    @Override
    protected void doShutdown() {
        // LuckPerms 整合不持有需要顯式釋放的外部資源；reflection-only 探測無外部 API 呼叫。
    }
}
