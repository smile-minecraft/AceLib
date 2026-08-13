package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API-surface contract test（Plan Phase 11 / Momus blocking finding）。
 *
 * <p>實作類別 {@link GuiServiceImpl}、其 constructors/factory 與 session registry
 * {@link GuiSessionRegistry} 不得作為 public API 暴露；下游插件只能依賴
 * {@link GuiService} 介面與公開值物件（{@code GuiResult} / {@code GuiSession} /
 * {@code GuiArgument} / {@code GuiConfirmation} / {@code GuiAsyncRequest} /
 * {@code GuiPage} / {@code GuiErrorCode}）。</p>
 *
 * <p>本測試位於 {@code gui} 套件內，因此即使目標型別為 package-private 仍可透過
 * reflection 讀取其 modifiers；修正前（仍為 public）會 Red，修正後（收斂為
 * package-private）轉為 Green。</p>
 */
class GuiApiSurfaceTest {

    @Test
    @DisplayName("GuiServiceImpl 實作類別不得為 public")
    void guiServiceImpl_isNotPublic() {
        assertFalse(Modifier.isPublic(GuiServiceImpl.class.getModifiers()),
            "GuiServiceImpl 必須為 package-private / non-public，不得暴露為 public API");
    }

    @Test
    @DisplayName("GuiServiceImpl 的 constructors 不得為 public")
    void guiServiceImpl_constructorsAreNotPublic() {
        for (java.lang.reflect.Constructor<?> c
                : GuiServiceImpl.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(c.getModifiers()),
                "GuiServiceImpl 建構子必須為 package-private / non-public: " + c);
        }
    }

    @Test
    @DisplayName("GuiServiceImpl.forProduction factory 不得為 public")
    void guiServiceImpl_forProductionIsNotPublic() {
        boolean found = false;
        for (java.lang.reflect.Method m : GuiServiceImpl.class.getDeclaredMethods()) {
            if ("forProduction".equals(m.getName())) {
                found = true;
                assertFalse(Modifier.isPublic(m.getModifiers()),
                    "GuiServiceImpl.forProduction 必須為 package-private / non-public");
            }
        }
        assertTrue(found, "GuiServiceImpl 必須提供 forProduction factory");
    }

    @Test
    @DisplayName("GuiSessionRegistry 不得為 public")
    void guiSessionRegistry_isNotPublic() {
        assertFalse(Modifier.isPublic(GuiSessionRegistry.class.getModifiers()),
            "GuiSessionRegistry 必須為 package-private / non-public，不得暴露為 public API");
    }

    @Test
    @DisplayName("GuiServiceUnavailableImpl 實作類別不得為 public")
    void guiServiceUnavailableImpl_isNotPublic() {
        assertFalse(Modifier.isPublic(GuiServiceUnavailableImpl.class.getModifiers()),
            "GuiServiceUnavailableImpl 必須為 package-private / non-public，不得暴露為 public API");
    }

    @Test
    @DisplayName("GuiServiceUnavailableImpl 的 constructor 不得為 public")
    void guiServiceUnavailableImpl_constructorIsNotPublic() {
        for (java.lang.reflect.Constructor<?> c
                : GuiServiceUnavailableImpl.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(c.getModifiers()),
                "GuiServiceUnavailableImpl 建構子必須為 package-private / non-public: " + c);
        }
    }
}
