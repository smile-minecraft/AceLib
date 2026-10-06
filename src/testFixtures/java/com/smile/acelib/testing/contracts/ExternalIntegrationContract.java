package com.smile.acelib.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 外部整合服務契約（真實作與假實作共用）。
 *
 * <p>鎖定「現有」公開 {@link ExternalIntegrationService} 語意：可用查詢、
 * 缺席（未知 id）、停用、移除後缺席、重新取得、服務停用。子類提供 harness
 * （生產側用 registry＋測試 adapter，假側用
 * {@code FakeExternalIntegrationService}）。</p>
 *
 * @since 1.4.0
 */
@DisplayName("外部整合服務契約（真／假共用）")
public abstract class ExternalIntegrationContract {

    /** 可操控 provider 生命週期的測試 harness（每測試全新）。 */
    protected interface Harness {
        ExternalIntegrationService service();

        /** 新增可用 provider。 */
        void addAvailable(String integrationId);

        /** 停用 provider（仍註冊，狀態轉 NOT_ENABLED）。 */
        void setDisabled(String integrationId);

        /** 移除 provider（模擬缺席）。 */
        void remove(String integrationId);

        /** 重新取得 provider（移除後再次可用）。 */
        void reacquire(String integrationId);
    }

    /** 每個測試全新 harness。 */
    protected abstract Harness newHarness();

    @Test
    @DisplayName("可用 provider 回 AVAILABLE")
    void availableProvider_isAvailable() {
        Harness harness = newHarness();
        harness.addAvailable("vault");

        IntegrationProbeResult result = harness.service().getStatus("vault");
        assertEquals(IntegrationStatus.AVAILABLE, result.status());
    }

    @Test
    @DisplayName("未知 id 回 INIT_FAILED（缺席）")
    void unknownId_isInitFailed() {
        Harness harness = newHarness();

        IntegrationProbeResult result = harness.service().getStatus("not-installed");
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertTrue(!result.reason().isBlank());
    }

    @Test
    @DisplayName("停用 provider 回 NOT_ENABLED")
    void disabledProvider_isNotEnabled() {
        Harness harness = newHarness();
        harness.addAvailable("vault");
        harness.setDisabled("vault");

        IntegrationProbeResult result = harness.service().getStatus("vault");
        assertEquals(IntegrationStatus.NOT_ENABLED, result.status());
    }

    @Test
    @DisplayName("移除後查詢回 INIT_FAILED；重新取得後恢復 AVAILABLE")
    void removedThenReacquired_recovers() {
        Harness harness = newHarness();
        harness.addAvailable("vault");
        harness.remove("vault");

        IntegrationProbeResult absent = harness.service().getStatus("vault");
        assertEquals(IntegrationStatus.INIT_FAILED, absent.status());

        harness.reacquire("vault");
        IntegrationProbeResult recovered = harness.service().getStatus("vault");
        assertEquals(IntegrationStatus.AVAILABLE, recovered.status());
        assertNotEquals(absent, recovered);
    }

    @Test
    @DisplayName("服務停用後已註冊 provider 不再 AVAILABLE，模組狀態為 SHUTDOWN")
    void shutdown_marksProvidersUnavailable() {
        Harness harness = newHarness();
        harness.addAvailable("vault");
        harness.service().shutdown();

        IntegrationProbeResult result = harness.service().getStatus("vault");
        assertNotEquals(IntegrationStatus.AVAILABLE, result.status());
        assertEquals("SHUTDOWN", harness.service().getModuleStatus());
    }

    @Test
    @DisplayName("空服務模組狀態為 NOT_INITIALIZED")
    void emptyService_isNotInitialized() {
        Harness harness = newHarness();
        assertEquals("NOT_INITIALIZED", harness.service().getModuleStatus());
    }

    @Test
    @DisplayName("邊界：null id 拋 IllegalArgumentException")
    void nullId_throws() {
        Harness harness = newHarness();
        assertThrows(IllegalArgumentException.class,
            () -> harness.service().getStatus(null));
    }
}
