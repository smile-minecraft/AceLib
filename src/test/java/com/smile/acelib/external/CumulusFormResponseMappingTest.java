package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CumulusFormTranslator 回應 handler 掛接與映射測試：以真實 Cumulus 1.1.2 物件
 * 驗證 builder 有掛上 handler，並以 {@code FormDefinitions.handleFormResponse}
 * 模擬 Floodgate 的原始回呼（真實 codec 解析路徑），斷言
 * {@code ResultType → FormResponseStatus} 與回應內容映射正確。
 */
@DisplayName("CumulusFormTranslator 回應 handler 掛接與映射")
class CumulusFormResponseMappingTest {

    /** 模擬 Floodgate 原始回呼：經真實 codec 解析並觸發 handler。 */
    private static void fireRawResponse(Form form, String responseData) throws Exception {
        FormDefinitions.instance().definitionFor(form).handleFormResponse(form, responseData);
    }

    private static FormSpec.Simple.Builder simpleBuilder(String... buttons) {
        FormSpec.Simple.Builder builder = FormSpec.simple("標題").content("內容");
        for (String button : buttons) {
            builder.button(button);
        }
        return builder;
    }

    // -----------------------------------------------------------------
    // Simple：VALID / CLOSED / INVALID 三種 ResultType 映射
    // -----------------------------------------------------------------

    @Test
    @DisplayName("simple：原始回應 \"1\" → VALID + clickedButton=1 + 空 values")
    void simple_validResponse_mapsClickedButton() throws Exception {
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form form = CumulusFormTranslator.toCumulus(
            simpleBuilder("主城", "資源世界").build(), captured::set);

        fireRawResponse(form, "1");

        FormResponse response = captured.get();
        assertNotNull(response, "handler 必須已掛在 builder 上並被觸發");
        assertEquals(FormResponseStatus.VALID, response.status());
        assertEquals(1, response.clickedButton().orElse(-1));
        assertTrue(response.values().isEmpty());
    }

    @Test
    @DisplayName("simple：原始回應 null（玩家關閉）→ CLOSED、無按鈕、空 values")
    void simple_closedResponse_mapsClosed() throws Exception {
        var spec = simpleBuilder("按鈕").build();
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form form = CumulusFormTranslator.toCumulus(spec, captured::set);

        fireRawResponse(form, null);

        FormResponse response = captured.get();
        assertNotNull(response);
        assertEquals(FormResponseStatus.CLOSED, response.status());
        assertTrue(response.clickedButton().isEmpty(), "CLOSED 不得攜帶按鈕索引");
        assertTrue(response.values().isEmpty());
    }

    @Test
    @DisplayName("simple：原始回應非整數 → INVALID")
    void simple_invalidResponse_mapsInvalid() throws Exception {
        var spec = simpleBuilder("按鈕").build();
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form form = CumulusFormTranslator.toCumulus(spec, captured::set);

        fireRawResponse(form, "not-a-number");

        FormResponse response = captured.get();
        assertNotNull(response);
        assertEquals(FormResponseStatus.INVALID, response.status());
        assertTrue(response.clickedButton().isEmpty(), "INVALID 不得攜帶按鈕索引");
    }

    // -----------------------------------------------------------------
    // Modal：0/1 按鈕語意
    // -----------------------------------------------------------------

    @Test
    @DisplayName("modal：原始回應 \"true\" / \"false\" → VALID + clickedButton 對應第一／第二顆")
    void modal_clickedButton_mapsZeroOrOne() throws Exception {
        var spec = FormSpec.modal("確認").content("要繼續嗎？").button1("是").button2("否").build();
        // Cumulus modal codec 的原始回應為布林字串：true → 第一顆（0）、false → 第二顆（1）
        AtomicReference<FormResponse> first = new AtomicReference<>();
        Form form1 = CumulusFormTranslator.toCumulus(spec, first::set);
        fireRawResponse(form1, "true");
        assertEquals(FormResponseStatus.VALID, first.get().status());
        assertEquals(0, first.get().clickedButton().orElse(-1));

        AtomicReference<FormResponse> second = new AtomicReference<>();
        Form form2 = CumulusFormTranslator.toCumulus(spec, second::set);
        fireRawResponse(form2, "false");
        assertEquals(1, second.get().clickedButton().orElse(-1));
    }

    // -----------------------------------------------------------------
    // Custom：元件答案依產值順序映射；label 不產值
    // -----------------------------------------------------------------

    @Test
    @DisplayName("custom：六元件（含 label）回應 → values 只含五個產值答案且型別正確")
    void custom_valuesMappedInOrder_labelsSkipped() throws Exception {
        var spec = FormSpec.custom("設定")
            .label("一般設定")
            .input("暱稱", "輸入", "")
            .dropdown("難度", List.of("簡單", "普通", "困難"), 0)
            .slider("音量", 0.0f, 100.0f, 5.0f, 50.0f)
            .stepSlider("模式", List.of("低", "中", "高"), 0)
            .toggle("通知", false)
            .build();
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form form = CumulusFormTranslator.toCumulus(spec, captured::set);

        // Bedrock custom 表單原始回應：JSON 陣列，label 位置為 null
        fireRawResponse(form, "[null,\"Steve\",2,25.0,1,true]");

        FormResponse response = captured.get();
        assertNotNull(response, "custom handler 必須被觸發");
        assertEquals(FormResponseStatus.VALID, response.status());
        assertTrue(response.clickedButton().isEmpty(), "custom 表單無按鈕語意");
        assertEquals(
            List.of(
                new FormValue.Text("Steve"),
                new FormValue.Option(2),
                new FormValue.Number(25.0f),
                new FormValue.Option(1),
                new FormValue.Switch(true)),
            response.values(),
            "values 必須依產值元件順序排列，label 不佔位");
    }

    @Test
    @DisplayName("custom：零元件表單 VALID 回應 → 空 values")
    void custom_emptyComponents_emptyValues() throws Exception {
        var spec = FormSpec.custom("僅標題").build();
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form form = CumulusFormTranslator.toCumulus(spec, captured::set);

        fireRawResponse(form, "[]");

        assertEquals(FormResponseStatus.VALID, captured.get().status());
        assertTrue(captured.get().values().isEmpty());
    }

    // -----------------------------------------------------------------
    // 雙參數路徑不掛 handler：既有 fire-and-forget 行為不變
    // -----------------------------------------------------------------

    @Test
    @DisplayName("雙參數 toCumulus：不掛 handler，原始回呼不觸發任何 callback")
    void twoArgTranslation_attachesNoHandler() throws Exception {
        AtomicReference<FormResponse> captured = new AtomicReference<>();
        Form plain = CumulusFormTranslator.toCumulus(
            simpleBuilder("按鈕").build());

        fireRawResponse(plain, "0");

        assertNull(captured.get(), "雙參數翻譯不得掛 handler、不得觸發任何 callback");
    }
}
