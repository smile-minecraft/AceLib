package com.smile.acelib.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiConfirmation;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiServiceControl;
import com.smile.acelib.gui.GuiState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GUI 服務契約（真實作與假實作共用）。
 *
 * <p>鎖定「現有」公開 {@link GuiService} 語意：開啟／重複／關閉／世代驗證／
 * 點擊驗證／一次性確認／過時非同步請求／停用。子類只需提供服務實例與玩家
 * 上下文（生產側用 MockBukkit 真實作，假側用 {@code FakeGuiService}）：</p>
 * <ul>
 *   <li>{@link #createService()} — 每個測試全新實例</li>
 *   <li>{@link #newPlayer()} — 在線玩家 UUID（生產側需真實在線玩家）</li>
 *   <li>{@link #disconnect(UUID)} — 使玩家離線（生產側 {@code disconnect()}，
 *       假側離線標記）</li>
 * </ul>
 *
 * <p>停用案例（{@code shutdown_rejectsNewWork}）只對同時實作內部
 * {@code GuiServiceControl} 的服務執行：只實作公開 {@link GuiService}
 * 的下游服務沒有內部停用入口，該案例以 {@code abort} 跳過（其餘全跑）。
 * 下游不得為了跑契約而依賴 {@code internal} 細節。</p>
 *
 * @since 1.4.0
 */
@DisplayName("GUI 服務契約（真／假共用）")
public abstract class GuiServiceContract {

    /** 每個測試全新服務實例。 */
    protected abstract GuiService createService();

    /** 在線玩家 UUID。 */
    protected abstract UUID newPlayer();

    /** 使玩家離線。 */
    protected abstract void disconnect(UUID playerUuid);

    private static GuiArgument argument(UUID playerUuid) {
        return GuiArgument.of(playerUuid, "Contract", 9, List.of(0, 1));
    }

    @Test
    @DisplayName("開啟成功帶 session；同一玩家重複開啟被拒 SESSION_EXISTS")
    void open_duplicateIsRejected() {
        GuiService service = createService();
        UUID player = newPlayer();

        GuiResult first = service.openInventory(argument(player));
        assertEquals(GuiState.SUCCESS, first.state());
        assertNotNull(first.session());
        assertTrue(first.session().generation() > 0L);

        GuiResult second = service.openInventory(argument(player));
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.SESSION_EXISTS, second.errorCode());
    }

    @Test
    @DisplayName("離線玩家開啟失敗 OPERATION_FAILED")
    void open_offlinePlayerFails() {
        GuiService service = createService();
        UUID player = newPlayer();
        disconnect(player);

        GuiResult result = service.openInventory(argument(player));
        assertEquals(GuiState.FAILED, result.state());
        assertEquals(GuiErrorCode.OPERATION_FAILED, result.errorCode());
    }

    @Test
    @DisplayName("關閉成功；關閉後查詢回 SESSION_NOT_FOUND；重開 generation 遞增")
    void close_reopenBumpsGeneration() {
        GuiService service = createService();
        UUID player = newPlayer();

        GuiResult opened = service.openInventory(argument(player));
        long firstGeneration = opened.session().generation();

        GuiResult closed = service.closeInventory(player, firstGeneration);
        assertEquals(GuiState.SUCCESS, closed.state());

        GuiResult after = service.getActiveSession(player);
        assertEquals(GuiState.REJECTED, after.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, after.errorCode());

        GuiResult reopened = service.openInventory(argument(player));
        assertEquals(GuiState.SUCCESS, reopened.state());
        assertTrue(reopened.session().generation() > firstGeneration,
            "重開 generation 必須遞增");
    }

    @Test
    @DisplayName("關閉未知玩家回 SESSION_NOT_FOUND；過時 generation 回 GENERATION_MISMATCH")
    void close_unknownAndStaleAreRejected() {
        GuiService service = createService();
        UUID player = newPlayer();

        GuiResult unknown = service.closeInventory(player, 1L);
        assertEquals(GuiState.REJECTED, unknown.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, unknown.errorCode());

        GuiResult opened = service.openInventory(argument(player));
        GuiResult stale = service.closeInventory(player,
            opened.session().generation() + 100L);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());
    }

    @Test
    @DisplayName("點擊驗證：未保護 slot 放行、受保護拒絕、越界拒絕、過時世代拒絕")
    void validateClick_rules() {
        GuiService service = createService();
        UUID player = newPlayer();
        GuiResult opened = service.openInventory(argument(player));
        long generation = opened.session().generation();

        assertEquals(GuiState.ALLOWED, service.validateClick(player, generation, 5).state());

        GuiResult blocked = service.validateClick(player, generation, 0);
        assertEquals(GuiState.REJECTED, blocked.state());
        assertEquals(GuiErrorCode.SLOT_PROTECTED, blocked.errorCode());

        GuiResult outOfRange = service.validateClick(player, generation, 99);
        assertEquals(GuiState.REJECTED, outOfRange.state());
        assertEquals(GuiErrorCode.INVALID_INPUT, outOfRange.errorCode());

        GuiResult stale = service.validateClick(player, generation + 100L, 5);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());
    }

    @Test
    @DisplayName("確認票券恰好執行一次；重複確認回 ACTION_ALREADY_RESOLVED")
    void confirm_runsCallbackExactlyOnce() {
        GuiService service = createService();
        UUID player = newPlayer();
        GuiResult opened = service.openInventory(argument(player));
        long generation = opened.session().generation();
        AtomicInteger runs = new AtomicInteger();

        GuiResult created = service.createConfirmation(player, generation,
            "delete-item-42", runs::incrementAndGet);
        assertEquals(GuiState.SUCCESS, created.state());
        GuiConfirmation confirmation = created.confirmation();
        assertNotNull(confirmation);

        GuiResult first = service.confirm(player, generation, confirmation.actionToken());
        assertEquals(GuiState.SUCCESS, first.state());
        assertEquals(1, runs.get());

        GuiResult second = service.confirm(player, generation, confirmation.actionToken());
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, second.errorCode());
        assertEquals(1, runs.get(), "callback 不得重複執行");
    }

    @Test
    @DisplayName("未知票券回 UNKNOWN_ACTION；取消後確認回 ACTION_ALREADY_RESOLVED 且不執行")
    void confirm_unknownAndCancelled() {
        GuiService service = createService();
        UUID player = newPlayer();
        GuiResult opened = service.openInventory(argument(player));
        long generation = opened.session().generation();

        GuiResult unknown = service.confirm(player, generation, "no-such-token");
        assertEquals(GuiState.REJECTED, unknown.state());
        assertEquals(GuiErrorCode.UNKNOWN_ACTION, unknown.errorCode());

        AtomicInteger runs = new AtomicInteger();
        GuiResult created = service.createConfirmation(player, generation,
            "action-1", runs::incrementAndGet);
        String token = created.confirmation().actionToken();
        GuiResult cancelled = service.cancel(player, generation, token);
        assertEquals(GuiState.SUCCESS, cancelled.state());

        GuiResult afterCancel = service.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, afterCancel.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, afterCancel.errorCode());
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("關閉 session 後票券失效：確認回 UNKNOWN_ACTION 且不執行")
    void confirm_afterCloseIsUnknown() {
        GuiService service = createService();
        UUID player = newPlayer();
        GuiResult opened = service.openInventory(argument(player));
        long generation = opened.session().generation();
        AtomicInteger runs = new AtomicInteger();

        String token = service.createConfirmation(player, generation,
            "action-1", runs::incrementAndGet).confirmation().actionToken();
        service.closeInventory(player, generation);

        GuiResult result = service.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.UNKNOWN_ACTION, result.errorCode());
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("過時非同步請求被拒 STALE_REQUEST（後發請求取代前者）")
    void asyncUpdate_staleRequestIsRejected() {
        GuiService service = createService();
        UUID player = newPlayer();
        GuiResult opened = service.openInventory(argument(player));
        long generation = opened.session().generation();

        GuiResult first = service.beginAsyncUpdate(player, generation, 0);
        assertEquals(GuiState.SUCCESS, first.state());
        assertNotNull(first.asyncRequest());
        service.beginAsyncUpdate(player, generation, 0);

        GuiResult stale = service.applyAsyncUpdate(first.asyncRequest(),
            GuiPage.<String>empty(), () -> {
                throw new AssertionError("過時請求不得執行 renderer");
            });
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.STALE_REQUEST, stale.errorCode());
    }

    @Test
    @DisplayName("經內部生命週期停用後開啟被拒 SHUTDOWN（僅具備內部生命週期的實作執行）")
    void shutdown_rejectsNewWork() {
        GuiService service = createService();
        if (!(service instanceof GuiServiceControl)) {
            // 純公開實作無內部停用入口：本案例不適用，標記跳過而非失敗。
            abort("服務未實作內部 GuiServiceControl；停用案例不適用");
        }
        UUID player = newPlayer();
        // 公開契約不再提供 shutdown；停用走內部生命週期（1.4.0 破壞性變更）。
        // 已由上方守衛確認具備內部生命週期，轉型安全。
        GuiServiceControl control = (GuiServiceControl) service;
        control.shutdownService();

        GuiResult result = service.openInventory(argument(player));
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());
    }

    @Test
    @DisplayName("邊界：null 輸入一律拋 IllegalArgumentException")
    void nullInputs_throw() {
        GuiService service = createService();
        UUID player = newPlayer();
        assertThrows(IllegalArgumentException.class,
            () -> service.openInventory(null));
        assertThrows(IllegalArgumentException.class,
            () -> service.closeInventory(null, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> service.getActiveSession(null));
        assertThrows(IllegalArgumentException.class,
            () -> service.validateClick(null, 1L, 0));
        assertThrows(IllegalArgumentException.class,
            () -> service.createConfirmation(null, 1L, "a", () -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> service.createConfirmation(player, 1L, null, () -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> service.createConfirmation(player, 1L, "a", null));
        assertThrows(IllegalArgumentException.class,
            () -> service.confirm(null, 1L, "t"));
        assertThrows(IllegalArgumentException.class,
            () -> service.confirm(player, 1L, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.beginAsyncUpdate(null, 1L, 0));
    }

    @Test
    @DisplayName("不同玩家 session 互不影響")
    void sessions_areIsolatedPerPlayer() {
        GuiService service = createService();
        UUID first = newPlayer();
        UUID second = newPlayer();
        assertNotEquals(first, second);

        assertEquals(GuiState.SUCCESS, service.openInventory(argument(first)).state());
        assertEquals(GuiState.SUCCESS, service.openInventory(argument(second)).state());
        assertEquals(first, service.getActiveSession(first).session().playerUuid());
        assertEquals(second, service.getActiveSession(second).session().playerUuid());
    }
}
