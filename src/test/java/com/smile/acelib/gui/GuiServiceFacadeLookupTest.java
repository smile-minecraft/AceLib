package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.AceLibApi;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GUI service facade lookup contract.
 *
 * <p>對應 Phase 11 Evidence Pack §5 Red 1：確認 {@code AceLibApi.getGuiService()}
 * 永不為 null，且未啟用（uninitialized）狀態下每次呼叫皆回 REJECTED + ACELIB-GUI-001。
 * 後續插件應能在 onEnable 之前就查得 facade 物件，無需 null 判斷。</p>
 *
 * <p>本測試對應 WorldServiceFacadeLookupTest 的 GUI 等價版本，
 * 驗證 lookup 與不可用狀態的「拒絕但不丟例外」契約。</p>
 */
@DisplayName("GuiService facade lookup")
class GuiServiceFacadeLookupTest {

    @Test
    @DisplayName("AceLibApi.uninitialized().getGuiService() 不可為 null")
    void uninitializedApi_facadeIsNonNull() {
        AceLibApi api = AceLibApi.uninitialized();
        GuiService svc = api.getGuiService();
        assertNotNull(svc, "getGuiService 必須永遠不為 null");
    }

    @Test
    @DisplayName("未啟用時 openInventory 應回 REJECTED + ACELIB-GUI-001")
    void uninitializedApi_openInventory_isRejected() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        GuiArgument arg = GuiArgument.of(UUID.randomUUID(), "test", 9,
            java.util.List.of());
        GuiResult result = svc.openInventory(arg);
        assertNotNull(result);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 closeInventory 應回 REJECTED + ACELIB-GUI-001")
    void uninitializedApi_closeInventory_isRejected() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        GuiResult result = svc.closeInventory(UUID.randomUUID(), 1L);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 validateClick 應回 REJECTED + ACELIB-GUI-001")
    void uninitializedApi_validateClick_isRejected() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        GuiResult result = svc.validateClick(UUID.randomUUID(), 1L, 0);
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 getActiveSession 應回 REJECTED + ACELIB-GUI-001")
    void uninitializedApi_getActiveSession_isRejected() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        GuiResult result = svc.getActiveSession(UUID.randomUUID());
        assertEquals(GuiState.REJECTED, result.state());
        assertEquals(GuiErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 getModuleStatus 應回 NOT_INITIALIZED")
    void uninitializedApi_getModuleStatus_isNotInitialized() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("未啟用時 shutdown 為 idempotent no-op，不丟例外")
    void uninitializedApi_shutdown_isNoOp() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        try {
            svc.shutdown();
        } catch (Throwable t) {
            fail("unavailable facade shutdown 必須不丟例外；實際: " + t);
        }
    }

    @Test
    @DisplayName("未啟用時 openInventory 傳入 null 應丟 IllegalArgumentException 帶 ACELIB-GUI-007")
    void uninitializedApi_nullInput_isRejected() {
        GuiService svc = AceLibApi.uninitialized().getGuiService();
        try {
            svc.openInventory(null);
            fail("expected IllegalArgumentException for null input");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(GuiErrorCode.INVALID_INPUT),
                "error code 必須出現在訊息中: " + ex.getMessage());
        }
    }
}
