package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FormImage 驗證測試：PATH／URL 兩種圖示的建構驗證（null／空白／{@code /} 開頭／
 * 含 {@code ..}／非 http(s)／缺少 host 一律以 IllegalArgumentException 拒絕），
 * 且直接呼叫 canonical constructor 也不能繞過驗證。
 */
@DisplayName("FormImage 圖示值型別")
class FormImageTest {

    @Test
    @DisplayName("path：合法資源包路徑保留型別與原始字串")
    void path_validPath_storesTypeAndData() {
        FormImage image = FormImage.path("textures/items/diamond_sword");

        assertEquals(FormImage.Type.PATH, image.type());
        assertEquals("textures/items/diamond_sword", image.data());
    }

    @Test
    @DisplayName("url：合法 https 網址保留型別與原始字串")
    void url_validUrl_storesTypeAndData() {
        FormImage image = FormImage.url("https://example.com/icon.png");

        assertEquals(FormImage.Type.URL, image.type());
        assertEquals("https://example.com/icon.png", image.data());
    }

    @Test
    @DisplayName("url：http 亦接受（scheme 大小寫不敏感）")
    void url_httpScheme_accepted() {
        assertEquals(FormImage.Type.URL, FormImage.url("http://example.com/i.png").type());
        assertEquals(FormImage.Type.URL, FormImage.url("HTTP://example.com/i.png").type());
    }

    @Test
    @DisplayName("path：null 或空白 → IllegalArgumentException")
    void path_nullOrBlank_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormImage.path(null));
        assertThrows(IllegalArgumentException.class, () -> FormImage.path(""));
        assertThrows(IllegalArgumentException.class, () -> FormImage.path("   "));
    }

    @Test
    @DisplayName("path：以 / 開頭 → IllegalArgumentException")
    void path_leadingSlash_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> FormImage.path("/textures/items/stone"));
    }

    @Test
    @DisplayName("path：含 .. → IllegalArgumentException")
    void path_dotDotSegment_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> FormImage.path("textures/../secret"));
        assertThrows(IllegalArgumentException.class, () -> FormImage.path(".."));
    }

    @Test
    @DisplayName("url：null 或空白 → IllegalArgumentException")
    void url_nullOrBlank_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormImage.url(null));
        assertThrows(IllegalArgumentException.class, () -> FormImage.url(""));
        assertThrows(IllegalArgumentException.class, () -> FormImage.url("  "));
    }

    @Test
    @DisplayName("url：非 http/https scheme → IllegalArgumentException")
    void url_nonHttpScheme_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> FormImage.url("ftp://example.com/icon.png"));
        assertThrows(IllegalArgumentException.class,
            () -> FormImage.url("textures/items/stone"));
    }

    @Test
    @DisplayName("url：缺少 host → IllegalArgumentException")
    void url_missingHost_rejected() {
        assertThrows(IllegalArgumentException.class, () -> FormImage.url("http://"));
        assertThrows(IllegalArgumentException.class, () -> FormImage.url("https://"));
        assertThrows(IllegalArgumentException.class, () -> FormImage.url("http:///path"));
    }

    @Test
    @DisplayName("canonical constructor：null type／不合法 data 不能繞過驗證")
    void canonicalConstructor_invalidInputs_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FormImage(null, "textures/x"));
        assertThrows(IllegalArgumentException.class,
            () -> new FormImage(FormImage.Type.PATH, null));
        assertThrows(IllegalArgumentException.class,
            () -> new FormImage(FormImage.Type.PATH, "/textures/x"));
        assertThrows(IllegalArgumentException.class,
            () -> new FormImage(FormImage.Type.URL, "ftp://example.com/x"));
    }

    @Test
    @DisplayName("record 語意：相同內容相等")
    void recordSemantics_equalContent_isEqual() {
        assertEquals(FormImage.path("textures/x"), FormImage.path("textures/x"));
    }
}
