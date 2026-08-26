package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FormSpec DSL 測試：Simple / Modal / Custom 三種表單的 builder 正常路徑、
 * 錯誤路徑（null / 空白標題與內容、不合法元件參數）與邊界路徑
 * （空元件清單、極端數值、不可變集合）。
 */
@DisplayName("FormSpec DSL")
class FormSpecTest {

    // -----------------------------------------------------------------
    // Simple：正常路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("simple：標題/內容/按鈕依序保留，kind 為 SIMPLE")
    void simple_normalPath_storesAllFields() {
        FormSpec.Simple spec = FormSpec.simple("傳送")
            .content("選擇目的地")
            .button("主城")
            .button("資源世界")
            .build();

        assertEquals("傳送", spec.title());
        assertEquals("選擇目的地", spec.content());
        assertEquals(List.of("主城", "資源世界"), spec.buttons());
        assertEquals(FormSpec.Kind.SIMPLE, spec.kind());
    }

    @Test
    @DisplayName("simple：buttons 回傳不可變清單，外部修改被拒絕且不受 builder 後續變更影響")
    void simple_buttonsAreImmutableSnapshot() {
        FormSpec.Simple.Builder builder = FormSpec.simple("t").content("c");
        builder.button("A");
        FormSpec.Simple spec = builder.button("B").build();

        assertThrows(UnsupportedOperationException.class, () -> spec.buttons().add("C"),
            "buttons() 必須回傳不可變清單");

        builder.button("C");
        assertEquals(List.of("A", "B"), spec.buttons(),
            "build 後 builder 繼續加按鈕不得影響已建立的 spec");
    }

