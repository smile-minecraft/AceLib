package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 提供者替換測試：覆寫優先、清除恢復內建、停用丟棄引用。
 */
@DisplayName("提供者替換")
class ProviderReplacementTest {

    private static OfflinePlayer player() {
        return Mockito.mock(OfflinePlayer.class);
    }

    private static EconomyProvider fixed(double balance) {
        return new EconomyProvider() {
            @Override
            public EconomyResult getBalance(OfflinePlayer p) {
                return EconomyResult.success(balance, "ok");
            }

            @Override
            public EconomyResult withdraw(OfflinePlayer p, double amount) {
                return EconomyResult.success(balance - amount, "ok");
            }

            @Override
            public EconomyResult deposit(OfflinePlayer p, double amount) {
                return EconomyResult.success(balance + amount, "ok");
            }
        };
    }

    @Test
    @DisplayName("覆寫優先於內建")
    void overrideWinsOverBuiltin() {
        ExternalIntegrationServiceImpl service = new ExternalIntegrationServiceImpl(
            new IntegrationRegistry(), fixed(10.0), null, null, null);
        service.setEconomyProvider(fixed(99.0));

        assertEquals(99.0, service.getBalance(player()).balance());
    }

    @Test
    @DisplayName("清除覆寫後恢復內建")
    void clearedRestoresBuiltin() {
        ExternalIntegrationServiceImpl service = new ExternalIntegrationServiceImpl(
            new IntegrationRegistry(), fixed(10.0), null, null, null);
        service.setEconomyProvider(fixed(99.0));
        service.clearEconomyProvider();

        assertEquals(10.0, service.getBalance(player()).balance());
    }

    @Test
    @DisplayName("停用後覆寫與內建引用皆丟棄（查詢不可用）")
    void shutdown_dropsAllReferences() {
        ExternalIntegrationServiceImpl service = new ExternalIntegrationServiceImpl(
            new IntegrationRegistry(), fixed(10.0), null, null, null);
        service.setEconomyProvider(fixed(99.0));
        service.shutdown();

        EconomyResult result = service.getBalance(player());
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("停用後 set* 拋 IllegalStateException（fail-closed）")
    void setAfterShutdown_throws() {
        ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        service.shutdown();

        assertThrows(IllegalStateException.class,
            () -> service.setEconomyProvider(fixed(1.0)));
        assertThrows(IllegalStateException.class,
            () -> service.setPermissionProvider(player -> PermissionResult.failure(
                ExternalResultState.FAILED, "x", "y")));
        assertThrows(IllegalStateException.class,
            () -> service.setPlaceholderProvider(new PlaceholderApiPlaceholderProvider()));
        assertThrows(IllegalStateException.class,
            () -> service.setBuildCheckProvider(
                (p, w, x, y, z) -> BuildCheckResult.success(true, "ok")));
    }

    @Test
    @DisplayName("停用後 clear* 為無害 no-op")
    void clearAfterShutdown_noop() {
        ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        service.shutdown();

        service.clearEconomyProvider();
        service.clearPermissionProvider();
        service.clearPlaceholderProvider();
        service.clearBuildCheckProvider();
    }

    @Test
    @DisplayName("set* 不接受 null（不吞錯）")
    void setNull_throws() {
        ExternalIntegrationServiceImpl service =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());

        assertThrows(IllegalArgumentException.class,
            () -> service.setEconomyProvider(null));
        assertThrows(IllegalArgumentException.class,
            () -> service.setPermissionProvider(null));
        assertThrows(IllegalArgumentException.class,
            () -> service.setPlaceholderProvider(null));
        assertThrows(IllegalArgumentException.class,
            () -> service.setBuildCheckProvider(null));
    }
}
