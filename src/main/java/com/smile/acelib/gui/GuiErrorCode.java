package com.smile.acelib.gui;

/**
 * 集中錯誤代碼常數（Plan §十六 Phase 11 共同契約）。
 *
 * <p>對應 {@code ACELIB-GUI-<CODE>} 格式分類代碼。
 * 所有對外拒絕或失敗的 operation result 必須攜帶其中之一。</p>
 *
 * <h2>錯誤代碼索引</h2>
 * <ul>
 *   <li>{@link #NOT_READY} — 服務尚未啟用（uninitialized / bind 前）</li>
 *   <li>{@link #SHUTDOWN} — 服務已停用（onDisable / reload 失敗）</li>
 *   <li>{@link #INVALID_INPUT} — 輸入為 null 或語意不合法</li>
 *   <li>{@link #SESSION_NOT_FOUND} — 該玩家目前沒有 active session</li>
 *   <li>{@link #SESSION_EXISTS} — 該玩家已開啟 GUI，重複呼叫 openInventory 被拒絕</li>
 *   <li>{@link #SLOT_PROTECTED} — 玩家嘗試操作受保護 slot</li>
 *   <li>{@link #GENERATION_MISMATCH} — 傳入的 generation 與持有 session 不符</li>
 *   <li>{@link #OPERATION_FAILED} — 通用 operation 失敗（內部執行拋例外）</li>
 *   <li>{@link #SCHEDULER_REJECTED} — player context executor 拒絕派送（SafeScheduler
 *       回傳 cancelled no-op task，例如 scheduler disabled、player offline、平台
 *       不支援；對應 Folia/Paper 安全入口語意）</li>
 * </ul>
 *
 * <p>設計原則與 {@code WorldErrorCode} 對齊：</p>
 * <ul>
 *   <li>本類別只暴露常數字串，不含 enum 語意（避免強制 caller switch 隨版本擴張）</li>
 *   <li>常數為 {@code public static final String}（caller 可直接讀取）</li>
 *   <li>常數格式固定為 {@code ACELIB-GUI-<CODE>}</li>
 * </ul>
 *
 * @see GuiResult
 * @since Phase 11 (Plan §十六)
 */
public final class GuiErrorCode {

    private GuiErrorCode() {
        // utility class
    }

    /** 001 — 服務尚未啟用（uninitialized / bind 前）。 */
    public static final String NOT_READY = "ACELIB-GUI-001";
    /** 002 — 服務已停用（onDisable / reload 失敗）。 */
    public static final String SHUTDOWN = "ACELIB-GUI-002";
    /** 007 — 輸入為 null 或語意不合法。 */
    public static final String INVALID_INPUT = "ACELIB-GUI-007";
    /** 008 — 該玩家目前沒有 active session。 */
    public static final String SESSION_NOT_FOUND = "ACELIB-GUI-008";
    /** 009 — 該玩家已開啟 GUI，重複呼叫 openInventory 被拒絕。 */
    public static final String SESSION_EXISTS = "ACELIB-GUI-009";
    /** 010 — 玩家嘗試操作受保護 slot。 */
    public static final String SLOT_PROTECTED = "ACELIB-GUI-010";
    /** 011 — 傳入的 generation 與持有 session 不符。 */
    public static final String GENERATION_MISMATCH = "ACELIB-GUI-011";
    /** 012 — 通用 operation 失敗（內部執行拋例外）。 */
    public static final String OPERATION_FAILED = "ACELIB-GUI-012";
    /** 013 — player context executor 拒絕派送（SafeScheduler 回傳 cancelled no-op task），
     *  例如 scheduler disabled、player offline、平台不支援；對應 Folia/Paper
     *  安全入口語意。 */
    public static final String SCHEDULER_REJECTED = "ACELIB-GUI-013";
}
