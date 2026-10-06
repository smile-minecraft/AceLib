package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code FakeGuiService} 專屬行為：關閉失敗注入、過時／重複回應、離線模擬。
 *
 * <p>與 production 共通的語意由 {@code GuiServiceContract} 覆蓋；本檔只鎖定
 * 假實作才有的可觀測失敗注入。</p>
 */
@DisplayName("FakeGuiService — 失敗注入")
class FakeGuiServiceTest {

    private static UUID player() {
        return UUID.randomUUID();
    }

    private static GuiResult open(FakeGuiService service, UUID player) {
        return service.openInventory(GuiArgument.of(player, "Fake", 9, List.of()));
    }

    @Test
    @DisplayName("failNextClose：下一次關閉回 FAILED＋OPERATION_FAILED，session 保留可重試")
    void failNextClose_keepsSessionRetryable() {
        FakeGuiService service = new FakeGuiService();
        UUID player = player();
        long generation = open(service, player).session().generation();

        service.failNextClose();
        GuiResult failed = service.closeInventory(player, generation);
        assertEquals(GuiState.FAILED, failed.state());
        assertEquals(GuiErrorCode.OPERATION_FAILED, failed.errorCode());
        assertEquals(1, service.activeSessionCount(), "關閉失敗後 session 必須保留");

        GuiResult retried = service.closeInventory(player, generation);
        assertEquals(GuiState.SUCCESS, retried.state(), "旗標一次性，重試應成功");
        assertEquals(0, service.activeSessionCount());
    }

    @Test
    @DisplayName("過時 generation 關閉被拒；session 保留")
    void staleClose_isRejectedSessionKept() {
        FakeGuiService service = new FakeGuiService();
        UUID player = player();
        long generation = open(service, player).session().generation();

        GuiResult stale = service.closeInventory(player, generation + 1L);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());
        assertEquals(1, service.activeSessionCount());
    }

    @Test
    @DisplayName("重複確認只生效一次；callback 拋錯回 OPERATION_FAILED 且仍視為已解決")
    void duplicateConfirm_runsOnce() {
        FakeGuiService service = new FakeGuiService();
        UUID player = player();
        long generation = open(service, player).session().generation();
        AtomicInteger runs = new AtomicInteger();

        String token = service.createConfirmation(player, generation, "a", runs::incrementAndGet)
            .confirmation().actionToken();
        assertEquals(1, service.pendingActionCount());

        assertEquals(GuiState.SUCCESS, service.confirm(player, generation, token).state());
        GuiResult repeat = service.confirm(player, generation, token);
        assertEquals(GuiState.REJECTED, repeat.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, repeat.errorCode());
        assertEquals(1, runs.get());

        String failing = service.createConfirmation(player, generation, "b", () -> {
            throw new IllegalStateException("boom");
        }).confirmation().actionToken();
        GuiResult failed = service.confirm(player, generation, failing);
        assertEquals(GuiState.FAILED, failed.state());
        assertEquals(GuiErrorCode.OPERATION_FAILED, failed.errorCode());
        GuiResult afterFailure = service.confirm(player, generation, failing);
        assertEquals(GuiState.REJECTED, afterFailure.state());
        assertEquals(GuiErrorCode.ACTION_ALREADY_RESOLVED, afterFailure.errorCode());
    }

    @Test
    @DisplayName("離線玩家開啟失敗；關閉仍可成功（已不再屬於此 GUI）")
    void offline_openFailsCloseSucceeds() {
        FakeGuiService service = new FakeGuiService();
        UUID player = player();
        long generation = open(service, player).session().generation();

        service.markOffline(player);
        GuiResult closed = service.closeInventory(player, generation);
        assertEquals(GuiState.SUCCESS, closed.state(), "關閉語意為已不再屬於此 GUI");

        GuiResult reopen = service.openInventory(
            GuiArgument.of(player, "Fake", 9, List.of()));
        assertEquals(GuiState.FAILED, reopen.state());
        assertEquals(GuiErrorCode.OPERATION_FAILED, reopen.errorCode());

        service.markOnline(player);
        GuiResult back = service.openInventory(
            GuiArgument.of(player, "Fake", 9, List.of()));
        assertEquals(GuiState.SUCCESS, back.state());
    }

    @Test
    @DisplayName("過時非同步請求被拒；當期請求同步執行 renderer")
    void asyncUpdate_currentSucceedsStaleRejected() {
        FakeGuiService service = new FakeGuiService();
        UUID player = player();
        long generation = open(service, player).session().generation();
        AtomicInteger runs = new AtomicInteger();

        GuiResult first = service.beginAsyncUpdate(player, generation, 0);
        GuiResult second = service.beginAsyncUpdate(player, generation, 0);

        GuiResult stale = service.applyAsyncUpdate(first.asyncRequest(),
            GuiPage.<String>empty(), runs::incrementAndGet);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.STALE_REQUEST, stale.errorCode());
        assertEquals(0, runs.get());

        GuiResult current = service.applyAsyncUpdate(second.asyncRequest(),
            GuiPage.<String>content(0, 1, List.of("x")), runs::incrementAndGet);
        assertEquals(GuiState.SUCCESS, current.state());
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("停用後新工作被拒；null 輸入拋 IAE")
    void shutdownAndNulls() {
        FakeGuiService service = new FakeGuiService();
        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus());
        GuiResult result = service.openInventory(
            GuiArgument.of(player(), "Fake", 9, List.of()));
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());

        FakeGuiService fresh = new FakeGuiService();
        assertEquals("READY", fresh.getModuleStatus());
        assertThrows(IllegalArgumentException.class, () -> fresh.openInventory(null));
        assertTrue(fresh.activeSessionCount() == 0 && fresh.pendingActionCount() == 0);
    }
}
