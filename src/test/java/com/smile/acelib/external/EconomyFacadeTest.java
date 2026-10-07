package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 經濟門面測試：餘額／扣款／入帳的正常、無效輸入、邊界與失敗映射。
 *
 * <p>以可編排的假 {@link EconomyProvider} 隔離門面邏輯；Vault 反射細節由
 * {@code VaultEconomyProviderTest} 覆蓋。</p>
 */
@DisplayName("經濟門面")
class EconomyFacadeTest {

    private ExternalIntegrationServiceImpl service;
    private OfflinePlayer player;

    @BeforeEach
    void setUp() {
        service = new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        player = Mockito.mock(OfflinePlayer.class);
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
    @DisplayName("正常：餘額查詢回成功與餘額")
    void getBalance_success() {
        service.setEconomyProvider(fixed(120.5));

        EconomyResult result = service.getBalance(player);
        assertTrue(result.isSuccess());
        assertEquals(120.5, result.balance());
        assertEquals(null, result.errorCode());
    }

    @Test
    @DisplayName("正常：扣款回扣款後餘額")
    void withdraw_success() {
        service.setEconomyProvider(fixed(120.5));

        EconomyResult result = service.withdraw(player, 20.5);
        assertTrue(result.isSuccess());
        assertEquals(100.0, result.balance(), 1e-9);
    }

    @Test
    @DisplayName("正常：入帳回入帳後餘額")
    void deposit_success() {
        service.setEconomyProvider(fixed(100.0));

        EconomyResult result = service.deposit(player, 25.0);
        assertTrue(result.isSuccess());
        assertEquals(125.0, result.balance(), 1e-9);
    }

    @Test
    @DisplayName("邊界：無提供者時三操作皆 UNAVAILABLE 且餘額為 NaN")
    void absentProvider_allUnavailable() {
        EconomyResult balance = service.getBalance(player);
        EconomyResult withdraw = service.withdraw(player, 10.0);
        EconomyResult deposit = service.deposit(player, 10.0);
        for (EconomyResult result : new EconomyResult[]{balance, withdraw, deposit}) {
            assertEquals(ExternalResultState.UNAVAILABLE, result.state());
            assertFalse(result.isSuccess());
            assertTrue(Double.isNaN(result.balance()));
            assertEquals(
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
                result.errorCode());
        }
    }

    @Test
    @DisplayName("無效：null 玩家拋 IllegalArgumentException")
    void nullPlayer_throws() {
        service.setEconomyProvider(fixed(10.0));
        assertThrows(IllegalArgumentException.class, () -> service.getBalance(null));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> service.deposit(null, 1.0));
    }

    @Test
    @DisplayName("無效：負金額／NaN／無限大拋 IllegalArgumentException")
    void invalidAmount_throws() {
        service.setEconomyProvider(fixed(10.0));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw(player, -1.0));
        assertThrows(IllegalArgumentException.class,
            () -> service.withdraw(player, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> service.deposit(player, Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("失敗：提供者回失敗原樣傳遞（不得視為成功）")
    void providerFailure_propagated() {
        service.setEconomyProvider(new EconomyProvider() {
            @Override
            public EconomyResult getBalance(OfflinePlayer p) {
                return EconomyResult.failure(ExternalResultState.FAILED,
                    ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED, "nope");
            }

            @Override
            public EconomyResult withdraw(OfflinePlayer p, double amount) {
                return getBalance(p);
            }

            @Override
            public EconomyResult deposit(OfflinePlayer p, double amount) {
                return getBalance(p);
            }
        });

        EconomyResult result = service.withdraw(player, 5.0);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("失敗：提供者回 null 轉為明確 FAILED（不逃逸、不默認成功）")
    void providerNull_mapsToFailed() {
        service.setEconomyProvider(new EconomyProvider() {
            @Override
            public EconomyResult getBalance(OfflinePlayer p) {
                return null;
            }

            @Override
            public EconomyResult withdraw(OfflinePlayer p, double amount) {
                return null;
            }

            @Override
            public EconomyResult deposit(OfflinePlayer p, double amount) {
                return null;
            }
        });

        EconomyResult result = service.getBalance(player);
        assertEquals(ExternalResultState.FAILED, result.state());
    }
}
