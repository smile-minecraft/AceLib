package com.smile.acelib.scheduler;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 可觀察終態的任務票據（Supported）。
 *
 * <p>由 {@link TaskScope} 各方法回傳，同時是兩種東西：</p>
 * <ul>
 *   <li>「已接受排程」句柄：沿用 {@link ScheduledTask} 的 {@code cancel()}／
 *       {@code isCancelled()} 語意（冪等取消、診斷資訊）；</li>
 *   <li>「動作完成」通知：{@link #stage()} 可串接後續動作，
 *       {@link #await(long, TimeUnit)} 可等待終態，
 *       {@link #whenComplete(Consumer)} 是最常用的單一通知。</li>
 * </ul>
 *
 * <h2>等待規則</h2>
 * <p>{@link #await(long, TimeUnit)} 是呼叫端在<strong>自己執行緒</strong>上的
 * 阻塞等待；絕對不可在 region 執行緒、實體 scheduler 回呼或主執行緒同步任務內
 * 呼叫（會卡住該執行緒負責的所有任務）。排程器內部永遠不阻塞等待，
 * 階段之間只用回呼串接。</p>
 *
 * <h2>取消與終態</h2>
 * <ul>
 *   <li>完成（{@code COMPLETED}）或失敗（{@code FAILED}）後再取消，
 *       終態不變；</li>
 *   <li>拒派（{@code REJECTED}）本身就是終態，派送當下即已完成；</li>
 *   <li>取消（{@code CANCELLED}）後動作保證不再執行使用者程式
 *       （已在執行的無法 preempt，依平台語意）。</li>
 * </ul>
 *
 * @param <T> 完成時攜回的值型別
 * @see TaskScope
 * @see TaskResult
 * @see TaskOutcome
 * @since 1.4.0
 */
public interface TaskTicket<T> extends ScheduledTask {

    /**
     * 取得終態的串接視圖（唯讀）。
     *
     * <p>回傳的是 {@link CompletionStage} 而非可完成的 future：
     * 終態只能由排程器寫入一次，呼叫端只能觀察與串接，不能外部完成。</p>
     *
     * @return 終態的串接視圖；永不為 null
     */
    CompletionStage<TaskResult<T>> stage();

    /**
     * 在呼叫端自己的執行緒上等待終態。
     *
     * <p>不可在 region 執行緒、主執行緒同步任務或 scheduler 回呼內呼叫。</p>
     *
     * @param timeout  最長等待時間；必須為正數
     * @param unit     時間單位；不可為 null
     * @return 終態；永不為 null
     * @throws InterruptedException 當等待被中斷（中斷旗標會保留）
     * @throws TimeoutException 當時限內未出現終態
     */
    TaskResult<T> await(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException;

    /**
     * 終態是否已出現（完成、失敗、取消、拒派皆算）。
     *
     * @return 已有終態為 true
     */
    boolean isDone();

    /**
     * 終態出現時通知一次（無論哪種終態都會觸發）。
     *
     * <p>通知回呼只做輕量工作：需要回到玩家執行緒的操作請重新派送，
     * 不可在回呼內阻塞等待。</p>
     *
     * @param action 終態通知；不可為 null
     * @return 本票據（方便串接）
     */
    TaskTicket<T> whenComplete(Consumer<? super TaskResult<T>> action);
}
