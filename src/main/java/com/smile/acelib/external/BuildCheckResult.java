package com.smile.acelib.external;

import java.util.Objects;

/**
 * 建造查詢結果（玩家能否在某位置建造）。
 *
 * <p>沿用世界操作結果慣例：狀態＋錯誤代碼＋人類可讀訊息；成功時不攜帶錯誤代碼。
 * 非成功時 {@link #allowed()} 一律為 {@code false}：提供者缺席或查詢失敗
 * 不得被解讀為「允許建造」，呼叫端必須以拒絕或降級路徑處理。</p>
 *
 * <p>AceLib 只包裝區域保護提供者；本期不內建任何外部區域保護 adapter，
 * 無提供者時一律回不可用。</p>
 *
 * @see ExternalResultState
 * @see BuildCheckProvider
 * @since 1.4.0
 */
public final class BuildCheckResult {

    private final ExternalResultState state;
    private final String errorCode;
    private final String detail;
    private final boolean allowed;

    private BuildCheckResult(ExternalResultState state, String errorCode, String detail,
            boolean allowed) {
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
        if (state != ExternalResultState.SUCCESS && allowed) {
            throw new IllegalArgumentException(
                "non-SUCCESS state must not allow building");
        }
        this.errorCode = errorCode;
        this.allowed = allowed;
    }

    /**
     * 建立成功結果。
     *
     * @param allowed 是否允許建造
     * @param detail 人類可讀訊息；不可為 null
     * @return 成功結果；錯誤代碼為 null
     */
    public static BuildCheckResult success(boolean allowed, String detail) {
        return new BuildCheckResult(ExternalResultState.SUCCESS, null, detail, allowed);
    }

    /**
     * 建立非成功結果（允許旗標一律為 false）。
     *
     * @param state 狀態；不可為 null 且不可為 SUCCESS
     * @param errorCode 錯誤代碼（{@code ACELIB-EXT-*}）；不可為 null
     * @param detail 人類可讀訊息；不可為 null
     * @return 非成功結果
     */
    public static BuildCheckResult failure(ExternalResultState state, String errorCode,
            String detail) {
        if (state == ExternalResultState.SUCCESS) {
            throw new IllegalArgumentException(
                "failure() must not use SUCCESS state");
        }
        return new BuildCheckResult(state, errorCode, detail, false);
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
     * 是否允許建造；非成功時一律為 false。
     *
     * @return 成功且提供者允許時為 true
     */
    public boolean allowed() {
        return allowed;
    }

    /** @return 是否成功 */
    public boolean isSuccess() {
        return state == ExternalResultState.SUCCESS;
    }
}
