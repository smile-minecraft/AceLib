package com.smile.acelib.scheduler;

import java.util.Objects;
import org.bukkit.scheduler.BukkitTask;

/**
 * Paper / Bukkit 任務句柄（Internal）。
 *
 * <p>包裝 {@link BukkitTask}，把 Paper 的真實句柄轉成
 * {@link PlatformTaskHandle}。Paper 的 task id 由伺服器 scheduler 指派，
 * 在同一 dispatcher 內本來就唯一，因此直接沿用。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class BukkitPlatformTaskHandle implements PlatformTaskHandle {

    private final BukkitTask task;

    BukkitPlatformTaskHandle(BukkitTask task) {
        this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    public int taskId() {
        return task.getTaskId();
    }

    @Override
    public void cancel() {
        task.cancel();
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }
}
