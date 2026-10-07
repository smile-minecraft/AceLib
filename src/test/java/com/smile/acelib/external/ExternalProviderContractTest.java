package com.smile.acelib.external;

import com.smile.acelib.testing.contracts.ExternalProviderContract;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

/**
 * 業務提供者契約真實側：{@link ExternalIntegrationServiceImpl}＋可數假提供者
 * 跑同一套契約。
 */
@DisplayName("業務提供者契約 — 真實作")
class ExternalProviderContractTest extends ExternalProviderContract {

    @Override
    protected Harness newHarness() {
        return new RealHarness();
    }

    private static final class RealHarness implements Harness {
        private final ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
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
