package com.smile.acelib.form;

/**
 * 表單服務錯誤代碼常數（{@code ACELIB-FORM-*} 格式）。
 *
 * <p>錯誤代碼格式為 {@code ACELIB-<AREA>-<CODE>}，本類別 AREA=FORM。
 * 已註冊於 {@code com.smile.acelib.diagnostics.ErrorCodeRegistry} 與
 * {@code docs/reference/error-codes.md}。</p>
 *
 * @since 1.0.0
 */
public final class FormErrorCodes {

    private FormErrorCodes() {
        // utility class
    }

    /** 001 — 表單服務尚未啟用（Floodgate 缺席，綁定為 absent 發送 seam）。 */
    public static final String ACELIB_FORM_SERVICE_NOT_READY = "ACELIB-FORM-001";

    /** 002 — 表單服務已停用（onDisable / reload 失敗後的 shutdown）。 */
    public static final String ACELIB_FORM_SERVICE_SHUTDOWN = "ACELIB-FORM-002";
}
