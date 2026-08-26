package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FloodgateFormSender 測試：翻譯→發送→boolean 映射的接線、延遲綁定契約
 * （建構時不得觸發 FloodgateApi.getInstance()）。
 */
@DisplayName("FloodgateFormSender")
class FloodgateFormSenderTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000004");

    private static FormSpec.Simple sampleSpec() {
        return FormSpec.simple("標題").content("內容").button("按鈕").build();
    }

    @Test
    @DisplayName("FloodgateApi.sendForm 回傳 true → FormSendResult.SENT，且送出的是翻譯後的 Cumulus 表單")
    void sendForm_accepted_mapsToSent_andTranslatesSpec() {
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(true);
        FloodgateFormSender sender = new FloodgateFormSender(() -> api);

        FormSendResult result = sender.sendForm(PLAYER_ID, sampleSpec());

        assertEquals(FormSendResult.SENT, result);
        ArgumentCaptor<Form> captor = ArgumentCaptor.forClass(Form.class);
        verify(api).sendForm(any(UUID.class), captor.capture());
        SimpleForm translated = assertInstanceOf(SimpleForm.class, captor.getValue(),
            "seam 必須把 AceLib spec 翻成 Cumulus 表單再交給 FloodgateApi");
        assertEquals("標題", translated.title());
    }

    @Test
    @DisplayName("FloodgateApi.sendForm 回傳 false → FormSendResult.REJECTED（boolean 不外洩）")
    void sendForm_refused_mapsToRejected() {
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(false);
        FloodgateFormSender sender = new FloodgateFormSender(() -> api);

        assertEquals(FormSendResult.REJECTED, sender.sendForm(PLAYER_ID, sampleSpec()));
    }

    @Test
    @DisplayName("延遲綁定：建構時不呼叫 supplier；sendForm 時才取 instance")
    void lazyBinding_supplierNotInvokedAtConstruction() {
        AtomicInteger calls = new AtomicInteger(0);
        FloodgateApi api = mock(FloodgateApi.class);
        when(api.sendForm(any(UUID.class), any(Form.class))).thenReturn(true);

        FloodgateFormSender sender = new FloodgateFormSender(() -> {
            calls.incrementAndGet();
            return api;
        });

        assertEquals(0, calls.get(), "建構時不得查詢 FloodgateApi instance");
        sender.sendForm(PLAYER_ID, sampleSpec());
        assertEquals(1, calls.get(), "sendForm 時才綁定 instance");
    }

    @Test
    @DisplayName("seam 介面契約：實作 FormService.FormSender")
    void implementsFormSenderSeam() {
        FloodgateFormSender sender = new FloodgateFormSender(() -> mock(FloodgateApi.class));
        assertInstanceOf(FormService.FormSender.class, sender);
    }
}
