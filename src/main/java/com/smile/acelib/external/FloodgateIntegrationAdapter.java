package com.smile.acelib.external;

import com.smile.acelib.bedrock.BedrockService;
import org.bukkit.plugin.PluginManager;

/**
 * Floodgate 外部整合 adapter（reflection-only 探測 + typed provider seam，Internal）。
 *
 * <p>以 {@link ExternalPluginProbe} 探測 Floodgate 的 marker API class、plugin 安裝 /
 * 啟用狀態與版本；只有探測結果為 {@link IntegrationStatus#AVAILABLE} 時
 * {@code initialize()} 才成功並進入 active 狀態，其餘狀態一律不得 active。</p>
 *
 * <p>與其他 reflection-only adapter 的差異：成功啟用後會建立 typed
 * {@link FloodgateBedrockPlayerLookup} 與 {@link FloodgateFormSender}（皆為
 * package-private seam）。Floodgate / Cumulus 型別只出現在 seam 實作內；本類別
 * 對外僅以 {@link com.smile.acelib.bedrock.BedrockService.PlayerLookup}
 * （只含 UUID / Optional 的 nested seam）交付查詢能力、以
 * {@link com.smile.acelib.form.FormService.FormSender} 交付表單發送能力，
 * 因此 Floodgate 缺席時 seam 實作類別不會被載入。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴。</p>
 *
 * @since 1.0.0
 */
public final class FloodgateIntegrationAdapter extends AbstractIntegrationAdapter {

    /** Floodgate marker API class 的完整名稱（用於 classpath 反射探測）。 */
    private static final String FLOODGATE_MARKER_FQCN = "org.geysermc.floodgate.api.FloodgateApi";

    /** Bukkit plugin 名稱（PluginManager 查詢鍵）。 */
    private static final String FLOODGATE_PLUGIN_NAME = "floodgate";

    /**
     * 需求的最低 Floodgate 版本。
     *
     * <p>選擇理由：本整合使用的 API 面（{@code isFloodgatePlayer} / {@code getPlayer} /
     * {@code LinkedPlayer}）在 2.x 全線存在；門檻設在 2.2.0 排除舊 1.x／2.0 早期安裝，
     * 同時允許 2.2.x release 與 snapshot（plugin 版本字串如
     * {@code "2.2.5-SNAPSHOT(b294-...)"} 經 {@link VersionComparator} 截斷 suffix 後
     * 可正確比較）。</p>
     */
    private static final String FLOODGATE_MIN_VERSION = "2.2.0";

    private final ExternalPluginProbe probe;
    private final String markerFqcn;
    private final String pluginName;
    private final String requiredMinVersion;
    private volatile BedrockService.PlayerLookup playerLookup;
    private volatile com.smile.acelib.form.FormService.FormSender formSender;

    /**
     * 建構子：以固定的 Floodgate marker / plugin 名稱 / 最低版本建立 adapter。
     *
     * @param classLoader   用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager Bukkit plugin manager（安裝 / 啟用狀態查詢）；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    public FloodgateIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager) {
        this(classLoader, pluginManager,
            FLOODGATE_MARKER_FQCN, FLOODGATE_PLUGIN_NAME, FLOODGATE_MIN_VERSION);
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
    FloodgateIntegrationAdapter(ClassLoader classLoader, PluginManager pluginManager,
                                String markerFqcn, String pluginName, String requiredMinVersion) {
        super("floodgate");
        this.probe = new ExternalPluginProbe(classLoader, pluginManager);
        this.markerFqcn = markerFqcn;
        this.pluginName = pluginName;
        this.requiredMinVersion = requiredMinVersion;
    }

    @Override
    protected IntegrationProbeResult doInitialize() {
        IntegrationProbeResult result = probe.probe(markerFqcn, pluginName, requiredMinVersion);
        if (result.status() == IntegrationStatus.AVAILABLE) {
            // marker 已確認存在，載入 typed seam 實作是安全的；
            // api instance 以 supplier 延遲綁定（查詢時才呼叫 getInstance()，
            // reload 後自動取到新 instance），建構失敗視為初始化失敗
            this.playerLookup = new FloodgateBedrockPlayerLookup(
                org.geysermc.floodgate.api.FloodgateApi::getInstance);
            this.formSender = new FloodgateFormSender(
                org.geysermc.floodgate.api.FloodgateApi::getInstance);
            return result;
        }
        // 缺席 / 未啟用 / 版本不符不得 active：轉為啟用失敗，
        // reason 保留底層探測結果供管理員判讀（缺席與異常是不同狀態，語意不混寫）
        throw new IntegrationLifecycleException(
            "Floodgate integration is not available: " + result.status() + " - " + result.reason(),
            null);
    }

    @Override
    protected void doShutdown() {
        // reflection-only 探測與 stateless lookup / sender 不持有需要顯式釋放的外部資源
        this.playerLookup = null;
        this.formSender = null;
    }

    /**
     * 取得成功啟用後建立的 typed 查詢 seam。
     *
     * <p>本類別為 Internal 實作細節；此方法僅供 plugin 接線
     * （{@code AceLibPlugin.bindBedrockService}）使用，非消費者契約。</p>
     *
     * @return typed lookup；未啟用或已停用時為 null
     */
    public BedrockService.PlayerLookup playerLookup() {
        return playerLookup;
    }

    /**
     * 取得成功啟用後建立的表單發送 seam。
     *
     * <p>本類別為 Internal 實作細節；此方法僅供 plugin 接線
     * （{@code AceLibPlugin.bindBedrockService}）使用，非消費者契約。</p>
     *
     * @return 表單發送 seam；未啟用或已停用時為 null
     */
    public com.smile.acelib.form.FormService.FormSender formSender() {
        return formSender;
    }
}
