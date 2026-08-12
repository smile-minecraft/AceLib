package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.AceLibVersion;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Phase 10 ↔ AceLibPlugin integration test.
 *
 * <p>對應 Evidence Pack §5 Red 7：模擬 plugin onEnable ↔ onDisable ↔ reload，
 * 確認 {@code getWorldService()} facade 永遠不為 null 且狀態語意正確。</p>
 *
 * <p>沿用 AceLibPluginTest 的手動 loadPlugin + 手動 onEnable 模式（繞過
 * MockBukkit plugin classloader 的 NPE）。</p>
 */
@DisplayName("AceLibPlugin world service 整合")
class AceLibPluginWorldServiceIntegrationTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @BeforeAll
    static void setUpClass() {
        server = MockBukkit.mock();
    }

    @AfterAll
    static void tearDownBukkit() {
        MockBukkit.unmock();
    }

    @BeforeEach
    void loadFresh() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @Test
    @DisplayName("onEnable 後 getApi().getWorldService() 永遠不為 null")
    void enabledPlugin_getWorldService_neverNull() {
        assertNotNull(plugin.getApi().getWorldService(),
            "getWorldService 必須永遠不為 null");
    }

    @Test
    @DisplayName("onEnable 後 plugin ready + facade 存在（impl 或 unavailable 皆通過）")
    void enabledPlugin_worldService_present() {
        // 整合測試允許兩種路徑：手動 onEnable 同時走 player-data / command / world bind，
        // 任意 bind 失敗亦須保證 facade 不為 null 以遵守 Plan §二十一契約。
        assertTrue(plugin.isReady(), "plugin must be ready after onEnable");
        WorldService ws = plugin.getApi().getWorldService();
        assertNotNull(ws, "facade 永遠不為 null");
    }

    @Test
    @DisplayName("onDisable 後 world service 為 SHUTDOWN facade")
    void disabledPlugin_worldService_isShutdown() {
        WorldService before = plugin.getApi().getWorldService();
        assertNotNull(before);
        plugin.onDisable();
        WorldService after = plugin.getApi().getWorldService();
        assertNotNull(after, "onDisable 後仍須回傳 worldService");
        // SHUTDOWN facade 一律回 SHUTDOWN 錯誤代碼
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0);
        assertEquals(WorldErrorCode.SHUTDOWN,
            after.readBlock(snapshot).errorCode());
        assertEquals(WorldErrorCode.SHUTDOWN,
            after.writeBlock(snapshot, "STONE").errorCode());
        // 第二輪 disable 仍為 idempotent
        plugin.onDisable();
        assertEquals(WorldErrorCode.SHUTDOWN,
            plugin.getApi().getWorldService().readBlock(snapshot).errorCode());
    }

    @Test
    @DisplayName("reload() 重新建立 world service + 既有 caller 行為恢復 READY")
    void reload_recreatesWorldService() {
        WorldService beforeReload = plugin.getApi().getWorldService();
        boolean ok = plugin.reload();
        assertTrue(ok, "reload 成功");
        WorldService afterReload = plugin.getApi().getWorldService();
        assertNotNull(afterReload);
        assertTrue(afterReload.getModuleStatus().equals("READY")
                || afterReload.getClass() != WorldServiceUnavailableImpl.class,
            "reload 後 worldService 應為 impl");
        // 對一個未知 world 仍回 WORLD_NOT_FOUND（impl 路徑），不是 SHUTDOWN
        BlockResult r = afterReload.readBlock(LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0));
        assertEquals(WorldErrorCode.WORLD_NOT_FOUND, r.errorCode());
        // 預期 disable 前後 reference 不一定相同；但 module 都應該可運作
        assertNotNull(beforeReload);
    }
}
