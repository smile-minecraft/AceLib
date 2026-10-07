package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.smile.acelib.bedrock.BedrockService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 確認基岩服務停用會一併結束它所擁有的表單回應生命週期。 */
@DisplayName("基岩服務的表單生命週期")
class BedrockServiceFormLifecycleTest {

    @Test
    @DisplayName("shutdown 清空 pending response，遲到的 Floodgate callback 零執行")
    void shutdownClearsPendingFormAndIgnoresLateResponse() {
        AtomicReference<Consumer<FormResponse>> responseHandler = new AtomicReference<>();
        AtomicInteger delivered = new AtomicInteger();
        FormService.FormSender sender = new FormService.FormSender() {
            @Override
            public FormSendResult sendForm(UUID playerId, FormSpec form) {
                return FormSendResult.SENT;
            }

            @Override
            public FormSendResult sendForm(UUID playerId, FormSpec form, UUID token,
                    Consumer<FormResponse> onResponse) {
                responseHandler.set(onResponse);
                return FormSendResult.SENT;
            }
        };
        FormService formService = FormService.forProduction(sender);
        BedrockService bedrock = BedrockService.forProduction(
            BedrockService.PlayerLookup.absent(), formService);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000107");

        assertSame(formService, bedrock.forms());
        assertEquals(FormSendResult.SENT,
            formService.sendForm(playerId,
                FormSpec.simple("title").content("body").button("ok").build(),
                response -> delivered.incrementAndGet()));
        assertEquals(1, ((FormServiceImpl) formService).pendingCountForTesting());

        bedrock.shutdown();

        assertEquals(0, ((FormServiceImpl) formService).pendingCountForTesting());
        responseHandler.get().accept(new FormResponse(FormResponseStatus.VALID, 0, List.of()));
        assertEquals(0, delivered.get(), "遲到回應不得執行已清理的 consumer");
        assertEquals("FAILED", bedrock.getModuleStatus());
    }
}
