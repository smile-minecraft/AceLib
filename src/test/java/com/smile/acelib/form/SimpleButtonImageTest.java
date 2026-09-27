package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Simple 按鈕圖示測試：新 overload 的呼叫當下驗證、舊 {@code buttons()} 與新
 * {@code buttonEntries()} 的一致性（同長度、同順序、內容可對應）與不可變性。
 */
@DisplayName("Simple 按鈕圖示")
class SimpleButtonImageTest {

    @Test
    @DisplayName("驗收範例：帶圖示與純文字按鈕可混用並 build")
    void mixedButtons_buildsSuccessfully() {
        FormSpec.Simple spec = FormSpec.simple("配方")
            .content("選擇配方")
            .button("鑄石劍", FormImage.path("textures/items/diamond_sword"))
            .button("返回")
            .build();

        assertEquals(List.of("鑄石劍", "返回"), spec.buttons());
        assertEquals(2, spec.buttonEntries().size());
    }

    @Test
    @DisplayName("button(String, FormImage)：null image 一律拒絕")
    void button_nullImage_rejected() {
        FormSpec.Simple.Builder builder = FormSpec.simple("t").content("c");
        assertThrows(IllegalArgumentException.class, () -> builder.button("x", null));
    }

    @Test
    @DisplayName("button(String, FormImage)：文字仍於呼叫當下驗證")
    void button_blankTextWithImage_rejected() {
        FormSpec.Simple.Builder builder = FormSpec.simple("t").content("c");
        FormImage image = FormImage.path("textures/x");
        assertThrows(IllegalArgumentException.class, () -> builder.button(null, image));
        assertThrows(IllegalArgumentException.class, () -> builder.button("", image));
        assertThrows(IllegalArgumentException.class, () -> builder.button("  ", image));
    }

    @Test
    @DisplayName("buttons() 與 buttonEntries() 同長度、同順序、內容可對應")
    void buttonsAndEntries_consistentInOrder() {
        FormImage sword = FormImage.path("textures/items/diamond_sword");
        FormSpec.Simple spec = FormSpec.simple("t")
            .content("c")
            .button("鑄石劍", sword)
            .button("返回")
            .build();

        List<String> buttons = spec.buttons();
        List<FormSpec.Simple.Button> entries = spec.buttonEntries();

        assertEquals(buttons.size(), entries.size(), "兩者必須同長度");
        for (int i = 0; i < buttons.size(); i++) {
            assertEquals(buttons.get(i), entries.get(i).text(),
                "索引 " + i + " 的文字必須可對應");
        }
        assertEquals(Optional.of(sword), entries.get(0).image());
        assertEquals(Optional.empty(), entries.get(1).image());
    }

    @Test
    @DisplayName("buttonEntries() 不可變；build 後 builder 續加不影響已建立的 spec")
    void buttonEntries_immutableSnapshot() {
        FormImage image = FormImage.path("textures/x");
        FormSpec.Simple.Builder builder = FormSpec.simple("t").content("c");
        builder.button("A", image);
        FormSpec.Simple spec = builder.button("B").build();

        assertThrows(UnsupportedOperationException.class,
            () -> spec.buttonEntries().add(new FormSpec.Simple.Button("C", Optional.empty())),
            "buttonEntries() 必須回傳不可變清單");
        assertThrows(UnsupportedOperationException.class, () -> spec.buttons().add("C"),
            "buttons() 必須回傳不可變清單");

        builder.button("C", image);
        assertEquals(List.of("A", "B"), spec.buttons(),
            "build 後 builder 繼續加按鈕不得影響已建立的 spec");
        assertEquals(2, spec.buttonEntries().size(),
            "build 後 builder 繼續加按鈕不得影響已建立的 spec entries");
    }

    @Test
    @DisplayName("Button record：null 文字或 null image optional 直接拒絕")
    void buttonRecord_invalidInputs_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FormSpec.Simple.Button(null, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new FormSpec.Simple.Button("  ", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new FormSpec.Simple.Button("x", null));
    }

    @Test
    @DisplayName("無圖示的既有按鈕：buttons() 內容與順序逐字不變")
    void plainButtons_unchanged() {
        FormSpec.Simple spec = FormSpec.simple("傳送")
            .content("選擇目的地")
            .button("主城")
            .button("資源世界")
            .build();

        assertEquals(List.of("主城", "資源世界"), spec.buttons());
        assertTrue(spec.buttonEntries().stream().allMatch(e -> e.image().isEmpty()),
            "既有無圖示按鈕的 entry image 必須全為 empty");
    }

    @Test
    @DisplayName("content 未設定或零按鈕時，build() 仍以既有錯誤型別拒絕")
    void buildValidation_existingSemanticsPreserved() {
        assertThrows(IllegalStateException.class,
            () -> FormSpec.simple("t").button("x", FormImage.path("textures/x")).build(),
            "未設定 content 必須以 IllegalStateException 拒絕");
        assertThrows(IllegalArgumentException.class,
            () -> FormSpec.simple("t").content("c").build(),
            "零按鈕必須以 IllegalArgumentException 拒絕");
    }
}
