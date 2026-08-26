package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FormServiceImpl 生命週期與發送語意測試：
 * NOT_READY（absent 綁定）以 ACELIB-FORM-001 拒絕、SHUTDOWN 以 ACELIB-FORM-002 拒絕、
 * shutdown 冪等、null 輸入契約，以及發送結果型別接受／拒絕可區分。
 */
@DisplayName("FormServiceImpl 生命週期與 sendForm")
class FormServiceImplTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000003");

    private static FormSpec.Simple sampleSpec() {
        return FormSpec.simple("標題").content("內容").button("按鈕").build();
    }

    // -----------------------------------------------------------------
    // 發送結果型別：接受 / 拒絕可區分
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FormSendResult：SENT 與 REJECTED 可明確區分，不得退化為 boolean")
    void formSendResult_sentAndRejectedAreDistinguishable() {
        assertTrue(FormSendResult.SENT.isSent());
        assertFalse(FormSendResult.SENT.isRejected());
        assertTrue(FormSendResult.REJECTED.isRejected());
        assertFalse(FormSendResult.REJECTED.isSent());
        assertNotNull(FormSendResult.valueOf("SENT"));
        assertNotNull(FormSendResult.valueOf("REJECTED"));
    }

    // -----------------------------------------------------------------
    // 正常路徑：委派 seam 並保留結果語意
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendForm：將同一 spec 原樣交給 sender，回傳 SENT")
    void sendForm_delegatesToSender_andReturnsSent() {
        AtomicReference<FormSpec> captured = new AtomicReference<>();
        FormService service = FormService.forProduction((playerId, form) -> {
            captured.set(form);
            return FormSendResult.SENT;
        });

        FormSpec.Simple spec = sampleSpec();
        FormSendResult result = service.sendForm(PLAYER_ID, spec);

        assertSame(spec, captured.get(), "spec 必須原樣傳遞給 sender seam");
        assertEquals(FormSendResult.SENT, result);
        assertEquals("READY", service.getModuleStatus());
    }

    @Test
    @DisplayName("sendForm：sender 回報 REJECTED 時原樣回傳，不吞不轉")
    void sendForm_senderRejected_mapsRejected() {
        FormService service = FormService.forProduction(
            (playerId, form) -> FormSendResult.REJECTED);

        assertEquals(FormSendResult.REJECTED, service.sendForm(PLAYER_ID, sampleSpec()));
    }

    // -----------------------------------------------------------------
    // NOT_READY：absent 綁定以 ACELIB-FORM-001 拒絕
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendForm（absent 綁定）：IllegalStateException 攜帶 ACELIB-FORM-001，不拋 NoClassDefFoundError")
    void sendForm_absentSender_rejectsWithNotReadyCode() {
        FormService service = FormService.forProduction(FormService.FormSender.absent());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.sendForm(PLAYER_ID, sampleSpec()),
            "Floodgate 缺席（absent 綁定）時必須以明確代碼拒絕");
        assertTrue(ex.getMessage().contains(FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY),
            "拒絕必須攜帶 ACELIB-FORM-001，實際：" + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // SHUTDOWN：ACELIB-FORM-002 拒絕 + 冪等
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown 後 sendForm 以 ACELIB-FORM-002 拒絕；getModuleStatus 為 FAILED")
    void afterShutdown_sendFormRejectsWithShutdownCode() {
        FormService service = FormService.forProduction(
            (playerId, form) -> FormSendResult.SENT);
        service.shutdown();

        assertEquals("FAILED", service.getModuleStatus(),
            "shutdown 後 module status 必須為 FAILED");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.sendForm(PLAYER_ID, sampleSpec()));
        assertTrue(ex.getMessage().contains(FormErrorCodes.ACELIB_FORM_SERVICE_SHUTDOWN),
            "shutdown 後拒絕必須攜帶 ACELIB-FORM-002，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("shutdown 冪等：重複呼叫不拋例外")
    void shutdown_isIdempotent() {
        FormService service = FormService.forProduction(FormService.FormSender.absent());
        service.shutdown();
        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus());
    }

    // -----------------------------------------------------------------
    // null 輸入契約
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendForm：playerId 或 form 為 null → IllegalArgumentException（優先於生命週期檢查）")
    void sendForm_nullInputs_throwIllegalArgument() {
        FormService service = FormService.forProduction(
            (playerId, form) -> FormSendResult.SENT);

        assertThrows(IllegalArgumentException.class, () -> service.sendForm(null, sampleSpec()));
        assertThrows(IllegalArgumentException.class, () -> service.sendForm(PLAYER_ID, null));

        service.shutdown();
        assertThrows(IllegalArgumentException.class, () -> service.sendForm(null, sampleSpec()),
            "null 輸入契約優先於 shutdown 拒絕（比照 bedrock requireReadyAndNonNull 順序）");
    }

    @Test
    @DisplayName("sendForm：null 輸入的 IllegalArgumentException 訊息不攜帶服務錯誤代碼")
    void sendForm_nullInputs_messageCarriesNoServiceErrorCode() {
        // 故意綁 absent seam：非 null 輸入會被 seam 以 FORM-001 拒絕，
        // 因此此處的 IAE 只能來自 null 檢查，可精準鎖定其訊息內容。
        FormService service = FormService.forProduction(FormService.FormSender.absent());

        IllegalArgumentException byNullPlayer = assertThrows(IllegalArgumentException.class,
            () -> service.sendForm(null, sampleSpec()));
        IllegalArgumentException byNullForm = assertThrows(IllegalArgumentException.class,
            () -> service.sendForm(PLAYER_ID, null));

        assertFalse(byNullPlayer.getMessage().contains("ACELIB-"),
            "null 輸入屬輸入驗證，訊息不得內嵌服務錯誤代碼，實際：" + byNullPlayer.getMessage());
        assertFalse(byNullForm.getMessage().contains("ACELIB-"),
            "null 輸入屬輸入驗證，訊息不得內嵌服務錯誤代碼，實際：" + byNullForm.getMessage());
    }

    @Test
    @DisplayName("forProduction(null)：sender 為 null → NullPointerException")
    void forProduction_nullSender_throwsNpe() {
        assertThrows(NullPointerException.class, () -> FormService.forProduction(null));
    }

    // -----------------------------------------------------------------
    // 三參數 overload：輸入驗證與生命週期契約
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendForm 三參數：null playerId / null form / null consumer 各自 IllegalArgumentException")
    void sendFormWithConsumer_nullInputs_throwIllegalArgument() {
        CapturingSender sender = new CapturingSender(FormSendResult.SENT);
        FormService service = new FormServiceImpl(sender);

        assertThrows(IllegalArgumentException.class,
            () -> service.sendForm(null, sampleSpec(), response -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> service.sendForm(PLAYER_ID, null, response -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> service.sendForm(PLAYER_ID, sampleSpec(), null),
            "要接收回應必須明確傳入 consumer；null consumer 一律拒絕");
    }

    @Test
    @DisplayName("sendForm 三參數：shutdown 後以 ACELIB-FORM-002 拒絕")
    void sendFormWithConsumer_afterShutdown_rejectsWithShutdownCode() {
        CapturingSender sender = new CapturingSender(FormSendResult.SENT);
        FormService service = new FormServiceImpl(sender);
        service.shutdown();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.sendForm(PLAYER_ID, sampleSpec(), response -> { }));
        assertTrue(ex.getMessage().contains(FormErrorCodes.ACELIB_FORM_SERVICE_SHUTDOWN),
            "三參數 overload 的 shutdown 拒絕必須攜帶 ACELIB-FORM-002");
    }

    @Test
    @DisplayName("sendForm 三參數：SENT/REJECTED 語意與雙參數版一致")
    void sendFormWithConsumer_resultSemanticsMatchTwoArgVersion() {
        assertEquals(FormSendResult.SENT,
            new FormServiceImpl(new CapturingSender(FormSendResult.SENT))
                .sendForm(PLAYER_ID, sampleSpec(), response -> { }));
        assertEquals(FormSendResult.REJECTED,
            new FormServiceImpl(new CapturingSender(FormSendResult.REJECTED))
                .sendForm(PLAYER_ID, sampleSpec(), response -> { }));
    }

    @Test
    @DisplayName("sendForm 三參數：absent seam（非 response-aware）退回 fire-and-forget，以 FORM-001 拒絕")
    void sendFormWithConsumer_absentSender_fallsBackToNotReadyRejection() {
        FormService service = FormService.forProduction(FormService.FormSender.absent());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.sendForm(PLAYER_ID, sampleSpec(), response -> { }));
        assertTrue(ex.getMessage().contains(FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY),
            "未接線回應派送的 sender 必須沿用既有 FORM-001 拒絕語意");
    }

    /**
     * 捕獲型 lambda fake sender（不覆寫四參數 default seam）：
     * 供驗證「未接線回應派送的 sender 走 fire-and-forget 路徑」。
     */
    private record CapturingSender(FormSendResult result)
            implements FormService.FormSender {
        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            return result;
        }
    }
}
