package com.smile.acelib.gui;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;

/**
 * Production {@link PlayerContextExecutor} 實作：透過既有
 * {@link SafeScheduler#runForPlayer(org.bukkit.entity.Player, Runnable)}
 * 派送 inventory mutation 到玩家所屬 region（Folia entity scheduler、
 * Paper main thread）。
 *
 * <p>此類別位於 {@code gui} 套件內，避免外部 caller 直接持有 SafeScheduler
 * reference；對應「inventory mutation 必須走既有安全入口」的契約。</p>
 *
 * <h2>派送成功 / 拒絕語意</h2>
 * <p>回傳 {@code true} 表示底層 {@link SafeScheduler} 已接受任務（Paper main
 * thread / Folia entity scheduler 已 enqueue）；回傳 {@code false} 表示
 * {@link SafeScheduler} 回傳了 cancelled no-op task（例如 scheduler disabled、
 * player offline、平台不支援）。對應「reload 期間舊 scheduler 仍被 GUI 持有」
 * 與「player 在 startSession 後離線」的 race — 此時 {@code openInventory} 必須
 * 回 {@link GuiResult#failed} + {@link GuiErrorCode#SCHEDULER_REJECTED} 而非
 * 留下 stale session。</p>
 *
 * @since Phase 11 (Plan §十六 §二十一)
 */
final class SafeSchedulerPlayerContextExecutor implements PlayerContextExecutor {

    private final SafeScheduler scheduler;

    SafeSchedulerPlayerContextExecutor(SafeScheduler scheduler) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public boolean runOnPlayerRegion(org.bukkit.entity.Player player, Runnable runnable) {
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(runnable, "runnable");
        // SafeScheduler 內部已處理 player offline / region unavailable 等邊界；
        // 透過 ScheduledTask.isCancelled() 區分「已派送」與「被拒絕（no-op task）」。
        // isCancelled() == true 表示 SafeScheduler 回傳的是 NoOpScheduledTask，
        // 對應 ACELIB-SCHED-002/003/004/005/006 任一類別。
        ScheduledTask task = scheduler.runForPlayer(player, runnable);
        return !task.isCancelled();
    }
}
