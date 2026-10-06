package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公開全服務 shutdown 移除驗證（Red：路線圖已決定的 1.4.0 破壞性變更）。
 *
 * <p>玩家開著別的 plugin 的 GUI 時，新的 GUI 取代舊的並通知原擁有者；
 * 作用域關閉只清自己的登記 — 公開介面上不再有「關閉整個服務」的方法。
 * 內部接線（reload／disable）改走內部生命週期，不經公開入口。</p>
 */
@DisplayName("公開 shutdown 移除")
class GuiServiceShutdownRemovalTest {

    @Test
    @DisplayName("GuiService 不再宣告公開 shutdown 方法")
    void guiService_hasNoPublicShutdownMethod() {
        try {
            GuiService.class.getMethod("shutdown");
            fail("GuiService 仍宣告公開 shutdown() — 1.4.0 已決定移除");
        } catch (NoSuchMethodException expected) {
            // 已移除：符合路線圖待決事項 #1 的決定
        }
    }

    @Test
    @DisplayName("內部生命週期入口存在（供 reload／disable 接線），下游不得經 GuiService 取得")
    void internalLifecycle_existsOutsidePublicContract() {
        Class<?> control;
        try {
            control = Class.forName("com.smile.acelib.gui.GuiServiceControl");
        } catch (ClassNotFoundException e) {
            fail("缺少內部生命週期入口 GuiServiceControl（reload／disable 需要它停用服務）");
            return;
        }
        try {
            control.getMethod("shutdownService");
        } catch (NoSuchMethodException e) {
            fail("GuiServiceControl 必須提供 shutdownService()");
        }
        // 內部入口不得出現在公開 GuiService 契約上
        for (var method : GuiService.class.getMethods()) {
            assertTrue(!"shutdownService".equals(method.getName()),
                "shutdownService 不得經公開 GuiService 取得");
        }
    }

    @Test
    @DisplayName("經內部入口停用後，新工作被拒且舊 session 不殘留")
    void shutdownViaControl_rejectsNewWork() {
        GuiService service;
        try {
            Class<?> control = Class.forName("com.smile.acelib.gui.GuiServiceControl");
            service = new GuiServiceImpl();
            assertTrue(control.isInstance(service),
                "GuiServiceImpl 必須實作內部生命週期入口");
            control.getMethod("shutdownService").invoke(service);
        } catch (ReflectiveOperationException e) {
            fail("內部停用路徑不可用: " + e);
            return;
        }
        GuiArgument argument = GuiArgument.of(UUID.randomUUID(), "test", 9,
            java.util.List.of());
        GuiResult result = service.openInventory(argument);
        assertNotNull(result);
        assertEquals(GuiErrorCode.SHUTDOWN, result.errorCode(),
            "內部停用後新工作必須回 SHUTDOWN");
    }
}
