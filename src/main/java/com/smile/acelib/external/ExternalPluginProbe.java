package com.smile.acelib.external;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * 外部插件整合狀態探測器。
 *
 * <p>以 classpath 反射（沿用 {@code PlatformDetector.isPresent} 模式）判定
 * marker API class 是否存在，再以 Bukkit {@link PluginManager} 查詢安裝 /
 * 啟用狀態，最後以版本範圍檢查判定整合狀態。</p>
 *
 * <h2>純 reflection 原則</h2>
 * <p>本類別不 import 任何外部插件 API 類別；marker class 一律以 String FQCN
 * 傳入，以 {@code Class.forName(fqcn, false, classLoader)} 探測（不觸發類別
 * 初始化）。外部 API 類別完全不在 classpath 時回 {@link IntegrationStatus#NOT_INSTALLED}，
 * 且不會觸發 {@link PluginManager} 查詢，仍可正常啟動。</p>
 *
 * <h2>判定順序</h2>
 * <ol>
 *   <li>marker class 不在 classpath → {@code NOT_INSTALLED}</li>
 *   <li>plugin 不存在 → {@code NOT_INSTALLED}</li>
 *   <li>plugin 未啟用 → {@code NOT_ENABLED}</li>
 *   <li>版本低於需求（或無法比較）→ {@code VERSION_UNSUPPORTED}</li>
 *   <li>其餘 → {@code AVAILABLE}</li>
 * </ol>
 *
 * <p>classpath 探測遭 sandbox 拒絕（{@link SecurityException}）時不靜默吞掉，
 * 以 {@code INIT_FAILED} + reason 呈現。</p>
 *
 * @see IntegrationStatus
 * @see IntegrationProbeResult
 */
public final class ExternalPluginProbe {

    private final ClassLoader classLoader;
    private final PluginManager pluginManager;

    /**
     * 建構子。
     *
     * @param classLoader   用於 classpath 探測的 classloader；不可為 null
     * @param pluginManager Bukkit plugin manager（安裝 / 啟用狀態查詢）；不可為 null
     * @throws IllegalArgumentException 任一參數為 null
     */
    public ExternalPluginProbe(ClassLoader classLoader, PluginManager pluginManager) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader must not be null");
        }
        if (pluginManager == null) {
            throw new IllegalArgumentException("pluginManager must not be null");
        }
        this.classLoader = classLoader;
        this.pluginManager = pluginManager;
    }

    /**
     * 探測單一外部插件整合狀態。
     *
     * @param fqcn               外部 API marker class 的完整名稱；不可為 null
     * @param pluginName         Bukkit plugin 名稱（PluginManager 查詢鍵）；不可為 null
     * @param requiredMinVersion 需求的最低版本字串；不可為 null
     * @return 永不為 null 的 {@link IntegrationProbeResult}
     * @throws IllegalArgumentException 任一參數為 null
     */
    public IntegrationProbeResult probe(String fqcn, String pluginName,
                                        String requiredMinVersion) {
        if (fqcn == null) {
            throw new IllegalArgumentException("fqcn must not be null");
        }
        if (pluginName == null) {
            throw new IllegalArgumentException("pluginName must not be null");
        }
        if (requiredMinVersion == null) {
            throw new IllegalArgumentException("requiredMinVersion must not be null");
        }

        IntegrationProbeResult markerResult = detectMarker(fqcn);
        if (markerResult != null) {
            return markerResult;
        }

        Plugin plugin = pluginManager.getPlugin(pluginName);
        if (plugin == null) {
            return IntegrationProbeResult.of(IntegrationStatus.NOT_INSTALLED,
                "external plugin '" + pluginName + "' is not installed on this server");
        }
        if (!plugin.isEnabled()) {
            return IntegrationProbeResult.of(IntegrationStatus.NOT_ENABLED,
                "external plugin '" + pluginName + "' is installed but not enabled");
        }

        String currentVersion = plugin.getDescription().getVersion();
        int comparison;
        try {
            comparison = VersionComparator.compare(currentVersion, requiredMinVersion);
        } catch (IllegalArgumentException e) {
            // 版本字串無法比較（非數值元件）：保守視為不支援，reason 說明兩版本
            return IntegrationProbeResult.of(IntegrationStatus.VERSION_UNSUPPORTED,
                "cannot compare versions for plugin '" + pluginName + "': current="
                    + currentVersion + ", required=" + requiredMinVersion
                    + " (" + e.getMessage() + ")");
        }
        if (comparison < 0) {
            return IntegrationProbeResult.of(IntegrationStatus.VERSION_UNSUPPORTED,
                "external plugin '" + pluginName + "' version " + currentVersion
                    + " is lower than required minimum " + requiredMinVersion);
        }
        return IntegrationProbeResult.of(IntegrationStatus.AVAILABLE,
            "external plugin '" + pluginName + "' version " + currentVersion
                + " is available");
    }

    /**
     * Classpath 反射探測（沿用 {@code PlatformDetector.isPresent} 模式）。
     *
     * <p>{@code Class.forName(fqcn, false, classLoader)} 的 {@code initialize=false}
     * 保證不觸發外部類別載入 / 靜態初始化。</p>
     *
     * <ul>
     *   <li>{@link ClassNotFoundException} — class 真的不在 classpath → 回
     *       {@code NOT_INSTALLED} 結果</li>
     *   <li>{@link IllegalStateException} — classloader 內部狀態異常（例如 MockBukkit
     *       的 {@code MockBukkitConfiguredPluginClassLoader}）→ 保守視為不在 → 回
     *       {@code NOT_INSTALLED} 結果</li>
     *   <li>{@link SecurityException} — sandbox 拒絕探測 → 不吞錯，回
     *       {@code INIT_FAILED} 結果</li>
     *   <li>{@link LinkageError}（含 {@link NoClassDefFoundError}）— marker class
     *       存在但傳遞依賴缺失導致連結失敗 → 保守視為初始化失敗，回
     *       {@code INIT_FAILED} 結果（不逃逸崩潰 enable/reload）</li>
     * </ul>
     *
     * @return marker 存在時回 null；否則回對應失敗結果
     */
    private IntegrationProbeResult detectMarker(String fqcn) {
        try {
            Class.forName(fqcn, false, classLoader);
            return null;
        } catch (ClassNotFoundException | IllegalStateException e) {
            return IntegrationProbeResult.of(IntegrationStatus.NOT_INSTALLED,
                "external plugin API class '" + fqcn
                    + "' is not present on the classpath; the external plugin is not installed");
        } catch (SecurityException e) {
            return IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "classpath probe for '" + fqcn + "' was denied: " + e.getMessage());
        } catch (LinkageError e) {
            // marker class 存在但傳遞依賴缺失（NoClassDefFoundError 等）導致連結失敗：
            // 保守視為初始化失敗，不逃逸崩潰 enable/reload。
            return IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "classpath probe for '" + fqcn + "' failed to link (missing transitive "
                    + "dependency): " + e);
        }
    }
}