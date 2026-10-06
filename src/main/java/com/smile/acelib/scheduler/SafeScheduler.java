package com.smile.acelib.scheduler;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Folia-safe 排程器介面（Supported）。
 *
 * <p>封裝 Bukkit/Folia 各種 scheduler API，確保後續插件不需要直接處理
 * region / entity / global scheduler 的選擇與有效性檢查。</p>
 *
 * <h2>9 種任務方法 + cancelAll</h2>
 * <ol>
 *   <li>{@link #runGlobal(Runnable)} — 全域同步任務（下一個 tick 跑）</li>
 *   <li>{@link #runAsync(Runnable)} — 非同步任務</li>
 *   <li>{@link #runLater(Runnable, long)} — 延遲任務</li>
 *   <li>{@link #runTimer(Runnable, long, long)} — 週期任務</li>
 *   <li>{@link #runForPlayer(Player, Runnable)} — 玩家所屬 region 同步任務</li>
 *   <li>{@link #runForPlayerLater(Player, Runnable, long)} — 玩家延遲任務</li>
 *   <li>{@link #runForEntity(Entity, Runnable)} — 實體所屬 region 同步任務</li>
 *   <li>{@link #runAtLocation(Location, Runnable)} — 指定位置所在 region 任務</li>
 *   <li>{@link #getRecorderErrors(int)} — 取得錯誤紀錄</li>
 * </ol>
 * <p>加上 {@link #cancelAll()} 共 10 個基礎方法；另有
 * {@link #scopeFor(Player)}／{@link #scopeFor(Entity)} 兩個作用域工廠
 *（回傳 {@link TaskScope}，派送結果以 {@link TaskTicket} 攜帶終態）。</p>
 *
 * <h2>邊界行為</h2>
 * <ul>
 *   <li>傳入 null runnable 一律拋 {@link NullPointerException}</li>
 *   <li>玩家離線、實體失效、chunk 未載入等情境不會丟例外，
 *       而是回傳 {@code isCancelled() == true} 的 no-op task，
 *       並在 {@link TaskErrorRecorder} 留下對應錯誤代碼紀錄</li>
 *   <li>插件停用後所有方法都會回傳 no-op task，
 *       並留下 {@code ACELIB-SCHED-006} 紀錄</li>
 * </ul>
 *
 * @see SafeSchedulerImpl
 * @see AceLibScheduler
 * @since 1.0.0
 */
public interface SafeScheduler {

    // -----------------------------------------------------------------
    // 9 種任務方法
    // -----------------------------------------------------------------

    /**
     * 全域同步任務（下一個 tick 跑）。
     *
     * @param runnable 要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼（可為已 cancelled 的 no-op）
     */
    ScheduledTask runGlobal(Runnable runnable);

    /**
     * 非同步任務（執行於 server async pool）。
     *
     * @param runnable 要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼
     */
    ScheduledTask runAsync(Runnable runnable);

    /**
     * 延遲任務（指定 tick 數後執行一次）。
     *
     * @param runnable   要執行的程式；不可為 null
     * @param delayTicks 延遲 tick 數；必須 &ge; 0
     * @return {@link ScheduledTask} 控制代碼
     * @throws IllegalArgumentException 當 {@code delayTicks < 0}
     */
    ScheduledTask runLater(Runnable runnable, long delayTicks);

    /**
     * 週期任務（指定延遲後週期執行）。
     *
     * @param runnable    要執行的程式；不可為 null
     * @param delayTicks  第一次延遲 tick 數；必須 &ge; 0
     * @param periodTicks 週期間隔 tick 數；必須 &gt; 0
     * @return {@link ScheduledTask} 控制代碼
     * @throws IllegalArgumentException 當 {@code delayTicks < 0} 或 {@code periodTicks <= 0}
     */
    ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks);

    /**
     * 玩家相關同步任務（Folia 下會使用玩家自己的 scheduler）。
     *
     * @param player   目標玩家；不可為 null
     * @param runnable 要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼；若玩家已離線，回傳已 cancelled 的 no-op
     */
    ScheduledTask runForPlayer(Player player, Runnable runnable);

    /**
     * 玩家相關延遲任務。
     *
     * @param player     目標玩家；不可為 null
     * @param runnable   要執行的程式；不可為 null
     * @param delayTicks 延遲 tick 數；必須 &ge; 0
     * @return {@link ScheduledTask} 控制代碼；若玩家已離線，回傳已 cancelled 的 no-op
     * @throws IllegalArgumentException 當 {@code delayTicks < 0}
     */
    ScheduledTask runForPlayerLater(Player player, Runnable runnable, long delayTicks);

    /**
     * 實體相關同步任務。
     *
     * @param entity   目標實體；不可為 null
     * @param runnable 要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼；若實體失效/死亡，回傳已 cancelled 的 no-op
     */
    ScheduledTask runForEntity(Entity entity, Runnable runnable);

    /**
     * 位置相關同步任務（Folia 下會使用該位置的 RegionScheduler）。
     *
     * @param location 目標位置；不可為 null
     * @param runnable 要執行的程式；不可為 null
     * @return {@link ScheduledTask} 控制代碼；若對應 chunk 未載入，回傳已 cancelled 的 no-op
     */
    ScheduledTask runAtLocation(Location location, Runnable runnable);

    /**
     * 取得最近的 N 筆錯誤紀錄。
     *
     * @param max 最多回傳幾筆（&lt;=0 回傳空清單）
     * @return 不可變的「時間由舊到新」紀錄清單
     */
    List<TaskErrorRecord> getRecorderErrors(int max);

    /**
     * 以指定玩家為作用域建立任務群組。
     *
     * <p>群組內的任務共享生命週期：玩家退服或 plugin 停用時自動取消，
     * 呼叫端經由 {@link TaskTicket} 收到終態。派送結果可區分
     * 「已接受排程」與「動作完成」，並支援等待與串接。</p>
     *
     * <p>生產實作要求 plugin 已啟用（退服監聽需註冊）；未啟用時拋
     * {@link IllegalStateException}，避免靜默失去自動取消。</p>
     *
     * @param player 作用域擁有者；不可為 null（離線玩家可建群組，但派送即拒派）
     * @return 新的作用域群組；永不為 null
     * @throws NullPointerException 當 {@code player} 為 null
     * @throws IllegalStateException 當 plugin 未啟用（生產實作）
     * @since 1.4.0
     */
    TaskScope scopeFor(Player player);

    /**
     * 以指定實體為作用域建立任務群組。
     *
     * <p>語意同 {@link #scopeFor(Player)}；實體退休（移除、死亡）時群組自動取消。
     * Folia 下回覆派送跟著實體當下所在的 region。</p>
     *
     * @param entity 作用域擁有者；不可為 null（已失效實體可建群組，但派送即拒派）
     * @return 新的作用域群組；永不為 null
     * @throws NullPointerException 當 {@code entity} 為 null
     * @throws IllegalStateException 當 plugin 未啟用（生產實作）
     * @since 1.4.0
     */
    TaskScope scopeFor(Entity entity);

    /**
     * 取消目前由本 scheduler 派送的所有任務。
     *
     * <p>呼叫後所有 tracked {@link ScheduledTask} 都會變成 cancelled 狀態。
     * 重複呼叫不丟例外。</p>
     */
    void cancelAll();
}