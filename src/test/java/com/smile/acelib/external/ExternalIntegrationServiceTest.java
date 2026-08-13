package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExternalIntegrationService 對外服務測試（Phase 13 W2）。
 *
 * <p>驗證 unavailable facade（NOT_READY / SHUTDOWN 兩態）的任何查詢方法
 * 不拋例外、回傳合理非 null 值；null input 依專案契約拋 IllegalArgumentException
 * （與 WorldService / GuiService unavailable facade 一致）。</p>
 */
@DisplayName("ExternalIntegrationService")
class ExternalIntegrationServiceTest {

    @Test
    @DisplayName("forUnavailable(NOT_READY) 回傳非 null facade")
    void forUnavailable_notReady_isNonNull() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        assertNotNull(svc);
    }

    @Test
    @DisplayName("forUnavailable(SHUTDOWN) 回傳非 null facade")
    void forUnavailable_shutdown_isNonNull() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.SHUTDOWN);
        assertNotNull(svc);
    }

    @Test
    @DisplayName("forUnavailable(null) 必須拋 IllegalArgumentException")
    void forUnavailable_nullCode_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> ExternalIntegrationService.forUnavailable(null));
    }

    @Test
    @DisplayName("forUnavailable 不接受非 NOT_READY / SHUTDOWN 的 code")
    void forUnavailable_invalidCode_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> ExternalIntegrationService.forUnavailable("ACELIB-EXT-001"));
    }

    @Test
    @DisplayName("NOT_READY facade 的 getStatus 不拋出、回傳非 null 且狀態可理解")
    void notReadyFacade_getStatus_returnsNonNullReasonableResult() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result, "getStatus 必須回傳非 null");
        assertNotNull(result.status(), "status 不可為 null");
        assertNotNull(result.reason(), "reason 不可為 null");
        assertEquals(IntegrationStatus.INIT_FAILED, result.status(),
            "未就緒服務的查詢結果應為 INIT_FAILED（等價表示）");
    }

    @Test
    @DisplayName("SHUTDOWN facade 的 getStatus 不拋出、回傳非 null 且狀態可理解")
    void shutdownFacade_getStatus_returnsNonNullReasonableResult() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.SHUTDOWN);
        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
    }

    @Test
    @DisplayName("NOT_READY facade 的 getModuleStatus() 為 NOT_INITIALIZED")
    void notReadyFacade_getModuleStatus_isNotInitialized() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("SHUTDOWN facade 的 getModuleStatus() 為 FAILED")
    void shutdownFacade_getModuleStatus_isFailed() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.SHUTDOWN);
        assertEquals("FAILED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("unavailable facade 的 shutdown() 為 no-op 不拋出")
    void unavailableFacade_shutdown_doesNotThrow() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(svc::shutdown);
    }

    @Test
    @DisplayName("getStatus(null) 依契約拋 IllegalArgumentException（不吞錯）")
    void getStatus_nullInput_throws() {
        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        assertThrows(IllegalArgumentException.class, () -> svc.getStatus(null));
    }
}