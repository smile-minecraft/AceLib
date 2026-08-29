package com.smile.acelib.scheduler;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Paper / Bukkit 全域 scheduler backend（Internal）。
 *
 * <p>透過 {@link BukkitScheduler} 派送；玩家 / 實體 / 位置任務在 Paper 下沒有
 * region scheduler 可用，因此統一退回主執行緒全域派送（既有行為，與原
 * {@code dispatchPaper} 完全一致）。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class PaperSchedulerBackend implements SchedulerBackend {

    private final JavaPlugin plugin;

    PaperSchedulerBackend(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public BukkitTask dispatch(TaskType type,
                               Runnable wrapped,
                               Player player,
                               Object entityOrLoc,
                               long delayTicks,
                               long periodTicks,
                               boolean async) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        if (async) {
            return scheduler.runTaskAsynchronously(plugin, wrapped);
        }
        if (periodTicks > 0L) {
            return scheduler.runTaskTimer(plugin, wrapped, delayTicks, periodTicks);
        }
        if (delayTicks > 0L) {
            return scheduler.runTaskLater(plugin, wrapped, delayTicks);
        }
        return scheduler.runTask(plugin, wrapped);
    }
}
