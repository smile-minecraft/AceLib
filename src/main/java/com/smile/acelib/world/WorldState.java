package com.smile.acelib.world;

/**
 * 世界操作結果狀態。
 *
 * <p>所有 {@link WorldResult} 子類型（{@link BlockResult} / {@link EntityResult} /
 * {@link TeleportResult} / {@link NearbyQueryResult}）皆攜帶下列狀態之一：</p>
 *
 * <ul>
 *   <li>{@link #NOT_STARTED} — operation 建立但尚未開始執行（保留語意，目前對外不可見）</li>
 *   <li>{@link #SUCCESS} — 同步或非同步 operation 成功完成</li>
 *   <li>{@link #REJECTED} — operation 在執行前被拒絕（前面檢查失敗）</li>
 *   <li>{@link #CANCELLED} — operation 開始後被取消（plugin 停用 / in-flight 被終止）</li>
 *   <li>{@link #PARTIAL} — 部分完成（跨 region/玩家傳送第一步成功但第二步失敗）</li>
 *   <li>{@link #FAILED} — 執行時失敗（內部拋例外 / 目標狀態異常）</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * 狀態順序凍結，不得更動。
 *
 * @see WorldResult
 * @since 1.0.0
 */
public enum WorldState {

    NOT_STARTED,
    SUCCESS,
    REJECTED,
    CANCELLED,
    PARTIAL,
    FAILED
}
