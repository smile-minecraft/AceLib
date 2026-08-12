package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 非同步更新請求合約（Phase 11 延伸第三切片：非同步資料載入後安全更新 GUI）。
 *
 * <p>對應 Evidence Pack §5 TDD：先以本檔取得真實 Red，再最小 Green。覆蓋：</p>
 * <ul>
 *   <li>正常更新（在安全 player context 執行 renderer 恰好一次）</li>
 *   <li>loading / empty / error 有明確結果</li>
 *   <li>stale request（被後發請求取代）</li>
 *   <li>舊 session / page mismatch（session generation 改變）</li>
 *   <li>關閉 / 離線 / shutdown 後套用被拒絕</li>
 *   <li>executor 拒絕派送（scheduler rejection）</li>
 *   <li>不覆寫新 inventory（inventory link generation 不符）</li>
 *   <li>request generation 單調遞增</li>
 * </ul>
 *
 * <p>本測試透過 {@link PlayerContextExecutor#direct()} 注入「直接同步執行」executor，
 * 模擬 Paper main-thread 環境下同一 region context 內執行的語意，使 renderer 與
 * inventory link 檢查可同步驗證。Folia runtime 路徑需另行以
 * {@code SafeScheduler.runForPlayer} 包裝，本測試不涵蓋（MockBukkit 只能模擬
 * Paper-like 行為）。</p>
 */
@DisplayName("GuiService 非同步更新請求合約")
class GuiAsyncUpdateTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private GuiServiceImpl service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        uuid = player.getUniqueId();
        // 注入同步 executor：MockBukkit 是 Paper-like，main thread 直接執行即 region context 安全。
        service = new GuiServiceImpl(PlayerContextExecutor.direct());
    }

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.shutdown();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // beginAsyncUpdate：合約與單調 request generation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("beginAsyncUpdate 回 SUCCESS + 不可為 null 的 GuiAsyncRequest，綁定 UUID/sessionGeneration/pageIndex")
    void beginAsyncUpdate_returnsRequest() {
        long generation = openSession();
        GuiResult result = service.beginAsyncUpdate(uuid, generation, 0);
        assertEquals(GuiState.SUCCESS, result.state());
        GuiAsyncRequest request = result.asyncRequest();
        assertNotNull(request, "SUCCESS 結果必須附帶 asyncRequest");
        assertEquals(uuid, request.playerUuid());
        assertEquals(generation, request.sessionGeneration());
        assertEquals(0, request.pageIndex());
        assertTrue(request.requestGeneration() > 0L,
            "requestGeneration 必須為正數；實際: " + request.requestGeneration());
    }

    @Test
    @DisplayName("beginAsyncUpdate 對同一 session 連續呼叫：requestGeneration 單調遞增")
    void beginAsyncUpdate_requestGenerationIsMonotonic() {
        long generation = openSession();
        GuiAsyncRequest first = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();
        GuiAsyncRequest second = service.beginAsyncUpdate(uuid, generation, 1).asyncRequest();
        assertTrue(second.requestGeneration() > first.requestGeneration(),
            "第二次 begin 的 requestGeneration 必須 > 第一次；第一次="
                + first.requestGeneration() + "，第二次=" + second.requestGeneration());
    }

    @Test
    @DisplayName("beginAsyncUpdate 對錯誤 generation 回 REJECTED + ACELIB-GUI-011")
    void beginAsyncUpdate_staleGeneration_isRejected() {
        long generation = openSession();
        GuiResult result = service.beginAsyncUpdate(uuid, generation + 1L, 0);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, result.errorCode());
    }

    @Test
    @DisplayName("beginAsyncUpdate 對無 session 回 REJECTED + ACELIB-GUI-008")
    void beginAsyncUpdate_noSession_isRejected() {
        GuiResult result = service.beginAsyncUpdate(uuid, 1L, 0);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, result.errorCode());
    }

    @Test
    @DisplayName("beginAsyncUpdate 對 shutdown 服務回 REJECTED + ACELIB-GUI-002")
    void beginAsyncUpdate_shutdown_isRejected() {
        long generation = openSession();
        service.shutdown();
        GuiResult result = service.beginAsyncUpdate(uuid, generation, 0);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());
    }

    // -----------------------------------------------------------------
    // applyAsyncUpdate：正常更新
    // -----------------------------------------------------------------

    @Test
    @DisplayName("applyAsyncUpdate 正常完成：renderer 在 player context 恰好執行一次並回 SUCCESS")
    void applyAsyncUpdate_normal_executesRendererExactlyOnce() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiPage<String> page = GuiPage.content(0, 1, List.of("a", "b"));
        GuiResult result = service.applyAsyncUpdate(request, page, () -> rendererRan.set(true));

        assertEquals(GuiState.SUCCESS, result.state(),
            "正常套用必須回 SUCCESS");
        assertTrue(rendererRan.get(), "renderer 必須被執行（在安全 player context 內）");
        assertTrue(result.detail().contains("page=CONTENT"),
            "detail 必須反映頁面種類: " + result.detail());
    }

    @Test
    @DisplayName("applyAsyncUpdate 對 loading / empty / error 頁面都有明確結果且 renderer 執行")
    void applyAsyncUpdate_loadingEmptyError_haveClearResults() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        // LOADING
        AtomicBoolean loadingRan = new AtomicBoolean();
        GuiResult loading = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> loadingRan.set(true));
        assertEquals(GuiState.SUCCESS, loading.state());
        assertTrue(loadingRan.get());

        // EMPTY
        AtomicBoolean emptyRan = new AtomicBoolean();
        GuiResult empty = service.applyAsyncUpdate(request, GuiPage.empty(),
            () -> emptyRan.set(true));
        assertEquals(GuiState.SUCCESS, empty.state());
        assertTrue(emptyRan.get());

        // ERROR（攜帶 ACELIB-GUI-* 錯誤代碼）
        AtomicBoolean errorRan = new AtomicBoolean();
        GuiPage<String> errorPage = GuiPage.error(GuiErrorCode.OPERATION_FAILED, "boom");
        GuiResult error = service.applyAsyncUpdate(request, errorPage,
            () -> errorRan.set(true));
        assertEquals(GuiState.SUCCESS, error.state(),
            "error 頁面仍須成功套用（renderer 負責呈現錯誤 UI）");
        assertTrue(errorRan.get());
        assertTrue(error.detail().contains("page=ERROR"),
            "detail 必須反映 ERROR 種類: " + error.detail());
        assertTrue(error.detail().contains(GuiErrorCode.OPERATION_FAILED),
            "detail 必須攜帶錯誤代碼: " + error.detail());
    }

    // -----------------------------------------------------------------
    // applyAsyncUpdate：stale / mismatch / lifecycle 拒絕
    // -----------------------------------------------------------------

    @Test
    @DisplayName("applyAsyncUpdate 對 stale request（被後發請求取代）回 REJECTED + ACELIB-GUI-016，renderer 不執行")
    void applyAsyncUpdate_staleRequest_isRejected() {
        long generation = openSession();
        GuiAsyncRequest old = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();
        // 後發請求取代舊請求（request generation 遞增）
        service.beginAsyncUpdate(uuid, generation, 1);

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(old, GuiPage.loading(),
            () -> rendererRan.set(true));

        assertEquals(GuiState.REJECTED, result.state(),
            "過時請求必須被拒絕");
        assertEquals(GuiErrorCode.STALE_REQUEST, result.errorCode(),
            "過時請求必須回 ACELIB-GUI-016 STALE_REQUEST");
        assertEquals(false, rendererRan.get(),
            "過時請求的 renderer 不得執行（不覆寫目前 GUI）");
    }

    @Test
    @DisplayName("applyAsyncUpdate 對舊 session generation（玩家重新開啟 GUI）回 REJECTED + ACELIB-GUI-011")
    void applyAsyncUpdate_sessionMismatch_isRejected() {
        long generation = openSession();
        GuiAsyncRequest old = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        // 關閉後重新開啟 → 新 session generation
        service.closeInventory(uuid, generation);
        long newGeneration = openSession();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(old, GuiPage.loading(),
            () -> rendererRan.set(true));

        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, result.errorCode());
        assertEquals(false, rendererRan.get(), "舊 session 的 renderer 不得執行");
        // 新 session 仍可用
        assertEquals(GuiState.SUCCESS,
            service.beginAsyncUpdate(uuid, newGeneration, 0).state());
    }

    @Test
    @DisplayName("applyAsyncUpdate 對已關閉 session 回 REJECTED + ACELIB-GUI-008")
    void applyAsyncUpdate_afterClose_isRejected() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        service.closeInventory(uuid, generation);

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, result.errorCode());
        assertEquals(false, rendererRan.get());
    }

    @Test
    @DisplayName("applyAsyncUpdate 對離線玩家回 REJECTED + ACELIB-GUI-017，renderer 不執行")
    void applyAsyncUpdate_playerOffline_isRejected() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        player.disconnect();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.REJECTED, result.state(),
            "離線玩家套用必須被拒絕");
        assertEquals(GuiErrorCode.PLAYER_OFFLINE, result.errorCode(),
            "離線必須回 ACELIB-GUI-017 PLAYER_OFFLINE");
        assertEquals(false, rendererRan.get(), "不得對離線玩家執行 renderer");
    }

    @Test
    @DisplayName("applyAsyncUpdate 對 shutdown 服務回 REJECTED + ACELIB-GUI-002")
    void applyAsyncUpdate_afterShutdown_isRejected() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        service.shutdown();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode());
        assertEquals(false, rendererRan.get());
    }

    @Test
    @DisplayName("applyAsyncUpdate 對 executor 拒絕派送回 FAILED + ACELIB-GUI-013，renderer 不執行")
    void applyAsyncUpdate_executorRejection_isRejected() {
        // 第一次派送（openInventory）允許，第二次（applyAsyncUpdate）拒絕
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PlayerContextExecutor rejectingAfterFirst = (p, r) -> calls.incrementAndGet() == 1;
        GuiServiceImpl svc = new GuiServiceImpl(rejectingAfterFirst);
        try {
            GuiArgument arg = GuiArgument.of(player, "Reject", 9, List.of());
            GuiResult opened = svc.openInventory(arg);
            assertEquals(GuiState.SUCCESS, opened.state(), "open 必須成功（第一次派送）");
            long generation = opened.session().generation();
            GuiAsyncRequest request = svc.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

            AtomicBoolean rendererRan = new AtomicBoolean();
            GuiResult result = svc.applyAsyncUpdate(request, GuiPage.loading(),
                () -> rendererRan.set(true));
            assertEquals(GuiState.FAILED, result.state(),
                "executor 拒絕派送必須回 FAILED");
            assertEquals(GuiErrorCode.SCHEDULER_REJECTED, result.errorCode(),
                "executor 拒絕必須回 ACELIB-GUI-013 SCHEDULER_REJECTED");
            assertEquals(false, rendererRan.get(), "拒絕派送時 renderer 不得執行");
        } finally {
            svc.shutdown();
        }
    }

    @Test
    @DisplayName("applyAsyncUpdate 對 inventory link generation 不符回 REJECTED + ACELIB-GUI-018，不覆寫新 inventory")
    void applyAsyncUpdate_inventoryMismatch_doesNotOverwrite() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        // 模擬玩家切換到不同 inventory：解除舊 link（link generation 不再相符）
        Inventory top = player.getOpenInventory().getTopInventory();
        GuiInventoryLink.unlink(top);

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult result = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.REJECTED, result.state(),
            "inventory 已不再是本 session 綁定的 inventory 時必須拒絕");
        assertEquals(GuiErrorCode.INVENTORY_MISMATCH, result.errorCode(),
            "必須回 ACELIB-GUI-018 INVENTORY_MISMATCH");
        assertEquals(false, rendererRan.get(), "不得覆寫新的 inventory");
    }

    @Test
    @DisplayName("applyAsyncUpdate renderer 拋例外不吞錯：回 FAILED + ACELIB-GUI-012 且 detail 含可追蹤訊息")
    void applyAsyncUpdate_rendererFailure_isNotSwallowed() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        GuiResult result = service.applyAsyncUpdate(request, GuiPage.content(0, 1, List.of("x")),
            () -> { throw new IllegalStateException("render exploded"); });
        assertEquals(GuiState.FAILED, result.state(),
            "renderer 失敗必須回 FAILED，不可吞錯");
        assertEquals(GuiErrorCode.OPERATION_FAILED, result.errorCode());
        assertTrue(result.detail().contains("render exploded"),
            "detail 必須包含可追蹤的例外訊息: " + result.detail());
    }

    @Test
    @DisplayName("applyAsyncUpdate 對 null 引數丟 IllegalArgumentException + ACELIB-GUI-007")
    void applyAsyncUpdate_nullArgs_isRejected() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();
        try {
            service.applyAsyncUpdate(null, GuiPage.loading(), () -> { });
            org.junit.jupiter.api.Assertions.fail("預期 IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT));
        }
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
}
