package com.smile.acelib.gui;

import java.util.Objects;

/**
 * GUI 操作結果的對外型別（Supported API）。
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
 * @since 1.0.0
 */
public final class GuiResult {

    private final GuiState state;
    private final String errorCode;
    private final String detail;
    private final GuiSession session;
    private final GuiConfirmation confirmation;
    private final GuiAsyncRequest asyncRequest;

    private GuiResult(GuiState state, String errorCode, String detail,
                      GuiSession session, GuiConfirmation confirmation,
                      GuiAsyncRequest asyncRequest) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = detail == null ? "" : detail;
        this.session = session;
        this.confirmation = confirmation;
        this.asyncRequest = asyncRequest;
        if ((state == GuiState.SUCCESS || state == GuiState.ACCEPTED
                || state == GuiState.ALLOWED) && session == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + state
                    + " state 必須攜帶 session");
        }
        if (state != GuiState.SUCCESS && state != GuiState.ACCEPTED
                && state != GuiState.ALLOWED && errorCode == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + state
                    + " state 必須攜帶 errorCode");
        }
        this.errorCode = errorCode;
    }

    /**
     * 建立「派送已接受」結果（非同步更新專用）。
     *
     * <p>對應 {@link GuiState#ACCEPTED}：player context executor 已接受派送
     * （enqueue 成功），但 renderer 尚未執行；實際完成結果需待執行時的重新驗證
     * 決定。呼叫端不得將此狀態視為 renderer 已完成。</p>
     *
     * @param session 派送時通過前置驗證的 session；不可為 null
     * @param detail  人類可讀訊息；可為 null（normalize 為空字串）
     * @return 不可為 null 的 {@link GuiResult}
     */
    public static GuiResult accepted(GuiSession session, String detail) {
        return new GuiResult(GuiState.ACCEPTED, null, detail,
            Objects.requireNonNull(session, "session"), null, null);
    }

    /**
     * 建立成功結果（攜帶 session）。
     */
    public static GuiResult success(GuiSession session) {
        return new GuiResult(GuiState.SUCCESS, null, "", session, null, null);
    }

    /**
     * 建立成功結果（攜帶 session 與詳細訊息）。
     */
    public static GuiResult success(GuiSession session, String detail) {
        return new GuiResult(GuiState.SUCCESS, null, detail, session, null, null);
    }

    /**
     * 建立成功結果（攜帶 session 與 confirmation；用於 createConfirmation）。
     */
    public static GuiResult success(GuiSession session, GuiConfirmation confirmation) {
        return new GuiResult(GuiState.SUCCESS, null, "", session, confirmation, null);
    }

    /**
     * 建立成功結果（攜帶 session 與 async request；用於 beginAsyncUpdate）。
     */
    public static GuiResult success(GuiSession session, GuiAsyncRequest asyncRequest) {
        return new GuiResult(GuiState.SUCCESS, null, "", session, null, asyncRequest);
    }

    /**
     * 建立驗證通過結果（攜帶 session）。用於 validateClick / validateDrag
     * 等「允許實際業務邏輯繼續」的情境。
     */
    public static GuiResult allowed(GuiSession session) {
        return new GuiResult(GuiState.ALLOWED, null, "", session, null, null);
    }

    /**
     * 建立被拒絕結果。對應於輸入合法但操作不被允許（例：SESSION_EXISTS、
     * GENERATION_MISMATCH、SLOT_PROTECTED 等）。
     */
    public static GuiResult rejected(String errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new GuiResult(GuiState.REJECTED, errorCode, detail, null, null, null);
    }

    /**
     * 建立內部失敗結果。對應於內部拋例外 / 平台不一致等不可恢復路徑。
     */
    public static GuiResult failed(String errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new GuiResult(GuiState.FAILED, errorCode, detail, null, null, null);
    }

    /**
     * 建立閉合結果（shutdown / reload 觸發的清理）。
     */
    public static GuiResult closed(GuiSession session) {
        return new GuiResult(GuiState.CLOSED, null, "", session, null, null);
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

    /**
     * 確認 action 合約（僅 {@code createConfirmation} 的 SUCCESS 結果有意義）。
     */
    public GuiConfirmation confirmation() {
        return confirmation;
    }

    /**
     * 非同步更新請求合約（僅 {@code beginAsyncUpdate} 的 SUCCESS 結果有意義）。
     */
    public GuiAsyncRequest asyncRequest() {
        return asyncRequest;
    }

    public boolean isSuccess() {
        return state == GuiState.SUCCESS;
    }

    /**
     * 是否為「派送已接受但尚未完成」狀態（非同步更新 enqueue 成功，
     * renderer 尚未執行）。呼叫端不得將其視為 renderer 已完成。
     */
    public boolean isAccepted() {
        return state == GuiState.ACCEPTED;
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
