package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Vault 外部整合 adapter 測試（reflection-only）。
 *
 * <p>以可注入 ClassLoader + PluginManager 隔離真實 classpath 與 Bukkit 狀態，
 * 驗證 Vault marker 缺失、plugin 未啟用、版本不符與可用四種探測路徑，以及
 * initialize / shutdown 生命週期。marker 缺失情境使用真實 Vault marker
 * （測試 classpath 必然不存在）；其餘情境使用測試 classpath 上必然存在的
 * {@code org.bukkit.plugin.Plugin} 作為 marker 以驅動 plugin / 版本邏輯。</p>
 */
@DisplayName("VaultIntegrationAdapter")
class VaultIntegrationAdapterTest {

    /** 測試 classpath 必然存在的 marker（paper-api 提供），用於模擬 Vault marker 存在。 */
    private static final String MARKER_PRESENT = "org.bukkit.plugin.Plugin";
    /** 測試 classpath 不存在的 marker（即真實 Vault marker）。 */
    private static final String MARKER_ABSENT = "net.milkbowl.vault.Vault";
    private static final String PLUGIN_NAME = "Vault";
    private static final String MIN_VERSION = "1.7.0";

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
    @DisplayName("integration id 固定為 vault")
    void idIsVault() {
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pluginManager());
        assertEquals("vault", adapter.getId());
    }

    @Test
    @DisplayName("marker 缺失 → initialize 失敗、不 active、getStatus 為非 null INIT_FAILED，且不查詢 PluginManager")
    void markerAbsent_notActive_andNeverQueriesPluginManager() {
        PluginManager pm = pluginManager();
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pm);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "marker 缺失時不得 active");
        IntegrationProbeResult result = adapter.getStatus();
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertNotNull(result.reason());
        verify(pm, never()).getPlugin(anyString());
    }

    @Test
    @DisplayName("plugin 未啟用 → initialize 失敗、不 active")
    void pluginDisabled_notActive() {
        Plugin installed = plugin("1.7.0", false);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "plugin 未啟用時不得 active");
    }

    @Test
    @DisplayName("版本不符 → initialize 失敗、不 active")
    void versionUnsupported_notActive() {
        Plugin installed = plugin("1.6.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "版本不符時不得 active");
    }

    @Test
    @DisplayName("可用（marker 存在 + plugin 啟用 + 版本符合）→ initialize 成功、active、status AVAILABLE")
    void available_activeAndAvailable() {
        Plugin installed = plugin("1.7.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        adapter.initialize();
        assertTrue(adapter.isActive());
        assertEquals(IntegrationStatus.AVAILABLE, adapter.getStatus().status());
        assertNotNull(adapter.getStatus().reason());
    }

    @Test
    @DisplayName("shutdown 後不 active")
    void shutdown_deactivates() {
        Plugin installed = plugin("1.7.0", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        VaultIntegrationAdapter adapter = new VaultIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        adapter.initialize();
        assertTrue(adapter.isActive());
        adapter.shutdown();
        assertFalse(adapter.isActive());
    }

    @Test
    @DisplayName("建構子：null classLoader 必須拋 IllegalArgumentException")
    void constructor_nullClassLoader_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new VaultIntegrationAdapter(null, pluginManager()));
    }

    @Test
    @DisplayName("建構子：null pluginManager 必須拋 IllegalArgumentException")
    void constructor_nullPluginManager_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new VaultIntegrationAdapter(getClass().getClassLoader(), null));
    }
}
