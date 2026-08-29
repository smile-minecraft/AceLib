package com.smile.acelib.scheduler;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Internal dispatch seam：將 runtime-specific scheduler 派送從
 * {@link SafeSchedulerImpl} 抽離，使 backend 選擇只依 capability profile，
 * 不依版本字串 switch。
 *
 * <p>實作：</p>
 * <ul>
 *   <li>{@link PaperSchedulerBackend} — Paper / Bukkit 全域 scheduler</li>
 *   <li>{@link FoliaSchedulerBackend} — Folia regionized scheduler（reflection）</li>
 * </ul>
 *
 * <p>所有派發失敗（含 Folia API 不存在）皆以 {@link Exception} 拋出，
 * 由 {@link SafeSchedulerImpl} 統一以 {@code ACELIB-SCHED-005} 記錄並回傳
 * no-op task（fail-closed，絕不退回 unsafe scheduler）。</p>
 *
 * <p>本介面為 package-private，不進 API surface；下游不得依賴 implementation class。</p>
 */
interface SchedulerBackend {

    /**
     * 執行一次 runtime-specific 派送。
     *
     * @param type         任務類型
     * @param wrapped      已包裝使用者 runnable 的 wrapper（執行時記錄 SCHED-001）
     * @param player       玩家目標（PLAYER / PLAYER_LATER）；其他型別為 null
     * @param entityOrLoc  實體或位置目標（ENTITY / LOCATION）；其他型別為 null
     * @param delayTicks   延遲 tick（runLater / runTimer / runForPlayerLater）
     * @param periodTicks  週期間隔（runTimer）
     * @param async        是否走 async pool
     * @return 派送後的 {@link BukkitTask}（Folia 路徑可能為 detached 佔位）
     * @throws Exception 當派發失敗（Folia API 不存在、反射錯誤、底層拋例外等）；
     *                   呼叫端必須 fail-closed，不得退回 unsafe scheduler
     */
    BukkitTask dispatch(TaskType type,
                        Runnable wrapped,
                        Player player,
                        Object entityOrLoc,
                        long delayTicks,
                        long periodTicks,
                        boolean async) throws Exception;
}
