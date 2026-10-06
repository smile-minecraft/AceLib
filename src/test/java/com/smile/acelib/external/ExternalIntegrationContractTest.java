package com.smile.acelib.external;

import com.smile.acelib.testing.contracts.ExternalIntegrationContract;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;

/**
 * 外部整合契約真實側：registry＋測試 adapter 的
 * {@code ExternalIntegrationServiceImpl} 跑同一套契約。
 */
@DisplayName("外部整合契約 — 真實作")
class ExternalIntegrationContractTest extends ExternalIntegrationContract {

    @Override
    protected Harness newHarness() {
        return new RealHarness();
    }

    private static final class RealHarness implements Harness {
        private final IntegrationRegistry registry = new IntegrationRegistry();
        private final ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(registry);
        private final Map<String, LifecycleAdapter> adapters = new LinkedHashMap<>();

        @Override
        public ExternalIntegrationService service() {
            return service;
        }

        @Override
        public void addAvailable(String integrationId) {
            LifecycleAdapter adapter = new LifecycleAdapter(integrationId);
            registry.register(adapter);
            adapters.put(integrationId, adapter);
            registry.initializeAll();
        }

        @Override
        public void setDisabled(String integrationId) {
            adapters.get(integrationId).shutdown();
        }

        @Override
        public void remove(String integrationId) {
            registry.unregister(integrationId);
            adapters.remove(integrationId);
        }

        @Override
        public void reacquire(String integrationId) {
            addAvailable(integrationId);
        }
    }

    /** 啟用→AVAILABLE、停用→NOT_ENABLED 的最小測試 adapter。 */
    private static final class LifecycleAdapter implements IntegrationAdapter {
        private final String id;
        private boolean active;

        LifecycleAdapter(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void initialize() {
            active = true;
        }

        @Override
        public void shutdown() {
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public IntegrationProbeResult getStatus() {
            if (active) {
                return IntegrationProbeResult.of(IntegrationStatus.AVAILABLE, null);
            }
            return IntegrationProbeResult.of(IntegrationStatus.NOT_ENABLED,
                "integration '" + id + "' is installed but not enabled");
        }
    }
}
