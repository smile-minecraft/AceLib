package com.smile.acelib.external;

import java.util.Objects;

/**
 * 外部整合操作結果（佔位符註冊／取消註冊等無額外酬載的操作）。
 *
 * <p>沿用世界操作結果慣例：狀態＋錯誤代碼＋人類可讀訊息；成功時不攜帶錯誤代碼。</p>
 *
 * @see ExternalResultState
 * @see PlaceholderProvider
 * @since 1.4.0
 */
public final class ExternalOperationResult {

    private final ExternalResultState state;
    private final String errorCode;
    private final String detail;

    private ExternalOperationResult(ExternalResultState state, String errorCode,
            String detail) {
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
        this.errorCode = errorCode;
    }

    /**
     * 建立成功結果。
     *
     * @param detail 人類可讀訊息；不可為 null
     * @return 成功結果；錯誤代碼為 null
     */
    public static ExternalOperationResult success(String detail) {
        return new ExternalOperationResult(ExternalResultState.SUCCESS, null, detail);
    }

    /**
     * 建立非成功結果。
     *
     * @param state 狀態；不可為 null 且不可為 SUCCESS
     * @param errorCode 錯誤代碼（{@code ACELIB-EXT-*}）；不可為 null
     * @param detail 人類可讀訊息；不可為 null
     * @return 非成功結果
     */
    public static ExternalOperationResult failure(ExternalResultState state,
            String errorCode, String detail) {
        if (state == ExternalResultState.SUCCESS) {
            throw new IllegalArgumentException(
                "failure() must not use SUCCESS state");
        }
        return new ExternalOperationResult(state, errorCode, detail);
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

    /** @return 是否成功 */
    public boolean isSuccess() {
        return state == ExternalResultState.SUCCESS;
    }
}
