package com.smile.acelib.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia regionized 任務句柄（Internal）。
 *
 * <p>包裝 Folia 真正回傳的 {@link ScheduledTask}，讓
 * {@link SafeSchedulerImpl#cancelAll()}、{@code cancel()} 與 plugin disable
 * 都能取消到底層任務。過去這一層被丟掉、換成本地旗標的佔位實作，
 * 因此 Folia 上的取消從來沒有真正生效。</p>
 *
 * <h2>task id</h2>
 * <p>Folia 的 {@code ScheduledTask} 不提供 id，因此由本 backend 以
 * {@link AtomicInteger} 遞增指派。使用負數區間（{@code Integer.MIN_VALUE}
 * 起算）以便與 Paper/Bukkit 指派的自增 task id 區隔，兩者在日誌中
 * 不會互相誤認。識別碼只在同一 dispatcher 內有效。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class FoliaPlatformTaskHandle implements PlatformTaskHandle {

    private final ScheduledTask task;
    private final int taskId;

    FoliaPlatformTaskHandle(ScheduledTask task, AtomicInteger idSource) {
        this.task = Objects.requireNonNull(task, "task");
        this.taskId = idSource.getAndIncrement();
    }

    @Override
    public int taskId() {
        return taskId;
    }

    @Override
    public void cancel() {
        // Folia 的 cancel() 回傳 CancelledState 而非 void；重複取消會得到
        // CANCELLED_ALREADY，屬正常冪等結果，不需要額外處理。
        task.cancel();
    }

    @Override
    public boolean isCancelled() {
        // 直接反映底層狀態；查詢失敗（例如 runtime 未實作 getExecutionState）
        // 由呼叫端 SafeSchedulerImpl.BukkitScheduledTask 決定 fail-closed 策略，
        // 這裡不吞例外也不猜測狀態。
        return task.isCancelled();
    }
}
