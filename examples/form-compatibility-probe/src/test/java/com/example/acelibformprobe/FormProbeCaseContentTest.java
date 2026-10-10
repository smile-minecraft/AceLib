package com.example.acelibformprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 探針案例內容回歸測試：防止案例「宣稱要測 A，實際送出 B」的設定錯誤再發。
 *
 * <p>背景：`translatable-with-fallback` 曾把備援文字誤當 translation 參數傳入，
 * 元件根本沒有 fallback，真人觀察顯示鍵名；`icon-url` 曾指向保留測試網域
 * `example.com`（回 HTML 而非圖片），客戶端無法顯示。兩者皆為探針設定錯誤，
 * 非產品缺陷。本類別直接斷言建構出的規格內容，確保案例送出的就是它宣稱要測的。</p>
 *
 * <p>注意：斷言對象是實際要發送的規格內容（即 {@code FormText.render} 輸出），
 * 不是轉換規則本身；轉換規則由 AceLib 自身測試覆蓋。</p>
 */
class FormProbeCaseContentTest {

    private Map<String, FormProbeCase> index() {
        return FormProbeCases.buildCatalog().stream()
            .collect(Collectors.toMap(FormProbeCase::id, c -> c,
                (a, b) -> { throw new IllegalStateException("重複識別碼：" + a.id()); }));
    }

    private FormSpec.Simple simpleSpec(String id) {
        FormProbeCase c = index().get(id);
        assertTrue(c != null, "找不到案例：" + id);
        FormSpec spec = c.buildSpec();
        assertInstanceOf(FormSpec.Simple.class, spec, "案例 " + id + " 應為 simple 表單");
        return (FormSpec.Simple) spec;
    }

    @Test
    void fallbackCaseRendersFallbackText() {
        String content = simpleSpec("translatable-with-fallback").content();
        assertTrue(content.contains("Fallback 備援文字"),
            "fallback 案例的渲染輸出應包含備援文字，實際：" + content);
        assertFalse(content.contains("acelib.probe.case.missing"),
            "fallback 案例的渲染輸出不應殘留鍵名，實際：" + content);
    }

    @Test
    void keyOnlyCaseRendersKey() {
        String content = simpleSpec("translatable-key-only").content();
        assertTrue(content.contains("acelib.probe.case.key.only"),
            "key-only 案例的渲染輸出應包含鍵名，實際：" + content);
        assertFalse(content.contains("Fallback"),
            "key-only 案例的渲染輸出不應含任何 fallback 文字，實際：" + content);
    }

    @Test
    void modalConfirmCarriesTwoFixedButtons() {
        FormProbeCase c = index().get("modal-confirm");
        assertTrue(c != null, "找不到案例：modal-confirm");
        FormSpec spec = c.buildSpec();
        assertInstanceOf(FormSpec.Modal.class, spec, "案例 modal-confirm 應為 modal 表單");
        FormSpec.Modal modal = (FormSpec.Modal) spec;
        assertEquals("確定", modal.button1(), "第一顆按鈕必須是固定的「確定」");
        assertEquals("取消", modal.button2(), "第二顆按鈕必須是固定的「取消」");
        assertTrue(!modal.content().isBlank(), "modal-confirm 的說明不得為空白");
    }

    @Test
    void modalDefaultButtonsCarriesEmptyLabels() {
        FormProbeCase c = index().get("modal-default-buttons");
        assertTrue(c != null, "找不到案例：modal-default-buttons");
        FormSpec spec = c.buildSpec();
        assertInstanceOf(FormSpec.Modal.class, spec,
            "案例 modal-default-buttons 應為 modal 表單");
        FormSpec.Modal modal = (FormSpec.Modal) spec;
        assertTrue(modal.button1().isEmpty() && modal.button2().isEmpty(),
            "modal-default-buttons 的兩顆按鈕文字必須為空（由基岩端預設文案呈現）");
        assertTrue(!modal.content().isBlank(), "modal-default-buttons 的說明不得為空白");
    }

    @Test
    void customAllComponentsKeepsFixedOrderAndDefaults() {
        FormProbeCase c = index().get("custom-all-components");
        assertTrue(c != null, "找不到案例：custom-all-components");
        FormSpec spec = c.buildSpec();
        assertInstanceOf(FormSpec.Custom.class, spec,
            "案例 custom-all-components 應為 custom 表單");
        List<FormSpec.Custom.Component> components =
            ((FormSpec.Custom) spec).components();
        assertEquals(8, components.size(), "custom-all-components 應恰有 8 個元件");
        assertInstanceOf(FormSpec.Custom.Label.class, components.get(0));
        assertInstanceOf(FormSpec.Custom.Input.class, components.get(1));
        assertInstanceOf(FormSpec.Custom.Label.class, components.get(2));
        assertInstanceOf(FormSpec.Custom.Dropdown.class, components.get(3));
        assertInstanceOf(FormSpec.Custom.Slider.class, components.get(4));
        assertInstanceOf(FormSpec.Custom.StepSlider.class, components.get(5));
        assertInstanceOf(FormSpec.Custom.Toggle.class, components.get(6));
        assertInstanceOf(FormSpec.Custom.Label.class, components.get(7));
    }

    @Test
    void urlCasePointsToRealPngHost() {
        FormSpec.Simple simple = simpleSpec("icon-url");
        assertEquals(1, simple.buttonEntries().size(), "icon-url 案例應恰有一顆按鈕");
        FormImage image = simple.buttonEntries().get(0).image()
            .orElseThrow(() -> new AssertionError("icon-url 案例的按鈕應帶圖示"));
        assertEquals(FormImage.Type.URL, image.type(), "icon-url 案例應為 URL 型別");
        assertTrue(image.data().startsWith("https://"),
            "icon-url 應為 https 網址，實際：" + image.data());
        assertTrue(image.data().endsWith(".png"),
            "icon-url 路徑應以 .png 結尾，實際：" + image.data());
        assertFalse(image.data().contains("example.com"),
            "icon-url 不得指向保留測試網域 example.com，實際：" + image.data());
    }
}
