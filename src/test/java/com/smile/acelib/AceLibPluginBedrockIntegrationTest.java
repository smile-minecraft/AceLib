package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.bedrock.BedrockErrorCodes;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.platform.PlatformDetector;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * AceLibPlugin 基岩服務（bedrockService）生命週期整合測試。
 *
 * <p>比照 {@link AceLibPluginExternalIntegrationTest} 的手動 loadPlugin + 手動
 * onEnable 模式，直接鎖定 plugin 層級的 bind / unbind / reload / rollback /
 * re-enable 接線行為：</p>
 *
 * <ul>
 *   <li>onEnable 兩路徑：Floodgate 缺席 → absent lookup（查詢安全回覆非基岩）；
 *       模擬 floodgate plugin 存在且版本 ≥2.2.0 → adapter AVAILABLE、服務 READY</li>
 *   <li>reload 後新服務綁定生效、舊實例 SHUTDOWN 拒絕（ACELIB-BED-002）</li>
 *   <li>reload rollback 後不留 READY 殘留</li>
 *   <li>onDisable 後一律 SHUTDOWN 拒絕</li>
 *   <li>disable 後重新 onEnable 可再次正常使用</li>
 * </ul>
 *
 * <p>「模擬 floodgate 存在」以 {@code MockBukkit.createMockPlugin} 於 AceLib
 * onEnable 前註冊名為 floodgate 的假 plugin；marker class 位於測試 classpath
 * （testImplementation），探測必過，缺席與否由 PluginManager 決定。注意：
 * typed lookup 查詢會呼叫 {@code FloodgateApi.getInstance()}，其 static holder
 * 在測試環境未初始化，故「存在」路徑的斷言僅止於 adapter 狀態與 module status，
 * 不對查詢方法做任何假設。</p>
 */
