package com.smile.acelib.scheduler;

/**
 * 排程任務類型列舉（Supported）。
 *
 * <p>用於在 {@link TaskErrorRecord} 與 {@link ScheduledTask} 中標記任務語意，
 * 方便後續診斷與除錯。</p>
 *
 * <h2>語意對照</h2>
 * <ul>
 *   <li>{@link #GLOBAL} — 主執行緒全域任務（下一個 tick 同步執行）</li>
 *   <li>{@link #ASYNC} — 非同步任務（執行於 server async pool）</li>
 *   <li>{@link #LATER} — 延遲任務（指定 tick 後執行）</li>
 *   <li>{@link #TIMER} — 週期任務（指定 tick 延遲後週期執行）</li>
 *   <li>{@link #PLAYER} — 玩家相關任務（玩家自己的 region，Folia 安全）</li>
 *   <li>{@link #PLAYER_LATER} — 玩家相關延遲任務</li>
 *   <li>{@link #ENTITY} — 實體相關任務</li>
 *   <li>{@link #LOCATION} — 位置相關任務（依 region scheduler）</li>
 * </ul>
 *
 * @see SafeScheduler
 * @see ScheduledTask
 * @since 1.0.0
 */
public enum TaskType {

    GLOBAL,
    ASYNC,
    LATER,
    TIMER,
    PLAYER,
    PLAYER_LATER,
    ENTITY,
    LOCATION
}