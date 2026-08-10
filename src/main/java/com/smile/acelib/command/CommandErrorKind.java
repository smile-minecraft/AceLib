package com.smile.acelib.command;

/**
 * 指令錯誤分類列舉 — Phase 6 指令系統封裝。
 *
 * <p>每一種 kind 對應一個固定的 {@code ACELIB-CMD-NNN} 錯誤代碼，
 * 方便 dispatcher 在內部標準化處理、測試與日誌紀錄。</p>
 *
 * <h2>錯誤代碼對照表</h2>
 * <table border="1">
 *   <caption>錯誤分類 → 錯誤代碼</caption>
 *   <tr><th>Kind</th><th>Code</th><th>語意</th></tr>
 *   <tr><td>{@link #MISSING_ARGUMENTS}</td><td>{@code ACELIB-CMD-001}</td><td>缺少必要參數</td></tr>
 *   <tr><td>{@link #UNKNOWN_SUBCOMMAND}</td><td>{@code ACELIB-CMD-002}</td><td>未知的子指令</td></tr>
 *   <tr><td>{@link #NO_PERMISSION}</td><td>{@code ACELIB-CMD-003}</td><td>沒有權限執行</td></tr>
 *   <tr><td>{@link #CONSOLE_NOT_ALLOWED}</td><td>{@code ACELIB-CMD-004}</td><td>此指令僅限玩家</td></tr>
 *   <tr><td>{@link #PLAYER_NOT_ALLOWED}</td><td>{@code ACELIB-CMD-005}</td><td>此指令僅限 console</td></tr>
 *   <tr><td>{@link #COOLDOWN_ACTIVE}</td><td>{@code ACELIB-CMD-006}</td><td>冷卻中（防止重複觸發）</td></tr>
 *   <tr><td>{@link #PLAYER_OFFLINE}</td><td>{@code ACELIB-CMD-007}</td><td>玩家已離線 / 失效</td></tr>
 *   <tr><td>{@link #ASYNC_EXECUTION_FAILED}</td><td>{@code ACELIB-CMD-008}</td><td>非同步指令流程失敗</td></tr>
 *   <tr><td>{@link #REGISTRY_DISABLED}</td><td>{@code ACELIB-CMD-009}</td><td>registry 已停用（plugin disable）</td></tr>
 *   <tr><td>{@link #CUSTOM}</td><td>{@code ACELIB-CMD-010}</td><td>caller 自訂錯誤代碼（由 caller 給 code）</td></tr>
 *   <tr><td>{@link #REPLY_BACKEND_UNAVAILABLE}</td><td>{@code ACELIB-CMD-011}</td><td>玩家回覆 backend 不可用（非 AceLib owner 無法 region-safe 派送）</td></tr>
 * </table>
 *
 * @see CommandException
 * @since Phase 6 — 指令系統封裝
 */
public enum CommandErrorKind {

    /** 缺少必要參數（minArgs 未滿足）。 */
    MISSING_ARGUMENTS,

    /** 未知的子指令（無對應 SubCommandSpec）。 */
    UNKNOWN_SUBCOMMAND,

    /** 沒有權限執行。 */
    NO_PERMISSION,

    /** 此指令僅限玩家（console 觸發時拒絕）。 */
    CONSOLE_NOT_ALLOWED,

    /** 此指令僅限 console（玩家觸發時拒絕）。 */
    PLAYER_NOT_ALLOWED,

    /** 冷卻中（防止重複觸發）。 */
    COOLDOWN_ACTIVE,

    /** 玩家已離線 / 失效。 */
    PLAYER_OFFLINE,

    /** 非同步指令流程失敗。 */
    ASYNC_EXECUTION_FAILED,

    /** registry 已停用（plugin disable 後）。 */
    REGISTRY_DISABLED,

    /** caller 自訂錯誤（透過 {@link CommandException#custom(String, String)}）。 */
    CUSTOM,

    /**
     * 玩家回覆 backend 不可用。
     *
     * <p>觸發情境：{@link BukkitReplySink} 的 owner plugin 不是
     * {@code AceLibPlugin}，因而無法取得 canonical platform / capability 快取，
     * 也無法透過 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion}
     * 派送玩家回覆。對應錯誤代碼 {@code ACELIB-CMD-011}。</p>
     *
     * <p>對應行為：{@link BukkitReplySink.SafeExecutorBackend} 必須
     * <strong>拋例外</strong>而非 inline 派送；sink 必須 catch 例外、輸出
     * warning 攜帶此 code，並保證不直接呼叫 {@code Player#sendMessage}。</p>
     */
    REPLY_BACKEND_UNAVAILABLE;

    /**
     * 將本 kind 映射為對應的標準錯誤代碼。
     *
     * @return {@code ACELIB-CMD-NNN} 格式字串
     */
    public String defaultCode() {
        return switch (this) {
            case MISSING_ARGUMENTS -> "ACELIB-CMD-001";
            case UNKNOWN_SUBCOMMAND -> "ACELIB-CMD-002";
            case NO_PERMISSION -> "ACELIB-CMD-003";
            case CONSOLE_NOT_ALLOWED -> "ACELIB-CMD-004";
            case PLAYER_NOT_ALLOWED -> "ACELIB-CMD-005";
            case COOLDOWN_ACTIVE -> "ACELIB-CMD-006";
            case PLAYER_OFFLINE -> "ACELIB-CMD-007";
            case ASYNC_EXECUTION_FAILED -> "ACELIB-CMD-008";
            case REGISTRY_DISABLED -> "ACELIB-CMD-009";
            case CUSTOM -> "ACELIB-CMD-010";
            case REPLY_BACKEND_UNAVAILABLE -> "ACELIB-CMD-011";
        };
    }
}