package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * GuiServiceImpl 行為契約（Plan §十六 Phase 11 第一個可驗收切片）。
 *
 * <p>對應 Evidence Pack §5 Red 2-5：openInventory / validateClick / closeInventory
 * 的正常、錯誤、邊界路徑；session 只持有 UUID + generation + owner；
 * 受保護 slot 阻擋；close 移除 session；shutdown 後拒絕新工作。</p>
 *
 * <p>本測試不直接觸發 Bukkit InventoryClickEvent（MockBukkit InventoryView mock
 * 行為有限），改透過服務層的 {@link GuiService#validateClick} 驗證點擊契約，
 * 並用持有 mock player 的 session 覆蓋 callback 路徑以驗證 generation /
 * owner 流程。</p>
 */
@DisplayName("GuiServiceImpl 行為契約")
class GuiServiceImplTest {

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
    // openInventory
    // -----------------------------------------------------------------

    @Test
    @DisplayName("openInventory 回傳 SUCCESS + 不可為 null 的 session，session 內含 UUID + generation")
    void openInventory_returnsSuccessSession() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult result = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, result.state(),
            "openInventory 必須回 SUCCESS");
        GuiSession session = result.session();
        assertNotNull(session, "SUCCESS 結果必須附帶 session");
        assertEquals(uuid, session.playerUuid(),
            "session 必須記錄玩家 UUID");
        assertTrue(session.generation() > 0L,
            "generation 必須為正數；實際: " + session.generation());
    }

    @Test
    @DisplayName("openInventory 對同一玩家重複呼叫：第二次回 REJECTED + ACELIB-GUI-009 SESSION_EXISTS")
    void openInventory_duplicateForSamePlayer_isRejected() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult first = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = service.openInventory(arg);
        assertEquals(GuiState.REJECTED, second.state(),
            "同一玩家已開啟 GUI 時，第二次呼叫必須拒絕");
        assertEquals(GuiErrorCode.SESSION_EXISTS, second.errorCode());
    }

    @Test
    @DisplayName("openInventory 對 null player 應丟 IllegalArgumentException + ACELIB-GUI-007")
    void openInventory_nullPlayer_isRejected() {
        try {
            service.openInventory(null);
            fail("預期 IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT),
                "error code 必須在訊息中: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("不同玩家的 session 互不影響，generation 各自單調遞增")
    void openInventory_multiplePlayers_eachHasOwnSession() {
        PlayerMock player2 = server.addPlayer();
        GuiArgument arg1 = GuiArgument.of(player, "Test1", 9, List.of());
        GuiArgument arg2 = GuiArgument.of(player2, "Test2", 9, List.of());

        GuiResult r1 = service.openInventory(arg1);
        GuiResult r2 = service.openInventory(arg2);

        assertEquals(GuiState.SUCCESS, r1.state());
        assertEquals(GuiState.SUCCESS, r2.state());
        assertEquals(uuid, r1.session().playerUuid());
        assertEquals(player2.getUniqueId(), r2.session().playerUuid());
        // generation 為 monotonic long，但兩個 session 各自的 generation 必須 > 0；
        // 順序由 service 內部分配，無法直接比較兩個玩家的 generation 大小，
        // 兩者各自獨立遞增即可。
        assertTrue(r1.session().generation() > 0L);
        assertTrue(r2.session().generation() > 0L);
    }

    @Test
    @DisplayName("openInventory 攜帶 protected slots：session 必須記錄這些 slot")
    void openInventory_protectedSlots_areStored() {
        Set<Integer> protectedSlots = new HashSet<>();
        protectedSlots.add(0);
        protectedSlots.add(4);
        protectedSlots.add(8);
        GuiArgument arg = GuiArgument.of(player, "Test", 9, protectedSlots);
        GuiResult result = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, result.state());
        GuiSession session = result.session();
        assertEquals(protectedSlots, session.protectedSlots(),
            "session 必須保存 protected slots 給點擊驗證使用");
    }

    // -----------------------------------------------------------------
    // validateClick
    // -----------------------------------------------------------------

    @Test
    @DisplayName("validateClick 對受保護 slot 回 REJECTED + ACELIB-GUI-010 SLOT_PROTECTED")
    void validateClick_protectedSlot_isRejected() {
        Set<Integer> protectedSlots = new HashSet<>();
        protectedSlots.add(0);
        protectedSlots.add(4);
        GuiArgument arg = GuiArgument.of(player, "Test", 9, protectedSlots);
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult click = service.validateClick(uuid, generation, 0);
        assertEquals(GuiState.REJECTED, click.state());
        assertEquals(GuiErrorCode.SLOT_PROTECTED, click.errorCode());
    }

    @Test
    @DisplayName("validateClick 對非受保護 slot 回 ALLOWED（可拿走）")
    void validateClick_unprotectedSlot_isAllowed() {
        Set<Integer> protectedSlots = new HashSet<>();
        protectedSlots.add(0);
        GuiArgument arg = GuiArgument.of(player, "Test", 9, protectedSlots);
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult click = service.validateClick(uuid, generation, 1);
        assertEquals(GuiState.ALLOWED, click.state(),
            "非受保護 slot 必須允許實際遊戲邏輯處理");
    }

    @Test
    @DisplayName("validateClick 對越界 slot 回 REJECTED + ACELIB-GUI-007 INVALID_INPUT")
    void validateClick_outOfBoundsSlot_isRejected() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult click = service.validateClick(uuid, generation, 100);
        assertEquals(GuiState.REJECTED, click.state());
        assertEquals(GuiErrorCode.INVALID_INPUT, click.errorCode());
    }

    @Test
    @DisplayName("validateClick 對不存在 session 回 REJECTED + ACELIB-GUI-008 SESSION_NOT_FOUND")
    void validateClick_noSession_isRejected() {
        GuiResult click = service.validateClick(uuid, 1L, 0);
        assertEquals(GuiState.REJECTED, click.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, click.errorCode());
    }

    @Test
    @DisplayName("validateClick 對錯誤 generation 回 REJECTED + ACELIB-GUI-011 GENERATION_MISMATCH")
    void validateClick_staleGeneration_isRejected() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long validGeneration = opened.session().generation();

        // 故意使用錯誤 generation
        GuiResult click = service.validateClick(uuid, validGeneration + 1L, 0);
        assertEquals(GuiState.REJECTED, click.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, click.errorCode(),
            "錯誤 generation 必須被拒絕；舊 generation 不可被重用");
    }

    @Test
    @DisplayName("validateClick 對 null UUID 應丟 IllegalArgumentException + ACELIB-GUI-007")
    void validateClick_nullUuid_isRejected() {
        try {
            service.validateClick(null, 1L, 0);
            fail("預期 IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT));
        }
    }

    // -----------------------------------------------------------------
    // closeInventory
    // -----------------------------------------------------------------

    @Test
    @DisplayName("closeInventory 對現有 session 回 SUCCESS 並移除 session")
    void closeInventory_existingSession_succeedsAndRemoves() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult close = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, close.state());

        // close 後應無法再找到 session
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
    }

    @Test
    @DisplayName("closeInventory 對不存在 session 回 REJECTED + ACELIB-GUI-008 SESSION_NOT_FOUND")
    void closeInventory_noSession_isRejected() {
        GuiResult close = service.closeInventory(uuid, 1L);
        assertEquals(GuiState.REJECTED, close.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, close.errorCode());
    }

    @Test
    @DisplayName("closeInventory 對錯誤 generation 回 REJECTED + ACELIB-GUI-011 GENERATION_MISMATCH")
    void closeInventory_staleGeneration_isRejected() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult close = service.closeInventory(uuid, generation + 99L);
        assertEquals(GuiState.REJECTED, close.state());
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, close.errorCode());

        // session 必須仍然存在（錯誤 generation 不應誤刪 session）
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.SUCCESS, query.state(),
            "錯誤 generation 不得關閉既有 session");
    }

    @Test
    @DisplayName("closeInventory 重複呼叫第二次：第一次成功後第二次回 REJECTED + SESSION_NOT_FOUND")
    void closeInventory_duplicateClose_secondCallRejected() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult first = service.closeInventory(uuid, generation);
        assertEquals(GuiState.SUCCESS, first.state());

        GuiResult second = service.closeInventory(uuid, generation);
        assertEquals(GuiState.REJECTED, second.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, second.errorCode());
    }

    @Test
    @DisplayName("closeInventory 對 null UUID 應丟 IllegalArgumentException + ACELIB-GUI-007")
    void closeInventory_nullUuid_isRejected() {
        try {
            service.closeInventory(null, 1L);
            fail("預期 IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT));
        }
    }

    // -----------------------------------------------------------------
    // getActiveSession
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getActiveSession 對現有 session 回 SUCCESS + session")
    void getActiveSession_existingSession_returnsSession() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();

        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.SUCCESS, query.state());
        assertNotNull(query.session());
        assertEquals(generation, query.session().generation(),
            "getActiveSession 必須回傳當前 session 的 generation");
    }

    @Test
    @DisplayName("getActiveSession 對無 session 回 REJECTED + SESSION_NOT_FOUND")
    void getActiveSession_noSession_isRejected() {
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
        assertNull(query.session(),
            "SESSION_NOT_FOUND 結果的 session 應為 null");
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getModuleStatus 啟用後回 READY，shutdown 後回 FAILED")
    void getModuleStatus_lifecycle() {
        assertEquals("READY", service.getModuleStatus());
        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus());
    }

    @Test
    @DisplayName("shutdown 後所有 operation 回 REJECTED + ACELIB-GUI-002 SHUTDOWN，且 session 不殘留")
    void shutdown_rejectsAllOperationsAndClearsSessions() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        long generation = opened.session().generation();
        assertEquals(GuiState.SUCCESS, opened.state());

        service.shutdown();

        // 所有後續 operation 必須回 SHUTDOWN
        assertEquals(GuiState.REJECTED, service.openInventory(arg).state());
        assertEquals(GuiErrorCode.SHUTDOWN,
            service.openInventory(arg).errorCode());
        assertEquals(GuiState.REJECTED, service.validateClick(uuid, generation, 0).state());
        assertEquals(GuiErrorCode.SHUTDOWN,
            service.validateClick(uuid, generation, 0).errorCode());
        assertEquals(GuiState.REJECTED, service.closeInventory(uuid, generation).state());
        assertEquals(GuiErrorCode.SHUTDOWN,
            service.closeInventory(uuid, generation).errorCode());

        // shutdown 為 idempotent
        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus());
    }

    @Test
    @DisplayName("internalCleanup(player) 移除 session 而不走 generation 驗證（用於 Bukkit close event）")
    void internalCleanup_removesSessionWithoutGenerationCheck() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult opened = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, opened.state());

        // internal cleanup（模擬 Bukkit InventoryCloseEvent 觸發）
        service.internalCleanup(uuid);

        // 主要契約：session 已被移除（getActiveSession 看不到）
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
    }

    // -----------------------------------------------------------------
    // 直接測試 registry 與 monotonic generation
    // -----------------------------------------------------------------

    /**
     * 驗證 GuiServiceImpl 內部的 session registry 行為符合契約：
     * generation 單調遞增、不重用。
     */
    @Test
    @DisplayName("同一玩家連續 close/open：第二次 open 的 generation 必須大於第一次")
    void generation_isMonotonicAcrossSessions() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());

        GuiResult first = service.openInventory(arg);
        long firstGen = first.session().generation();
        service.closeInventory(uuid, firstGen);

        GuiResult second = service.openInventory(arg);
        long secondGen = second.session().generation();

        assertTrue(secondGen > firstGen,
            "第二次 open 的 generation 必須 > 第一次的 generation；第一次="
                + firstGen + "，第二次=" + secondGen);
    }

    /**
     * 驗證 session 物件正確暴露給測試的契約欄位（owner / protectedSlots）。
     */
    @Test
    @DisplayName("session 物件須暴露 owner（plugin owner 標記）與 size")
    void session_exposesOwnerAndSize() {
        GuiArgument arg = GuiArgument.of(player, "Test", 18, List.of());
        GuiResult result = service.openInventory(arg);
        GuiSession session = result.session();
        assertEquals(18, session.size(),
            "session.size 必須反映 GUI 總 slot 數");
        assertNotNull(session.owner(),
            "session.owner 必須為不可變 plugin owner 標記（非 null）");
    }

    // -----------------------------------------------------------------
    // 驗證 service 對反覆 action 與 null 引數的處理
    // -----------------------------------------------------------------

    @Test
    @DisplayName("openInventory 對 size <= 0 應丟 IllegalArgumentException + ACELIB-GUI-007")
    void openInventory_invalidSize_isRejected() {
        try {
            GuiArgument bad = GuiArgument.builder(player, "Test")
                .size(0)
                .build();
            service.openInventory(bad);
            fail("預期 IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT));
        }
    }

    @Test
    @DisplayName("Generation 跨 registry 從 1 開始 — 應用計數器驗證 implementation 內部行為")
    void openInventory_generationStartsAtOne() {
        GuiArgument arg = GuiArgument.of(player, "Test", 9, List.of());
        GuiResult result = service.openInventory(arg);
        assertEquals(1L, result.session().generation(),
            "第一個 session 的 generation 應為 1（start monotonic 從 1 開始）");
    }

    @Test
    @DisplayName("openInventory 對 null protected-slots 集合通過 builder 模式：應允許（視為無保護）")
    void openInventory_nullProtectedSlots_areAllowed() {
        GuiArgument arg = GuiArgument.builder(player, "Test")
            .size(9)
            .protectedSlots(null)
            .build();
        GuiResult result = service.openInventory(arg);
        assertEquals(GuiState.SUCCESS, result.state());
        assertNotNull(result.session().protectedSlots());
        assertTrue(result.session().protectedSlots().isEmpty(),
            "null protected slots 必須 normalize 為空集合");
    }
}
