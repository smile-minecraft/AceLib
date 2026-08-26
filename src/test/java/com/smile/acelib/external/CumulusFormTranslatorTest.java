package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormSpec;
import java.util.List;
import org.geysermc.cumulus.component.util.ComponentType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CumulusFormTranslator 翻譯層測試：以 test classpath 上的真實 Cumulus 1.1.2
 * 斷言 AceLib FormSpec 正確翻成 Cumulus 形式（欄位值逐一核對）。
 */
@DisplayName("CumulusFormTranslator")
class CumulusFormTranslatorTest {

    // -----------------------------------------------------------------
    // Simple
    // -----------------------------------------------------------------

    @Test
    @DisplayName("simple spec → Cumulus SimpleForm：title/content/buttons 逐欄一致")
    void simple_translatesAllFields() {
        FormSpec.Simple spec = FormSpec.simple("傳送")
            .content("選擇目的地")
            .button("主城")
            .button("資源世界")
            .build();

        Form translated = CumulusFormTranslator.toCumulus(spec);
        SimpleForm form = assertInstanceOf(SimpleForm.class, translated,
            "simple spec 必須翻成 Cumulus SimpleForm");
        assertEquals("傳送", form.title());
        assertEquals("選擇目的地", form.content());
        assertEquals(2, form.buttons().size());
        assertEquals("主城", form.buttons().get(0).text());
        assertEquals("資源世界", form.buttons().get(1).text());
    }

    // -----------------------------------------------------------------
    // Modal
    // -----------------------------------------------------------------

    @Test
    @DisplayName("modal spec → Cumulus ModalForm：明確按鈕逐欄一致")
    void modal_explicitButtons_translateVerbatim() {
        FormSpec.Modal spec = FormSpec.modal("確認")
            .content("要繼續嗎？")
            .button1("是")
            .button2("否")
            .build();

        ModalForm form = assertInstanceOf(ModalForm.class,
            CumulusFormTranslator.toCumulus(spec));
        assertEquals("確認", form.title());
        assertEquals("要繼續嗎？", form.content());
        assertEquals("是", form.button1());
        assertEquals("否", form.button2());
    }

    @Test
    @DisplayName("modal spec：省略按鈕時翻譯結果為空字串（Cumulus 預設）")
    void modal_omittedButtons_translateToEmptyDefaults() {
        FormSpec.Modal spec = FormSpec.modal("確認").content("要繼續嗎？").build();

        ModalForm form = assertInstanceOf(ModalForm.class,
            CumulusFormTranslator.toCumulus(spec));
        assertEquals("", form.button1());
        assertEquals("", form.button2());
    }

    // -----------------------------------------------------------------
    // Custom
    // -----------------------------------------------------------------

    @Test
    @DisplayName("custom spec → Cumulus CustomForm：六種元件依序翻譯且欄位值一致")
    void custom_allComponents_translateInOrder() {
        FormSpec.Custom spec = FormSpec.custom("設定")
            .label("一般設定")
            .input("暱稱", "輸入暱稱", "Steve")
            .dropdown("難度", List.of("簡單", "普通", "困難"), 1)
            .slider("音量", 0.0f, 100.0f, 5.0f, 50.0f)
            .stepSlider("模式", List.of("低", "中", "高"), 2)
            .toggle("通知", true)
            .build();

        CustomForm form = assertInstanceOf(CustomForm.class,
            CumulusFormTranslator.toCumulus(spec));
        assertEquals("設定", form.title());

        var components = form.content();
        assertEquals(6, components.size(), "六個元件必須全部翻譯");

        assertEquals(ComponentType.LABEL, components.get(0).type());
        assertEquals(ComponentType.INPUT, components.get(1).type());
        assertEquals(ComponentType.DROPDOWN, components.get(2).type());
        assertEquals(ComponentType.SLIDER, components.get(3).type());
        assertEquals(ComponentType.STEP_SLIDER, components.get(4).type());
        assertEquals(ComponentType.TOGGLE, components.get(5).type());

        var input = assertInstanceOf(org.geysermc.cumulus.component.InputComponent.class,
            components.get(1));
        assertEquals("暱稱", input.text());
        assertEquals("輸入暱稱", input.placeholder());
        assertEquals("Steve", input.defaultText());

        var dropdown = assertInstanceOf(org.geysermc.cumulus.component.DropdownComponent.class,
            components.get(2));
        assertEquals(List.of("簡單", "普通", "困難"), dropdown.options());
        assertEquals(1, dropdown.defaultOption());

        var slider = assertInstanceOf(org.geysermc.cumulus.component.SliderComponent.class,
            components.get(3));
        assertEquals(0.0f, slider.minValue());
        assertEquals(100.0f, slider.maxValue());
        assertEquals(5.0f, slider.step());
        assertEquals(50.0f, slider.defaultValue());

        var stepSlider = assertInstanceOf(org.geysermc.cumulus.component.StepSliderComponent.class,
            components.get(4));
        assertEquals(List.of("低", "中", "高"), stepSlider.steps());
        assertEquals(2, stepSlider.defaultStep());

        var toggle = assertInstanceOf(org.geysermc.cumulus.component.ToggleComponent.class,
            components.get(5));
        assertTrue(toggle.defaultValue(), "toggle 預設值必須為 true");
    }

    @Test
    @DisplayName("custom spec：零元件翻成空 content 的 CustomForm")
    void custom_emptyComponents_translateToEmptyContent() {
        FormSpec.Custom spec = FormSpec.custom("僅標題").build();

        CustomForm form = assertInstanceOf(CustomForm.class,
            CumulusFormTranslator.toCumulus(spec));
        assertTrue(form.content().isEmpty());
    }
}
