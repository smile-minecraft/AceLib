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
 * Vault 經濟反射包裝測試。
 *
 * <p>以同名方法簽章的假 {@code Economy}／{@code EconomyResponse} 驗證反射呼叫：
 * 成功取值、交易失敗（{@code transactionSuccess=false} 不得視為成功）、
 * 提供者拋例外轉失敗、Vault 缺席回不可用。Vault 型別只以字串常數出現，
 * 本測試不依賴任何 Vault jar。</p>
 */
@DisplayName("Vault 經濟反射包裝")
class VaultEconomyProviderTest {

    /** 假 EconomyResponse（方法／欄位名與 Vault legacy 一致）。 */
    static final class FakeResponse {
        final boolean ok;
        final double balance;
        final String error;

        FakeResponse(boolean ok, double balance, String error) {
            this.ok = ok;
            this.balance = balance;
            this.error = error;
        }

        public boolean transactionSuccess() {
            return ok;
        }

        public String getErrorMessage() {
            return error;
        }
    }

    /** 假成功回應但無 balance 欄位（餘額回補路徑用）。 */
    static final class FakeOkNoBalance {
        public boolean transactionSuccess() {
            return true;
        }

        public String getErrorMessage() {
            return null;
        }
    }

    /** 假 Economy（方法簽章與 Vault legacy 一致）。 */
    static final class FakeEconomy {
        double balance = 100.0;
        boolean failNext;
        boolean throwNext;
        boolean balanceThrows;
        boolean noBalanceField;

        public double getBalance(OfflinePlayer player) {
            if (balanceThrows) {
                throw new IllegalStateException("balance backend down");
            }
            return balance;
        }

        public Object withdrawPlayer(OfflinePlayer player, double amount) {
            if (throwNext) {
                throw new IllegalStateException("boom");
            }
            if (failNext) {
                return new FakeResponse(false, balance, "insufficient funds");
            }
            balance -= amount;
            if (noBalanceField) {
                return new FakeOkNoBalance();
            }
            return new FakeResponse(true, balance, null);
        }

        public FakeResponse depositPlayer(OfflinePlayer player, double amount) {
            if (throwNext) {
                throw new IllegalStateException("boom");
            }
            balance += amount;
            return new FakeResponse(true, balance, null);
        }
    }

    private static OfflinePlayer player() {
        return Mockito.mock(OfflinePlayer.class);
    }

    @Test
    @DisplayName("正常：餘額查詢回成功與餘額")
    void balance_success() {
        FakeEconomy economy = new FakeEconomy();
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.getBalance(player());
        assertTrue(result.isSuccess());
        assertEquals(100.0, result.balance());
    }

    @Test
    @DisplayName("正常：扣款回扣款後餘額")
    void withdraw_success() {
        FakeEconomy economy = new FakeEconomy();
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.withdraw(player(), 30.0);
        assertTrue(result.isSuccess());
        assertEquals(70.0, result.balance(), 1e-9);
    }

    @Test
    @DisplayName("正常：入帳回入帳後餘額")
    void deposit_success() {
        FakeEconomy economy = new FakeEconomy();
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.deposit(player(), 25.0);
        assertTrue(result.isSuccess());
        assertEquals(125.0, result.balance(), 1e-9);
    }

    @Test
    @DisplayName("邊界：Vault 缺席回 UNAVAILABLE（餘額為 NaN）")
    void absent_unavailable() {
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> null);

        EconomyResult result = provider.getBalance(player());
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertTrue(Double.isNaN(result.balance()));
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("金流：交易成功但回應餘額讀不到時，以餘額重讀回補為 SUCCESS")
    void successResponseWithoutBalance_fallsBackToGetBalance() {
        FakeEconomy economy = new FakeEconomy();
        economy.noBalanceField = true;
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.withdraw(player(), 10.0);
        assertTrue(result.isSuccess());
        assertEquals(90.0, result.balance(), 1e-9);
    }

    @Test
    @DisplayName("金流：餘額回補亦失敗時回 FAILED 並明示勿盲目重試")
    void balanceFallbackFails_failedWithNoRetryHint() {
        FakeEconomy economy = new FakeEconomy();
        economy.noBalanceField = true;
        economy.balanceThrows = true;
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.withdraw(player(), 10.0);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
            result.errorCode());
        assertTrue(result.detail().contains("請勿盲目重試"),
            "FAILED 須明示交易可能已完成，實際：" + result.detail());
    }

    @Test
    @DisplayName("失敗：transactionSuccess=false 轉為明確 FAILED（不得視為成功）")
    void transactionFailure_failed() {
        FakeEconomy economy = new FakeEconomy();
        economy.failNext = true;
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.withdraw(player(), 10.0);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.isSuccess());
        assertTrue(result.detail().contains("insufficient funds"));
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
            result.errorCode());
    }

    @Test
    @DisplayName("失敗：提供者拋例外轉為明確 FAILED（不逃逸）")
    void providerThrows_failed() {
        FakeEconomy economy = new FakeEconomy();
        economy.throwNext = true;
        VaultEconomyProvider provider = new VaultEconomyProvider(() -> economy);

        EconomyResult result = provider.deposit(player(), 10.0);
        assertEquals(ExternalResultState.FAILED, result.state());
    }

    @Test
    @DisplayName("無效：null 玩家拋 IllegalArgumentException")
    void nullPlayer_throws() {
        VaultEconomyProvider provider = new VaultEconomyProvider(FakeEconomy::new);
        assertThrows(IllegalArgumentException.class, () -> provider.getBalance(null));
    }

    @Test
    @DisplayName("解析器每次呼叫重新解析（不跨呼叫快取舊實例）")
    void resolver_calledPerCall() {
        FakeEconomy first = new FakeEconomy();
        FakeEconomy second = new FakeEconomy();
        second.balance = 5.0;
        java.util.Iterator<FakeEconomy> it =
            java.util.List.of(first, second, first).iterator();
        VaultEconomyProvider provider = new VaultEconomyProvider(it::next);

        assertEquals(100.0, provider.getBalance(player()).balance());
        assertEquals(5.0, provider.getBalance(player()).balance());
    }
}
