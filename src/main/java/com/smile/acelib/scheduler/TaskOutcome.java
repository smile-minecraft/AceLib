package com.smile.acelib.scheduler;

/**
 * 排程動作的終態分類（Supported）。
 *
 * <p>一個任務從「已接受排程」到結束，只會落到下列其中一種終態，
 * 由 {@link TaskResult#outcome()} 攜帶，並透過 {@link TaskTicket} 通知呼叫端：</p>
 * <ul>
 *   <li>{@link #COMPLETED} — 動作已執行完成（含攜回值）；</li>
 *   <li>{@link #FAILED} — 動作執行時拋出例外（記 {@code ACELIB-SCHED-001}）；</li>
 *   <li>{@link #CANCELLED} — 接受後被取消：呼叫端顯式取消、玩家退服、
 *       實體退休、plugin 停用或作用域關閉；</li>
 *   <li>{@link #REJECTED} — 派送當下即被拒絕，從未被接受：玩家已離線
 *      （{@code ACELIB-SCHED-002}）、實體已失效（{@code ACELIB-SCHED-003}）、
 *       chunk 未載入（{@code ACELIB-SCHED-004}）、平台不支援
 *      （{@code ACELIB-SCHED-005}）或插件已停用（{@code ACELIB-SCHED-006}）。</li>
 * </ul>
 *
 * <p>既有 {@code ScheduledTask} 只回答「已接受排程、可取消」；
 * 終態語意一律看 {@link TaskTicket}，不要用 {@code isCancelled()} 推測
 * 動作是否完成（完成的任務 {@code isCancelled()} 為 false，
 * 拋錯的任務同樣為 false）。</p>
 *
 * @see TaskResult
 * @see TaskTicket
 * @see TaskScope
 * @since 1.4.0
 */
public enum TaskOutcome {

    /**
     * 動作已執行完成。{@link TaskResult#value()} 攜帶結果（可為 null）。
     */
    COMPLETED,

    /**
     * 動作執行時拋出例外。{@link TaskResult#cause()} 與
     * {@link TaskResult#errorRecord()} 必不為 null。
     */
    FAILED,

    /**
     * 接受後被取消。呼叫端顯式取消時 {@link TaskResult#errorRecord()} 可為 null；
     * 退服／退休／停用觸發時攜帶對應分類紀錄。
     */
    CANCELLED,

    /**
     * 派送當下即被拒絕，從未被接受。{@link TaskResult#errorRecord()} 必不為 null。
     */
    REJECTED
}