    // -----------------------------------------------------------------
    // Simple：錯誤路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("simple：title 為 null 或空白 → IllegalArgumentException")
    void simple_nullOrBlankTitle_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormSpec.simple(null));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.simple(""));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.simple("   "));
    }

    @Test
    @DisplayName("simple：content 為 null 或空白 → IllegalArgumentException")
    void simple_nullOrBlankContent_rejected() {
        FormSpec.Simple.Builder b = FormSpec.simple("t");
        assertThrows(IllegalArgumentException.class, () -> b.content(null));
        assertThrows(IllegalArgumentException.class, () -> b.content(""));
        assertThrows(IllegalArgumentException.class, () -> b.content(" \t"));
    }

    @Test
    @DisplayName("simple：button 文字為 null 或空白 → IllegalArgumentException")
    void simple_nullOrBlankButtonText_rejected() {
        FormSpec.Simple.Builder b = FormSpec.simple("t").content("c");
        assertThrows(IllegalArgumentException.class, () -> b.button(null));
        assertThrows(IllegalArgumentException.class, () -> b.button(""));
        assertThrows(IllegalArgumentException.class, () -> b.button("  "));
    }

    @Test
    @DisplayName("simple：零按鈕 build → IllegalArgumentException（至少一個按鈕）")
    void simple_zeroButtons_buildRejected() {
        FormSpec.Simple.Builder b = FormSpec.simple("t").content("c");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, b::build,
            "沒有任何按鈕的 simple 表單必須在 build 時拒絕");
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank(),
            "拒絕訊息必須可診斷");
    }

    // -----------------------------------------------------------------
    // Modal：正常 / 錯誤 / 邊界
    // -----------------------------------------------------------------

    @Test
    @DisplayName("modal：明確指定兩顆按鈕時完整保留，kind 為 MODAL")
    void modal_explicitButtons_stored() {
        FormSpec.Modal spec = FormSpec.modal("確認")
            .content("要繼續嗎？")
            .button1("是")
            .button2("否")
            .build();

        assertEquals("確認", spec.title());
        assertEquals("要繼續嗎？", spec.content());
        assertEquals("是", spec.button1());
        assertEquals("否", spec.button2());
        assertEquals(FormSpec.Kind.MODAL, spec.kind());
    }

    @Test
    @DisplayName("modal：省略按鈕時以空字串為預設（翻譯層交由 Cumulus 預設行為）")
    void modal_omittedButtons_defaultToEmptyString() {
        FormSpec.Modal spec = FormSpec.modal("確認").content("要繼續嗎？").build();
        assertEquals("", spec.button1(), "未指定的 button1 必須是空字串而非 null");
        assertEquals("", spec.button2(), "未指定的 button2 必須是空字串而非 null");
    }

    @Test
    @DisplayName("modal：title/content null 或空白 → IllegalArgumentException；button 明確傳 null → IllegalArgumentException")
    void modal_invalidInputs_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormSpec.modal(null));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.modal(" "));
        FormSpec.Modal.Builder b = FormSpec.modal("t");
        assertThrows(IllegalArgumentException.class, () -> b.content(null));
        assertThrows(IllegalArgumentException.class, () -> b.content(""));
        assertThrows(IllegalArgumentException.class, () -> b.button1(null));
        assertThrows(IllegalArgumentException.class, () -> b.button2(null));
    }

    // -----------------------------------------------------------------
    // Custom：正常路徑（六種元件）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("custom：六種元件依序保留，kind 為 CUSTOM")
    void custom_allComponentTypes_storedInOrder() {
        FormSpec.Custom spec = FormSpec.custom("設定")
            .label("一般設定")
            .input("暱稱", "輸入暱稱", "Steve")
            .dropdown("難度", List.of("簡單", "普通", "困難"), 1)
            .slider("音量", 0.0f, 100.0f, 5.0f, 50.0f)
            .stepSlider("模式", List.of("低", "中", "高"), 2)
            .toggle("通知", true)
            .build();

        assertEquals("設定", spec.title());
        assertEquals(FormSpec.Kind.CUSTOM, spec.kind());
        List<FormSpec.Custom.Component> components = spec.components();
        assertEquals(6, components.size(), "六個元件必須依加入順序保留");

        assertEquals(new FormSpec.Custom.Label("一般設定"), components.get(0));
        assertEquals(new FormSpec.Custom.Input("暱稱", "輸入暱稱", "Steve"), components.get(1));
        assertEquals(new FormSpec.Custom.Dropdown("難度", List.of("簡單", "普通", "困難"), 1),
            components.get(2));
        assertEquals(new FormSpec.Custom.Slider("音量", 0.0f, 100.0f, 5.0f, 50.0f),
            components.get(3));
        assertEquals(new FormSpec.Custom.StepSlider("模式", List.of("低", "中", "高"), 2),
            components.get(4));
        assertEquals(new FormSpec.Custom.Toggle("通知", true), components.get(5));
    }

    @Test
    @DisplayName("custom：components 回傳不可變快照")
    void custom_componentsAreImmutableSnapshot() {
        FormSpec.Custom.Builder builder = FormSpec.custom("t");
        builder.label("a");
        FormSpec.Custom spec = builder.build();

        assertThrows(UnsupportedOperationException.class,
            () -> spec.components().add(new FormSpec.Custom.Label("b")),
            "components() 必須回傳不可變清單");
    }

    @Test
    @DisplayName("custom：input 的 placeholder/defaultText 允許 null，正規化為空字串")
    void custom_inputNullableTexts_normalizedToEmpty() {
        FormSpec.Custom spec = FormSpec.custom("t").input("名稱", null, null).build();
        assertEquals(new FormSpec.Custom.Input("名稱", "", ""), spec.components().get(0));
    }

    // -----------------------------------------------------------------
    // Custom：錯誤路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("custom：title null 或空白 → IllegalArgumentException")
    void custom_nullOrBlankTitle_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom(null));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom(""));
    }

    @Test
    @DisplayName("custom：各元件 label 為 null 或空白 → IllegalArgumentException")
    void custom_componentLabels_required() {
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").label(null));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").label(" "));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").input(null, "p", "d"));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").dropdown(null, List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").slider(null, 0f, 1f, 0.5f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").stepSlider(null, List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").toggle(null, true));
    }

    @Test
    @DisplayName("custom：dropdown options 為 null / 空 / 含空白項 → IllegalArgumentException；defaultOption 越界 → IllegalArgumentException")
    void custom_dropdownValidation() {
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").dropdown("d", null));
        assertThrows(IllegalArgumentException.class, () -> FormSpec.custom("t").dropdown("d", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").dropdown("d", List.of("a", " ")));
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").dropdown("d", List.of("a", "b"), 2),
            "defaultOption 越界必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").dropdown("d", List.of("a", "b"), -1),
            "負 defaultOption 必須拒絕");
    }

    @Test
    @DisplayName("custom：slider 參數不合法（min>=max、step<=0、預設值越界、非有限值）→ IllegalArgumentException")
    void custom_sliderValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", 10f, 0f, 1f, 5f), "min >= max 必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", 0f, 10f, 0f, 5f), "step <= 0 必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", 0f, 10f, 1f, 11f), "預設值 > max 必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", 0f, 10f, 1f, -1f), "預設值 < min 必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", Float.NaN, 10f, 1f, 5f), "NaN 必須拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").slider("s", 0f, Float.POSITIVE_INFINITY, 1f, 5f),
            "非有限值必須拒絕");
    }

    @Test
    @DisplayName("custom：stepSlider steps 為 null / 空 / 含空白項 → IllegalArgumentException；defaultStep 越界 → IllegalArgumentException")
    void custom_stepSliderValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").stepSlider("s", null));
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").stepSlider("s", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").stepSlider("s", List.of("a", "")));
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.custom("t").stepSlider("s", List.of("a", "b"), 5), "defaultStep 越界必須拒絕");
    }

    // -----------------------------------------------------------------
    // 邊界路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("custom：零元件允許 build（純標題表單），components 為空清單")
    void custom_emptyComponents_allowed() {
        FormSpec.Custom spec = FormSpec.custom("僅標題").build();
        assertTrue(spec.components().isEmpty(), "零元件的 custom 表單必須允許建立");
    }

    @Test
    @DisplayName("simple：大量按鈕與含換行／unicode 文字可正常建立")
    void boundary_largeAndExoticTexts_accepted() {
        FormSpec.Simple.Builder b = FormSpec.simple("標題\n第二行").content("內容 ✓");
        for (int i = 0; i < 64; i++) {
            b.button("按鈕-" + i);
        }
        FormSpec.Simple spec = b.build();
        assertEquals(64, spec.buttons().size());
        assertEquals("標題\n第二行", spec.title());
    }
}
