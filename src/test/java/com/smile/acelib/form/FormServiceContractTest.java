package com.smile.acelib.form;

import com.smile.acelib.testing.contracts.FormServiceContract;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;

/**
 * 表單契約真實側：錄製 sender＋同步派送的 {@code FormServiceImpl} 跑同一套契約。
 */
@DisplayName("表單契約 — 真實作")
class FormServiceContractTest extends FormServiceContract {

    @Override
    protected Harness newHarness() {
        return new RealHarness();
    }

    private static final class RealHarness implements Harness {
        private final RecordingSender sender = new RecordingSender();
        private final FormServiceImpl service =
            new FormServiceImpl(sender, FormResponseDispatcher.direct());

        @Override
        public FormService service() {
            return service;
        }

        @Override
        public UUID requireSinglePending() {
            if (service.pendingCountForTesting() != 1 || sender.callbacks.size() != 1) {
                throw new IllegalStateException("expected exactly one pending response, got: "
                    + service.pendingCountForTesting());
            }
            return sender.callbacks.keySet().iterator().next();
        }

        @Override
        public int pendingCount() {
            return service.pendingCountForTesting();
        }

        @Override
        public void emit(UUID token, FormResponse response) {
            Consumer<FormResponse> callback = sender.callbacks.get(token);
            if (callback != null) {
                callback.accept(response);
            }
        }

        @Override
        public void rejectNextSend() {
            sender.rejectNext = true;
        }

        @Override
        public void setTransportAbsent() {
            sender.absent = true;
        }
    }

    /** 錄製 token→callback 的 scripted sender（缺席／拒絕可編排）。 */
    private static final class RecordingSender implements FormService.FormSender {
        final Map<UUID, Consumer<FormResponse>> callbacks = new LinkedHashMap<>();
        boolean rejectNext;
        boolean absent;

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            if (absent) {
                throw new IllegalStateException("["
                    + FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY
                    + "] form service is unavailable: no bedrock form transport bound");
            }
            return consumeNext();
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form, UUID token,
                Consumer<FormResponse> onResponse) {
            if (absent) {
                throw new IllegalStateException("["
                    + FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY
                    + "] form service is unavailable: no bedrock form transport bound");
            }
            callbacks.put(token, onResponse);
            return consumeNext();
        }

        private FormSendResult consumeNext() {
            FormSendResult result = rejectNext ? FormSendResult.REJECTED : FormSendResult.SENT;
            rejectNext = false;
            return result;
        }
    }
}
