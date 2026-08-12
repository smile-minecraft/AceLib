package com.smile.acelib.world;

/**
 * 集中錯誤代碼常數（Plan §十九 Phase 10 共同契約）。
 *
 * <p>對應 {@code ACELIB-WORLD-<CODE>} 格式分類代碼。
 * 所有對外拒絕或失敗的 operation result 必須攜帶其中之一。</p>
 *
 * <h2>錯誤代碼索引</h2>
 * <ul>
 *   <li>{@link #NOT_READY} — 服務尚未啟用（uninitialized / bind 前）</li>
 *   <li>{@link #SHUTDOWN} — 服務已停用（onDisable / reload 失敗）</li>
 *   <li>{@link #WORLD_NOT_FOUND} — 指定的 world UUID 不存在於伺服器</li>
 *   <li>{@link #CHUNK_UNLOADED} — 目標座標所在 chunk 未載入</li>
 *   <li>{@link #ENTITY_GONE} — 目標 entity 已移除 / 死亡 / 不在線</li>
 *   <li>{@link #PLAYER_OFFLINE} — 目標玩家離線</li>
 *   <li>{@link #INVALID_INPUT} — 輸入為 null 或語意不合法</li>
 *   <li>{@link #CONTEXT_UNSAFE} — 當前執行緒不允許 mutate 目標物件</li>
 *   <li>{@link #PLATFORM_UNSUPPORTED} — 平台不支援此操作</li>
 *   <li>{@link #OPERATION_FAILED} — 通用 operation 失敗（內部執行拋例外）</li>
 *   <li>{@link #EFFECT_REJECTED} — 效果施展被拒絕（chunk 未載入等）</li>
 *   <li>{@link #NEARBY_QUERY_FAILED} — 鄰近查詢失敗</li>
 *   <li>{@link #TELEPORT_REJECTED} — 傳送被 Bukkit 拒絕（{@code teleport()} 回傳 false）</li>
 *   <li>{@link #TELEPORT_EXCEPTION} — 傳送拋例外（CompletionStage 異常完成）</li>
 *   <li>{@link #PARTIAL_COMPLETION} — 跨 region/玩家傳送部分完成</li>
 * </ul>
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>本類別只暴露常數字串，不含 enum 語意（避免強制 caller switch 隨版本擴張）</li>
 *   <li>常數為 {@code public static final String}（caller 可直接讀取）</li>
 *   <li>常數格式固定為 {@code ACELIB-WORLD-<CODE>}，與既有
 *       {@code ACELIB-SCHED-*} / {@code ACELIB-PLAYER-*} 一致</li>
 * </ul>
 *
 * @see WorldResult
 * @since Phase 10 (Plan §十九)
 */
public final class WorldErrorCode {

    private WorldErrorCode() {
        // utility class
    }

    /** 001 — 服務尚未啟用（uninitialized / bind 前）。 */
    public static final String NOT_READY = "ACELIB-WORLD-001";
    /** 002 — 服務已停用（onDisable / reload 失敗）。 */
    public static final String SHUTDOWN = "ACELIB-WORLD-002";
    /** 003 — 指定的 world UUID 不存在於伺服器。 */
    public static final String WORLD_NOT_FOUND = "ACELIB-WORLD-003";
    /** 004 — 目標座標所在 chunk 未載入。 */
    public static final String CHUNK_UNLOADED = "ACELIB-WORLD-004";
    /** 005 — 目標 entity 已移除 / 死亡 / 不在線。 */
    public static final String ENTITY_GONE = "ACELIB-WORLD-005";
    /** 006 — 目標玩家離線。 */
    public static final String PLAYER_OFFLINE = "ACELIB-WORLD-006";
    /** 007 — 輸入為 null 或語意不合法。 */
    public static final String INVALID_INPUT = "ACELIB-WORLD-007";
    /** 008 — 當前執行緒不允許 mutate 目標物件（Folia 上下文違規）。 */
    public static final String CONTEXT_UNSAFE = "ACELIB-WORLD-008";
    /** 009 — 平台不支援此操作（UNKNOWN / 缺失 capability）。 */
    public static final String PLATFORM_UNSUPPORTED = "ACELIB-WORLD-009";
    /** 010 — 通用 operation 失敗（內部執行拋例外）。 */
    public static final String OPERATION_FAILED = "ACELIB-WORLD-010";
    /** 011 — 效果施展被拒絕（chunk 未載入 / target 不再有效）。 */
    public static final String EFFECT_REJECTED = "ACELIB-WORLD-011";
    /** 012 — 鄰近查詢失敗。 */
    public static final String NEARBY_QUERY_FAILED = "ACELIB-WORLD-012";
    /** 013 — 通用 block 操作失敗（如材質不存在）。 */
    public static final String BLOCK_OPERATION_FAILED = "ACELIB-WORLD-013";
    /** 014 — 傳送被 Bukkit 拒絕（{@code teleport()} 回傳 false）。 */
    public static final String TELEPORT_REJECTED = "ACELIB-WORLD-014";
    /** 015 — 傳送拋例外（CompletionStage 異常完成）。 */
    public static final String TELEPORT_EXCEPTION = "ACELIB-WORLD-015";
    /** 016 — 跨 region/玩家傳送部分完成（第一步成功但第二步失敗）。 */
    public static final String PARTIAL_COMPLETION = "ACELIB-WORLD-016";
}
