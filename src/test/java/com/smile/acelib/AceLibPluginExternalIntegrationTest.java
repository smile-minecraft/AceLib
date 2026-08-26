package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.DiagnosticSnapshot;
import com.smile.acelib.diagnostics.ModuleState;
import com.smile.acelib.diagnostics.ModuleStatus;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.platform.PlatformDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * AceLibPlugin 外部整合服務 lifecycle 整合測試。
 *
 * <p>模擬 plugin onEnable ↔ onDisable ↔ reload，確認 external service 永遠不為 null、
 * 8 參數 ready 攜帶實際實作、diagnostics integration 模組狀態正確註冊/解除，
 * 且 reload 失敗時不新舊混用。</p>
 *
 * <p>沿用 AceLibPluginGuiServiceIntegrationTest 的手動 loadPlugin + 手動 onEnable 模式。</p>
 */
@DisplayName("AceLibPlugin external integration service 整合")
class AceLibPluginExternalIntegrationTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @BeforeAll
    static void setUpClass() {
        server = MockBukkit.mock();
    }

    @BeforeEach
    void loadFresh() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void unloadPlugin() {
        if (plugin != null && plugin.isReady()) {
            plugin.onDisable();
        }
        MockBukkit.unmock();
    }

    private static ModuleState integrationModule(DiagnosticSnapshot snapshot) {
        return snapshot.modules().get("integration");
    }

    @Test
    @DisplayName("onEnable 後 getExternalIntegrationService() 與 getApi().getExternalIntegrationService() 同一非 null 實例")
    void enabledPlugin_externalService_neverNull_andSameAsApi() {
        ExternalIntegrationService pluginExt = plugin.getExternalIntegrationService();
        ExternalIntegrationService apiExt = plugin.getApi().getExternalIntegrationService();
        assertNotNull(pluginExt, "onEnable 後 plugin.getExternalIntegrationService 必須非 null");
        assertNotNull(apiExt, "onEnable 後 api.getExternalIntegrationService 必須非 null");
        assertSame(pluginExt, apiExt,
            "plugin 與 api 必須攜帶同一 external service 實例（8 參數 ready 傳入）");
        // 四個 reflection-only adapter 皆已註冊但外部插件缺席 → FAILED 模組狀態
        assertEquals("FAILED", pluginExt.getModuleStatus(),
            "註冊四個 adapter 但外部插件缺席時，external service 模組狀態必須為 FAILED");
    }

    @Test
    @DisplayName("onEnable 後 diagnostics 註冊 integration 模組（NOT_INITIALIZED，detail 來自實作）")
    void enabledPlugin_diagnostics_registersIntegrationModule() {
        ModuleState mod = integrationModule(plugin.getDiagnosticsService().buildSnapshot());
        assertNotNull(mod, "onEnable 後 diagnostics 必須註冊 integration 模組");
        assertEquals(ModuleStatus.FAILED, mod.status(),
            "四個 adapter 皆未可用時 integration 模組必須為 FAILED");
        assertEquals("all external integrations failed", mod.detail(),
            "integration 模組 detail 必須來自 ExternalIntegrationServiceImpl.toModuleState()");
    }

    @Test
    @DisplayName("onDisable 後 external service 為 SHUTDOWN facade，且 integration 模組狀態解除（回到預設 NOT_INITIALIZED）")
    void disabledPlugin_externalService_isShutdownFacade_andModuleUnregistered() {
        ExternalIntegrationService before = plugin.getExternalIntegrationService();
        assertNotNull(before);
        plugin.onDisable();

        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        assertNotNull(after, "onDisable 後仍須回傳 external service（SHUTDOWN facade）");
        assertEquals("FAILED", after.getModuleStatus(),
            "SHUTDOWN facade 的 module status 必須為 FAILED");

        // unregister MODULE_INTEGRATION：模組回到預設 NOT_INITIALIZED（detail 為預設值）
        ModuleState mod = integrationModule(plugin.getDiagnosticsService().buildSnapshot());
        assertNotNull(mod, "integration 模組仍出現在快照（預設值）");
        assertEquals(ModuleStatus.NOT_INITIALIZED, mod.status());
        assertEquals("Phase 13 未實作", mod.detail(),
            "onDisable 解除註冊後，integration 模組必須回到預設 detail（非實作註冊值）");
    }

    @Test
    @DisplayName("reload() 重新建立 external service 且 integration 模組仍正確註冊")
    void reload_recreatesExternalService_andModuleStillRegistered() {
        ExternalIntegrationService beforeReload = plugin.getExternalIntegrationService();
        assertNotNull(beforeReload);

        boolean ok = plugin.reload();
        assertTrue(ok, "reload 必須成功");

        ExternalIntegrationService afterReload = plugin.getExternalIntegrationService();
        assertNotNull(afterReload, "reload 後 external service 必須非 null");
        assertSame(afterReload, plugin.getApi().getExternalIntegrationService(),
            "reload 後 api 與 plugin 必須攜帶同一（新）external service 實例");
        assertEquals("FAILED", afterReload.getModuleStatus(),
            "reload 後 external service 模組狀態仍為 FAILED（四個 adapter 缺席）");

        ModuleState mod = integrationModule(plugin.getDiagnosticsService().buildSnapshot());
        assertNotNull(mod, "reload 後 diagnostics 必須仍註冊 integration 模組");
        assertEquals(ModuleStatus.FAILED, mod.status());
    }

    @Test
    @DisplayName("reload 失敗（external bind hook）時不建立新服務、舊服務已 shutdown、不新舊混用")
    void reload_externalBindFailure_doesNotMixOldAndNew() {
        ExternalIntegrationService before = plugin.getExternalIntegrationService();
        assertNotNull(before);

        // 注入受控失敗：reload 的 external bind 階段拋錯
        plugin.reloadExternalBindFailureHook = () -> {
            throw new RuntimeException("simulated external bind failure");
        };

        boolean ok = plugin.reload();
        assertFalse(ok, "external bind 失敗時 reload 必須回傳 false");

        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        // 不新舊混用：after 必須是同一個舊 reference（已 shutdown），而非新建立的 active 實例
        assertSame(before, after,
            "external bind 失敗時不得建立新 active 服務，必須保留舊 reference（已 shutdown）");
        assertEquals("SHUTDOWN", after.getModuleStatus(),
            "失敗路徑下舊 external service 已被 shutdown（module status = SHUTDOWN）");
    }

    @Test
    @DisplayName("reload 失敗（external bind hook）後 diagnostics scheduler 模組與 plugin scheduler 一致（FAILED，非 READY），不殘留 newScheduler 綁定")
    void reload_externalBindFailure_diagnosticsConsistentWithScheduler() {
        SafeSchedulerImpl beforeScheduler = plugin.getSchedulerForDiagnostics();
        assertNotNull(beforeScheduler, "reload 前 plugin scheduler 必須非 null");

        // 注入受控失敗：reload 的 external bind 階段拋錯
        plugin.reloadExternalBindFailureHook = () -> {
            throw new RuntimeException("simulated external bind failure");
        };

        boolean ok = plugin.reload();
        assertFalse(ok, "external bind 失敗時 reload 必須回傳 false");

        // plugin scheduler 仍為舊 scheduler（Phase A 已將其 disabled，且未 commit 新 scheduler）
        SafeSchedulerImpl afterScheduler = plugin.getSchedulerForDiagnostics();
        assertSame(beforeScheduler, afterScheduler,
            "reload 失敗不得 commit 新 scheduler 至 this.scheduler");
        assertTrue(afterScheduler.isDisabled(),
            "失敗後 plugin scheduler 仍為 Phase A 已 disabled 的舊 scheduler");

        // diagnostics 綁定的 scheduler 必須與 plugin scheduler 一致（皆為 disabled 舊 scheduler），
        // 不得殘留 Phase C 已綁定的 newScheduler（否則 scheduler 模組會是 READY 而非 FAILED）
        ModuleState schedMod = plugin.getDiagnosticsService().buildSnapshot()
            .modules().get("scheduler");
        assertNotNull(schedMod, "diagnostics 必須註冊 scheduler 模組");
        assertEquals(ModuleStatus.FAILED, schedMod.status(),
            "external bind 失敗 rollback 後，diagnostics scheduler 模組必須為 FAILED"
                + "（與 disabled 舊 scheduler 一致），不得為 READY（殘留 newScheduler 綁定）");

        // external service 仍為舊 reference（已 shutdown），不新舊混用
        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        assertEquals("SHUTDOWN", after.getModuleStatus(),
            "失敗路徑下舊 external service 已被 shutdown（module status = SHUTDOWN）");

        // api 仍攜帶同一 external service（未 commit 新 api），與 plugin 一致
        assertSame(after, plugin.getApi().getExternalIntegrationService(),
            "api 與 plugin 必須攜帶同一（舊、已 shutdown）external service 實例");
    }

    @Test
    @DisplayName("reload 失敗（external bind hook）後 integration 模組狀態解除（回到預設 NOT_INITIALIZED），與 SHUTDOWN external service 一致")
    void reload_externalBindFailure_integrationModuleUnregistered() {
        // 注入受控失敗：reload 的 external bind 階段拋錯
        plugin.reloadExternalBindFailureHook = () -> {
            throw new RuntimeException("simulated external bind failure");
        };

        boolean ok = plugin.reload();
        assertFalse(ok, "external bind 失敗時 reload 必須回傳 false");

        // integration 模組必須解除註冊（回到預設 NOT_INITIALIZED），不得殘留舊的
        // FAILED 註冊值（否則 diagnostics 會顯示 FAILED 但實際 external service 已 SHUTDOWN）
        ModuleState mod = integrationModule(plugin.getDiagnosticsService().buildSnapshot());
        assertNotNull(mod, "integration 模組仍出現在快照（預設值）");
        assertEquals(ModuleStatus.NOT_INITIALIZED, mod.status(),
            "external bind 失敗 rollback 後，integration 模組必須解除註冊"
                + "（NOT_INITIALIZED），不得殘留舊的 FAILED 註冊狀態");
        assertEquals("Phase 13 未實作", mod.detail(),
            "external bind 失敗 rollback 後，integration 模組 detail 必須回到預設值");

        // external service 仍為 SHUTDOWN facade，與 diagnostics 解除註冊後的語意一致
        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        assertEquals("SHUTDOWN", after.getModuleStatus(),
            "失敗路徑下舊 external service 已被 shutdown（module status = SHUTDOWN）");
    }

    private static final String[] REGISTERED_IDS =
        {"vault", "luckperms", "placeholderapi", "floodgate"};

    @Test
    @DisplayName("onEnable 後 vault / luckperms / placeholderapi / floodgate 四個 adapter 皆已註冊且狀態可查詢")
    void enabledPlugin_allAdaptersRegisteredAndQueryable() {
        ExternalIntegrationService ext = plugin.getExternalIntegrationService();
        for (String id : REGISTERED_IDS) {
            IntegrationProbeResult result = ext.getStatus(id);
            assertNotNull(result, id + " 狀態必須非 null");
            assertNotEquals(IntegrationStatus.AVAILABLE, result.status(),
                id + " 在外部插件缺席時不得為 AVAILABLE");
            assertFalse(result.reason().contains("is not registered"),
                id + " 必須已註冊（不應出現 not registered 訊息）");
        }
        // 未註冊 id 仍回傳 not registered 結果，證明只有這四個被註冊
        assertTrue(ext.getStatus("unknown-integration").reason().contains("is not registered"),
            "未註冊 id 必須回傳 not registered 結果");
    }

    @Test
    @DisplayName("Floodgate 缺席環境：getStatus(\"floodgate\") 可診斷且底層為 NOT_INSTALLED，其他 adapter 不受影響")
    void enabledPlugin_floodgateAbsent_notInstalledAndOthersUnaffected() {
        ExternalIntegrationService ext = plugin.getExternalIntegrationService();
        // adapter 狀態機契約（比照 vault）：缺席時 getStatus 為 INIT_FAILED，
        // 但 reason 必須保留底層探測語意 NOT_INSTALLED 供管理員判讀
        IntegrationProbeResult floodgate = ext.getStatus("floodgate");
        assertNotEquals(IntegrationStatus.AVAILABLE, floodgate.status());
        assertTrue(floodgate.reason().contains("NOT_INSTALLED"),
            "缺席時 reason 必須保留底層 NOT_INSTALLED 語意，實際：" + floodgate.reason());
        assertFalse(floodgate.reason().contains("is not registered"),
            "floodgate 必須已註冊");
        // 其他 adapter 不受影響：仍已註冊且可查詢
        for (String id : new String[] {"vault", "luckperms", "placeholderapi"}) {
            assertNotNull(ext.getStatus(id), id + " 仍必須可查詢");
            assertFalse(ext.getStatus(id).reason().contains("is not registered"),
                id + " 不得因 floodgate 加入而受影響");
        }
    }

    @Test
    @DisplayName("bindExternalService 注入探測器的 classloader 必須是 AceLib 自身的 plugin classloader，而非伺服器主 classloader")
    void externalProbeClassLoader_isPluginClassLoader_notServerClassLoader() {
        // 根因鎖定：舊實作使用 server.getClass().getClassLoader()（所有 plugin classloader 的父），
        // 父優先委派下永遠看不到插件 JAR 提供的 marker class，導致四個 adapter 全數 INIT_FAILED。
        // 修復後必須使用 AceLib 自身的 plugin classloader（會依 softdepend 委派到依賴插件）。
        ClassLoader probeLoader = plugin.externalProbeClassLoader();
        ClassLoader pluginLoader = plugin.getClass().getClassLoader();
        ClassLoader serverLoader = server.getClass().getClassLoader();

        assertSame(pluginLoader, probeLoader,
            "探測 classloader 必須等於 plugin 自身的 classloader（才能看見依賴插件的 API class）");
        assertNotSame(serverLoader, probeLoader,
            "探測 classloader 不得是伺服器主 classloader（否則看不到任何插件提供的 marker class）");
    }

    @Test
    @DisplayName("onEnable 時外部插件（Vault/LuckPerms/PlaceholderAPI）缺席不拋錯，模組狀態為可診斷的 FAILED")
    void enabledPlugin_absentExternalPlugins_safeAndDiagnostic() {
        // loadFresh() 已呼叫 onEnable；若 bind 崩潰此處不會被執行，故到達即代表安全。
        ExternalIntegrationService ext = plugin.getExternalIntegrationService();
        String moduleStatus = ext.getModuleStatus();
        assertNotNull(moduleStatus, "模組狀態必須非 null");
        assertEquals("FAILED", moduleStatus,
            "外部插件全數缺席時模組狀態必須為 FAILED（可診斷，非崩潰）");

        ModuleState mod = integrationModule(plugin.getDiagnosticsService().buildSnapshot());
        assertNotNull(mod, "diagnostics 必須註冊 integration 模組");
        assertEquals(ModuleStatus.FAILED, mod.status(),
            "integration 模組狀態必須為 FAILED（可診斷）");
    }

    @Test
    @DisplayName("reload 後 external service 為新實例，舊服務已 SHUTDOWN，新服務仍註冊四個 adapter")
    void reload_replacesService_oldShutdown_newRegistered() {
        ExternalIntegrationService before = plugin.getExternalIntegrationService();
        assertNotNull(before);

        boolean ok = plugin.reload();
        assertTrue(ok, "reload 必須成功");

        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        assertNotNull(after, "reload 後 external service 必須非 null");
        assertNotSame(before, after, "reload 必須建立新的 external service 實例");

        // 舊服務在 reload 的 commit 階段已被 shutdown，不得仍為 active
        assertEquals("SHUTDOWN", before.getModuleStatus(),
            "舊 external service 在 reload 後必須已 shutdown");

        // 新服務仍註冊四個 adapter（且外部插件仍缺席 → FAILED）
        assertEquals("FAILED", after.getModuleStatus(),
            "新 external service 仍應為 FAILED（四個 adapter 缺席）");
        for (String id : REGISTERED_IDS) {
            assertFalse(after.getStatus(id).reason().contains("is not registered"),
                id + " 在新服務中必須已註冊");
        }
    }
}
