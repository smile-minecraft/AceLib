package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.ErrorCategory;
import com.smile.acelib.diagnostics.ErrorCodeRegistry;
import com.smile.acelib.form.FormErrorCodes;
import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cumulus 圖示映射測試：混合有圖示／無圖示按鈕的逐顆對應，以及單顆圖示轉換失敗
 * 時退回純文字（記錄 ACELIB-FORM-003 warning、表單照常送出、按鈕索引不變）。
 *
 * <p>失敗注入經 package-private 的 imageMapper 接縫（無全域可變狀態）：
 * Cumulus 1.1.2 的 {@code FormImage.of}／{@code ButtonComponent.of}／
 * {@code SimpleForm.Builder.button} 對合法輸入不做內容驗證（見 sources jar），
 * 真實失敗僅來自未預期的運行期例外；測試以拋例外的 mapper 重現該路徑。</p>
 */
@DisplayName("CumulusFormTranslator 圖示映射")
class CumulusFormTranslatorImageTest {

    @Test
    @DisplayName("混合按鈕逐顆對應：有圖示者帶圖示、無圖示者不帶圖示")
    void mixedButtons_mappedPerButton() {
        FormSpec.Simple spec = FormSpec.simple("配方")
            .content("選擇配方")
            .button("鑄石劍", FormImage.path("textures/items/diamond_sword"))
            .button("返回")
            .button("官網", FormImage.url("https://example.com/icon.png"))
            .build();

        SimpleForm form = assertInstanceOf(SimpleForm.class,
            CumulusFormTranslator.toCumulus(spec));

        assertEquals(3, form.buttons().size());
        assertEquals("鑄石劍", form.buttons().get(0).text());
        assertEquals("返回", form.buttons().get(1).text());
        assertEquals("官網", form.buttons().get(2).text());

        assertNotNull(form.buttons().get(0).image(), "帶圖示按鈕必須有 image");
        assertEquals(org.geysermc.cumulus.util.FormImage.Type.PATH,
            form.buttons().get(0).image().type());
        assertEquals("textures/items/diamond_sword", form.buttons().get(0).image().data());

        assertNull(form.buttons().get(1).image(), "無圖示按鈕不得帶 image");

        assertNotNull(form.buttons().get(2).image(), "帶圖示按鈕必須有 image");
        assertEquals(org.geysermc.cumulus.util.FormImage.Type.URL,
            form.buttons().get(2).image().type());
        assertEquals("https://example.com/icon.png", form.buttons().get(2).image().data());
    }

    @Test
    @DisplayName("單顆圖示轉換失敗：該按鈕退回純文字、不重複加入、後續索引不變、表單仍可送出、記錄 ACELIB-FORM-003")
    void singleImageFailure_fallsBackToPlainText() {
        FormSpec.Simple spec = FormSpec.simple("配方")
            .content("選擇配方")
            .button("鑄石劍", FormImage.path("textures/items/diamond_sword"))
            .button("壞掉的圖", FormImage.url("https://example.com/broken.png"))
            .button("返回")
            .build();

        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("AceLib");
        logger.addHandler(capture);
        try {
            Form translated = CumulusFormTranslator.toCumulus(spec, response -> {
            }, image -> {
                if (image.data().contains("broken")) {
                    throw new IllegalStateException("simulated image mapping failure");
                }
                return org.geysermc.cumulus.util.FormImage.of(
                    org.geysermc.cumulus.util.FormImage.Type.valueOf(image.type().name()),
                    image.data());
            });

            SimpleForm form = assertInstanceOf(SimpleForm.class, translated);
            assertEquals(3, form.buttons().size(), "按鈕數必須不變（不得重複加入）");
            assertEquals("鑄石劍", form.buttons().get(0).text());
            assertEquals("壞掉的圖", form.buttons().get(1).text());
            assertEquals("返回", form.buttons().get(2).text());

            assertNotNull(form.buttons().get(0).image(), "成功的圖示必須保留");
            assertNull(form.buttons().get(1).image(), "失敗的按鈕必須退回純文字");
            assertNull(form.buttons().get(2).image(), "後續按鈕索引不得位移");
        } finally {
            logger.removeHandler(capture);
        }

        assertTrue(records.stream().anyMatch(record ->
                record.getLevel() == Level.WARNING
                    && record.getMessage() != null
                    && record.getMessage().contains(FormErrorCodes.ACELIB_FORM_IMAGE_FALLBACK)),
            "必須以 WARNING 記錄 " + FormErrorCodes.ACELIB_FORM_IMAGE_FALLBACK
                + "（實際記錄：" + records.stream().map(LogRecord::getMessage).toList() + "）");
    }

    @Test
    @DisplayName("ACELIB-FORM-003 已登記於 ErrorCodeRegistry（FORM 分類）")
    void form003_registeredInRegistry() {
        assertEquals("ACELIB-FORM-003", FormErrorCodes.ACELIB_FORM_IMAGE_FALLBACK);
        assertNotNull(ErrorCodeRegistry.lookup("ACELIB-FORM-003"),
            "ACELIB-FORM-003 必須已登記");
        assertSame(ErrorCategory.FORM,
            ErrorCodeRegistry.categorize("ACELIB-FORM-003"));
    }

    @Test
    @DisplayName("接縫 overload 的 null 參數 fail-fast（不吞錯）")
    void seamOverload_nullArguments_rejected() {
        FormSpec.Simple spec = FormSpec.simple("t").content("c").button("x").build();
        Consumer<FormResponse> noop = response -> {
        };
        Function<FormImage, org.geysermc.cumulus.util.FormImage> mapper =
            image -> org.geysermc.cumulus.util.FormImage.of(
                org.geysermc.cumulus.util.FormImage.Type.valueOf(image.type().name()),
                image.data());

        assertThrows(NullPointerException.class,
            () -> CumulusFormTranslator.toCumulus(spec, null, mapper));
        assertThrows(NullPointerException.class,
            () -> CumulusFormTranslator.toCumulus(spec, noop, null));
    }
}
