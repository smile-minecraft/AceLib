package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 未啟用／已停用 facade 的業務方法測試：任何業務呼叫皆回 {@code UNAVAILABLE}，
 * {@code set*} 拒絕（fail-closed），{@code clear*} 為無害 no-op。
 */
@DisplayName("業務門面 — 未啟用／已停用")
class BusinessUnavailableFacadeTest {

    private static OfflinePlayer player() {
        return Mockito.mock(OfflinePlayer.class);
    }

    @Test
    @DisplayName("NOT_READY：四類業務皆 UNAVAILABLE 且不成功")
    void notReady_allUnavailable() {
        ExternalIntegrationService svc =
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY);
        UUID id = UUID.randomUUID();

        EconomyResult balance = svc.getBalance(player());
        assertEquals(ExternalResultState.UNAVAILABLE, balance.state());
        assertFalse(balance.isSuccess());

        PermissionResult groups = svc.getPermissionGroups(id);
        assertEquals(ExternalResultState.UNAVAILABLE, groups.state());

        ExternalOperationResult registered =
            svc.registerPlaceholder("x", (p, params) -> "v");
        assertEquals(ExternalResultState.UNAVAILABLE, registered.state());

        BuildCheckResult build = svc.canBuild(id, "world", 0, 64, 0);
        assertEquals(ExternalResultState.UNAVAILABLE, build.state());
        assertFalse(build.allowed());
    }

    @Test
    @DisplayName("SHUTDOWN：建造查詢不可用且不允許")
    void shutdown_buildDenied() {
        ExternalIntegrationService svc =
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.SHUTDOWN);

        BuildCheckResult result = svc.canBuild(UUID.randomUUID(), "world", 0, 64, 0);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.allowed());
    }

    @Test
    @DisplayName("未啟用 facade 的 set* 拒絕且不吞 null 檢查")
    void notReady_setRejected() {
        ExternalIntegrationService svc =
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY);

        assertThrows(IllegalArgumentException.class,
            () -> svc.setEconomyProvider(null));
        assertThrows(IllegalStateException.class,
            () -> svc.setEconomyProvider(new VaultEconomyProvider(() -> null)));
        assertThrows(IllegalStateException.class,
            () -> svc.setPermissionProvider(
                new LuckPermsPermissionProvider(() -> null)));
        assertThrows(IllegalStateException.class,
            () -> svc.setPlaceholderProvider(new PlaceholderApiPlaceholderProvider()));
        assertThrows(IllegalStateException.class,
            () -> svc.setBuildCheckProvider(
                (player, world, x, y, z) -> BuildCheckResult.success(true, "ok")));
    }

    @Test
    @DisplayName("未啟用 facade 的 clear* 為無害 no-op")
    void notReady_clearNoop() {
        ExternalIntegrationService svc =
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY);

        svc.clearEconomyProvider();
        svc.clearPermissionProvider();
        svc.clearPlaceholderProvider();
        svc.clearBuildCheckProvider();
    }

    @Test
    @DisplayName("未啟用 facade 的業務方法仍做輸入檢查（不吞錯）")
    void notReady_validatesInputs() {
        ExternalIntegrationService svc =
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY);

        assertThrows(IllegalArgumentException.class, () -> svc.getBalance(null));
        assertThrows(IllegalArgumentException.class,
            () -> svc.withdraw(player(), -1.0));
        assertThrows(IllegalArgumentException.class,
            () -> svc.getPermissionGroups(null));
        assertThrows(IllegalArgumentException.class,
            () -> svc.registerPlaceholder(" ", (p, params) -> "v"));
        assertThrows(IllegalArgumentException.class,
            () -> svc.canBuild(UUID.randomUUID(), " ", 0, 0, 0));
    }
}
