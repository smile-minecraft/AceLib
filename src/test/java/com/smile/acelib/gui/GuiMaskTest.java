package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GuiMask} 字元遮罩契約。
 *
 * <p>遮罩只做「字元 → 固定欄位」的換算：列數 1～6、每列等長、
 * 列數 × 列寬必須為合法箱子尺寸（9／18／27／36／45／54），
 * 不符在建立時拒絕；不做自動版面配置與資料綁定。</p>
 */
@DisplayName("GuiMask 字元遮罩")
class GuiMaskTest {

    @Test
    @DisplayName("7 列被拒：訊息攜帶 INVALID_INPUT 與實際列數")
    void sevenRows_isRejected() {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> GuiMask.of(
                ".........",
                ".........",
                ".........",
                ".........",
                ".........",
                ".........",
                "........."));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT：實際: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("7"),
            "拒絕訊息必須包含實際列數 7：實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("每列不等長被拒：訊息攜帶 INVALID_INPUT 與實際列寬")
    void raggedRows_areRejected() {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> GuiMask.of(
                ".........",
                ".........."));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT：實際: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("10"),
            "拒絕訊息必須包含實際列寬 10：實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("2 列 × 5 寬（=10 非合法箱子尺寸）被拒：訊息攜帶實際數值")
    void illegalSize_isRejected() {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> GuiMask.of(
                "12345",
                "12345"));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT：實際: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("10"),
            "拒絕訊息必須包含實際總格數 10：實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("合法 3 列 × 9 寬：size=27、列數與列寬正確")
    void threeByNine_reportsSize27() {
        GuiMask mask = GuiMask.of(
            ".........",
            ".........",
            ".........");
        assertEquals(3, mask.rowCount(), "列數必須為 3");
        assertEquals(9, mask.width(), "列寬必須為 9");
        assertEquals(27, mask.size(), "總格數必須為 27");
    }

    @Test
    @DisplayName("符號 → 欄位為列優先有序：跨列符號順序正確")
    void symbolMapping_isRowMajorOrdered() {
        GuiMask mask = GuiMask.of(
            "A.B......",
            "...A.....",
            "........B");
        assertEquals(List.of(0, 12), mask.slots('A'),
            "A 應落在欄位 0 與 12（列優先）");
        assertEquals(List.of(2, 26), mask.slots('B'),
            "B 應落在欄位 2 與 26（列優先）");
    }

    @Test
    @DisplayName("同一符號多格：同一列內順序穩定")
    void repeatedSymbol_keepsStableOrder() {
        GuiMask mask = GuiMask.of("BAAB.....");
        assertEquals(List.of(1, 2), mask.slots('A'),
            "同一列內 A 的順序必須穩定");
        assertEquals(List.of(0, 3), mask.slots('B'),
            "同一列內 B 的順序必須穩定");
    }

    @Test
    @DisplayName("未出現的符號回空清單")
    void absentSymbol_returnsEmptyList() {
        GuiMask mask = GuiMask.of("A........");
        assertTrue(mask.slots('Z').isEmpty(),
            "未出現的符號必須回空清單");
    }

    @Test
    @DisplayName("零列與 null 列被拒")
    void emptyAndNullRows_areRejected() {
        IllegalArgumentException empty = assertThrows(
            IllegalArgumentException.class, () -> GuiMask.of());
        assertTrue(empty.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "零列必須攜帶 INVALID_INPUT：實際: " + empty.getMessage());
        IllegalArgumentException nullRow = assertThrows(
            IllegalArgumentException.class,
            () -> GuiMask.of(".........", null));
        assertTrue(nullRow.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "null 列必須攜帶 INVALID_INPUT：實際: " + nullRow.getMessage());
    }

    @Test
    @DisplayName("遮罩建箱子視圖：格數取自遮罩，符號欄位可用於 allow 與按鈕")
    void maskBuildsChestView_slotsUsableForAllowAndButton() {
        GuiMask mask = GuiMask.of(
            "#########",
            "#..BBB..#",
            "#########");
        GuiView view = GuiView.chest("遮罩商店", mask)
            .allow(mask.slots('.'))
            .button(mask.slots('B').get(0), "buy", click -> {
            })
            .build();
        assertEquals(27, view.size(), "視圖格數必須取自遮罩");
        assertTrue(view.allowedSlots().containsAll(mask.slots('.')),
            "遮罩空白欄位必須可放行");
        assertTrue(view.buttons().containsKey(mask.slots('B').get(0)),
            "遮罩符號欄位必須可註冊按鈕");
        assertTrue(view.buttonIcons().isEmpty(),
            "純回呼按鈕不得攜帶物品");
    }
}
