package com.example.acelibguiprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code /gprobe send} 案例選擇的單元測試（純函式，不需伺服器）。
 *
 * <p>語意與 {@code /fprobe} 對齊：未給識別碼送全部；有給則去空白、
 * 不分大小寫比對；未知識別碼拋錯且不發送。</p>
 */
class GuiProbeSendSelectionTest {

    private static List<GuiProbeCase> catalog() {
        return GuiProbeCases.buildCatalog();
    }

    private static List<String> ids(List<GuiProbeCase> catalog) {
        return catalog.stream().map(GuiProbeCase::id).toList();
    }

    @Test
    void nullOrBlankSelectsWholeCatalogInOrder() {
        assertEquals(ids(catalog()), ids(GuiProbeSendSelection.selectCases(catalog(), null)));
        assertEquals(ids(catalog()), ids(GuiProbeSendSelection.selectCases(catalog(), "   ")));
    }

    @Test
    void matchingIgnoresCaseAndSurroundingWhitespace() {
        List<GuiProbeCase> selected =
            GuiProbeSendSelection.selectCases(catalog(), "  NAV ");
        assertEquals(1, selected.size());
        assertEquals("nav", selected.get(0).id());
    }

    @Test
    void unknownIdThrowsWithAvailableList() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> GuiProbeSendSelection.selectCases(catalog(), "nope"));
        assertTrue(ex.getMessage().contains("nope"), "錯誤訊息應含使用者輸入");
        assertTrue(ex.getMessage().contains("nav"), "錯誤訊息應列出可用識別碼");
    }
}
