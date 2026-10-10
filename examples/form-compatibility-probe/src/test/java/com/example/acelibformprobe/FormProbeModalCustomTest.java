package com.example.acelibformprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Modal 與 Custom 探針案例的齊全性測試（TDD 錨點）。
 *
 * <p>本測試斷言 {@link FormProbeCases#buildCatalog()} 涵蓋真人驗收所需的
 * Modal 與 Custom 案例：每個案例識別碼唯一且期望觀察點（expectation）非空、
 * Modal 兩顆按鈕的回傳索引與關閉行為可觀察、Custom 五種元件（input／dropdown／
 * slider／stepSlider／toggle）皆有對應觀察點且 label 與元件交錯順序固定。
 * 客戶端實際呈現仍須真人觀察後回填相容矩陣，本測試只驗證「案例送出的就是
 * 它宣稱要測的」。</p>
 */
class FormProbeModalCustomTest {

    private static final List<String> MODAL_IDS = List.of(
        "modal-confirm",
        "modal-default-buttons");

    private static final List<String> CUSTOM_IDS = List.of(
        "custom-all-components",
        "custom-defaults");

    private Map<String, FormProbeCase> index() {
        Map<String, FormProbeCase> byId = new LinkedHashMap<>();
        for (FormProbeCase c : FormProbeCases.buildCatalog()) {
            assertTrue(!byId.containsKey(c.id()), "案例識別碼重複：" + c.id());
            byId.put(c.id(), c);
        }
        return byId;
    }

    @Test
    void modalAndCustomCasesExist() {
        Map<String, FormProbeCase> byId = index();
        List<String> wanted = new ArrayList<>(MODAL_IDS);
        wanted.addAll(CUSTOM_IDS);
        for (String id : wanted) {
            assertTrue(byId.containsKey(id), "案例目錄缺少必要案例：" + id);
        }
    }

    @Test
    void everyCaseHasNonBlankExpectation() {
        for (FormProbeCase c : FormProbeCases.buildCatalog()) {
            assertTrue(c.expectation() != null && !c.expectation().isBlank(),
                "案例 " + c.id() + " 的期望觀察點不得為空");
        }
    }

    @Test
    void modalConfirmHasTwoExplicitButtons() {
        FormProbeCase c = index().get("modal-confirm");
        assertTrue(c != null, "找不到案例：modal-confirm");
        assertInstanceOf(FormSpec.Modal.class, c.buildSpec(), "modal-confirm 應為 modal 表單");
        FormSpec.Modal modal = (FormSpec.Modal) c.buildSpec();
        assertEquals("確定", modal.button1(), "第一顆按鈕文字必須固定，點擊索引才有意義");
        assertEquals("取消", modal.button2(), "第二顆按鈕文字必須固定，點擊索引才有意義");
        assertTrue(!modal.content().isBlank(), "modal-confirm 的說明不得為空白");
        assertTrue(c.expectation().contains("0") && c.expectation().contains("1"),
            "modal-confirm 的期望觀察點應提及兩顆按鈕的回傳索引，實際：" + c.expectation());
    }

    @Test
    void modalDefaultButtonsOmitsBothLabels() {
        FormProbeCase c = index().get("modal-default-buttons");
        assertTrue(c != null, "找不到案例：modal-default-buttons");
        assertInstanceOf(FormSpec.Modal.class, c.buildSpec(),
            "modal-default-buttons 應為 modal 表單");
        FormSpec.Modal modal = (FormSpec.Modal) c.buildSpec();
        assertTrue(modal.button1().isEmpty() && modal.button2().isEmpty(),
            "modal-default-buttons 應省略兩顆按鈕文字（由基岩端預設文案呈現）");
    }

    @Test
    void customCasesCoverAllFiveComponents() {
        Map<String, FormProbeCase> byId = index();
        List<String> seen = new ArrayList<>();
        for (String id : CUSTOM_IDS) {
            assertTrue(byId.containsKey(id), "找不到案例：" + id);
            assertInstanceOf(FormSpec.Custom.class, byId.get(id).buildSpec(),
                "案例 " + id + " 應為 custom 表單");
            for (FormSpec.Custom.Component component
                    : ((FormSpec.Custom) byId.get(id).buildSpec()).components()) {
                String kind = component.getClass().getSimpleName();
                if (!seen.contains(kind)) {
                    seen.add(kind);
                }
            }
        }
        for (String kind : List.of("Input", "Dropdown", "Slider", "StepSlider", "Toggle", "Label")) {
            assertTrue(seen.contains(kind), "Custom 案例應覆蓋 " + kind + " 元件，實際僅見：" + seen);
        }
    }

    @Test
    void customAllComponentsKeepsPayloadOrder() {
        FormProbeCase c = index().get("custom-all-components");
        assertTrue(c != null, "找不到案例：custom-all-components");
        List<FormSpec.Custom.Component> components =
            ((FormSpec.Custom) c.buildSpec()).components();
        // label 不產值；產值元件的相對順序即回傳 values 的順序。
        List<String> payloadOrder = new ArrayList<>();
        for (FormSpec.Custom.Component component : components) {
            if (!(component instanceof FormSpec.Custom.Label)) {
                payloadOrder.add(component.getClass().getSimpleName());
            }
        }
        assertEquals(List.of("Input", "Dropdown", "Slider", "StepSlider", "Toggle"),
            payloadOrder, "產值元件順序必須固定，回傳 values 才有意義");
        assertInstanceOf(FormSpec.Custom.Label.class, components.get(0),
            "首個元件應為 label（順序觀察起點）");
        assertInstanceOf(FormSpec.Custom.Label.class,
            components.get(components.size() - 1), "末個元件應為 label（順序觀察終點）");
    }

    @Test
    void customDefaultsAreFixed() {
        FormProbeCase full = index().get("custom-all-components");
        assertTrue(full != null, "找不到案例：custom-all-components");
        List<FormSpec.Custom.Component> components =
            ((FormSpec.Custom) full.buildSpec()).components();
        FormSpec.Custom.Input input = null;
        FormSpec.Custom.Dropdown dropdown = null;
        FormSpec.Custom.Slider slider = null;
        FormSpec.Custom.StepSlider stepSlider = null;
        FormSpec.Custom.Toggle toggle = null;
        for (FormSpec.Custom.Component component : components) {
            if (component instanceof FormSpec.Custom.Input i) {
                input = i;
            } else if (component instanceof FormSpec.Custom.Dropdown d) {
                dropdown = d;
            } else if (component instanceof FormSpec.Custom.Slider s) {
                slider = s;
            } else if (component instanceof FormSpec.Custom.StepSlider ss) {
                stepSlider = ss;
            } else if (component instanceof FormSpec.Custom.Toggle t) {
                toggle = t;
            }
        }
        assertTrue(input != null && dropdown != null && slider != null
            && stepSlider != null && toggle != null, "custom-all-components 應含五種產值元件");
        assertEquals("預設名稱", input.defaultText(), "input 預設文字必須固定");
        assertEquals(1, dropdown.defaultOption(), "dropdown 預設索引必須固定");
        assertEquals(List.of("紅", "綠", "藍"), dropdown.options(), "dropdown 選項必須固定");
        assertEquals(5f, slider.defaultValue(), "slider 預設值必須固定");
        assertEquals(2, stepSlider.defaultStep(), "stepSlider 預設索引必須固定");
        assertEquals(List.of("簡單", "普通", "困難"), stepSlider.steps(), "stepSlider 步驟必須固定");
        assertTrue(toggle.defaultValue(), "toggle 預設應為開");

        FormProbeCase defaults = index().get("custom-defaults");
        assertTrue(defaults != null, "找不到案例：custom-defaults");
        List<FormSpec.Custom.Component> others =
            ((FormSpec.Custom) defaults.buildSpec()).components();
        boolean seenEmptyInput = false;
        boolean seenClosedToggle = false;
        for (FormSpec.Custom.Component component : others) {
            if (component instanceof FormSpec.Custom.Input i
                && i.defaultText().isEmpty()) {
                seenEmptyInput = true;
            }
            if (component instanceof FormSpec.Custom.Toggle t && !t.defaultValue()) {
                seenClosedToggle = true;
            }
        }
        assertTrue(seenEmptyInput, "custom-defaults 應含空預設文字的 input（與前案對照）");
        assertTrue(seenClosedToggle, "custom-defaults 應含預設為關的 toggle（與前案對照）");
    }
}
