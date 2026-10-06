package com.smile.acelib.testing;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.testing.contracts.ExternalIntegrationContract;
import org.junit.jupiter.api.DisplayName;

/**
 * 外部整合契約假側：{@code FakeExternalIntegrationService} 跑同一套契約。
 */
@DisplayName("外部整合契約 — 假實作")
class ExternalIntegrationFakeContractTest extends ExternalIntegrationContract {

    @Override
    protected Harness newHarness() {
        return new FakeHarness();
    }

    private static final class FakeHarness implements Harness {
        private final FakeExternalIntegrationService service =
            new FakeExternalIntegrationService();

        @Override
        public ExternalIntegrationService service() {
            return service;
        }

        @Override
        public void addAvailable(String integrationId) {
            service.registerProvider(integrationId);
        }

        @Override
        public void setDisabled(String integrationId) {
            service.updateProviderStatus(integrationId, IntegrationStatus.NOT_ENABLED, null);
        }

        @Override
        public void remove(String integrationId) {
            service.unregisterProvider(integrationId);
        }

        @Override
        public void reacquire(String integrationId) {
            service.registerProvider(integrationId);
        }
    }
}
