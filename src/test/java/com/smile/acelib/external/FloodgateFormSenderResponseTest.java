package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FloodgateFormSender 回應派送擴充測試：四參數路徑把 token + 接收端接到
 * Cumulus handler（以真實 codec 觸發驗證端到端映射）、雙參數路徑維持
 * fire-and-forget、REJECTED 映射不變。
 *
 * <p>本測試位於 external 套件內，可直接參照 package-private 的
 * {@code FloodgateFormSender}。</p>
 */
@DisplayName("FloodgateFormSender 回應派送")
class FloodgateFormSenderResponseTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000008");
    private static final UUID TOKEN = UUID.fromString(
        "00000000-0000-0000-0000-000000000009");

    private static FormSpec.Simple sampleSpec() {
        return FormSpec.simple("標題").content("內容").button("按鈕").build();
    }

    @Test
    @DisplayName("實作既有 FormSender seam（四參數為 default 方法覆寫）")
    void implementsFormSenderSeam() {
        FloodgateFormSender sender =
            new FloodgateFormSender(() -> mock(FloodgateApi.class));
        assertInstanceOf(FormService.FormSender.class, sender);
    }

    @Test
    @DisplayName("四參數發送：送出的表單已掛 handler；原始回呼經接收端交付映射結果")
    void responseAwareSend_attachesHandler_deliversMappedResponse() throws Exception {
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(true);
        FloodgateFormSender sender = new FloodgateFormSender(() -> api);

        AtomicReference<FormResponse> received = new AtomicReference<>();
        FormSendResult result = sender.sendForm(PLAYER_ID, sampleSpec(), TOKEN, received::set);

        assertEquals(FormSendResult.SENT, result);

        // 從送出的表單模擬 Floodgate 原始回呼（真實 codec 解析路徑）
        ArgumentCaptor<Form> captor = ArgumentCaptor.forClass(Form.class);
        verify(api).sendForm(any(UUID.class), captor.capture());
        SimpleForm sent = assertInstanceOf(SimpleForm.class, captor.getValue());
        FormDefinitions.instance().definitionFor(sent).handleFormResponse(sent, "0");

        FormResponse response = received.get();
        assertNotNull(response, "handler 必須已掛在 builder 上並被觸發");
        assertEquals(FormResponseStatus.VALID, response.status());
        assertEquals(0, response.clickedButton().orElse(-1));
        assertNotNull(response.values());
        assertTrue(response.values().isEmpty());
    }

    @Test
    @DisplayName("四參數 REJECTED：映射不變（SENT/REJECTED 語意與雙參數版一致）")
    void responseAwareRejected_mapsRejected() {
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(false);
        FloodgateFormSender sender = new FloodgateFormSender(() -> api);

        FormSendResult result = sender.sendForm(PLAYER_ID, sampleSpec(), TOKEN,
            response -> {
                throw new AssertionError("被拒發送不得觸發回應接收端");
            });

        assertEquals(FormSendResult.REJECTED, result);
    }

    @Test
    @DisplayName("雙參數 sendForm：行為不變——送出的表單不掛 handler")
    void legacySend_stillFireAndForget() throws Exception {
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(true);
        FloodgateFormSender sender = new FloodgateFormSender(() -> api);

        assertEquals(FormSendResult.SENT, sender.sendForm(PLAYER_ID, sampleSpec()));

        ArgumentCaptor<Form> captor = ArgumentCaptor.forClass(Form.class);
        verify(api).sendForm(any(UUID.class), captor.capture());
        SimpleForm sent = assertInstanceOf(SimpleForm.class, captor.getValue());
        // 不掛 handler 的表單：handleFormResponse 為 no-op（無 handler 可呼叫）
        FormDefinitions.instance().definitionFor(sent).handleFormResponse(sent, "0");
    }
}
