package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.inventory.Inventory;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 非同步更新「延遲執行（deferred）」路徑合約（Phase 11 延伸第三切片）。
 *
 * <p>對應 Momus blocking：{@code applyAsyncUpdate} 在 enqueue 後、renderer 真正執行前
 * 必須重新驗證 running / session / generation / request / player / link；close / 新請求 /
 * shutdown / offline 發生在 enqueue 與執行窗口之間時，renderer 不得執行。</p>
 *
 * <p>本測試使用 {@link PlayerContextExecutor#deferred()} 注入「enqueue 但不立即執行」的
 * executor，模擬 production {@code SafeSchedulerPlayerContextExecutor} 的 Folia entity
 * scheduler enqueue 語意。這是 {@link PlayerContextExecutor#direct()}（同步）無法覆蓋的
 * 關鍵路徑：direct 在 enqueue 當下就執行 renderer，無法重現「enqueue 成功、執行前狀態改變」
 * 的 race。</p>
 *
 * <p>測試結構：</p>
 * <ol>
 *   <li>{@code openInventory} 的 runnable 先透過 {@code runPending()} 實際開啟並 link
 *       inventory（否則 renderer 的 inventory link 檢查無法通過）；</li>
 *   <li>{@code applyAsyncUpdate} 將 renderer 的 runnable enqueue，回傳 {@code ACCEPTED}
 *       （證明 enqueue 當下 renderer 尚未執行）；</li>
 *   <li>在 enqueue 與執行窗口之間改變狀態（close / 新請求 / shutdown / offline）；</li>
 *   <li>再次 {@code runPending()} 執行 deferred runnable，驗證 renderer 是否執行。</li>
 * </ol>
 */
@DisplayName("GuiService 非同步更新 deferred（enqueue 後重新驗證）合約")
class GuiAsyncUpdateDeferredTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private GuiServiceImpl service;
    private PlayerContextExecutor.DeferredPlayerContextExecutor deferred;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        uuid = player.getUniqueId();
        // 注入延遲 executor：enqueue 但不立即執行，模擬 Folia entity scheduler 窗口。
        deferred = PlayerContextExecutor.deferred();
        service = new GuiServiceImpl(deferred);
    }

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.shutdown();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 正常 deferred 路徑：enqueue → 執行窗口無變化 → renderer 恰好執行一次
    // -----------------------------------------------------------------

    @Test
    @DisplayName("deferred 正常路徑：applyAsyncUpdate 回 ACCEPTED（renderer 尚未執行），runPending 後 renderer 恰好執行一次")
    void deferred_normal_enqueueThenRun_executesRendererExactlyOnce() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicInteger renderCount = new AtomicInteger();
        GuiPage<String> page = GuiPage.content(0, 1, List.of("a", "b"));
        GuiResult enqueued = service.applyAsyncUpdate(request, page,
            renderCount::incrementAndGet);

        // enqueue 當下：executor 接受派送但 renderer 尚未執行 → ACCEPTED
        assertEquals(GuiState.ACCEPTED, enqueued.state(),
            "deferred executor 必須回 ACCEPTED（enqueue 成功，renderer 尚未執行）");
        assertEquals(0, renderCount.get(), "enqueue 當下 renderer 不得執行");
        assertEquals(1, deferred.pendingCount(), "必須恰好有一個 deferred runnable 待執行");

        // 執行窗口無狀態變化 → runPending 執行 renderer 恰好一次
        deferred.runPending();
        assertEquals(1, renderCount.get(), "renderer 必須恰好執行一次");
        assertEquals(0, deferred.pendingCount(), "執行後佇列必須清空");
    }

    // -----------------------------------------------------------------
    // deferred race：enqueue 後、執行前狀態改變 → renderer 不執行
    // -----------------------------------------------------------------

    @Test
    @DisplayName("deferred race：enqueue 後 closeInventory → runPending 時 renderer 不執行（SESSION_NOT_FOUND）")
    void deferred_closeAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：關閉 session（同步移除 registry 與 requestGeneration）
        GuiResult closed = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, closed.state());

        // 執行 deferred runnable：revalidation 於執行當下發現 session 已不存在
        deferred.runPending();
        assertFalse(rendererRan.get(),
            "close 發生在 enqueue 與執行窗口之間時，renderer 不得執行");
    }

    @Test
    @DisplayName("deferred race：enqueue 後新 beginAsyncUpdate（取代舊請求）→ runPending 時 renderer 不執行（STALE_REQUEST）")
    void deferred_newRequestAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest old = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(old, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：後發請求遞增 requestGeneration，取代舊請求
        GuiResult newer = service.beginAsyncUpdate(uuid, generation, 1);
        assertEquals(GuiState.SUCCESS, newer.state());

        deferred.runPending();
        assertFalse(rendererRan.get(),
            "舊請求被後發請求取代時，renderer 不得執行（不覆寫目前 GUI）");
    }

    @Test
    @DisplayName("deferred race：enqueue 後 shutdown → runPending 時 renderer 不執行（SHUTDOWN）")
    void deferred_shutdownAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：服務停用
        service.shutdown();
        assertFalse(service.isRunning(), "shutdown 後服務必須停止");

        deferred.runPending();
        assertFalse(rendererRan.get(),
            "shutdown 發生在 enqueue 與執行窗口之間時，renderer 不得執行");
    }

    @Test
    @DisplayName("deferred race：enqueue 後玩家離線 → runPending 時 renderer 不執行（PLAYER_OFFLINE）")
    void deferred_offlineAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：玩家離線
        player.disconnect();

        deferred.runPending();
        assertFalse(rendererRan.get(),
            "玩家離線發生在 enqueue 與執行窗口之間時，renderer 不得執行");
    }

    @Test
    @DisplayName("deferred race：enqueue 後玩家重新開啟 GUI（新 session generation）→ runPending 時 renderer 不執行（GENERATION_MISMATCH）")
    void deferred_sessionReopenAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest old = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(old, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：關閉後重新開啟 → 新 session generation
        service.closeInventory(uuid, generation);
        long newGeneration = openSession();

        deferred.runPending();
        assertFalse(rendererRan.get(),
            "舊 session generation 失效時，renderer 不得執行");
        // 新 session 仍可用
        assertEquals(GuiState.SUCCESS,
            service.beginAsyncUpdate(uuid, newGeneration, 0).state());
    }

    @Test
    @DisplayName("deferred race：enqueue 後 inventory link 被替換（不同 generation）→ runPending 時 renderer 不執行（INVENTORY_MISMATCH）")
    void deferred_linkReplacedAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：同一個 inventory 被重新綁定到不同 generation
        // （例如玩家開了另一個 GUI 並複用 inventory 實例，或 link 被外部重建）。
        Inventory inv = player.getOpenInventory().getTopInventory();
        GuiInventoryLink.link(inv, generation + 1000L);

        // 執行 deferred runnable：revalidation 於執行當下發現 link generation 不符
        deferred.runPending();
        assertFalse(rendererRan.get(),
            "inventory link 被替換時，renderer 不得執行（不得覆寫非本 session 的 inventory）");

        // 控制組：還原正確 link 後，同一個 request 仍可被接受並執行 renderer，
        // 證明拒絕原因僅為 link mismatch（session / generation / request / player 皆仍有效）。
        GuiInventoryLink.link(inv, generation);
        GuiResult reEnqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, reEnqueued.state());
        deferred.runPending();
        assertTrue(rendererRan.get(),
            "link 還原後，同一 request 的 renderer 必須執行，證明先前拒絕為 INVENTORY_MISMATCH");
    }

    @Test
    @DisplayName("deferred race：enqueue 後 inventory link 被解除（unlink）→ runPending 時 renderer 不執行（INVENTORY_MISMATCH）")
    void deferred_linkClearedAfterEnqueue_rendererNotExecuted() {
        long generation = openSession();
        GuiAsyncRequest request = service.beginAsyncUpdate(uuid, generation, 0).asyncRequest();

        AtomicBoolean rendererRan = new AtomicBoolean();
        GuiResult enqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, enqueued.state());

        // enqueue 與執行窗口之間：inventory link 被解除（例如 InventoryCloseEvent
        // 觸發 unlink，或 reload 清空所有 link）。
        Inventory inv = player.getOpenInventory().getTopInventory();
        GuiInventoryLink.unlink(inv);

        deferred.runPending();
        assertFalse(rendererRan.get(),
            "inventory link 被解除時，renderer 不得執行（generationOf 回傳 null）");

        // 控制組：重新綁定正確 link 後，同一 request 的 renderer 仍執行，
        // 證明拒絕原因僅為 link mismatch。
        GuiInventoryLink.link(inv, generation);
        GuiResult reEnqueued = service.applyAsyncUpdate(request, GuiPage.loading(),
            () -> rendererRan.set(true));
        assertEquals(GuiState.ACCEPTED, reEnqueued.state());
        deferred.runPending();
        assertTrue(rendererRan.get(),
            "link 重新綁定後，同一 request 的 renderer 必須執行，證明先前拒絕為 INVENTORY_MISMATCH");
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * 開啟 session 並實際執行 openInventory 的 deferred runnable，使 inventory 開啟且
     * 與 session generation 綁定（renderer 的 inventory link 檢查才能通過）。
     */
    private long openSession() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, opened.state(), "前置 openInventory 必須成功");
        // 實際執行 open runnable：開啟並 link inventory
        deferred.runPending();
        return opened.session().generation();
    }
}
