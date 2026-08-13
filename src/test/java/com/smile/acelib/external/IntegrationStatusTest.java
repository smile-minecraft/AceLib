package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IntegrationStatus 狀態模型測試（Phase 13 W1）。
 *
 * <p>五種狀態（AVAILABLE / NOT_INSTALLED / NOT_ENABLED / VERSION_UNSUPPORTED /
 * INIT_FAILED）各自必須有管理員可理解的預設 reason；factory 回傳的 instance
 * reason 永不為 null / 空白。動態 reason（例如版本比較結果）由
 * {@link IntegrationProbeResult} 承載，null reason 由 factory 給定預設。</p>
 */
@DisplayName("IntegrationStatus")
class IntegrationStatusTest {

    @Test
    @DisplayName("五種狀態 factory 各自回傳對應常數")
    void factories_returnMatchingConstants() {
        assertSame(IntegrationStatus.AVAILABLE, IntegrationStatus.available());
        assertSame(IntegrationStatus.NOT_INSTALLED, IntegrationStatus.notInstalled());
        assertSame(IntegrationStatus.NOT_ENABLED, IntegrationStatus.notEnabled());
        assertSame(IntegrationStatus.VERSION_UNSUPPORTED, IntegrationStatus.versionUnsupported());
        assertSame(IntegrationStatus.INIT_FAILED, IntegrationStatus.initFailed());
    }

    @Test
    @DisplayName("五種狀態的預設 reason 皆非空")
    void allStates_haveNonEmptyDefaultReason() {
        for (IntegrationStatus status : IntegrationStatus.values()) {
            String reason = status.getDefaultReason();
            assertNotNull(reason, status + " 的預設 reason 不可為 null");
            assertFalse(reason.isBlank(), status + " 的預設 reason 不可為空白");
        }
    }

    @Test
    @DisplayName("AVAILABLE 預設 reason 描述可用狀態")
    void available_defaultReason_isAdminUnderstandable() {
        assertTrue(IntegrationStatus.AVAILABLE.getDefaultReason().toLowerCase().contains("available"),
            "reason 應描述可用狀態: " + IntegrationStatus.AVAILABLE.getDefaultReason());
    }

    @Test
    @DisplayName("IntegrationProbeResult.of(status, null) 由 factory 給定預設 reason")
    void probeResult_nullReason_getsDefault() {
        IntegrationProbeResult result = IntegrationProbeResult.of(IntegrationStatus.NOT_INSTALLED, null);
        assertNotNull(result);
        assertEquals(IntegrationStatus.NOT_INSTALLED, result.status());
        assertEquals(IntegrationStatus.NOT_INSTALLED.getDefaultReason(), result.reason());
    }

    @Test
    @DisplayName("IntegrationProbeResult.of(status, blank) 由 factory 給定預設 reason")
    void probeResult_blankReason_getsDefault() {
        IntegrationProbeResult result = IntegrationProbeResult.of(IntegrationStatus.NOT_ENABLED, "   ");
        assertEquals(IntegrationStatus.NOT_ENABLED.getDefaultReason(), result.reason());
    }

    @Test
    @DisplayName("IntegrationProbeResult.of(status, custom) 保留自訂 reason")
    void probeResult_customReason_isPreserved() {
        IntegrationProbeResult result = IntegrationProbeResult.of(
            IntegrationStatus.VERSION_UNSUPPORTED, "custom reason");
        assertEquals("custom reason", result.reason());
    }

    @Test
    @DisplayName("IntegrationProbeResult.of(null, reason) 必須拋例外（不吞錯）")
    void probeResult_nullStatus_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> IntegrationProbeResult.of(null, "reason"));
    }
}