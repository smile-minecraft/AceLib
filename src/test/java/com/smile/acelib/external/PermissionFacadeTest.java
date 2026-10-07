package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 權限門面測試：群組／情境查詢的正常、無效輸入、邊界與失敗映射。
 */
@DisplayName("權限門面")
class PermissionFacadeTest {

    private ExternalIntegrationServiceImpl service;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        service = new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        playerId = UUID.randomUUID();
    }

    private static PermissionProvider fixed() {
        return player -> PermissionResult.success("admin",
            Set.of("admin", "vip"), Map.of("world", Set.of("world_nether")),
            "ok");
    }

    @Test
    @DisplayName("正常：回主要群組、所屬群組與情境")
    void groups_success() {
        service.setPermissionProvider(fixed());

        PermissionResult result = service.getPermissionGroups(playerId);
        assertTrue(result.isSuccess());
        assertEquals("admin", result.primaryGroup());
        assertEquals(Set.of("admin", "vip"), result.groups());
        assertEquals(Map.of("world", Set.of("world_nether")), result.contexts());
        assertEquals(null, result.errorCode());
    }

    @Test
    @DisplayName("邊界：無提供者回 UNAVAILABLE，群組為空、主要群組為 null")
    void absentProvider_unavailable() {
        PermissionResult result = service.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.isSuccess());
        assertEquals(null, result.primaryGroup());
        assertTrue(result.groups().isEmpty());
        assertTrue(result.contexts().isEmpty());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("無效：null 識別拋 IllegalArgumentException")
    void nullId_throws() {
        service.setPermissionProvider(fixed());
        assertThrows(IllegalArgumentException.class,
            () -> service.getPermissionGroups(null));
    }

    @Test
    @DisplayName("失敗：查無玩家回 FAILED（群組為空，不得做授權判斷）")
    void unknownUser_failed() {
        service.setPermissionProvider(player -> PermissionResult.failure(
            ExternalResultState.FAILED,
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED,
            "no luckperms user for " + player));

        PermissionResult result = service.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    @DisplayName("失敗：提供者拋例外轉為明確 FAILED（不逃逸）")
    void providerThrows_mapsToFailed() {
        service.setPermissionProvider(player -> {
            throw new IllegalStateException("boom");
        });

        PermissionResult result = service.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED,
            result.errorCode());
    }
}
