package com.smile.acelib.gui;

import java.util.Objects;

/**
 * GUI 操作結果的對外型別（Plan §十六 Phase 11 共同契約）。
 *
 * <p>所有對外 {@link GuiService} 操作皆回傳 {@link GuiResult}。
 * 結果內含狀態（{@link GuiState}）、錯誤代碼（{@link GuiErrorCode} *）、
 * 人類可讀訊息以及選擇性附帶的 {@link GuiSession}。</p>
 *
 * <h2>設計</h2>
 * <ul>
 *   <li>不可變 record — 執行緒安全</li>
 *   <li>錯誤代碼為 {@code String} 常數而非 enum，便於版本演進並與
 *       {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 對齊</li>
 *   <li>{@link GuiState#SUCCESS} 與 {@link GuiState#ALLOWED} 必須攜帶對應 session；
 *       其他狀態 session 可為 null</li>
 *   <li>detail 訊息不可為 null，可為空字串</li>
 * </ul>
 *
 * @see GuiService
 * @since Phase 11 (Plan §十六)
 */
public final class GuiResult {

    private final GuiState state;
    private final String errorCode;
    private final String detail;
    private final GuiSession session;

    private GuiResult(GuiState state, String errorCode, String detail,
                      GuiSession session) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = detail == null ? "" : detail;
        this.session = session;
        if ((state == GuiState.SUCCESS || state == GuiState.ALLOWED)
                && session == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + state
                    + " state 必須攜帶 session");
        }
        if (state != GuiState.SUCCESS && state != GuiState.ALLOWED && errorCode == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + state
                    + " state 必須攜帶 errorCode");
        }
        this.errorCode = errorCode;
    }

    /**
     * 建立成功結果（攜帶 session）。
     */
    public static GuiResult success(GuiSession session) {
        return new GuiResult(GuiState.SUCCESS, null, "", session);
    }

    /**
     * 建立成功結果（攜帶 session 與詳細訊息）。
     */
    public static GuiResult success(GuiSession session, String detail) {
        return new GuiResult(GuiState.SUCCESS, null, detail, session);
    }

    /**
     * 建立驗證通過結果（攜帶 session）。用於 validateClick / validateDrag
     * 等「允許實際業務邏輯繼續」的情境。
     */
    public static GuiResult allowed(GuiSession session) {
        return new GuiResult(GuiState.ALLOWED, null, "", session);
    }

    /**
     * 建立被拒絕結果。對應於輸入合法但操作不被允許（例：SESSION_EXISTS、
     * GENERATION_MISMATCH、SLOT_PROTECTED 等）。
     */
    public static GuiResult rejected(String errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new GuiResult(GuiState.REJECTED, errorCode, detail, null);
    }

    /**
     * 建立內部失敗結果。對應於內部拋例外 / 平台不一致等不可恢復路徑。
     */
    public static GuiResult failed(String errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new GuiResult(GuiState.FAILED, errorCode, detail, null);
    }

    /**
     * 建立閉合結果（shutdown / reload 觸發的清理）。
     */
    public static GuiResult closed(GuiSession session) {
        return new GuiResult(GuiState.CLOSED, null, "", session);
    }

    public GuiState state() {
        return state;
    }

    public String errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    public GuiSession session() {
        return session;
    }

    public boolean isSuccess() {
        return state == GuiState.SUCCESS;
    }

    public boolean isAllowed() {
        return state == GuiState.ALLOWED;
    }

    public boolean isRejected() {
        return state == GuiState.REJECTED;
    }

    public boolean isFailed() {
        return state == GuiState.FAILED;
    }

    @Override
    public String toString() {
        return "GuiResult{state=" + state
            + ", errorCode=" + errorCode
            + ", detail=" + detail
            + ", session=" + session
            + "}";
    }
}
