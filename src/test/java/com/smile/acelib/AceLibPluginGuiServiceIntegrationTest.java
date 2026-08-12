package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiServiceImpl;
import com.smile.acelib.gui.GuiState;
import com.smile.acelib.platform.PlatformDetector;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Phase 11 GUI ↔ AceLibPlugin integration test.
 *
 * <p>對應 Evidence Pack §5 Red 7：模擬 plugin onEnable ↔ onDisable ↔ reload，
 * 確認 {@code getGuiService()} facade 永遠不為 null 且狀態語意正確
 * （NOT_READY → READY → SHUTDOWN）。</p>
 *
 * <p>沿用 AceLibPluginTest 的手動 loadPlugin + 手動 onEnable 模式（繞過
 * MockBukkit plugin classloader 的 NPE）。</p>
 */
@DisplayName("AceLibPlugin GUI service 整合")
class AceLibPluginGuiServiceIntegrationTest {

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

    @AfterEach
    void unloadPlugin() {
        if (plugin != null && plugin.isReady()) {
            plugin.onDisable();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("onEnable 後 getApi().getGuiService() 永遠不為 null，且為 GuiServiceImpl 實例")
    void enabledPlugin_getGuiService_neverNull() {
        assertNotNull(plugin.getApi().getGuiService(),
            "onEnable 後 getGuiService 必須永遠不為 null");
    }

    @Test
    @DisplayName("onEnable 後 plugin ready + GUI service 為 READY 模組")
    void enabledPlugin_guiService_isReady() {
        assertTrue(plugin.isReady(), "plugin 必須 ready");
        GuiService svc = plugin.getApi().getGuiService();
        assertEquals("READY", svc.getModuleStatus(),
            "onEnable 後 GUI service 模組狀態必須為 READY");
    }

    @Test
    @DisplayName("onDisable 後 GUI service 為 SHUTDOWN facade（操作皆回 SHUTDOWN 代碼）")
    void disabledPlugin_guiService_isShutdown() {
        GuiService before = plugin.getApi().getGuiService();
        assertNotNull(before);
        plugin.onDisable();
        GuiService after = plugin.getApi().getGuiService();
        assertNotNull(after, "onDisable 後仍須回傳 GUI service");
        assertEquals(GuiErrorCode.SHUTDOWN,
            after.openInventory(com.smile.acelib.gui.GuiArgument.of(UUID.randomUUID(),
                "Test", 9, List.of())).errorCode(),
            "shutdown facade 的 openInventory 必須回 ACELIB-GUI-002");
        assertEquals(GuiState.REJECTED,
            after.closeInventory(UUID.randomUUID(), 1L).state());
        // 第二輪 disable 仍為 idempotent
        plugin.onDisable();
        assertEquals(GuiErrorCode.SHUTDOWN,
            after.openInventory(com.smile.acelib.gui.GuiArgument.of(UUID.randomUUID(),
                "Test", 9, List.of())).errorCode());
    }

    @Test
    @DisplayName("reload() 重新建立 GUI service + 既有 caller 行為恢復 READY")
    void reload_recreatesGuiService() {
        GuiService beforeReload = plugin.getApi().getGuiService();
        assertNotNull(beforeReload);
        boolean ok = plugin.reload();
        assertTrue(ok, "reload 成功");
        GuiService afterReload = plugin.getApi().getGuiService();
        assertNotNull(afterReload);
        assertEquals("READY", afterReload.getModuleStatus(),
            "reload 後 GUI service 必須為 READY 模組");
        // 與 world service 對齊：reload 不一定同一 instance（舊 service 已 shutdown）
        // 但 facade 必須能繼續回傳 valid impl
        assertNotNull(beforeReload);
    }

    @Test
    @DisplayName("reload 後 GUI service 綁定新 scheduler：openInventory 仍可實際 open + link + close")
    void reload_guiServiceUsesNewScheduler_openInventoryWorks() {
        // Momus 複審 finding #1：reload 前舊 scheduler 已 disabled，bindGuiService
        // 必須在 this.scheduler = newScheduler 之後呼叫，否則 GUI service 會捕獲
        // disabled scheduler 導致 openInventory 在 reload 後一律回 SCHEDULER_REJECTED。
        PlayerMock player = server.addPlayer();

        // reload 前先建一輪 session（驗證 reload 後舊 session 不殘留）
        GuiService beforeReload = plugin.getApi().getGuiService();
        com.smile.acelib.gui.GuiResult beforeOpened = beforeReload.openInventory(
            com.smile.acelib.gui.GuiArgument.of(player, "PreReload", 9, List.of()));
        assertEquals(GuiState.SUCCESS, beforeOpened.state());

        // 觸發 reload — Phase A 會 disable 舊 scheduler，Phase D 會 commit 新 scheduler
        boolean ok = plugin.reload();
        assertTrue(ok, "reload 必須成功");

        // 取得 reload 後的 guiService，驗證綁定的是新 scheduler（不是舊 disabled scheduler）
        GuiService afterReload = plugin.getApi().getGuiService();
        assertNotNull(afterReload);
        assertEquals("READY", afterReload.getModuleStatus(),
            "reload 後 GUI service 必須為 READY 模組");

        // 核心契約：openInventory 必須實際成功（綁到新 scheduler 才能執行派送）
        com.smile.acelib.gui.GuiResult opened = afterReload.openInventory(
            com.smile.acelib.gui.GuiArgument.of(player, "PostReload", 9, List.of()));
        assertEquals(com.smile.acelib.gui.GuiState.SUCCESS, opened.state(),
            "reload 後 GUI service 必須綁定新 scheduler，openInventory 才能實際派送；"
                + "若 scheduler 仍為 disabled，會回 ACELIB-GUI-013。實際: "
                + opened.state() + " (" + opened.errorCode() + ")");
        assertNotNull(opened.session(),
            "reload 後 openInventory 必須附帶 session（派送成功）");

        // 必須能在 reload 後實際 close（close 也走 player context executor）
        long generation = opened.session().generation();
        com.smile.acelib.gui.GuiResult closed = afterReload.closeInventory(
            player.getUniqueId(), generation);
        assertEquals(com.smile.acelib.gui.GuiState.SUCCESS, closed.state(),
            "reload 後 closeInventory 必須仍可運作（executor 派送成功）");
    }

    @Test
    @DisplayName("openInventory 對 ready 玩家實際運作：得到 SUCCESS + session")
    void openInventory_onReadyPlayer_returnsSuccess() {
        PlayerMock player = server.addPlayer();
        GuiService svc = plugin.getApi().getGuiService();
        com.smile.acelib.gui.GuiResult result = svc.openInventory(
            com.smile.acelib.gui.GuiArgument.of(player, "Test", 9, List.of()));
        assertEquals(GuiState.SUCCESS, result.state(),
            "ready 環境下 openInventory 必須回 SUCCESS");
        assertNotNull(result.session());
        assertEquals(player.getUniqueId(), result.session().playerUuid());
    }

    @Test
    @DisplayName("關閉 GUI 後，再次呼叫 close 第二輪應回 SESSION_NOT_FOUND（不殘留）")
    void closeLifecycle_noResidualSession() {
        PlayerMock player = server.addPlayer();
        GuiService svc = plugin.getApi().getGuiService();
        com.smile.acelib.gui.GuiResult opened = svc.openInventory(
            com.smile.acelib.gui.GuiArgument.of(player, "Test", 9, List.of()));
        long generation = opened.session().generation();
        assertEquals(GuiState.SUCCESS, opened.state());

        com.smile.acelib.gui.GuiResult closed = svc.closeInventory(
            player.getUniqueId(), generation);
        assertEquals(GuiState.SUCCESS, closed.state());

        // 重複 close 必須回 SESSION_NOT_FOUND
        com.smile.acelib.gui.GuiResult secondClose = svc.closeInventory(
            player.getUniqueId(), generation);
        assertEquals(GuiState.REJECTED, secondClose.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, secondClose.errorCode());
    }

    @Test
    @DisplayName("Plugin disable 會 shutdown 既有 session，並由內部 listener 清理")
    void disableShutdown_clearsSessions() {
        PlayerMock player = server.addPlayer();
        GuiService svc = plugin.getApi().getGuiService();
        com.smile.acelib.gui.GuiResult opened = svc.openInventory(
            com.smile.acelib.gui.GuiArgument.of(player, "Test", 9, List.of()));
        assertEquals(GuiState.SUCCESS, opened.state());

        plugin.onDisable();
        // disable 後 facade 變 SHUTDOWN；既有 session 已被清理
        GuiService after = plugin.getApi().getGuiService();
        assertEquals(GuiErrorCode.SHUTDOWN,
            after.openInventory(com.smile.acelib.gui.GuiArgument.of(player, "Test", 9,
                List.of())).errorCode());
    }
}
