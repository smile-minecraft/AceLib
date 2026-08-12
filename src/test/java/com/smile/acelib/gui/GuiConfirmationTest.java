package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 確認 / 取消 action contract（Phase 11 延伸第二切片：confirmation/cancellation）。
 *
 * <p>對應 Evidence Pack §5 TDD：confirm success、cancel success、duplicate
 * confirm/cancel、wrong generation、unknown token/action、closed/shutdown session、
 * callback exactly-once、callback failure 不吞錯且回可追蹤 GUI error。</p>
 *
 * <p>契約重點：confirmation 綁定 UUID + session generation + action token；
 * 成功後 action 一次性失效；重複 confirm/cancel、stale generation、關閉或 shutdown
 * 後操作必須安全拒絕並回 {@code ACELIB-GUI-*}，不可重複執行 callback。</p>
 */
@DisplayName("GuiConfirmation 確認/取消 contract")
class GuiConfirmationTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private GuiServiceImpl service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        uuid = player.getUniqueId();
        service = new GuiServiceImpl();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // createConfirmation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("createConfirmation 回 SUCCESS + 不可為 null 的 GuiConfirmation，綁定 UUID/generation/actionToken")
    void createConfirmation_returnsConfirmation() {
        long generation = openSession();
        GuiResult result = service.createConfirmation(uuid, generation,
            "delete-item-42", () -> { });
        assertEquals(GuiState.SUCCESS, result.state());
        GuiConfirmation confirmation = result.confirmation();
        assertNotNull(confirmation, "SUCCESS 結果必須附帶 confirmation");
        assertEquals(uuid, confirmation.playerUuid());
        assertEquals(generation, confirmation.generation());
        assertEquals("delete-item-42", confirmation.actionId());
        assertNotNull(confirmation.actionToken(), "actionToken 必須由服務產生");
        assertSame(GuiConfirmation.State.PENDING, confirmation.state());
    }

    @Test
    @DisplayName("createConfirmation 對錯誤 generation 回 REJECTED + ACELIB-GUI-011")
    void createConfirmation_staleGeneration_isRejected() {
        long generation = openSession();
        GuiResult result = service.createConfirmation(uuid, generation + 1L,
            "x", () -> { });
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, result.errorCode());
    }

    @Test
    @DisplayName("createConfirmation 對無 session 回 REJECTED + ACELIB-GUI-008")
    void createConfirmation_noSession_isRejected() {
        GuiResult result = service.createConfirmation(uuid, 1L, "x", () -> { });
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, result.errorCode());
    }

    @Test
    @DisplayName("createConfirmation 對 shutdown 服務回 REJECTED + ACELIB-GUI-002")
    void createConfirmation_shutdown_isRejected() {
        long generation = openSession();
        service.shutdown();
        GuiResult result = service.createConfirmation(uuid, generation, "x", () -> { });
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());
    }

    // -----------------------------------------------------------------
    // confirm / cancel success
    // -----------------------------------------------------------------

    @Test
    @DisplayName("confirm 成功執行 callback 一次並回 SUCCESS")
    void confirm_success_executesCallbackOnce() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        GuiResult result = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.SUCCESS, result.state(),
            "confirm 必須回 SUCCESS");
        assertEquals(1, counter.get(), "callback 必須恰好執行一次");
    }

    @Test
    @DisplayName("cancel 成功且不執行 callback，回 SUCCESS")
    void cancel_success_doesNotExecuteCallback() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        GuiResult result = service.cancel(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.SUCCESS, result.state(),
            "cancel 必須回 SUCCESS");
        assertEquals(0, counter.get(), "cancel 不得執行 callback");
    }

    // -----------------------------------------------------------------
    // duplicate / idempotent
    // -----------------------------------------------------------------

    @Test
    @DisplayName("重複 confirm 第二次被拒絕（ACTION_ALREADY_RESOLVED）且 callback 不重複執行")
    void confirm_duplicate_isRejectedAndIdempotent() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        GuiResult first = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, second.state(),
            "重複 confirm 必須被拒絕");
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, second.errorCode());
        assertEquals(1, counter.get(), "callback 不得因重複 confirm 重複執行");
    }

    @Test
    @DisplayName("重複 cancel 第二次被拒絕（ACTION_ALREADY_RESOLVED）")
    void cancel_duplicate_isRejected() {
        long generation = openSession();
        GuiConfirmation confirmation = createConfirmation(generation, "act", () -> { });

        GuiResult first = service.cancel(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = service.cancel(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, second.errorCode());
    }

    @Test
    @DisplayName("confirm 後再 cancel 被拒絕（ACTION_ALREADY_RESOLVED）")
    void confirmThenCancel_isRejected() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        assertEquals(GuiState.SUCCESS,
            service.confirm(uuid, generation, confirmation.actionToken()).state());
        GuiResult cancel = service.cancel(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, cancel.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, cancel.errorCode());
        assertEquals(1, counter.get());
    }

    // -----------------------------------------------------------------
    // wrong generation / unknown token / wrong player
    // -----------------------------------------------------------------

    @Test
    @DisplayName("confirm 對錯誤 generation 回 REJECTED + ACELIB-GUI-011，不執行 callback")
    void confirm_wrongGeneration_isRejected() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        GuiResult result = service.confirm(uuid, generation + 7L, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, result.errorCode());
        assertEquals(0, counter.get(), "錯誤 generation 不得執行 callback");
    }

    @Test
    @DisplayName("confirm 對未知 token 回 REJECTED + ACELIB-GUI-015")
    void confirm_unknownToken_isRejected() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        service.createConfirmation(uuid, generation, "act", counter::incrementAndGet);

        GuiResult result = service.confirm(uuid, generation, "not-a-real-token");
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.UNKNOWN_ACTION, result.errorCode());
        assertEquals(0, counter.get());
    }

    @Test
    @DisplayName("confirm 對其他玩家的 token 回 REJECTED + ACELIB-GUI-015")
    void confirm_wrongPlayer_isRejected() {
        long generation = openSession();
        GuiConfirmation confirmation = createConfirmation(generation, "act", () -> { });

        PlayerMock other = server.addPlayer();
        GuiResult result = service.confirm(other.getUniqueId(), generation,
            confirmation.actionToken());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.UNKNOWN_ACTION, result.errorCode());
    }

    // -----------------------------------------------------------------
    // closed / shutdown session
    // -----------------------------------------------------------------

    @Test
    @DisplayName("session 關閉後 confirm 回 REJECTED + ACELIB-GUI-015（action 已失效）")
    void confirm_afterClose_isRejected() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        // 關閉 session（模擬玩家關閉 GUI）
        GuiResult close = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, close.state());

        GuiResult result = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, result.state(),
            "關閉後的 confirm 必須被拒絕");
        assertEquals(GuiErrorCode.UNKNOWN_ACTION, result.errorCode());
        assertEquals(0, counter.get(), "關閉後不得執行 callback");
    }

    @Test
    @DisplayName("shutdown 後 confirm 回 REJECTED + ACELIB-GUI-002")
    void confirm_afterShutdown_isRejected() {
        long generation = openSession();
        AtomicInteger counter = new AtomicInteger();
        GuiConfirmation confirmation = createConfirmation(generation,
            "act", counter::incrementAndGet);

        service.shutdown();

        GuiResult result = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());
        assertEquals(0, counter.get());
    }

    // -----------------------------------------------------------------
    // callback failure
    // -----------------------------------------------------------------

    @Test
    @DisplayName("callback 拋例外不吞錯：confirm 回 FAILED + ACELIB-GUI-012 且 detail 含可追蹤訊息")
    void confirm_callbackFailure_isNotSwallowed() {
        long generation = openSession();
        GuiConfirmation confirmation = createConfirmation(generation, "boom",
            () -> { throw new IllegalStateException("domain exploded"); });

        GuiResult result = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.FAILED, result.state(),
            "callback 失敗必須回 FAILED，不可吞錯");
        assertEquals(GuiErrorCode.OPERATION_FAILED, result.errorCode());
        assertTrue(result.detail().contains("domain exploded"),
            "detail 必須包含可追蹤的例外訊息: " + result.detail());

        // 失敗後 action 仍視為已解決：重複 confirm 回 ACTION_ALREADY_RESOLVED
        GuiResult again = service.confirm(uuid, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, again.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, again.errorCode());
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private long openSession() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, opened.state(), "前置 openInventory 必須成功");
        return opened.session().generation();
    }

    private GuiConfirmation createConfirmation(long generation, String actionId,
                                              Runnable callback) {
        GuiResult result = service.createConfirmation(uuid, generation, actionId, callback);
        assertEquals(GuiState.SUCCESS, result.state(), "前置 createConfirmation 必須成功");
        return result.confirmation();
    }
}
