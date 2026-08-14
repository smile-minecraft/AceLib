package com.smile.acelib.external;

import org.bukkit.plugin.PluginManager;

/**
 * Vault 外部整合 adapter（reflection-only，Internal）。
 *
 * <p>以 {@link ExternalPluginProbe} 探測 Vault 的 marker API class、plugin 安裝 / 啟用
 * 狀態與版本；只有探測結果為 {@link IntegrationStatus#AVAILABLE} 時 {@code initialize()}
 * 才成功並進入 active 狀態，其餘狀態（marker 缺失、plugin 未啟用、版本不符）一律不得
 * active。</p>
 *
 * <p>本 adapter 不 import 任何 Vault API 類別；Vault marker / plugin 名稱 / 最低版本皆以
 * 字串常數表示，完全透過 classpath 反射與 Bukkit {@link PluginManager} 探測，因此外部
 * Vault 類別不在 classpath 時仍可安全啟動。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴。</p>
 *
 * @since 1.0.0
 */
public final class VaultIntegrationAdapter extends AbstractIntegrationAdapter {

    /** Vault marker API class 的完整名稱（用於 classpath 反射探測）。 */
    private static final String VAULT_MARKER_FQCN = "net.milkbowl.vault.Vault";

    /** Bukkit plugin 名稱（PluginManager 查詢鍵）。 */
    private static final String VAULT_PLUGIN_NAME = "Vault";

    /** 需求的最低 Vault 版本。 */
    private static final String VAULT_MIN_VERSION = "1.7.0";

    private final ExternalPluginProbe probe;
    private final String markerFqcn;
    private final String pluginName;
    private final String requiredMinVersion;

    /**
     * 建構子：以固定的 Vault marker / plugin 名稱 / 最低版本建立 adapter。
     *
     * @param classLoader   用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager Bukkit plugin manager（安裝 / 啟用狀態查詢）；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    public VaultIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager) {
        this(classLoader, pluginManager, VAULT_MARKER_FQCN, VAULT_PLUGIN_NAME, VAULT_MIN_VERSION);
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
    VaultIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager,
                            String markerFqcn, String pluginName, String requiredMinVersion) {
        super("vault");
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
            "Vault integration is not available: " + result.status() + " - " + result.reason(), null);
    }

    @Override
    protected void doShutdown() {
        // Vault 整合不持有需要顯式釋放的外部資源；reflection-only 探測無外部 API 呼叫。
    }
}
