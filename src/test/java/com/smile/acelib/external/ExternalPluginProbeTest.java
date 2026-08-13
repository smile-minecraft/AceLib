package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExternalPluginProbe 單元測試（Phase 13 W1）。
 *
 * <p>以可注入 ClassLoader + PluginManager 隔離真實 classpath 與 Bukkit 狀態，
 * 驗證五種整合狀態的分類邏輯。空 classloader 情境證明外部 API 類別完全不在
 * classpath 時回 NOT_INSTALLED 且不觸發 PluginManager 查詢（不觸發外部類別載入）。</p>
 *
 * <p>「marker 存在」情境使用真實 classpath 上的 {@code org.bukkit.plugin.Plugin}
 * 作為 marker（測試環境必然存在）；「marker 不存在」情境使用不存在的 FQCN。</p>
 */
@DisplayName("ExternalPluginProbe")
class ExternalPluginProbeTest {

    /** 測試 classpath 必然存在的 marker（paper-api 提供）。 */
    private static final String MARKER_PRESENT = "org.bukkit.plugin.Plugin";
    /** 測試 classpath 不存在的 marker。 */
    private static final String MARKER_ABSENT = "net.example.external.ExternalApi";
    private static final String PLUGIN_NAME = "ExamplePlugin";

    private PluginManager pluginManager() {
        return mock(PluginManager.class);
    }

    private Plugin plugin(String version, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        PluginDescriptionFile description = mock(PluginDescriptionFile.class);
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn(version);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    @Test
    @DisplayName("marker 存在 + plugin 存在且啟用 + 版本符合 → AVAILABLE")
    void probe_available_whenMarkerPresentPluginEnabledVersionOk() {
        Plugin installed = plugin("1.2.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.AVAILABLE, result.status());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains(PLUGIN_NAME), "reason 應含 plugin 名稱: " + result.reason());
    }

    @Test
    @DisplayName("空 classpath（marker 不存在）→ NOT_INSTALLED，且不觸發 PluginManager 查詢")
    void probe_notInstalled_whenMarkerAbsent_doesNotTouchPluginManager() {
        ClassLoader empty = new ClassLoader(null) {};
        PluginManager pm = pluginManager();
        ExternalPluginProbe probe = new ExternalPluginProbe(empty, pm);

        IntegrationProbeResult result = probe.probe(MARKER_ABSENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.NOT_INSTALLED, result.status());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains(MARKER_ABSENT),
            "reason 應說明缺少的 API class: " + result.reason());
        verify(pm, never()).getPlugin(anyString());
    }

    @Test
    @DisplayName("marker 存在 + plugin disabled → NOT_ENABLED")
    void probe_notEnabled_whenPluginDisabled() {
        Plugin installed = plugin("1.2.0", false);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.NOT_ENABLED, result.status());
        assertTrue(result.reason().contains(PLUGIN_NAME), "reason 應含 plugin 名稱: " + result.reason());
    }

    @Test
    @DisplayName("marker 存在 + plugin 版本過低 → VERSION_UNSUPPORTED，reason 含目前與需求版本")
    void probe_versionUnsupported_whenVersionTooLow() {
        Plugin installed = plugin("0.9.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.VERSION_UNSUPPORTED, result.status());
        assertTrue(result.reason().contains("0.9.0"), "reason 應含目前版本: " + result.reason());
        assertTrue(result.reason().contains("1.0.0"), "reason 應含需求版本: " + result.reason());
    }

    @Test
    @DisplayName("marker 存在 + plugin 不存在 → NOT_INSTALLED")
    void probe_notInstalled_whenPluginMissing() {
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(null);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.NOT_INSTALLED, result.status());
        assertTrue(result.reason().contains(PLUGIN_NAME), "reason 應含 plugin 名稱: " + result.reason());
    }

    @Test
    @DisplayName("版本格式容忍：1.0 與 1.0.0 視為相等 → AVAILABLE")
    void probe_versionComparison_toleratesComponentCountMismatch() {
        Plugin installed = plugin("1.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.AVAILABLE, result.status());
    }

    @Test
    @DisplayName("版本格式容忍：1.0.0-SNAPSHOT 視為 1.0.0 → AVAILABLE")
    void probe_versionComparison_toleratesSnapshotSuffix() {
        Plugin installed = plugin("1.0.0-SNAPSHOT", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.AVAILABLE, result.status());
    }

    @Test
    @DisplayName("版本無法比較（非數值）→ 保守 VERSION_UNSUPPORTED，reason 說明兩版本")
    void probe_versionComparison_unparseable_isConservative() {
        Plugin installed = plugin("beta", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.VERSION_UNSUPPORTED, result.status());
        assertTrue(result.reason().contains("beta"), "reason 應含目前版本: " + result.reason());
        assertTrue(result.reason().contains("1.0.0"), "reason 應含需求版本: " + result.reason());
    }

    @Test
    @DisplayName("marker 探測拋 LinkageError（缺少傳遞依賴）→ INIT_FAILED，且不觸發 PluginManager 查詢")
    void probe_linkageError_whenTransitiveClassMissing_isInitFailed_andDoesNotTouchPluginManager() {
        ClassLoader linkageFailing = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (MARKER_PRESENT.equals(name)) {
                    throw new NoClassDefFoundError(
                        "missing transitive dependency for " + name);
                }
                return super.loadClass(name);
            }
        };
        PluginManager pm = pluginManager();
        ExternalPluginProbe probe = new ExternalPluginProbe(linkageFailing, pm);

        IntegrationProbeResult result = probe.probe(MARKER_PRESENT, PLUGIN_NAME, "1.0.0");

        assertEquals(IntegrationStatus.INIT_FAILED, result.status(),
            "LinkageError 必須保守分類為 INIT_FAILED，不得逃逸崩潰 enable/reload");
        assertNotNull(result.reason());
        assertTrue(result.reason().contains(MARKER_PRESENT),
            "reason 應說明 marker class: " + result.reason());
        verify(pm, never()).getPlugin(anyString());
    }

    @Test
    @DisplayName("建構子：null classLoader 必須拋 IllegalArgumentException")
    void constructor_nullClassLoader_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExternalPluginProbe(null, pluginManager()));
    }

    @Test
    @DisplayName("建構子：null pluginManager 必須拋 IllegalArgumentException")
    void constructor_nullPluginManager_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExternalPluginProbe(getClass().getClassLoader(), null));
    }

    @Test
    @DisplayName("probe：null fqcn 必須拋 IllegalArgumentException")
    void probe_nullFqcn_throws() {
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pluginManager());
        assertThrows(IllegalArgumentException.class,
            () -> probe.probe(null, PLUGIN_NAME, "1.0.0"));
    }

    @Test
    @DisplayName("probe：null pluginName 必須拋 IllegalArgumentException")
    void probe_nullPluginName_throws() {
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pluginManager());
        assertThrows(IllegalArgumentException.class,
            () -> probe.probe(MARKER_PRESENT, null, "1.0.0"));
    }

    @Test
    @DisplayName("probe：null requiredMinVersion 必須拋 IllegalArgumentException")
    void probe_nullRequiredMinVersion_throws() {
        ExternalPluginProbe probe = new ExternalPluginProbe(getClass().getClassLoader(), pluginManager());
        assertThrows(IllegalArgumentException.class,
            () -> probe.probe(MARKER_PRESENT, PLUGIN_NAME, null));
    }
}