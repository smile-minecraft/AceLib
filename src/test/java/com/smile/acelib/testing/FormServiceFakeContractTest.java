package com.smile.acelib.testing;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormService;
import com.smile.acelib.testing.contracts.FormServiceContract;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;

/**
 * 表單契約假側：{@code FakeFormService} 跑同一套契約。
 */
@DisplayName("表單契約 — 假實作")
class FormServiceFakeContractTest extends FormServiceContract {

    @Override
    protected Harness newHarness() {
        return new FakeHarness();
    }

    private static final class FakeHarness implements Harness {
        private final FakeFormService service = new FakeFormService();

        @Override
        public FormService service() {
            return service;
        }

        @Override
        public UUID requireSinglePending() {
            if (service.pendingCount() != 1) {
                throw new IllegalStateException(
                    "expected exactly one pending response, got: " + service.pendingCount());
            }
            return service.pendingTokens().get(0);
        }

        @Override
        public int pendingCount() {
            return service.pendingCount();
        }

        @Override
        public void emit(UUID token, FormResponse response) {
            service.deliverResponse(token, response);
        }

        @Override
        public void rejectNextSend() {
            service.rejectNextSend();
        }

        @Override
        public void setTransportAbsent() {
            service.setTransportAbsent(true);
        }
    }
}
