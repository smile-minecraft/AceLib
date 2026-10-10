package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 按鈕標籤與同一宣告的基岩表單生成契約。
 *
 * <p>按鈕宣告可攜帶基岩可見文字（label，可為 null＝不進表單）；
 * {@link GuiFlowStep#labeled} 依欄位升序把有標籤的按鈕排成簡單表單，
 * 轉移表的按鈕索引對應該順序；無標籤按鈕被排除。</p>
 */
@DisplayName("按鈕標籤與標籤表單")
class GuiViewLabelTest {

    private static GuiView labeledView() {
        // 故意不依欄位順序宣告：欄位 5 先、欄位 0 後，驗證表單依欄位升序排列
        return GuiView.chest("選單", 9)
            .button(5, "settings", "設定", click -> {
            })
            .button(0, "shop", "商店", click -> {
            })
            .button(2, "plain", click -> {
            })
            .build();
    }

    @Test
    @DisplayName("buttonLabels 只收有標籤的按鈕（欄位→標籤，不可變）")
    void buttonLabels_containsOnlyLabeled() {
        GuiView view = labeledView();

        assertEquals(Map.of(0, "商店", 5, "設定"), view.buttonLabels());
        assertThrows(UnsupportedOperationException.class,
            () -> view.buttonLabels().put(1, "竄改"));
    }

    @Test
    @DisplayName("labeled 依欄位升序產生表單按鈕，無標籤按鈕被排除")
    void labeled_buildsFormInSlotOrder() {
        GuiFlowStep step = GuiFlowStep.labeled("menu", labeledView(),
            Map.of(0, "shop", 1, "settings"));

        assertTrue(step.form() instanceof FormSpec.Simple,
            "標籤表單必須是簡單表單");
        FormSpec.Simple form = (FormSpec.Simple) step.form();
        assertEquals(List.of("商店", "設定"), form.buttons(),
            "表單按鈕必須依欄位升序（欄位 0 先、欄位 5 後），與宣告順序無關");
        assertEquals(Map.of(0, "shop", 1, "settings"), step.transitions(),
            "轉移表按鈕索引對應表單順序：第 0 顆是商店、第 1 顆是設定");
        assertEquals("menu", step.id());
    }

    @Test
    @DisplayName("全無標籤的視圖退回純 Java 步驟（form 為 null）")
    void labeled_withoutLabels_returnsJavaOnlyStep() {
        GuiView plain = GuiView.chest("純展示", 9)
            .button(0, "look", click -> {
            })
            .build();

        GuiFlowStep step = GuiFlowStep.labeled("plain", plain, Map.of());

        assertNull(step.form(), "無標籤按鈕時不得產生表單");
        assertEquals("plain", step.id());
    }

    @Test
    @DisplayName("全無標籤但轉移表非空時被拒（索引無對應按鈕）")
    void labeled_withoutLabelsAndNonEmptyTransitions_isRejected() {
        GuiView plain = GuiView.chest("純展示", 9)
            .button(0, "look", click -> {
            })
            .build();

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> GuiFlowStep.labeled("plain", plain, Map.of(0, "elsewhere")));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT；實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("轉移索引超出標籤按鈕範圍時被拒")
    void labeled_transitionIndexOutOfRange_isRejected() {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> GuiFlowStep.labeled("menu", labeledView(), Map.of(2, "shop")));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT；實際: " + failure.getMessage());
    }

    @Test
    @DisplayName("空白標籤被拒；null 標籤視為無標籤（不進表單）")
    void blankLabel_isRejected_nullLabelIsExcluded() {
        assertThrows(IllegalArgumentException.class,
            () -> GuiView.chest("選單", 9).button(0, "bad", "  ", click -> {
            }), "空白標籤必須被拒");

        GuiView view = GuiView.chest("選單", 9)
            .button(0, "shop", "商店", click -> {
            })
            .button(1, "hidden", (String) null, click -> {
            })
            .build();
        assertEquals(Map.of(0, "商店"), view.buttonLabels());
        GuiFlowStep step = GuiFlowStep.labeled("menu", view, Map.of(0, "shop"));
        assertEquals(List.of("商店"),
            ((FormSpec.Simple) step.form()).buttons());
    }

    @Test
    @DisplayName("既有兩份宣告模式不受影響：純 Java 與自備表單建構子照舊")
    void existingDeclarationModes_stillWork() {
        GuiView view = GuiView.chest("選單", 9)
            .button(0, "shop", click -> {
            })
            .build();
        FormSpec form = FormSpec.simple("選單").content("請選擇").button("商店").build();

        GuiFlowStep javaOnly = new GuiFlowStep("java", view);
        assertNull(javaOnly.form(), "雙參數建構子維持純 Java 步驟");

        GuiFlowStep withForm = new GuiFlowStep("both", view, form,
            Map.of(0, "shop"));
        assertEquals(form, withForm.form(), "自備表單建構子維持原表單");
        assertEquals(Map.of(0, "shop"), withForm.transitions());
    }

    @Test
    @DisplayName("null 輸入一律被拒")
    void nullInputs_throw() {
        GuiView view = labeledView();
        assertThrows(NullPointerException.class,
            () -> GuiFlowStep.labeled(null, view, Map.of()));
        assertThrows(NullPointerException.class,
            () -> GuiFlowStep.labeled("menu", null, Map.of()));
        assertThrows(NullPointerException.class,
            () -> GuiView.chest("選單", 9).button(0, null, "商店", click -> {
            }));
        assertThrows(NullPointerException.class,
            () -> GuiView.chest("選單", 9).button(0, "shop", "商店", null));
    }
}
