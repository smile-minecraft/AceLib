package com.smile.acelib.testing;

import com.smile.acelib.external.EconomyProvider;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.testing.contracts.ExternalProviderContract;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

/**
 * 業務提供者契約假側：{@code FakeExternalIntegrationService} 跑同一套契約。
 */
@DisplayName("業務提供者契約 — 假實作")
class ExternalProviderFakeContractTest extends ExternalProviderContract {

    @Override
    protected Harness newHarness() {
        return new FakeHarness();
    }

    private static final class FakeHarness implements Harness {
        private final FakeExternalIntegrationService service =
            new FakeExternalIntegrationService();
        private final OfflinePlayer player = Mockito.mock(OfflinePlayer.class);

        @Override
        public ExternalIntegrationService service() {
            return service;
        }

        @Override
        public OfflinePlayer player() {
            return player;
        }

        @Override
        public void useEconomy(EconomyProvider provider) {
            service.setEconomyProvider(provider);
        }

        @Override
        public void dropEconomy() {
            service.clearEconomyProvider();
        }
    }
}