@DisplayName("AceLibPlugin bedrock service 生命週期整合")
class AceLibPluginBedrockIntegrationTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000002");

    /** FloodgateIntegrationAdapter 的 Bukkit plugin 查詢鍵。 */
    private static final String FLOODGATE_PLUGIN_NAME = "floodgate";

    /**
     * ≥2.2.0 的 floodgate 版本字串（含 {@code -} suffix；VersionComparator
     * 截斷後比較 2.2.5 > 2.2.0）。
     */
    private static final String FLOODGATE_VERSION_OK = "2.2.5-SNAPSHOT(b294-20b6c25)";

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void freshServer() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = null;
    }

    @AfterEach
    void unloadPlugin() {
        if (plugin != null && plugin.isReady()) {
            plugin.onDisable();
        }
        MockBukkit.unmock();
    }

    /** 載入並手動 onEnable 一個全新 plugin 實例。 */
    private AceLibPlugin enableFreshPlugin() {
        AceLibPlugin p = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        p.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        return p;
    }

    // -----------------------------------------------------------------
    // 情境一：onEnable 兩路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onEnable（floodgate 缺席）：bedrockService 綁 absent lookup，查詢安全回覆非基岩且不拋例外")
    void enabled_floodgateAbsent_bindsAbsentLookup_queriesSafe() {
        plugin = enableFreshPlugin();

        BedrockService svc = plugin.getApi().getBedrockService();
        assertNotNull(svc, "onEnable 後 api.getBedrockService() 必須非 null");
        assertEquals("READY", svc.getModuleStatus(),
            "缺席路徑仍綁定 production 實作（absent lookup），module status 必須為 READY");

        // absent lookup 契約：任意 UUID 一律「非基岩玩家」，不拋例外
        assertFalse(svc.isBedrockPlayer(PLAYER_ID),
            "缺席時 isBedrockPlayer 必須安全回覆 false");
        assertTrue(svc.getPlayerInfo(PLAYER_ID).isEmpty(),
            "缺席時 getPlayerInfo 必須安全回覆 empty");
        assertNotNull(svc.forms(), "production 實作的 forms() 必須可用（unavailable facade 才拒絕）");

        // 對照信號：adapter 已註冊但外部插件缺席 → 非 AVAILABLE
        IntegrationStatus floodgate = plugin.getExternalIntegrationService()
            .getStatus("floodgate").status();
        assertFalse(floodgate == IntegrationStatus.AVAILABLE,
            "floodgate 缺席時 adapter 不得為 AVAILABLE");
    }

    @Test
    @DisplayName("onEnable（模擬 floodgate plugin 存在且版本 ≥2.2.0）：adapter AVAILABLE、bedrockService READY")
    void enabled_floodgatePresent_adapterAvailable_bedrockServiceReady() {
        // 於 AceLib onEnable 前註冊並啟用名為 floodgate 的假 plugin；
        // marker class 在測試 classpath 上，探測由 PluginManager 狀態決定。
        MockBukkit.createMockPlugin(FLOODGATE_PLUGIN_NAME, FLOODGATE_VERSION_OK);

        plugin = enableFreshPlugin();

        IntegrationStatus floodgate = plugin.getExternalIntegrationService()
            .getStatus("floodgate").status();
        assertEquals(IntegrationStatus.AVAILABLE, floodgate,
            "模擬 floodgate 啟用後 adapter 探測必須為 AVAILABLE");

        BedrockService svc = plugin.getApi().getBedrockService();
        assertNotNull(svc);
        assertEquals("READY", svc.getModuleStatus(),
            "adapter AVAILABLE 時 bindBedrockService 必須綁定 production 實作（typed lookup），"
                + "module status 為 READY");
        // 注意：此處刻意不呼叫 isBedrockPlayer / getPlayerInfo——typed lookup
        // 查詢會觸發 FloodgateApi.getInstance()，static holder 未初始化時行為
        // 取決上游實作細節；查詢語意由 FloodgateBedrockPlayerLookupTest 以
        // fake api 覆蓋，本測試只鎖定 plugin 接線結果。
    }

    // -----------------------------------------------------------------
    // 情境二：reload 成功
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload 後：新 bedrockService 綁定生效（READY），舊實例拒絕操作（ACELIB-BED-002）")
    void reload_rebindsNewReadyService_oldInstanceRejectsWithShutdownCode() {
        plugin = enableFreshPlugin();

        BedrockService before = plugin.getApi().getBedrockService();
        assertEquals("READY", before.getModuleStatus());

        assertTrue(plugin.reload(), "reload 必須成功");

        BedrockService after = plugin.getApi().getBedrockService();
        assertNotNull(after, "reload 後 bedrockService 必須非 null");
        assertNotSame(before, after, "reload commit 必須重新建立 bedrockService 實例");
        assertEquals("READY", after.getModuleStatus(), "reload 後新服務必須為 READY");
        assertFalse(after.isBedrockPlayer(PLAYER_ID),
            "reload 後新服務（absent lookup）查詢必須安全回覆 false");

        // 舊實例已在 Phase D shutdown：查詢轉為 ACELIB-BED-002 拒絕
        assertEquals("FAILED", before.getModuleStatus(),
            "舊 bedrockService 在 reload 後必須呈現已停用狀態");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> before.isBedrockPlayer(PLAYER_ID),
            "舊實例查詢必須被拒絕");
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "拒絕必須攜帶 ACELIB-BED-002，實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // 情境三：reload rollback（external bind 失敗）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload rollback 後：bedrockService 呈現安全停用狀態（ACELIB-BED-002 拒絕），不留 READY 殘留")
    void reload_externalBindFailure_bedrockServiceLeftShutdown_noReadyResidue() {
        plugin = enableFreshPlugin();

        BedrockService before = plugin.getApi().getBedrockService();
        assertEquals("READY", before.getModuleStatus());

        // 注入受控失敗：reload 的 external bind 階段拋錯（比照既有 hook 模式）
        plugin.reloadExternalBindFailureHook = () -> {
            throw new RuntimeException("simulated external bind failure");
        };

        assertFalse(plugin.reload(), "external bind 失敗時 reload 必須回傳 false");

        BedrockService after = plugin.getApi().getBedrockService();
        // rollback 不替換欄位：仍指向同一個舊實例（Phase D 已先行 shutdown），
        // 不得出現「欄位已換新、狀態卻殘留」或「舊實例仍 READY」的混合狀態
        assertSame(before, after,
            "rollback 路徑不得替換 bedrockService 欄位（避免新舊混用）");
        assertNotEquals("READY", after.getModuleStatus(),
            "rollback 後不得殘留 READY 語意");
        assertEquals("FAILED", after.getModuleStatus(),
            "rollback 後 bedrockService 必須呈現已停用狀態");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> after.isBedrockPlayer(PLAYER_ID),
            "rollback 後查詢必須被保守拒絕");
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "拒絕必須攜帶 ACELIB-BED-002，實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // 情境四：onDisable
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onDisable 後：getApi().getBedrockService() 所有操作一律 SHUTDOWN 拒絕（ACELIB-BED-002）")
    void disabled_bedrockServiceRejectsAllOperationsWithShutdownCode() {
        plugin = enableFreshPlugin();

        BedrockService enabled = plugin.getApi().getBedrockService();
        assertEquals("READY", enabled.getModuleStatus());

        plugin.onDisable();

        BedrockService after = plugin.getApi().getBedrockService();
        assertNotNull(after, "onDisable 後仍須回傳 bedrockService（SHUTDOWN facade）");
        assertEquals("FAILED", after.getModuleStatus(),
            "SHUTDOWN facade 的 module status 必須為 FAILED");

        IllegalStateException query = assertThrows(IllegalStateException.class,
            () -> after.isBedrockPlayer(PLAYER_ID), "disable 後查詢必須拒絕");
        assertTrue(query.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "isBedrockPlayer 拒絕必須攜帶 ACELIB-BED-002，實際：" + query.getMessage());

        IllegalStateException info = assertThrows(IllegalStateException.class,
            () -> after.getPlayerInfo(PLAYER_ID), "disable 後 getPlayerInfo 必須拒絕");
        assertTrue(info.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "getPlayerInfo 拒絕必須攜帶 ACELIB-BED-002，實際：" + info.getMessage());

        IllegalStateException forms = assertThrows(IllegalStateException.class,
            after::forms, "disable 後 forms() 必須拒絕");
        assertTrue(forms.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "forms() 拒絕必須攜帶 ACELIB-BED-002，實際：" + forms.getMessage());
    }

    // -----------------------------------------------------------------
    // 情境五：disable 後 re-enable
    // -----------------------------------------------------------------

    @Test
    @DisplayName("disable 後重新 onEnable：bedrockService 可再次正常使用（absent lookup 查詢回 false 不拋例外）")
    void disableThenReenable_bedrockServiceUsableAgainWithSafeQueries() {
        plugin = enableFreshPlugin();
        plugin.onDisable();
        assertFalse(plugin.isReady(), "disable 後 plugin 必須為非 ready");

        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        assertTrue(plugin.isReady(), "重新 onEnable 後 plugin 必須恢復 ready");

        BedrockService svc = plugin.getApi().getBedrockService();
        assertNotNull(svc, "re-enable 後 bedrockService 必須非 null");
        assertEquals("READY", svc.getModuleStatus(),
            "re-enable 必須重新綁定 production 實作，不得殘留 SHUTDOWN facade");
        assertFalse(svc.isBedrockPlayer(PLAYER_ID),
            "re-enable 後查詢必須安全回覆 false（absent lookup）");
        assertTrue(svc.getPlayerInfo(PLAYER_ID).isEmpty(),
            "re-enable 後 getPlayerInfo 必須安全回覆 empty");
        assertNotNull(svc.forms(), "re-enable 後 forms() 必須可用");
    }
}
