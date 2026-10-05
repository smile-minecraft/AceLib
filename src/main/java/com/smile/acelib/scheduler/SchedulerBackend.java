package com.smile.acelib.scheduler;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Internal dispatch seam：將 runtime-specific scheduler 派送從
 * {@link SafeSchedulerImpl} 抽離，使 backend 選擇只依 capability profile，
 * 不依版本字串 switch。
 *
 * <p>實作：</p>
 * <ul>
 *   <li>{@link PaperSchedulerBackend} — Paper / Bukkit 全域 scheduler</li>
 *   <li>{@link FoliaSchedulerBackend} — Folia regionized scheduler</li>
 * </ul>
 *
 * <p>所有派發失敗（平台不支援該組合、底層拋例外等）皆以 {@link Exception}
 * 拋出，由 {@link SafeSchedulerImpl} 統一以 {@code ACELIB-SCHED-005} 記錄並
 * 回傳 no-op task（fail-closed，絕不退回 unsafe scheduler）。</p>
 *
 * <p>本介面為 package-private，不進 API surface；下游不得依賴 implementation class。</p>
 */
interface SchedulerBackend {

    /**
     * 執行一次 runtime-specific 派送。
     *
     * <p>回傳的 {@link PlatformTaskHandle} 必須包裝<strong>真實</strong>的
     * 底層任務句柄，不可為了統一回傳型別而丟棄它；否則取消只會作用在本地
     * 狀態上，底層任務仍會繼續執行。</p>
     *
     * @param type         任務類型（供錯誤紀錄與診斷使用）
     * @param wrapped      已包裝使用者 runnable 的 wrapper（執行時記錄 SCHED-001；
     *                     一次性任務執行完會解除追蹤）
     * @param retired      任務在執行前就被平台判定為不可能執行時的收尾通知
     *                     （Folia entity 退役；Paper 不支援此概念，會忽略）
     * @param player       玩家目標（PLAYER / PLAYER_LATER）；其他型別為 null
     * @param entityOrLoc  實體或位置目標（ENTITY / LOCATION）；其他型別為 null
     * @param delayTicks   延遲 tick（runLater / runTimer / runForPlayerLater）
     * @param periodTicks  週期間隔（runTimer）
     * @param async        是否走 async pool
     * @return 真實的底層任務句柄
     * @throws Exception 當派發失敗（平台不支援該組合、底層拋例外等）；
     *                   呼叫端必須 fail-closed，不得退回 unsafe scheduler
     */
    PlatformTaskHandle dispatch(TaskType type,
                                Runnable wrapped,
                                Runnable retired,
                                Player player,
                                Object entityOrLoc,
                                long delayTicks,
                                long periodTicks,
                                boolean async) throws Exception;
}
