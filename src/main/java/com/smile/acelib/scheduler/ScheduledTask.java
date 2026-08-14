package com.smile.acelib.scheduler;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * 已派送任務的抽象控制介面（Supported）。
 *
 * <p>由 {@link SafeScheduler} 各方法回傳；持有者可用 {@link #cancel()} 取消任務、
 * 用 {@link #isCancelled()} 查詢當前狀態，或在診斷時取得任務的 plugin owner、
 * 類型與建立時的 tick。</p>
 *
 * <h2>取消語意</h2>
 * <ul>
 *   <li>{@link #cancel()} 必須為冪等：重複呼叫不丟例外且不會影響其他任務</li>
 *   <li>取消後 {@link #isCancelled()} 一律回傳 true</li>
 *   <li>若任務在派送前就被取消（例如玩家已離線），回傳的 instance 已是 cancelled 狀態</li>
 * </ul>
 *
 * @see SafeScheduler
 * @see TaskType
 * @since 1.0.0
 */
public interface ScheduledTask {

    /**
     * 取消此任務。
     *
     * <p>呼叫後任務不再被執行；若任務正在執行，執行行為依平台決定
     * （Paper: 當前任務跑完；Folia: 取決於 retired callback）。</p>
     *
     * <p>必須為冪等。重複呼叫不丟例外。</p>
     */
    void cancel();

    /**
     * 查詢任務是否已被取消。
     *
     * @return 若已取消（含派送前就被拒絕的 case）為 true
     */
    boolean isCancelled();

    /**
     * 取得派送此任務的 plugin owner。
     *
     * @return 當初呼叫 SafeScheduler 方法的 {@link JavaPlugin}；永遠不為 null
     */
    JavaPlugin getPlugin();

    /**
     * 取得任務類型。
     *
     * @return 對應的 {@link TaskType}；永遠不為 null
     */
    TaskType getType();

    /**
     * 取得任務被建立（提交給 scheduler）時的 server tick。
     *
     * <p>若伺服器不支援 {@code Bukkit.getCurrentTick()}（測試環境邊界情境），
     * 回傳 0。</p>
     *
     * @return 建立時的 tick 值（&ge;0）
     */
    long getCreationTick();
}