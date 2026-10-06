package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 聊天／鐵砧輸入（Red）：提示、送出、一次性、過時／重複拒絕、
 * 退服／停用失效；鐵砧以 ANVIL 視圖＋結果欄位送出呈現。
 */
@DisplayName("聊天／鐵砧輸入")
class GuiInputTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiServiceImpl service;
    private AtomicLong now;
    private GuiScope scope;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("InputPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        now = new AtomicLong(1_000L);
        // direct executor：送出後 consumer 同步執行（MockBukkit 為 Paper-like，
        // main thread 直接執行即 region 安全）；production 走 region 派送。
        service = new GuiServiceImpl(PlayerContextExecutor.direct(), now::get);
        scope = GuiScopes.create(plugin, service, now::get);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private UUID openPlayer() {
        UUID player = server.addPlayer().getUniqueId();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, GuiView.chest("大廳", 27).allow(10).build()).state());
        return player;
    }

    private long generationOf(UUID player) {
        return service.getActiveSession(player).session().generation();
    }

    @Test
    @DisplayName("聊天提示＋送出完成一次；重複送出回 INPUT_EXPIRED")
    void chatPrompt_submitOnce() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, generation,
            GuiInputPrompt.chat("請輸入暱稱", 16, 60_000L), received::add);

        assertEquals(player, ticket.playerUuid());
        assertEquals(GuiInputKind.CHAT, ticket.kind());

        GuiResult result = scope.submitInput(ticket.token(), "小明");
        assertEquals(GuiState.SUCCESS, result.state());
        assertEquals(1, received.size());
        assertEquals("小明", received.get(0).text());
        assertEquals(GuiInputKind.CHAT, received.get(0).kind());

        GuiResult duplicate = scope.submitInput(ticket.token(), "小明");
        assertEquals(GuiState.REJECTED, duplicate.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, duplicate.errorCode());
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("未知票券送出回 INPUT_EXPIRED")
    void unknownTicket_isExpired() {
        UUID player = openPlayer();
        GuiResult result = scope.submitInput(UUID.randomUUID(), "hello");
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, result.errorCode());
    }

    @Test
    @DisplayName("超長文字被拒且票券保留，可重試")
    void tooLongText_isRetryable() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, generation,
            GuiInputPrompt.chat("hint", 4, 0L), received::add);

        GuiResult rejected = scope.submitInput(ticket.token(), "超過四個字元");
        assertEquals(GuiState.REJECTED, rejected.state());
        assertEquals(GuiErrorCode.INVALID_INPUT, rejected.errorCode());
        assertTrue(received.isEmpty());

        assertEquals(GuiState.SUCCESS, scope.submitInput(ticket.token(), "四字").state());
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("逾時後送出回 INPUT_EXPIRED")
    void expiredPrompt_isRejected() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, generation,
            GuiInputPrompt.chat("hint", 16, 1_000L), received::add);

        now.addAndGet(1_001L);
        GuiResult result = scope.submitInput(ticket.token(), "晚了");
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, result.errorCode());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("導航後舊票券送出被拒（INPUT_EXPIRED：票券隨舊 session 失效）")
    void staleGenerationSubmit_isRejected() {
        UUID player = openPlayer();
        long gen1 = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, gen1,
            GuiInputPrompt.chat("hint", 16, 0L), received::add);

        scope.pushView(player, GuiView.chest("下一頁", 27).allow(1).build());

        GuiResult result = scope.submitInput(ticket.token(), "舊代");
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, result.errorCode());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("離線玩家送出被拒（PLAYER_OFFLINE）")
    void offlineSubmit_isRejected() {
        PlayerMock mock = server.addPlayer();
        UUID player = mock.getUniqueId();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, GuiView.chest("大廳", 27).allow(10).build()).state());
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, generation,
            GuiInputPrompt.chat("hint", 16, 0L), received::add);

        mock.disconnect();
        GuiResult result = scope.submitInput(ticket.token(), "離線了");
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.PLAYER_OFFLINE, result.errorCode());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("關閉 GUI／退服後送出回 INPUT_EXPIRED（票券隨 session 失效）；退服後亦同")
    void closeAndQuit_invalidateInputs() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptChat(player, generation,
            GuiInputPrompt.chat("hint", 16, 0L), received::add);

        assertEquals(GuiState.SUCCESS, scope.close(player).state());
        GuiResult afterClose = scope.submitInput(ticket.token(), "關了");
        assertEquals(GuiState.REJECTED, afterClose.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, afterClose.errorCode());

        UUID second = openPlayer();
        long gen2 = generationOf(second);
        GuiInputTicket ticket2 = scope.promptChat(second, gen2,
            GuiInputPrompt.chat("hint", 16, 0L), received::add);
        scope.handlePlayerQuit(second);
        GuiResult afterQuit = scope.submitInput(ticket2.token(), "退了");
        assertEquals(GuiState.REJECTED, afterQuit.state());
        assertEquals(GuiErrorCode.INPUT_EXPIRED, afterQuit.errorCode());
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("鐵砧提示開啟 ANVIL 視圖；結果欄位點擊送出文字")
    void anvilPrompt_opensAnvilView() {
        UUID player = openPlayer();
        List<GuiInputResult> received = new ArrayList<>();
        GuiInputTicket ticket = scope.promptAnvil(player,
            GuiInputPrompt.anvil("輸入名稱", 16, 0L), received::add);
        assertEquals(GuiInputKind.ANVIL, ticket.kind());

        GuiView current = scope.viewOf(player).orElseThrow();
        assertEquals(GuiView.Kind.ANVIL, current.kind());

        // 結果欄位（slot 2）點擊攜帶鐵砧文字 → 完成輸入
        long generation = generationOf(player);
        GuiResult result = scope.handleClick(player, generation, 2,
            Optional.of("鐵砧文字"));
        assertEquals(GuiState.SUCCESS, result.state());
        assertEquals(1, received.size());
        assertEquals("鐵砧文字", received.get(0).text());
        assertEquals(GuiInputKind.ANVIL, received.get(0).kind());
    }

    @Test
    @DisplayName("聊天事件命中提示時被消耗（取消），未命中時放行")
    void chatEvent_consumesMatchingPrompt() {
        PlayerMock mock = server.addPlayer();
        UUID player = mock.getUniqueId();
        assertEquals(GuiState.SUCCESS,
            scope.openView(player, GuiView.chest("大廳", 27).allow(10).build()).state());
        long generation = generationOf(player);
        List<GuiInputResult> received = new ArrayList<>();
        scope.promptChat(player, generation,
            GuiInputPrompt.chat("hint", 16, 0L), received::add);

        GuiListener listener = new GuiListener(service);
        AsyncPlayerChatEvent hit = new AsyncPlayerChatEvent(false, mock, "暱稱來了",
            java.util.Set.of());
        listener.onChat(hit);
        assertTrue(hit.isCancelled());
        assertEquals(1, received.size());
        assertEquals("暱稱來了", received.get(0).text());

        AsyncPlayerChatEvent miss = new AsyncPlayerChatEvent(false, mock, "一般聊天",
            java.util.Set.of());
        listener.onChat(miss);
        assertTrue(!miss.isCancelled());
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("null 輸入拋 IllegalArgumentException")
    void nullInputs_throw() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        Consumer<GuiInputResult> consumer = result -> { };
        assertThrows(IllegalArgumentException.class,
            () -> scope.promptChat(null, generation,
                GuiInputPrompt.chat("h", 4, 0L), consumer));
        assertThrows(IllegalArgumentException.class,
            () -> scope.promptChat(player, generation, null, consumer));
        assertThrows(IllegalArgumentException.class,
            () -> scope.promptChat(player, generation,
                GuiInputPrompt.chat("h", 4, 0L), null));
        assertThrows(IllegalArgumentException.class,
            () -> scope.submitInput(null, "x"));
        assertThrows(IllegalArgumentException.class,
            () -> scope.submitInput(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> {
            UUID other = server.addPlayer().getUniqueId();
            scope.promptAnvil(other, null, consumer);
        });
    }
}
