package com.smile.acelib.scheduler;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 以玩家或實體為作用域的任務群組（Supported）。
 *
 * <p>經由 {@link SafeScheduler#scopeFor(Player)} 或
 * {@link SafeScheduler#scopeFor(Entity)} 取得。群組內的任務共享同一個生命週期：
 * 玩家退服、實體退休或 plugin 停用時，群組內尚未結束的任務自動取消，
 * 呼叫端經由 {@link TaskTicket} 收到 {@code CANCELLED} 終態；
 * 之後此作用域的新派送一律為 {@code REJECTED}。</p>
 *
 * <h2>單一流程</h2>
 * <p>{@link #pipeline(Supplier, Function, Consumer)} 把「讀取 → 背景計算 →
 * 回玩家／實體所在執行緒回覆」串成一條流程：讀取與計算跑在非同步池，
 * 回覆派送到擁有者當下所在的執行緒（Folia 下跟著跨區後的玩家）。
 * 任一階段失敗、被取消或被拒派，整條流程即為該終態，後續階段不再執行；
 * 退服後回覆階段保證不執行使用者程式。</p>
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>{@link #cancelAll()} 取消群組內待執行任務，但作用域仍可用；</li>
 *   <li>退服／退休／停用後 {@link #isActive()} 為 false，後續派送直接拒派；</li>
 *   <li>作用域本身是輕量物件，請重複使用，不要每次任務都新建。</li>
 * </ul>
 *
 * @see SafeScheduler#scopeFor(Player)
 * @see SafeScheduler#scopeFor(Entity)
 * @see TaskTicket
 * @since 1.4.0
 */
public interface TaskScope {

    /**
     * 取得派送任務的 plugin owner。
     *
     * @return 當初建立作用域的 {@link JavaPlugin}；永遠不為 null
     */
    JavaPlugin plugin();

    /**
     * 作用域是否仍可用（擁有者仍在、plugin 未停用）。
     *
     * @return 可用為 true；退服／退休／停用後為 false
     */
    boolean isActive();

    /**
     * 在擁有者所在執行緒執行一次性動作（玩家：其 entity scheduler；
     * 實體：其 entity scheduler；Paper：主執行緒）。
     *
     * @param action 要執行的程式；不可為 null
     * @return 可觀察終態的票據；擁有者已失效時為已完成的拒派票據
     */
    TaskTicket<Void> run(Runnable action);

    /**
     * 在擁有者所在執行緒執行一次性動作並攜回值。
     *
     * @param action 要執行的程式；不可為 null
     * @param <T> 回傳值型別
     * @return 可觀察終態的票據；值經由終態攜回
     */
    <T> TaskTicket<T> supply(Supplier<T> action);

    /**
     * 在背景非同步池執行一次性動作並攜回值（作用域守衛全程有效）。
     *
     * <p>動作內不可直接操作玩家、實體、世界；需要回覆時請經由
     * {@link #pipeline(Supplier, Function, Consumer)} 或另行派送
     * {@link #run(Runnable)}。</p>
     *
     * @param action 要執行的程式；不可為 null
     * @param <T> 回傳值型別
     * @return 可觀察終態的票據
     */
    <T> TaskTicket<T> supplyAsync(Supplier<T> action);

    /**
     * 讀取 → 背景計算 → 回擁有者所在執行緒回覆的單一流程。
     *
     * <p>讀取與計算跑在背景池；回覆派送到擁有者當下所在的執行緒。
     * 任一階段失敗、取消或拒派即為整條流程的終態，後續階段不再執行。</p>
     *
     * @param read    讀取階段；不可為 null（背景執行）
     * @param compute 計算階段；不可為 null（背景執行；回傳值可為 null）
     * @param reply   回覆階段；不可為 null（擁有者所在執行緒執行）
     * @param <T> 讀取值型別
     * @param <R> 計算結果型別（亦為流程完成時攜回的值型別）
     * @return 整條流程的可觀察終態票據
     */
    <T, R> TaskTicket<R> pipeline(Supplier<T> read,
                                  Function<? super T, ? extends R> compute,
                                  Consumer<? super R> reply);

    /**
     * 取消群組內目前待執行的任務（各票據收到 {@code CANCELLED}）。
     *
     * <p>作用域本身仍可用，後續派送不受影響。重複呼叫不丟例外。</p>
     */
    void cancelAll();

    /**
     * 群組內尚未結束的任務數（診斷用）。
     *
     * @return 仍被追蹤的票據數
     */
    int pendingCount();
}
