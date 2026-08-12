package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 相容性測試：驗證 Phase 11 延伸方法（createConfirmation / confirm / cancel /
 * beginAsyncUpdate / applyAsyncUpdate）為 binary/source compatible 的 default methods。
 *
 * <p>本測試建立一個只實作「既有抽象方法」的 legacy stub（模擬 Phase 11 之前已存在、
 * 未 override 這五個方法的第三方 {@code GuiService} 實作）。該 stub 必須能夠編譯
 * （source compatible），且呼叫這五個新方法時得到 non-null、可診斷的 {@link GuiResult}
 * 拒絕，絕不執行 callback / renderer，也不丟 {@link AbstractMethodError}。</p>
 *
 * <p>對應 Momus blocking finding：新增的 abstract methods 會破壞既有外部實作者。</p>
 */
class GuiServiceCompatibilityTest {

    /**
     * Legacy implementation that predates the Phase 11 extension methods.
     * 只實作原本的抽象方法，不 override 五個延伸方法。
     */
    private static final class LegacyGuiServiceStub implements GuiService {

        @Override
        public GuiResult openInventory(GuiArgument argument) {
            return GuiResult.rejected(GuiErrorCode.NOT_READY, "legacy stub");
        }

        @Override
        public GuiResult closeInventory(UUID playerUuid, long generation) {
            return GuiResult.rejected(GuiErrorCode.NOT_READY, "legacy stub");
        }

        @Override
        public GuiResult getActiveSession(UUID playerUuid) {
            return GuiResult.rejected(GuiErrorCode.NOT_READY, "legacy stub");
        }

        @Override
        public GuiResult validateClick(UUID playerUuid, long generation, int slot) {
            return GuiResult.rejected(GuiErrorCode.NOT_READY, "legacy stub");
        }

        @Override
        public String getModuleStatus() {
            return "NOT_INITIALIZED";
        }

        @Override
        public void shutdown() {
            // no-op for legacy stub
        }
    }

    private final LegacyGuiServiceStub stub = new LegacyGuiServiceStub();
    private final UUID uuid = UUID.randomUUID();

    @Test
    void legacyStubNewMethodsReturnSafeRejection() {
        // createConfirmation：callback 絕不執行
        boolean[] callbackRan = {false};
        GuiResult r1 = stub.createConfirmation(uuid, 1L, "act",
            () -> callbackRan[0] = true); // callback 不得被 default 執行
        assertNotNull(r1, "createConfirmation 必須回 non-null GuiResult");
        assertTrue(r1.isFailed(), "legacy 實作應以 FAILED 拒絕");
        assertEquals(GuiErrorCode.OPERATION_FAILED, r1.errorCode());
        assertEquals(false, callbackRan[0], "default 不得執行 callback");

        // confirm
        GuiResult r2 = stub.confirm(uuid, 1L, "token");
        assertNotNull(r2);
        assertEquals(GuiErrorCode.OPERATION_FAILED, r2.errorCode());

        // cancel
        GuiResult r3 = stub.cancel(uuid, 1L, "token");
        assertNotNull(r3);
        assertEquals(GuiErrorCode.OPERATION_FAILED, r3.errorCode());

        // beginAsyncUpdate
        GuiResult r4 = stub.beginAsyncUpdate(uuid, 1L, 0);
        assertNotNull(r4);
        assertEquals(GuiErrorCode.OPERATION_FAILED, r4.errorCode());

        // applyAsyncUpdate：renderer 絕不執行
        boolean[] rendererRan = {false};
        GuiResult r5 = stub.applyAsyncUpdate(
            new GuiAsyncRequest(uuid, 1L, 0, 1L),
            GuiPage.content(0, 1, List.of("x")),
            () -> rendererRan[0] = true);
        assertNotNull(r5);
        assertEquals(GuiErrorCode.OPERATION_FAILED, r5.errorCode());
        assertEquals(false, rendererRan[0], "default 不得執行 renderer");
    }

    @Test
    void legacyStubNullInputsThrowContractException() {
        assertThrows(IllegalArgumentException.class,
            () -> stub.createConfirmation(null, 1L, "act", () -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> stub.createConfirmation(uuid, 1L, null, () -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> stub.confirm(uuid, 1L, null));
        assertThrows(IllegalArgumentException.class,
            () -> stub.cancel(uuid, 1L, null));
        assertThrows(IllegalArgumentException.class,
            () -> stub.beginAsyncUpdate(null, 1L, 0));
        assertThrows(IllegalArgumentException.class,
            () -> stub.applyAsyncUpdate(null,
                GuiPage.content(0, 1, List.of("x")), () -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> stub.applyAsyncUpdate(
                new GuiAsyncRequest(uuid, 1L, 0, 1L), null, () -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> stub.applyAsyncUpdate(
                new GuiAsyncRequest(uuid, 1L, 0, 1L),
                GuiPage.content(0, 1, List.of("x")), null));
    }
}
