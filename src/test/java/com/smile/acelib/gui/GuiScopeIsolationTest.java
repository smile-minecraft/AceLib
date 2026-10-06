package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 插件隔離的 GUI 作用域（Red）。
 *
 * <p>每個 plugin 取得自己的 handle，只能操作自己開的 GUI；
 * 底層共用同一份 session 登記；新 GUI 取代其他 plugin 的 GUI 時通知原擁有者。</p>
 */
@DisplayName("GuiScope 插件隔離")
class GuiScopeIsolationTest {

    private ServerMock server;
    private JavaPlugin pluginA;
    private JavaPlugin pluginB;
    private GuiService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        pluginA = mockPlugin("PluginA");
        pluginB = mockPlugin("PluginB");
        service = new GuiServiceImpl();
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(pluginA);
        GuiScopes.close(pluginB);
        MockBukkit.unmock();
    }

    private static JavaPlugin mockPlugin(String name) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static GuiView chestView() {
        return GuiView.chest("Shop", 27)
            .allow(10, 11, 12)
            .build();
    }

    @Test
    @DisplayName("同一 plugin 重複建立作用域被拒（ACELIB-GUI-020）")
    void duplicateCreate_isRejected() {
        GuiScope first = GuiScopes.create(pluginA, service);
        assertNotNull(first);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> GuiScopes.create(pluginA, service));
        assertTrue(failure.getMessage().contains(GuiErrorCode.SCOPE_CLOSED),
            "重複建立必須攜帶 SCOPE_CLOSED：實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("關閉後使用作用域被拒（ACELIB-GUI-020）；close 具冪等性")
    void useAfterClose_isRejected() {
        GuiScope scope = GuiScopes.create(pluginA, service);
        scope.close();
        scope.close();
        assertTrue(scope.isClosed());
        assertEquals(Optional.empty(), GuiScopes.get(pluginA));
        GuiResult result = scope.openView(UUID.randomUUID(), chestView());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SCOPE_CLOSED, result.errorCode());
    }

    @Test
    @DisplayName("跨 plugin 關閉他人 GUI 被拒（ACELIB-GUI-019），原 session 不受影響")
    void crossPluginClose_isRejected() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        GuiScope scopeB = GuiScopes.create(pluginB, service);
        UUID player = server.addPlayer().getUniqueId();

        GuiResult opened = scopeA.openView(player, chestView());
        assertEquals(GuiState.SUCCESS, opened.state());

        GuiResult foreign = scopeB.close(player);
        assertEquals(GuiState.REJECTED, foreign.state());
        assertEquals(GuiErrorCode.NOT_OWNER, foreign.errorCode());

        // 原擁有者的 session 仍有效
        GuiResult query = scopeA.viewOf(player).isPresent()
            ? GuiResult.success(opened.session()) : null;
        assertNotNull(query);
        assertEquals(GuiState.SUCCESS,
            service.getActiveSession(player).state());
        assertEquals("PluginA",
            service.getActiveSession(player).session().owner());
    }

    @Test
    @DisplayName("跨 plugin 確認票券被拒，callback 不執行")
    void crossPluginConfirm_isRejected() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        GuiScope scopeB = GuiScopes.create(pluginB, service);
        UUID player = server.addPlayer().getUniqueId();

        GuiResult opened = scopeA.openView(player, chestView());
        long generation = opened.session().generation();
        boolean[] ran = {false};
        String token = scopeA.createConfirmation(player, generation, "act",
            () -> ran[0] = true).confirmation().actionToken();

        GuiResult foreign = scopeB.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, foreign.state());
        assertEquals(GuiErrorCode.NOT_OWNER, foreign.errorCode());
        assertFalse(ran[0]);
    }

    @Test
    @DisplayName("新 GUI 取代其他 plugin 的 GUI 時通知原擁有者")
    void replace_notifiesPreviousOwner() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        GuiScope scopeB = GuiScopes.create(pluginB, service);
        UUID player = server.addPlayer().getUniqueId();

        List<String> events = new ArrayList<>();
        AtomicReference<GuiSession> oldRef = new AtomicReference<>();
        AtomicReference<GuiSession> newRef = new AtomicReference<>();
        scopeA.onReplaced((uuid, oldSession, newSession) -> {
            events.add(uuid.toString());
            oldRef.set(oldSession);
            newRef.set(newSession);
        });

        GuiResult first = scopeA.openView(player, chestView());
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = scopeB.openView(player, chestView());
        assertEquals(GuiState.SUCCESS, second.state());
        assertTrue(second.session().generation() > first.session().generation(),
            "取代後 generation 必須遞增");
        assertEquals("PluginB", second.session().owner());

        assertEquals(List.of(player.toString()), events);
        assertEquals(first.session(), oldRef.get());
        assertEquals(second.session(), newRef.get());

        // 原擁有者的視圖狀態已失效
        assertEquals(Optional.empty(), scopeA.viewOf(player));
        // 原擁有者再操作舊 generation 被拒
        GuiResult stale = scopeA.close(player);
        assertEquals(GuiState.REJECTED, stale.state());
    }

    @Test
    @DisplayName("同一 plugin 內導航不觸發取代通知")
    void sameOwnerNavigation_doesNotNotify() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        UUID player = server.addPlayer().getUniqueId();
        List<String> events = new ArrayList<>();
        scopeA.onReplaced((uuid, oldSession, newSession) ->
            events.add(uuid.toString()));

        assertEquals(GuiState.SUCCESS, scopeA.openView(player, chestView()).state());
        assertEquals(GuiState.SUCCESS,
            scopeA.pushView(player, chestView()).state());
        assertTrue(events.isEmpty(), "同一擁有者內導航不得通知");
    }

    @Test
    @DisplayName("未啟用服務上的作用域操作透出 NOT_READY")
    void operationsOnUnavailableService_propagateCode() {
        GuiScope scope = GuiScopes.create(pluginA,
            GuiService.forUnavailable(GuiErrorCode.NOT_READY));
        GuiResult result = scope.openView(UUID.randomUUID(), chestView());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("玩家退服後狀態清除，舊操作回 SESSION_NOT_FOUND")
    void playerQuit_clearsState() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        UUID player = server.addPlayer().getUniqueId();
        assertEquals(GuiState.SUCCESS, scopeA.openView(player, chestView()).state());

        scopeA.handlePlayerQuit(player);

        assertEquals(Optional.empty(), scopeA.viewOf(player));
        GuiResult query = service.getActiveSession(player);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
    }

    @Test
    @DisplayName("插件停用自動關閉其作用域並結束其 GUI")
    void pluginDisable_closesScope() {
        GuiScope scopeA = GuiScopes.create(pluginA, service);
        GuiScope scopeB = GuiScopes.create(pluginB, service);
        UUID player = server.addPlayer().getUniqueId();
        assertEquals(GuiState.SUCCESS, scopeA.openView(player, chestView()).state());

        GuiScopes.handlePluginDisable(pluginA);

        assertTrue(scopeA.isClosed());
        assertEquals(Optional.empty(), GuiScopes.get(pluginA));
        // 被停用 plugin 的 session 已結束
        assertEquals(GuiState.REJECTED, service.getActiveSession(player).state());
        // 未被停用的作用域不受影響
        assertFalse(scopeB.isClosed());
    }
}
