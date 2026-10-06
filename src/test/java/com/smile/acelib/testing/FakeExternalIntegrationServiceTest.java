package com.smile.acelib.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.external.IntegrationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code FakeExternalIntegrationService} 專屬行為：模組狀態聚合與生命週期。
 *
 * <p>與 production 共通的語意由 {@code ExternalIntegrationContract} 覆蓋；
 * 本檔只鎖定狀態聚合（AVAILABLE／DEGRADED／FAILED／NOT_INITIALIZED）與
 * 重複註冊／未知更新的拒絕。</p>
 */
@DisplayName("FakeExternalIntegrationService — 生命週期")
class FakeExternalIntegrationServiceTest {

    @Test
    @DisplayName("模組狀態聚合：空→NOT_INITIALIZED；全可用→AVAILABLE；混雜→DEGRADED；全壞→FAILED")
    void moduleStatus_aggregates() {
        FakeExternalIntegrationService service = new FakeExternalIntegrationService();
        assertEquals("NOT_INITIALIZED", service.getModuleStatus());

        service.registerProvider("vault");
        service.registerProvider("luckperms");
        assertEquals("AVAILABLE", service.getModuleStatus());
        assertEquals(2, service.registeredIds().size());

        service.updateProviderStatus("luckperms", IntegrationStatus.NOT_ENABLED, null);
        assertEquals("DEGRADED", service.getModuleStatus());

        service.updateProviderStatus("vault", IntegrationStatus.VERSION_UNSUPPORTED, "too old");
        assertEquals("FAILED", service.getModuleStatus());
        assertTrue(service.getStatus("vault").reason().contains("too old"));
    }

    @Test
    @DisplayName("重複註冊、更新未知 id、移除未知 id 都被拒絕")
    void duplicateAndUnknown_rejected() {
        FakeExternalIntegrationService service = new FakeExternalIntegrationService();
        service.registerProvider("vault");

        assertThrows(IllegalArgumentException.class,
            () -> service.registerProvider("vault"));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateProviderStatus("ghost", IntegrationStatus.AVAILABLE, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.unregisterProvider("ghost"));
        assertThrows(IllegalArgumentException.class,
            () -> service.registerProvider(null));
    }

    @Test
    @DisplayName("停用後已註冊 provider 回 NOT_ENABLED，未知 id 仍回 INIT_FAILED")
    void shutdown_freezesProviders() {
        FakeExternalIntegrationService service = new FakeExternalIntegrationService();
        service.registerProvider("vault");
        service.shutdown();
        service.shutdown();

        assertEquals(IntegrationStatus.NOT_ENABLED, service.getStatus("vault").status());
        assertEquals(IntegrationStatus.INIT_FAILED, service.getStatus("ghost").status());
        assertEquals("SHUTDOWN", service.getModuleStatus());
    }
}
