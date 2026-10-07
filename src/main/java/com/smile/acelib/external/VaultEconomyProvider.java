package com.smile.acelib.external;

import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;

/**
 * Vault legacy 經濟的純反射包裝（Internal）。
 *
 * <p>零外部 import：Vault marker／方法名稱皆以字串常數表示，{@code Economy} 實例
 * 每次呼叫重新經 {@code ServicesManager} 解析，不跨呼叫快取（停用後下一次呼叫
 * 即回到不可用，無舊引用殘留）。Vault 缺席時本類別仍可安全載入（不觸發任何
 * 外部類別載入），呼叫一律回 {@code UNAVAILABLE}。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；下游以
 * {@link EconomyProvider} 介面取得經濟能力。</p>
 *
 * @since 1.4.0
 */
public final class VaultEconomyProvider implements EconomyProvider {

    /** Vault legacy 經濟介面的完整名稱（只用於反射字串，不做 import）。 */
    static final String ECONOMY_FQCN = "net.milkbowl.vault.economy.Economy";

    private final Supplier<Object> economyResolver;

    /**
     * 建構子（production 用：每次呼叫經 Bukkit {@code ServicesManager}
     * 重新解析 {@code Economy} 註冊）。
     */
    public VaultEconomyProvider() {
        this(VaultEconomyServices::resolve);
    }

    /**
     * 完整建構子（package-private，供測試注入解析函式）。
     *
     * @param economyResolver 每次呼叫解析 {@code Economy} 實例；缺席時回 null；
     *     不可為 null
     */
    VaultEconomyProvider(Supplier<Object> economyResolver) {
        this.economyResolver = Objects.requireNonNull(economyResolver, "economyResolver");
    }

    @Override
    public EconomyResult getBalance(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        Object economy = economyResolver.get();
        if (economy == null) {
            return unavailable();
        }
        return callGetBalance(economy, player);
    }

    @Override
    public EconomyResult withdraw(OfflinePlayer player, double amount) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        requireValidAmount(amount);
        Object economy = economyResolver.get();
        if (economy == null) {
            return unavailable();
        }
        return callWithdraw(economy, player, amount);
    }

    @Override
    public EconomyResult deposit(OfflinePlayer player, double amount) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        requireValidAmount(amount);
        Object economy = economyResolver.get();
        if (economy == null) {
            return unavailable();
        }
        return callDeposit(economy, player, amount);
    }

    private static EconomyResult unavailable() {
        return EconomyResult.failure(ExternalResultState.UNAVAILABLE,
            ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
            "vault economy is not available");
    }

    private static void requireValidAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException(
                "amount must be a finite non-negative number, got: " + amount);
        }
    }

    // ----- 反射呼叫 -----

    /**
     * 反射呼叫 {@code getBalance(OfflinePlayer)}。
     *
     * @param economy Vault {@code Economy} 實例
     * @param player 查詢對象
     * @return 成功攜帶餘額；呼叫失敗攜帶 {@code ACELIB-EXT-008}
     */
    private static EconomyResult callGetBalance(Object economy, OfflinePlayer player) {
        try {
            Object value = VaultEconomyReflection.invoke(economy, "getBalance",
                new Class<?>[]{org.bukkit.OfflinePlayer.class}, player);
            return EconomyResult.success(((Number) value).doubleValue(),
                "vault balance lookup succeeded");
        } catch (Exception e) {
            return failed("vault balance lookup failed: " + concise(e));
        }
    }

    /**
     * 解析 Vault {@code EconomyResponse}：{@code transactionSuccess()==false}
     * 為明確失敗（不得視為成功），錯誤訊息取自 {@code getErrorMessage()}；
     * 成功時餘額取自 {@code balance} 欄位。
     */
    private static EconomyResult callWithdraw(Object economy, OfflinePlayer player,
            double amount) {
        try {
            Object response = VaultEconomyReflection.invoke(economy, "withdrawPlayer",
                new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class},
                player, amount);
            return parseResponse(economy, player, response, "withdraw");
        } catch (Exception e) {
            return failed("vault withdraw failed: " + concise(e));
        }
    }

    private static EconomyResult callDeposit(Object economy, OfflinePlayer player,
            double amount) {
        try {
            Object response = VaultEconomyReflection.invoke(economy, "depositPlayer",
                new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class},
                player, amount);
            return parseResponse(economy, player, response, "deposit");
        } catch (Exception e) {
            return failed("vault deposit failed: " + concise(e));
        }
    }

    /**
     * 解析 Vault {@code EconomyResponse}：{@code transactionSuccess()==false}
     * 為明確失敗（不得視為成功），錯誤訊息取自 {@code getErrorMessage()}；
     * 成功時餘額取自 {@code balance} 欄位。
     *
     * <p>金流語意：{@code transactionSuccess()==true} 之後讀 {@code balance}
     * 失敗，不代表交易失敗（錢可能已移動）。此時改以一次 {@code getBalance}
     * 回補餘額並回成功；回補亦失敗才回 {@code FAILED}，且訊息明示交易可能已完成、
     * 呼叫端請勿盲目重試（重試可能造成重複扣款）。</p>
     */
    private static EconomyResult parseResponse(Object economy, OfflinePlayer player,
            Object response, String operation) {
        try {
            Object ok = VaultEconomyReflection.invoke(response, "transactionSuccess",
                new Class<?>[0]);
            if (!Boolean.TRUE.equals(ok)) {
                Object message = VaultEconomyReflection.invoke(response,
                    "getErrorMessage", new Class<?>[0]);
                return failed("vault " + operation + " was rejected by the provider: "
                    + message);
            }
            try {
                double balance =
                    VaultEconomyReflection.readDoubleField(response, "balance");
                return EconomyResult.success(balance, "vault " + operation + " succeeded");
            } catch (Exception balanceError) {
                return fallbackBalance(economy, player, operation, balanceError);
            }
        } catch (Exception e) {
            return failed("vault " + operation + " response parsing failed: "
                + concise(e));
        }
    }

    /**
     * 交易成功但回應餘額讀不到時，以一次 {@code getBalance} 回補。
     *
     * @param economy Vault {@code Economy} 實例
     * @param player 查詢對象
     * @param operation 操作名（診斷用）
     * @param balanceError 讀 {@code balance} 欄位的失敗原因（診斷用）
     * @return 回補成功為 SUCCESS；回補亦失敗為 FAILED（明示勿盲目重試）
     */
    private static EconomyResult fallbackBalance(Object economy, OfflinePlayer player,
            String operation, Exception balanceError) {
        try {
            Object value = VaultEconomyReflection.invoke(economy, "getBalance",
                new Class<?>[]{org.bukkit.OfflinePlayer.class}, player);
            double balance = ((Number) value).doubleValue();
            return EconomyResult.success(balance, "vault " + operation
                + " succeeded (balance re-read after response parsing failed: "
                + concise(balanceError) + ")");
        } catch (Exception retryError) {
            return failed("vault " + operation
                + " response balance unreadable and re-read failed ("
                + concise(balanceError) + " / " + concise(retryError)
                + ")；交易可能已完成，請勿盲目重試");
        }
    }

    private static EconomyResult failed(String detail) {
        return EconomyResult.failure(ExternalResultState.FAILED,
            ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED, detail);
    }

    private static String concise(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    }
}
