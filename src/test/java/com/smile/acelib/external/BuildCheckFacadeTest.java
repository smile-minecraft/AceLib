package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 建造查詢門面測試：允許／拒絕、不可用預設拒絕、無效輸入與失敗映射。
 */
@DisplayName("建造查詢門面")
class BuildCheckFacadeTest {

    private ExternalIntegrationServiceImpl service;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        service = new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        playerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("邊界：無提供者回 UNAVAILABLE 且不允許（不得默認允許）")
    void absentProvider_deniedByDefault() {
        BuildCheckResult result = service.canBuild(playerId, "world", 1, 64, -3);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.isSuccess());
        assertFalse(result.allowed());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_BUILD_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("正常：提供者允許回成功且允許")
    void providerAllows_success() {
        service.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(true, "ok"));

        BuildCheckResult result = service.canBuild(playerId, "world", 1, 64, -3);
        assertTrue(result.isSuccess());
        assertTrue(result.allowed());
        assertEquals(null, result.errorCode());
    }

    @Test
    @DisplayName("正常：提供者拒絕回成功但不允許")
    void providerDenies_successButNotAllowed() {
        service.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(false, "claimed"));

        BuildCheckResult result = service.canBuild(playerId, "world", 1, 64, -3);
        assertTrue(result.isSuccess());
        assertFalse(result.allowed());
    }

    @Test
    @DisplayName("無效：null 識別／空白世界名拋 IllegalArgumentException")
    void invalid_throws() {
        service.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(true, "ok"));
        assertThrows(IllegalArgumentException.class,
            () -> service.canBuild(null, "world", 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> service.canBuild(playerId, null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> service.canBuild(playerId, "  ", 0, 0, 0));
    }

    @Test
    @DisplayName("失敗：提供者拋例外回 FAILED 且不允許（不逃逸）")
    void providerThrows_failedAndDenied() {
        service.setBuildCheckProvider((player, world, x, y, z) -> {
            throw new IllegalStateException("boom");
        });

        BuildCheckResult result = service.canBuild(playerId, "world", 1, 64, -3);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.allowed());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_BUILD_FAILED,
            result.errorCode());
    }

    @Test
    @DisplayName("替換：清除後恢復為無提供者（不可用且不允許）")
    void cleared_revertsToAbsent() {
        service.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(true, "ok"));
        service.clearBuildCheckProvider();

        BuildCheckResult result = service.canBuild(playerId, "world", 0, 64, 0);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.allowed());
    }
}
