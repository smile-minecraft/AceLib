package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 一次性確認票券與送出前重新驗證（Red）。
 *
 * <p>票券一次性：confirm／cancel 競爭只解決一次；
 * 送出前可經回呼重新驗證，失敗時不執行 domain action 且票券仍一次性失效。</p>
 */
@DisplayName("確認票券與送出前重新驗證")
class GuiTicketRevalidationTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiService service;
    private GuiScope scope;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("TicketPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        service = new GuiServiceImpl();
        scope = GuiScopes.create(plugin, service);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private UUID openPlayer() {
        UUID player = server.addPlayer().getUniqueId();
        GuiView view = GuiView.chest("確認", 9).build();
        assertEquals(GuiState.SUCCESS, scope.openView(player, view).state());
        return player;
    }

    private long generationOf(UUID player) {
        return service.getActiveSession(player).session().generation();
    }

    @Test
    @DisplayName("重新驗證通過 → callback 恰好執行一次；重複確認回 ACTION_ALREADY_RESOLVED")
    void revalidationPass_runsOnce() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        AtomicInteger runs = new AtomicInteger();
        String token = scope.createConfirmation(player, generation, "delete-1",
            runs::incrementAndGet).confirmation().actionToken();

        GuiResult first = scope.confirmWithRevalidation(player, generation, token,
            (uuid, gen) -> GuiResult.success(service.getActiveSession(uuid).session()));
        assertEquals(GuiState.SUCCESS, first.state());
        assertEquals(1, runs.get());

        GuiResult second = scope.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, second.errorCode());
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("重新驗證失敗 → callback 不執行，票券仍一次性失效")
    void revalidationFail_skipsCallbackAndConsumes() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        AtomicInteger runs = new AtomicInteger();
        String token = scope.createConfirmation(player, generation, "delete-1",
            runs::incrementAndGet).confirmation().actionToken();

        GuiResult rejected = scope.confirmWithRevalidation(player, generation, token,
            (uuid, gen) -> GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "餘額不足，拒絕送出"));
        assertEquals(GuiState.REJECTED, rejected.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, rejected.errorCode());
        assertEquals(0, runs.get());

        // 票券已被消費：重複確認不得重放
        GuiResult replay = scope.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, replay.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, replay.errorCode());
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("重新驗證拋例外 → fail-closed，不執行 callback 且票券失效")
    void revalidationThrowing_failsClosed() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        AtomicInteger runs = new AtomicInteger();
        String token = scope.createConfirmation(player, generation, "delete-1",
            runs::incrementAndGet).confirmation().actionToken();

        GuiResult result = scope.confirmWithRevalidation(player, generation, token,
            (uuid, gen) -> {
                throw new IllegalStateException("驗證器內部錯誤");
            });
        assertEquals(GuiState.FAILED, result.state());
        assertEquals(GuiErrorCode.OPERATION_FAILED, result.errorCode());
        assertEquals(0, runs.get());

        GuiResult replay = scope.confirm(player, generation, token);
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, replay.errorCode());
    }

    @Test
    @DisplayName("confirm／cancel 競爭：先到者勝，後到回 ACTION_ALREADY_RESOLVED")
    void confirmCancelRace_firstWins() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        AtomicInteger runs = new AtomicInteger();

        String first = scope.createConfirmation(player, generation, "a-1",
            runs::incrementAndGet).confirmation().actionToken();
        assertEquals(GuiState.SUCCESS, scope.confirm(player, generation, first).state());
        GuiResult lateCancel = scope.cancel(player, generation, first);
        assertEquals(GuiState.REJECTED, lateCancel.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, lateCancel.errorCode());

        String second = scope.createConfirmation(player, generation, "a-2",
            runs::incrementAndGet).confirmation().actionToken();
        assertEquals(GuiState.SUCCESS, scope.cancel(player, generation, second).state());
        GuiResult lateConfirm = scope.confirm(player, generation, second);
        assertEquals(GuiState.REJECTED, lateConfirm.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, lateConfirm.errorCode());

        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("重新驗證回呼不在鎖內執行：回呼內可再呼叫作用域查詢而不死鎖")
    void revalidation_runsOutsideLocks() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        AtomicInteger runs = new AtomicInteger();
        String token = scope.createConfirmation(player, generation, "delete-1",
            runs::incrementAndGet).confirmation().actionToken();

        GuiResult result = scope.confirmWithRevalidation(player, generation, token,
            (uuid, gen) -> {
                // 回呼內再查 session：若實作持有鎖，此處會死鎖或被拒
                GuiResult query = scope.confirm(uuid, gen, "unrelated-token");
                assertTrue(query.isRejected());
                return GuiResult.success(service.getActiveSession(uuid).session());
            });
        assertEquals(GuiState.SUCCESS, result.state());
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("null 重新驗證器拋 IllegalArgumentException")
    void nullRevalidation_throws() {
        UUID player = openPlayer();
        long generation = generationOf(player);
        String token = scope.createConfirmation(player, generation, "a",
            () -> { }).confirmation().actionToken();
        assertThrows(IllegalArgumentException.class,
            () -> scope.confirmWithRevalidation(player, generation, token, null));
    }
}
