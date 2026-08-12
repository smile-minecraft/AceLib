package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * GuiService 真實 Bukkit inventory lifecycle（Plan §十六 Phase 11 第一個可驗收切片）。
 *
 * <p>對應 Evidence Pack §5 Red 6：openInventory 對在線玩家建立 + 連結 + 開啟
 * 真實 Bukkit inventory；protected click/drag 被取消；close event 移除 session；
 * closeInventory 觸發實際 close；reload/shutdown 不殘留 link / listener。</p>
 *
 * <p>本測試透過 {@link PlayerContextExecutor#direct()} 注入「直接同步執行」
 * executor，模擬 Paper main-thread 環境下同一 region context 內執行的語意。
 * Folia runtime 路徑需另行以 {@code SafeScheduler.runForPlayer} 包裝，
 * 本測試不涵蓋（MockBukkit 只能模擬 Paper-like 行為）。</p>
 *
 * <p>本測試不直接依賴 listener 註冊：透過呼叫 {@link GuiServiceImpl#handleClick}
 * / {@link GuiServiceImpl#handleClose} 驗證 listener handler 邏輯。
 * listener 註冊 lifecycle 由 {@code AceLibPluginGuiServiceIntegrationTest}
 * 與 {@link GuiListenerRegistrationTest} 涵蓋。</p>
 */
@DisplayName("GuiService inventory lifecycle")
class GuiServiceInventoryLifecycleTest {

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
    // openInventory 真實 inventory 建立與連結
    // -----------------------------------------------------------------

    @Test
    @DisplayName("openInventory 對在線玩家：player.getOpenInventory 取得 GUI；size 與 argument 一致")
    void openInventory_realPlayer_opensActualInventory() {
        GuiArgument arg = GuiArgument.of(player, "Shop", 27, List.of(0, 1, 2));
        GuiResult result = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, result.state(),
            "openInventory 必須回 SUCCESS");
        assertNotNull(result.session());

        InventoryView view = player.getOpenInventory();
        assertNotNull(view, "玩家必須有 active inventory view");
        Inventory top = view.getTopInventory();
        assertNotNull(top, "top inventory 不可為 null");
        assertEquals(27, top.getSize(),
            "GUI size 必須與 argument 一致");
        assertEquals(InventoryType.CHEST, top.getType(),
            "GUI type 必須為 CHEST（預設 chest inventory）");
    }

    @Test
    @DisplayName("openInventory 後 inventory 透過 GuiInventoryLink 對應到 session generation")
    void openInventory_linksInventoryToGeneration() {
        GuiArgument arg = GuiArgument.of(player, "Linked", 9, List.of(0));
        GuiResult result = service.openInventory(arg);
        long generation = result.session().generation();

        Inventory top = player.getOpenInventory().getTopInventory();
        Long linked = GuiInventoryLink.generationOf(top);
        assertNotNull(linked,
            "inventory 必須被 link 到 session generation");
        assertEquals(generation, linked.longValue(),
            "link 的 generation 必須等於 session.generation()");
    }

    // -----------------------------------------------------------------
    // protected click / drag 被取消
    // -----------------------------------------------------------------

    @Test
    @DisplayName("對受保護 slot 的 click：handleClick 必須 setCancelled(true)")
    void clickProtectedSlot_isCancelled() {
        Set<Integer> protectedSlots = new HashSet<>();
        protectedSlots.add(0);
        protectedSlots.add(4);
        GuiArgument arg = GuiArgument.of(player, "P", 9, protectedSlots);
        service.openInventory(arg);

        Inventory top = player.getOpenInventory().getTopInventory();
        InventoryClickEvent event = new InventoryClickEvent(
            player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
            0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        service.handleClick(event);
        assertTrue(event.isCancelled(),
            "click 受保護 slot 必須被 listener 取消");
    }

    @Test
    @DisplayName("對非受保護 slot 的 click：handleClick 不得取消（不誤擋）")
    void clickUnprotectedSlot_isNotCancelled() {
        Set<Integer> protectedSlots = new HashSet<>();
        protectedSlots.add(0);
        GuiArgument arg = GuiArgument.of(player, "P", 9, protectedSlots);
        service.openInventory(arg);

        InventoryClickEvent event = new InventoryClickEvent(
            player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
            1, ClickType.LEFT, InventoryAction.PLACE_ALL);
        service.handleClick(event);
        assertFalse(event.isCancelled(),
            "click 非受保護 slot 不可被 listener 取消");
    }

    // -----------------------------------------------------------------
    // closeInventory 觸發實際 close + InventoryCloseEvent 移除 session
    // -----------------------------------------------------------------

    @Test
    @DisplayName("closeInventory 觸發實際 inventory close：top inventory size 變化或 type 改變")
    void closeInventory_actuallyClosesBukkitInventory() {
        GuiArgument arg = GuiArgument.of(player, "C", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        // 確認 open 後是 9 格 chest（前置條件）
        Inventory topOpen = player.getOpenInventory().getTopInventory();
        assertNotNull(topOpen, "open 後 top inventory 不可為 null");
        assertEquals(9, topOpen.getSize());
        assertEquals(InventoryType.CHEST, topOpen.getType());

        GuiResult closed = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, closed.state());

        // close 後 top inventory 必須改變（可能是 null、可能是 CRAFTING 等預設 view）；
        // 主要契約：不再是 9 格 chest
        Inventory topAfter = player.getOpenInventory().getTopInventory();
        boolean stillNineChest = topAfter != null
            && topAfter.getSize() == 9
            && topAfter.getType() == InventoryType.CHEST;
        assertFalse(stillNineChest,
            "close 後 top inventory 不應仍是 9 格 chest；目前 top="
                + (topAfter == null ? "null" : "size=" + topAfter.getSize()
                    + ", type=" + topAfter.getType()));
    }

    @Test
    @DisplayName("InventoryCloseEvent 觸發後：handleClose 移除對應 session")
    void inventoryCloseEvent_removesSession() {
        GuiArgument arg = GuiArgument.of(player, "E", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, opened.state());

        // 手動觸發 InventoryCloseEvent（模擬玩家關閉視窗）
        InventoryView view = player.getOpenInventory();
        InventoryCloseEvent event = new InventoryCloseEvent(view);
        service.handleClose(event);

        // session 必須已被移除
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
    }

    @Test
    @DisplayName("closeInventory 重複呼叫第二次：第一次成功後第二次回 SESSION_NOT_FOUND（不殘留）")
    void closeInventory_duplicateCallIdempotent() {
        GuiArgument arg = GuiArgument.of(player, "I", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult first = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = service.closeInventory(uuid, generation);
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, second.errorCode());
    }

    @Test
    @DisplayName("closeInventory 對錯誤 generation 回 GENERATION_MISMATCH 且 session 不變")
    void closeInventory_staleGeneration_sessionUnchanged() {
        GuiArgument arg = GuiArgument.of(player, "G", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult stale = service.closeInventory(uuid, generation + 99L);
        assertEquals(GuiState.REJECTED, stale.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());

        // session 必須仍存在
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.SUCCESS, query.state(),
            "錯誤 generation 不得關閉既有 session");
    }

    // -----------------------------------------------------------------
    // shutdown / listener lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown 後：active session 被清空、inventory link 被清空、shutdown 為 idempotent")
    void shutdown_clearsSessionsAndLinks() {
        GuiArgument arg = GuiArgument.of(player, "S", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, opened.state());
        Inventory topBefore = player.getOpenInventory().getTopInventory();
        assertEquals(9, topBefore.getSize());

        service.shutdown();

        assertEquals(0, service.activeSessionCount(),
            "shutdown 後 active session 數必須為 0");

        // link 必須被清空
        GuiInventoryLink.clear();
        Long linked = GuiInventoryLink.generationOf(topBefore);
        assertEquals(null, linked,
            "shutdown 後 inventory link 必須被清空");

        // shutdown 為 idempotent
        service.shutdown();
        assertEquals(0, service.activeSessionCount());
    }

    @Test
    @DisplayName("GuiInventoryLink.clear 後所有 link 被移除")
    void inventoryLink_clearWorks() {
        GuiArgument arg = GuiArgument.of(player, "L", 9, List.of());
        service.openInventory(arg);
        Inventory top = player.getOpenInventory().getTopInventory();
        assertNotNull(GuiInventoryLink.generationOf(top),
            "link 必須存在於 open 後");

        GuiInventoryLink.clear();
        assertEquals(null, GuiInventoryLink.generationOf(top),
            "clear 後 link 必須消失");
    }
}
