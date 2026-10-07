package com.smile.acelib.external;

import java.util.Objects;

/**
 * 經濟操作結果（餘額查詢／扣款／入帳）。
 *
 * <p>沿用世界操作結果慣例：狀態＋錯誤代碼＋人類可讀訊息；成功時不攜帶錯誤代碼。
 * 成功時 {@link #balance()} 為操作後餘額；非成功時餘額一律為 {@code NaN}
 *（沒有可信數字，不得以 0 解讀為「沒錢」）。</p>
 *
 * <p>AceLib 只包裝外部經濟提供者，不自製經濟系統、不做領域授權判斷、
 * 不做扣款去重或持久操作紀錄；重複呼叫的後果由提供者語意決定。</p>
 *
 * @see ExternalResultState
 * @see EconomyProvider
 * @since 1.4.0
 */
public final class EconomyResult {

    private final ExternalResultState state;
    private final String errorCode;
    private final String detail;
    private final double balance;

    private EconomyResult(ExternalResultState state, String errorCode, String detail,
            double balance) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (state == ExternalResultState.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException(
                "SUCCESS state must not carry an error code, got: " + errorCode);
        }
        if (state != ExternalResultState.SUCCESS && errorCode == null) {
            throw new IllegalArgumentException(
                "non-SUCCESS state must carry an error code");
        }
        if (state == ExternalResultState.SUCCESS && Double.isNaN(balance)) {
            throw new IllegalArgumentException(
                "SUCCESS state must carry a balance");
        }
        if (state != ExternalResultState.SUCCESS && !Double.isNaN(balance)) {
            throw new IllegalArgumentException(
                "non-SUCCESS state must not carry a balance (use NaN)");
        }
        this.errorCode = errorCode;
        this.balance = balance;
    }

    /**
     * 建立成功結果。
     *
     * @param balance 操作後餘額；不可為 NaN
     * @param detail 人類可讀訊息；不可為 null
     * @return 成功結果；錯誤代碼為 null
     */
    public static EconomyResult success(double balance, String detail) {
        return new EconomyResult(ExternalResultState.SUCCESS, null, detail, balance);
    }

    /**
     * 建立非成功結果。
     *
     * @param state 狀態；不可為 null 且不可為 SUCCESS
     * @param errorCode 錯誤代碼（{@code ACELIB-EXT-*}）；不可為 null
     * @param detail 人類可讀訊息；不可為 null
     * @return 非成功結果；餘額為 NaN
     */
    public static EconomyResult failure(ExternalResultState state, String errorCode,
            String detail) {
        if (state == ExternalResultState.SUCCESS) {
            throw new IllegalArgumentException(
                "failure() must not use SUCCESS state");
        }
        return new EconomyResult(state, errorCode, detail, Double.NaN);
    }

    /** @return 結果狀態；永不為 null */
    public ExternalResultState state() {
        return state;
    }

    /** @return 錯誤代碼；成功時為 null */
    public String errorCode() {
        return errorCode;
    }

    /** @return 人類可讀訊息；永不為 null */
    public String detail() {
        return detail;
    }

    /**
     * 操作後餘額；非成功時為 NaN。
     *
     * @return 餘額或 NaN
     */
    public double balance() {
        return balance;
    }

    /** @return 是否成功 */
    public boolean isSuccess() {
        return state == ExternalResultState.SUCCESS;
    }
}
