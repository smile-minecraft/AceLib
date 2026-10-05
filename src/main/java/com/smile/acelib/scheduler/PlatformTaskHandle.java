package com.smile.acelib.scheduler;

/**
 * 平台任務句柄（Internal）：把 Paper 與 Folia 各自的可取消句柄收斂成同一個
 * 內部形狀，讓 {@link SafeSchedulerImpl} 不需要知道底層是
 * {@code org.bukkit.scheduler.BukkitTask} 還是 Folia 的
 * {@code ScheduledTask}。
 *
 * <p>存在的理由：Folia 的 {@code ScheduledTask} 不是 {@code BukkitTask}，
 * 兩者沒有共同介面。過去為了讓 backend 回傳 {@code BukkitTask}，
 * Folia 路徑只能丟掉真實句柄、改用本地旗標的佔位實作，導致
 * {@code cancel()} / {@code cancelAll()} / plugin disable 都沒有真的
 * 取消到底層任務。本介面讓 backend 可以把真實句柄交回來。</p>
 *
 * <p>本介面為 package-private，不進 API surface；下游不得依賴。</p>
 *
 * @see SchedulerBackend
 * @since 1.3.1
 */
interface PlatformTaskHandle {

    /**
     * 取得此句柄的識別碼。
     *
     * <p>Paper 路徑回傳 Bukkit 伺服器派發的 task id；Folia 的
     * {@code ScheduledTask} 不提供 id，因此由 backend 以 per-backend
     * 遞增序號指派。兩者皆保證<strong>同一 dispatcher（同一 backend
     * 實例）內唯一</strong>，且同一個 id 只會對應到一個活著的任務實例。</p>
     *
     * <p>此值僅供診斷與日誌定位使用，不可假定跨平台可比。</p>
     *
     * @return dispatcher 內唯一的任務識別碼
     */
    int taskId();

    /**
     * 取消底層任務。
     *
     * <p>必須冪等：重複呼叫不丟例外，且對已完成或已取消的任務呼叫
     * 不應影響其他任務。</p>
     */
    void cancel();

    /**
     * 查詢底層任務是否已被取消。
     *
     * @return 已取消為 true
     */
    boolean isCancelled();
}
