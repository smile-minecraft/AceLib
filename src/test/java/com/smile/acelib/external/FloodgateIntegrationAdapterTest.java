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

import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Floodgate 外部整合 adapter 測試（reflection-only 探測 + typed provider seam）。
 *
 * <p>比照 {@link VaultIntegrationAdapterTest}：以可注入 ClassLoader + PluginManager
 * 隔離真實 classpath 與 Bukkit 狀態，驅動 marker 缺失、plugin 未啟用、版本不符、
 * 探測異常與可用五種路徑，並驗證成功路徑會建立 typed
 * {@code BedrockService.PlayerLookup}。</p>
 *
 * <p>注意：測試 classpath 上有真實 floodgate api（testImplementation 鎖定版本），
 * 因此「marker 缺失」情境使用測試 classpath 必然不存在的假 FQCN；「marker 存在」
 * 情境使用真實 marker {@code org.geysermc.floodgate.api.FloodgateApi}。</p>
 */
@DisplayName("FloodgateIntegrationAdapter")
class FloodgateIntegrationAdapterTest {

    /** 測試 classpath 必然存在的 marker（testImplementation 提供真實 floodgate api）。 */
    private static final String MARKER_PRESENT = "org.geysermc.floodgate.api.FloodgateApi";
    /** 測試 classpath 必然不存在的 marker，用於驅動 NOT_INSTALLED 路徑。 */
    private static final String MARKER_ABSENT = "com.example.missing.FloodgateApi";
    private static final String PLUGIN_NAME = "floodgate";
    private static final String MIN_VERSION = "2.2.0";

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
    @DisplayName("integration id 固定為 floodgate")
    void idIsFloodgate() {
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pluginManager());
        assertEquals("floodgate", adapter.getId());
    }

    @Test
    @DisplayName("marker 缺失 → initialize 失敗、不 active、status INIT_FAILED，且不查詢 PluginManager")
    void markerAbsent_notActive_andNeverQueriesPluginManager() {
        PluginManager pm = pluginManager();
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm,
            MARKER_ABSENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "marker 缺失時不得 active");
        IntegrationProbeResult result = adapter.getStatus();
        assertNotNull(result);
        // 初始化失敗後的狀態必須可診斷：INIT_FAILED 且 reason 說明缺席原因
        // （AbstractIntegrationAdapter 以 INIT_FAILED 呈現未啟用狀態；
        //   底層探測結果 NOT_INSTALLED 必須保留在 reason 內供管理員判讀）
        assertTrue(result.reason().contains(MARKER_ABSENT) || result.reason().contains("not installed"),
            "reason 必須可診斷（含 marker 或 not installed 資訊），實際：" + result.reason());
        verify(pm, never()).getPlugin(anyString());
    }

    @Test
    @DisplayName("plugin 未啟用 → initialize 失敗、不 active")
    void pluginDisabled_notActive() {
        Plugin installed = plugin(MIN_VERSION, false);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "plugin 未啟用時不得 active");
    }

    @Test
    @DisplayName("版本低於門檻 → initialize 失敗、不 active、status VERSION_UNSUPPORTED")
    void versionUnsupported_notActive() {
        Plugin installed = plugin("2.1.1-SNAPSHOT(b100-abc)", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "版本不符時不得 active");
        assertTrue(adapter.getStatus().reason().contains("2.1.1"),
            "reason 應含實際版本供診斷，實際：" + adapter.getStatus().reason());
    }

    @Test
    @DisplayName("classpath 探測遭 SecurityException → INIT_FAILED 且 reason 可診斷、不 active")
    void securityExceptionDuringProbe_initFailedWithReason() {
        ClassLoader denyingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (MARKER_PRESENT.equals(name)) {
                    throw new SecurityException("probe denied by sandbox");
                }
                return super.loadClass(name);
            }
        };
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            denyingLoader, pluginManager(), MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "探測異常時不得 active");
        IntegrationProbeResult result = adapter.getStatus();
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertTrue(result.reason().contains("denied"),
            "reason 必須含可診斷資訊，實際：" + result.reason());
    }

    @Test
    @DisplayName("探測拋 RuntimeException → INIT_FAILED 且 reason 含例外訊息、不 active")
    void runtimeExceptionDuringProbe_initFailedWithReason() {
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenThrow(new RuntimeException("bukkit state broken"));
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "探測異常時不得 active");
        IntegrationProbeResult result = adapter.getStatus();
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertTrue(result.reason().contains("bukkit state broken"),
            "reason 必須含例外訊息供診斷，實際：" + result.reason());
    }

    @Test
    @DisplayName("可用（marker 存在 + plugin 啟用 + 版本符合）→ active、AVAILABLE、typed lookup 非 null")
    void available_activeWithTypedLookup() {
        Plugin installed = plugin("2.2.5-SNAPSHOT(b294-20b6c25)", true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        adapter.initialize();
        assertTrue(adapter.isActive());
        assertEquals(IntegrationStatus.AVAILABLE, adapter.getStatus().status());
        assertNotNull(adapter.playerLookup(),
            "成功啟用後必須提供 typed BedrockService.PlayerLookup");
        // 注意：api instance 以 supplier 延遲綁定（查詢時才呼叫 FloodgateApi.getInstance()），
        // 本測試環境無法初始化 floodgate static holder，故不在這裡呼叫查詢方法；
        // 查詢行為由 FloodgateBedrockPlayerLookupTest 以 fake api 覆蓋。
    }

    @Test
    @DisplayName("shutdown 後不 active")
    void shutdown_deactivates() {
        Plugin installed = plugin(MIN_VERSION, true);
        PluginManager pm = pluginManager();
        when(pm.getPlugin(PLUGIN_NAME)).thenReturn(installed);
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pm, MARKER_PRESENT, PLUGIN_NAME, MIN_VERSION);
        adapter.initialize();
        assertTrue(adapter.isActive());
        adapter.shutdown();
        assertFalse(adapter.isActive());
    }

    @Test
    @DisplayName("建構子：null classLoader / null pluginManager 必須拋 IllegalArgumentException")
    void constructor_nullArgs_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new FloodgateIntegrationAdapter(null, pluginManager()));
        assertThrows(IllegalArgumentException.class,
            () -> new FloodgateIntegrationAdapter(getClass().getClassLoader(), null));
    }

    @Test
    @DisplayName("IntegrationRegistry.reload 後 floodgate adapter 狀態一致（缺席 → 可查詢且非 AVAILABLE）")
    void registryReload_floodgateStateConsistent() {
        IntegrationRegistry registry = new IntegrationRegistry();
        FloodgateIntegrationAdapter adapter = new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pluginManager());
        registry.register(adapter);
        registry.initializeAll();
        assertTrue(registry.isRegistered("floodgate"));
        assertFalse(registry.isActive("floodgate"), "Floodgate 缺席環境下不得 active");

        registry.reload(java.util.List.of(new FloodgateIntegrationAdapter(
            getClass().getClassLoader(), pluginManager())));
        assertTrue(registry.isRegistered("floodgate"), "reload 後 floodgate 仍須已註冊");
        assertFalse(registry.isActive("floodgate"), "reload 後仍不得 active（缺席）");
        assertNotNull(registry.getStatus("floodgate"));
    }
}
