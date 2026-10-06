package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.diagnostics.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 按鈕回呼、預設全擋欄位與指定放行、按鈕冷卻（Red）。
 *
 * <p>禁止拿「受保護欄位被拒絕」錯誤當按鈕事件：
 * 按鈕點擊走專屬回呼路徑，不得以 {@code SLOT_PROTECTED} 拒絕冒充。</p>
 */
@DisplayName("按鈕、欄位放行與冷卻")
class GuiButtonCooldownTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiService service;
    private AtomicLong now;
    private GuiScope scope;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("ButtonPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        service = new GuiServiceImpl();
        now = new AtomicLong(1_000L);
        Clock clock = now::get;
        scope = GuiScopes.create(plugin, service, clock);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private static GuiView shopView(List<String> clicks) {
        return GuiView.chest("商店", 27)
            .allow(10, 11)
            .button(13, "buy", 5_000L, click -> clicks.add(click.buttonId()))
            .button(14, "info", click -> clicks.add(click.buttonId()))
            .build();
    }

    @Test
    @DisplayName("按鈕點擊執行專屬回呼（SUCCESS），不用 SLOT_PROTECTED 冒充")
    void buttonClick_runsCallback() {
        UUID player = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, shopView(clicks)).state());
        long generation = service.getActiveSession(player).session().generation();

        GuiResult result = scope.handleClick(player, generation, 13);
        assertEquals(GuiState.SUCCESS, result.state());
        assertEquals(List.of("buy"), clicks);
    }

    @Test
    @DisplayName("放行欄位回 ALLOWED；未放行亦非按鈕欄位回 SLOT_PROTECTED")
    void allowlist_defaultDeny() {
        UUID player = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, shopView(clicks)).state());
        long generation = service.getActiveSession(player).session().generation();

        GuiResult allowed = scope.handleClick(player, generation, 10);
        assertEquals(GuiState.ALLOWED, allowed.state());

        GuiResult blocked = scope.handleClick(player, generation, 0);
        assertEquals(GuiState.REJECTED, blocked.state());
        assertEquals(GuiErrorCode.SLOT_PROTECTED, blocked.errorCode());
        assertTrue(clicks.isEmpty());
    }

    @Test
    @DisplayName("冷卻中再次點擊被拒（ACELIB-GUI-021），回呼不重複執行；過期後放行")
    void buttonCooldown_blocksThenReleases() {
        UUID player = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, shopView(clicks)).state());
        long generation = service.getActiveSession(player).session().generation();

        assertEquals(GuiState.SUCCESS, scope.handleClick(player, generation, 13).state());
        GuiResult cooled = scope.handleClick(player, generation, 13);
        assertEquals(GuiState.REJECTED, cooled.state());
        assertEquals(GuiErrorCode.COOLDOWN_ACTIVE, cooled.errorCode());
        assertEquals(List.of("buy"), clicks);

        now.addAndGet(5_000L);
        assertEquals(GuiState.SUCCESS, scope.handleClick(player, generation, 13).state());
        assertEquals(List.of("buy", "buy"), clicks);
    }

    @Test
    @DisplayName("無冷卻按鈕可連續觸發")
    void noCooldownButton_runsEveryTime() {
        UUID player = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, shopView(clicks)).state());
        long generation = service.getActiveSession(player).session().generation();

        assertEquals(GuiState.SUCCESS, scope.handleClick(player, generation, 14).state());
        assertEquals(GuiState.SUCCESS, scope.handleClick(player, generation, 14).state());
        assertEquals(List.of("info", "info"), clicks);
    }

    @Test
    @DisplayName("不同玩家冷卻互不影響")
    void cooldown_isPerPlayer() {
        UUID first = server.addPlayer().getUniqueId();
        UUID second = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        assertEquals(GuiState.SUCCESS, scope.openView(first, shopView(clicks)).state());
        assertEquals(GuiState.SUCCESS, scope.openView(second, shopView(clicks)).state());
        long genFirst = service.getActiveSession(first).session().generation();
        long genSecond = service.getActiveSession(second).session().generation();

        assertEquals(GuiState.SUCCESS, scope.handleClick(first, genFirst, 13).state());
        assertEquals(GuiState.SUCCESS, scope.handleClick(second, genSecond, 13).state());
        assertEquals(2, clicks.size());
    }

    @Test
    @DisplayName("導航後舊代點擊被拒，冷卻不跨代殘留誤判")
    void staleClick_afterNavigation_isRejected() {
        UUID player = server.addPlayer().getUniqueId();
        List<String> clicks = new ArrayList<>();
        long gen1 = scope.openView(player, shopView(clicks))
            .session().generation();
        scope.pushView(player, shopView(clicks));

        GuiResult stale = scope.handleClick(player, gen1, 13);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());
        assertTrue(clicks.isEmpty());
    }
}
