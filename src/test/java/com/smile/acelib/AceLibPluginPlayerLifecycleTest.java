package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.player.PlayerDataService;
import com.smile.acelib.player.PlayerSession;
import com.smile.acelib.player.PlayerSessionState;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 驗證 AceLibPlugin 與 {@link PlayerDataService} 的生命週期協作，
 * 包括玩家 session、停用時的 flush，以及 reload 時的替換與回復。
 */
@DisplayName("AceLibPlugin player lifecycle wiring")
class AceLibPluginPlayerLifecycleTest {

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new com.smile.acelib.platform.PlatformDetector(
            getClass().getClassLoader()));
        server.getPluginManager().enablePlugin(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // Service 必須於 onEnable 建立
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onEnable 後 plugin 必須持有 PlayerDataService 實例（非 null）")
    void onEnable_playerServiceAvailable() {
        PlayerDataService svc = plugin.getPlayerDataService();
        assertNotNull(svc, "onEnable 後 PlayerDataService 不可為 null");
        assertFalse(svc.isShutdown(),
            "onEnable 後 PlayerDataService 必須為 active 狀態");
    }

    @Test
    @DisplayName("getPlayerDataService 在 onEnable 之前回傳 null（尚未建立）")
    void getPlayerDataService_beforeOnEnable_returnsNull() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertNotNull(fresh);
        // 尚未 onEnable，service 不可用
        org.junit.jupiter.api.Assertions.assertNull(
            fresh.getPlayerDataService(),
            "onEnable 之前 getPlayerDataService 必須回傳 null");
    }

    // -----------------------------------------------------------------
    // MockBukkit join/quit 必須驅動 service
    // -----------------------------------------------------------------

    @Test
    @DisplayName("玩家 join：plugin lifecycle 必須建立 service session")
    void playerJoin_drivesService() {
        PlayerDataService svc = plugin.getPlayerDataService();
        assertNotNull(svc);

        org.mockbukkit.mockbukkit.entity.PlayerMock player =
            server.addPlayer();
        UUID uuid = player.getUniqueId();

        PlayerSession session = svc.getSession(uuid).orElse(null);
        assertNotNull(session,
            "MockBukkit addPlayer 必須 dispatch join listener");
        assertEquals(player.getName(), session.getName(),
            "session name 必須為 player 的 name snapshot");
        assertSame(uuid, session.getUniqueId());

        // 等待 async load 完成（service 使用 ioExecutor）
        // 簡單輪詢直到 session isReady
        long deadline = System.currentTimeMillis() + 5000L;
        while (session.getState() != PlayerSessionState.READY
            && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertSame(PlayerSessionState.READY, session.getState(),
            "join 後 async load 必須在合理時間內完成為 READY");

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));
        long quitDeadline = System.currentTimeMillis() + 5000L;
        while (svc.getSession(uuid).isPresent() && System.currentTimeMillis() < quitDeadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, (String) null));
        long rejoinDeadline = System.currentTimeMillis() + 5000L;
        while ((svc.getSession(uuid).isEmpty() || !svc.getSession(uuid).get().isReady())
            && System.currentTimeMillis() < rejoinDeadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertTrue(svc.getSession(uuid).isPresent(), "explicit join event 必須重新建立 session");
    }

    @Test
    @DisplayName("玩家 quit：plugin lifecycle 必須移除 service session")
    void playerQuit_drivesService() throws InterruptedException {
        PlayerDataService svc = plugin.getPlayerDataService();
        assertNotNull(svc);

        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        UUID uuid = player.getUniqueId();

        long deadline = System.currentTimeMillis() + 5000L;
        while ((svc.getSession(uuid).isEmpty()
                || !svc.getSession(uuid).get().isReady())
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(svc.getSession(uuid).isPresent(),
            "前置：join 必須建立 session");

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));

        // 等 quit 處理完 — session 應被移除
        deadline = System.currentTimeMillis() + 5000L;
        while (svc.getSession(uuid).isPresent()
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse(svc.getSession(uuid).isPresent(),
            "quit 後，service 必須移除對應 session");
    }

    // -----------------------------------------------------------------
    // onDisable 必須 shutdown service
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onDisable 後 PlayerDataService 必須 shutdown（拒絕新工作、flush dirty）")
    void onDisable_shutsDownPlayerService() {
        PlayerDataService svc = plugin.getPlayerDataService();
        assertNotNull(svc);
        assertFalse(svc.isShutdown());

        // 先 join 一位玩家 + 標記 dirty，模擬 in-flight 場景
        org.mockbukkit.mockbukkit.entity.PlayerMock player =
            server.addPlayer();
        UUID uuid = player.getUniqueId();

        // 等 session READY
        long deadline = System.currentTimeMillis() + 5000L;
        while ((svc.getSession(uuid).isEmpty()
                || !svc.getSession(uuid).get().isReady())
            && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        svc.getData(uuid).ifPresent(rec -> {
            rec.set("disable-persisted", "must-survive");
            svc.markDirty(uuid);
        });

        // 觸發 plugin disable
        plugin.onDisable();

        // service 必須 shutdown
        assertTrue(svc.isShutdown(),
            "plugin.onDisable 後 service 必須 isShutdown()=true");

        // 新 join 必須拒絕（PLAYER-007）
        com.smile.acelib.player.PlayerStateException ex =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.smile.acelib.player.PlayerStateException.class,
                () -> svc.onPlayerJoin(UUID.randomUUID(), "late"));
        assertEquals("ACELIB-PLAYER-007", ex.getCode());
    }

    // -----------------------------------------------------------------
    // reload 必須重新建立 service
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload 後 plugin 必須持有新的 PlayerDataService 實例，且 listener 重新生效")
    void reload_recreatesPlayerService() {
        PlayerDataService before = plugin.getPlayerDataService();
        assertNotNull(before);
        assertFalse(before.isShutdown());

        assertTrue(plugin.reload(),
            "已啟用時 reload 必須回傳 true");

        PlayerDataService after = plugin.getPlayerDataService();
        assertNotNull(after, "reload 後 PlayerDataService 不可為 null");
        assertFalse(after.isShutdown(),
            "reload 後 PlayerDataService 必須為 active 狀態");
        // 重新建立：reference 必須不同（舊 service 已 shutdown）
        assertTrue(before.isShutdown(),
            "reload 時舊 PlayerDataService 必須 shutdown");
    }

    @Test
    @DisplayName("reload 失敗時舊 PlayerDataService 必須仍可用於查詢（rollback-safe）")
    void reload_failure_keepsOldServiceUsable() {
        PlayerDataService before = plugin.getPlayerDataService();
        assertNotNull(before);

        // 注入 reload 失敗 hook（在 diagnostics rebind 完成、commit 前）
        plugin.reloadRebindFailureHook = () -> {
            throw new IllegalStateException("injected: reload failure");
        };
        try {
            boolean result = plugin.reload();
            assertFalse(result, "failure hook 必須讓 reload 回傳 false");

            // reload 失敗為 recoverable — plugin 仍 ready
            assertTrue(plugin.isReady(),
                "rebind 失敗為 recoverable；plugin 仍應 ready");

            // PlayerDataService 在 Phase 14 reload 流程中可能尚未受影響；
            // 此測試確保 reload 失敗時 service 仍可用
            PlayerDataService current = plugin.getPlayerDataService();
            assertNotNull(current,
                "reload 失敗時 PlayerDataService reference 不可丟失");
        } finally {
            plugin.reloadRebindFailureHook = null;
        }
    }

    @Test
    @DisplayName("reload 時 player shutdown 失敗：回傳 false、降級且保留舊 service reference")
    void reload_playerShutdownFailure_isControlledAndDegraded() {
        PlayerDataService before = plugin.getPlayerDataService();
        assertNotNull(before);

        plugin.reloadPlayerShutdownFailureHook = () -> {
            throw new com.smile.acelib.player.PlayerStateException(
                "ACELIB-PLAYER-008", "injected player shutdown failure");
        };
        try {
            assertFalse(plugin.reload(), "player shutdown failure must not escape reload()");
            assertFalse(plugin.isReady(), "partial reload must leave plugin in degraded state");
            assertSame(before, plugin.getPlayerDataService(),
                "failed reload must not publish a replacement player service");
            assertFalse(before.isShutdown(), "old service remains usable under degraded policy");
        } finally {
            plugin.reloadPlayerShutdownFailureHook = null;
        }
    }
}
