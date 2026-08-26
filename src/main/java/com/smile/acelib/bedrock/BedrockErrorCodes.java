package com.smile.acelib.bedrock;

/**
 * 基岩服務錯誤代碼常數（{@code ACELIB-BED-*} 格式）。
 *
 * <p>錯誤代碼格式為 {@code ACELIB-<AREA>-<CODE>}，本類別 AREA=BED。
 * 已註冊於 {@code com.smile.acelib.diagnostics.ErrorCodeRegistry} 與
 * {@code docs/reference/error-codes.md}。</p>
 *
 * @since 1.0.0
 */
public final class BedrockErrorCodes {

    private BedrockErrorCodes() {
        // utility class
    }

    /** 001 — 基岩服務尚未啟用（facade {@code NOT_READY}）。 */
    public static final String ACELIB_BED_SERVICE_NOT_READY = "ACELIB-BED-001";

    /** 002 — 基岩服務已停用（facade {@code SHUTDOWN}）。 */
    public static final String ACELIB_BED_SERVICE_SHUTDOWN = "ACELIB-BED-002";

    /** 003 — 輸入為 null 或語意不合法。 */
    public static final String ACELIB_BED_INVALID_INPUT = "ACELIB-BED-003";
}
