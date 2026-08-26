package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.form.FormService;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Floodgate 表單發送 seam 的 adapter 接線測試：探測 AVAILABLE 時建立 typed
 * {@code FormService.FormSender}；shutdown 後清除。比照
 * {@code FloodgateIntegrationAdapterTest} 的隔離手法（可注入 ClassLoader +
 * PluginManager）。
 */
@DisplayName("FloodgateIntegrationAdapter 表單發送 seam 接線")
class FloodgateFormSeamWiringTest {

    /** 測試 classpath 必然存在的 marker（testImplementation 提供真實 floodgate api）。 */
    private static final String MARKER_PRESENT = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String PLUGIN_NAME = "floodgate";
    private static final String MIN_VERSION = "2.2.0";

    private PluginManager pluginManager() {
        return mock(PluginManager.class);
    }

    private Plugin enabledFloodgatePlugin() {
        Plugin plugin = mock(Plugin.class);
        PluginDescriptionFile description = mock(PluginDescriptionFile.class);
        when(plugin.getDescription()).thenReturn(description);
        when(description.getVersion()).thenReturn("2.2.5-SNAPSHOT(b294-20b6c25)");
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    @Test
    @DisplayName("探測 AVAILABLE：formSender() 非 null 且為 FormService.FormSender")
    void available_adapterExposesFormSender() throws Exception {
        PluginManager pm = pluginManager();
        Plugin floodgate = enabledFloodgatePlugin();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(floodgate);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);

        adapter.initialize();

        FormService.FormSender sender = adapter.formSender();
        assertNotNull(sender, "adapter 啟用後必須暴露表單發送 seam");
    }

    @Test
    @DisplayName("shutdown 後 formSender() 清為 null（比照 playerLookup 生命週期）")
    void shutdown_clearsFormSender() throws Exception {
        PluginManager pm = pluginManager();
        Plugin floodgate = enabledFloodgatePlugin();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(floodgate);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        adapter.initialize();
        assertNotNull(adapter.formSender());

        adapter.shutdown();

        assertNull(adapter.formSender(), "shutdown 後表單發送 seam 必須清除");
        assertEquals(null, adapter.playerLookup(), "既有 lookup 清除語意不受影響");
    }

    @Test
    @DisplayName("初始化失敗（marker 缺失）：不暴露 formSender")
    void initFailed_noFormSenderExposed() {
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pluginManager(),
            "com.example.missing.FloodgateApi", PLUGIN_NAME, MIN_VERSION);

        try {
            adapter.initialize();
        } catch (IntegrationLifecycleException expected) {
            // 探測失敗即為本情境的前置條件
        }

        assertNull(adapter.formSender(), "未啟用時不得暴露表單發送 seam");
    }
}
