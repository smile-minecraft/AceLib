package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 五導航操作（Red）：open／push／replace／back／close，
 * 共用 session 登記上的 generation 推進、過時拒絕與返回狀態。
 */
@DisplayName("GUI 五導航")
class GuiNavigationTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiService service;
    private GuiScope scope;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("NavPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        service = new GuiServiceImpl();
        scope = GuiScopes.create(plugin, service);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private static GuiView view(String title) {
        return GuiView.chest(title, 27).allow(10).build();
    }

    @Test
    @DisplayName("open→push→replace→back→close 完整序列，generation 單調遞增")
    void fullSequence_bumpsGeneration() {
        UUID player = server.addPlayer().getUniqueId();

        GuiResult opened = scope.openView(player, view("第一頁"));
        assertEquals(GuiState.SUCCESS, opened.state());
        long gen1 = opened.session().generation();
        assertEquals("第一頁", scope.viewOf(player).orElseThrow().title());

        GuiResult pushed = scope.pushView(player, view("第二頁"));
        assertEquals(GuiState.SUCCESS, pushed.state());
        assertTrue(pushed.session().generation() > gen1);
        assertEquals("第二頁", scope.viewOf(player).orElseThrow().title());

        GuiResult replaced = scope.replaceView(player, view("第二頁改"));
        assertEquals(GuiState.SUCCESS, replaced.state());
        assertTrue(replaced.session().generation() > pushed.session().generation());
        assertEquals("第二頁改", scope.viewOf(player).orElseThrow().title());

        GuiResult back = scope.back(player);
        assertEquals(GuiState.SUCCESS, back.state());
        assertEquals("第一頁", scope.viewOf(player).orElseThrow().title());
        assertTrue(back.session().generation() > replaced.session().generation());

        GuiResult closed = scope.close(player);
        assertEquals(GuiState.SUCCESS, closed.state());
        assertEquals(Optional.empty(), scope.viewOf(player));
        assertEquals(GuiState.REJECTED, service.getActiveSession(player).state());
    }

    @Test
    @DisplayName("無歷史時 back 被拒（ACELIB-GUI-023），session 保持不變")
    void backWithoutHistory_isRejected() {
        UUID player = server.addPlayer().getUniqueId();
        assertEquals(GuiState.SUCCESS, scope.openView(player, view("唯一頁")).state());

        GuiResult back = scope.back(player);
        assertEquals(GuiState.REJECTED, back.state());
        assertEquals(GuiErrorCode.NO_PREVIOUS_VIEW, back.errorCode());

        // 拒絕後目前視圖仍在
        assertEquals("唯一頁", scope.viewOf(player).orElseThrow().title());
    }

    @Test
    @DisplayName("舊 generation 操作在導航後被拒（GENERATION_MISMATCH），session 不被誤刪")
    void staleGeneration_isRejected() {
        UUID player = server.addPlayer().getUniqueId();
        long gen1 = scope.openView(player, view("第一頁")).session().generation();
        scope.pushView(player, view("第二頁"));

        // 以舊 generation 直接經底層服務關閉必須被拒
        GuiResult direct = service.closeInventory(player, gen1);
        assertEquals(GuiState.REJECTED, direct.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, direct.errorCode());
        // 目前 session 仍在（未被舊代誤刪）
        assertEquals(GuiState.SUCCESS, service.getActiveSession(player).state());
    }

    @Test
    @DisplayName("reopen 在底層服務替換後以目前視圖重開（reload 恢復）")
    void reopen_restoresCurrentView() {
        UUID player = server.addPlayer().getUniqueId();
        GuiService first = new GuiServiceImpl();
        GuiService second = new GuiServiceImpl();
        java.util.concurrent.atomic.AtomicReference<GuiService> current =
            new java.util.concurrent.atomic.AtomicReference<>(first);
        GuiScopes.close(plugin);
        GuiScope reloadable = GuiScopes.create(plugin, current::get,
            com.smile.acelib.diagnostics.Clock.system(), null, null);

        assertEquals(GuiState.SUCCESS, reloadable.openView(player, view("商店")).state());
        assertEquals(GuiState.SUCCESS, reloadable.pushView(player, view("分類")).state());

        // 模擬 reload commit：底層服務替換，舊 session 全失
        ((GuiServiceControl) first).shutdownService();
        current.set(second);

        GuiResult revived = reloadable.reopen(player);
        assertEquals(GuiState.SUCCESS, revived.state());
        assertEquals("分類", reloadable.viewOf(player).orElseThrow().title());
        GuiScopes.close(plugin);
    }

    @Test
    @DisplayName("null 輸入一律拋 IllegalArgumentException")
    void nullInputs_throw() {
        UUID player = server.addPlayer().getUniqueId();
        assertThrows(IllegalArgumentException.class,
            () -> scope.openView(null, view("x")));
        assertThrows(IllegalArgumentException.class,
            () -> scope.openView(player, null));
        assertThrows(IllegalArgumentException.class,
            () -> scope.pushView(player, null));
        assertThrows(IllegalArgumentException.class,
            () -> scope.replaceView(player, null));
        assertThrows(IllegalArgumentException.class, () -> scope.back(null));
        assertThrows(IllegalArgumentException.class, () -> scope.close(null));
    }
}
