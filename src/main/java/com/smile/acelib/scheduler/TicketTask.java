package com.smile.acelib.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link TaskTicket} 的內部實作（Internal）。
 *
 * <p>同時扮演兩個角色：對外是「已接受排程」句柄（{@code cancel()} 冪等、
 * {@code isCancelled()}、診斷資訊），對內持有 {@link TaskCompletionSource}
 * 供派送路徑寫入終態。兩條路徑的順序保證：</p>
 * <ul>
 *   <li>先寫終態、再取消底層：特定原因（退服、退休、拒派）的紀錄優先於
 *       通用取消，後寫者因 once 語意被丟棄；</li>
 *   <li>完成後再取消不改變終態（{@code isCancelled()} 仍回 false）；</li>
 *   <li>拒派票據以已取消的 no-op 為底層，完成單元預先寫入拒派終態。</li>
 * </ul>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 *
 * @param <T> 完成時攜回的值型別
 */
final class TicketTask<T> implements TaskTicket<T> {

    private final JavaPlugin plugin;
    private final TaskType type;
    private final long creationTick;
    private final TaskCompletionSource<T> source;
    private final AtomicReference<ScheduledTask> delegate = new AtomicReference<>();
    private final AtomicReference<Runnable> onCancel = new AtomicReference<>(() -> {
    });
    private final AtomicReference<Runnable> onSettle = new AtomicReference<>(() -> {
    });

    TicketTask(JavaPlugin plugin, TaskType type, long creationTick,
               TaskCompletionSource<T> source) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.type = Objects.requireNonNull(type, "type");
        this.creationTick = creationTick;
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * 綁定底層派送句柄（派送成功後呼叫）。
     *
     * <p>句柄晚於票據建立是因為派送可能失敗：失敗時票據沿用空底層，
     * 終態已由拒派路徑預先寫入。</p>
     *
     * @param task 底層句柄；不可為 null
     */
    void attach(ScheduledTask task) {
        delegate.set(Objects.requireNonNull(task, "task"));
    }

    /**
     * 設定取消時的額外動作（例如流程票據取消進行中的階段）。
     *
     * <p>額外動作必須冪等；取消路徑保證最多觸發一次有效終態寫入，
     * 但底層取消與額外動作都可能重複執行。</p>
     *
     * @param action 額外動作；不可為 null
     */
    void setOnCancel(Runnable action) {
        onCancel.set(Objects.requireNonNull(action, "action"));
    }

    /**
     * 嘗試寫入終態（測試與內部路徑用，once 語意）。
     *
     * <p>寫入生效時觸發 {@link #setOnSettle(Runnable)} 註冊的收尾
     * （例如作用域解除追蹤），且恰好觸發一次。</p>
     *
     * @param result 終態；不可為 null
     * @return 本次寫入生效為 true
     */
    boolean trySettle(TaskResult<T> result) {
        if (source.tryComplete(Objects.requireNonNull(result, "result"))) {
            onSettle.get().run();
            return true;
        }
        return false;
    }

    /**
     * 設定終態寫入生效時的收尾動作（例如作用域解除追蹤）。
     *
     * <p>恰好觸發一次（首次寫入生效時）；從未寫入則永不觸發。</p>
     *
     * @param action 收尾動作；不可為 null
     */
    void setOnSettle(Runnable action) {
        onSettle.set(Objects.requireNonNull(action, "action"));
    }

    /**
     * 以指定紀錄寫入取消終態（作用域關閉路徑用）。
     *
     * @param record 取消原因紀錄；顯式取消可為 null
     */
    void settleCancelled(TaskErrorRecord record) {
        trySettle(TaskResult.cancelled(record));
    }

    @Override
    public void cancel() {
        trySettle(TaskResult.cancelled(null));
        cancelUnderlying();
    }

    /**
     * 取消底層派送與額外動作（不寫入終態）。
     *
     * <p>供作用域關閉路徑使用：終態已事先統一寫入，此處只負責停掉底層任務。
     * 必須冪等。</p>
     */
    void cancelUnderlying() {
        ScheduledTask current = delegate.get();
        if (current != null) {
            current.cancel();
        }
        onCancel.get().run();
    }

    @Override
    public boolean isCancelled() {
        TaskResult<T> settled = source.peek();
        if (settled != null) {
            return settled.isCancelled() || settled.isRejected();
        }
        ScheduledTask current = delegate.get();
        return current != null && current.isCancelled();
    }

    @Override
    public CompletionStage<TaskResult<T>> stage() {
        return source.stage();
    }

    @Override
    public TaskResult<T> await(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        if (timeout <= 0L) {
            throw new IllegalArgumentException("timeout must be > 0, got: " + timeout);
        }
        Objects.requireNonNull(unit, "unit");
        try {
            return source.await(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    @Override
    public boolean isDone() {
        return source.isDone();
    }

    @Override
    public TaskTicket<T> whenComplete(Consumer<? super TaskResult<T>> action) {
        Objects.requireNonNull(action, "action");
        source.stage().whenComplete((result, failure) -> action.accept(result));
        return this;
    }

    @Override
    public JavaPlugin getPlugin() {
        return plugin;
    }

    @Override
    public TaskType getType() {
        return type;
    }

    @Override
    public long getCreationTick() {
        return creationTick;
    }
}
