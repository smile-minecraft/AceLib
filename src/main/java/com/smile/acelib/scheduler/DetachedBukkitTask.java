package com.smile.acelib.scheduler;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Folia dispatch 的相容性佔位 {@link BukkitTask}（Internal）。
 *
 * <p>由於 Folia API 回傳的 retired task 在不同版本間不保證相容 {@link BukkitTask}，
 * 統一以這個 lightweight 實作包裝；{@link SafeSchedulerImpl} 本身仍透過
 * {@link SafeSchedulerImpl#cancelAll()} 統一管理所有 Folia 派送任務的生命週期。</p>
 */
final class DetachedBukkitTask implements BukkitTask {

    private final Plugin owner;
    private final int taskId;
    private volatile boolean cancelled = false;

    DetachedBukkitTask(JavaPlugin plugin, TaskType type) {
        this.owner = plugin;
        this.taskId = nextDetachedId();
    }

    private static int nextDetachedId() {
        // 使用 System.identityHashCode + 負數區間以避免與 Bukkit.getScheduler() 的 taskId 衝突
        return -1 - (System.identityHashCode(Thread.currentThread()) & 0x3FFFFFFF);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Plugin getOwner() {
        return owner;
    }

    @Override
    public int getTaskId() {
        return taskId;
    }

    @Override
    public boolean isSync() {
        // Folia 環境下每個 task 都隱含綁定到自己的 region/thread，
        // 沒有 Paper 的「全域同步 vs async」二分法；回傳 true 表示「由 scheduler 管理」語意。
        return true;
    }
}
