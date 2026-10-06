package com.smile.acelib.world;

import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskTicket;
import com.smile.acelib.scheduler.TaskType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 未經派送即已終結的任務票據（Internal）。
 *
 * <p>延後操作在「派送前就被拒絕」的路徑上使用：玩家已離線、服務已停用、
 * 作用域建立失敗。此時沒有任何底層任務，票據建立當下即攜帶終態；
 * 終態只完成一次，後續取消不改變終態，語意與
 * {@link TaskTicket} 的拒派票據一致。</p>
 *
 * <p>本類別為 package-private，不進 API surface；呼叫端只看到
 * {@link TaskTicket} 介面。注意：未經派送的票據沒有 plugin owner，
 * {@link #getPlugin()} 可能回傳 null（已派送的作用域票據不受影響，
 * 其 owner 永不為 null）。</p>
 *
 * @param <T> 完成時攜回的值型別
 */
final class TerminalTaskTicket<T> implements TaskTicket<T> {

    private final CompletableFuture<TaskResult<T>> future;
    private final JavaPlugin plugin;
    private final TaskType type;
    private final long creationTick;

    private TerminalTaskTicket(TaskResult<T> result, JavaPlugin plugin, TaskType type) {
        this.future = CompletableFuture.completedFuture(
            Objects.requireNonNull(result, "result"));
        this.plugin = plugin;
        this.type = Objects.requireNonNull(type, "type");
        this.creationTick = currentTick();
    }

    /**
     * 建立已完成的拒派票據（派送期拒絕用，不追蹤、不執行任何動作）。
     *
     * @param type 任務類型；不可為 null
     * @param record 拒派原因紀錄；不可為 null
     * @param plugin 作用域 owner；無派送時可為 null
     * @param <T> 值型別
     * @return 已完成拒派終態的票據；永不為 null
     */
    static <T> TaskTicket<T> rejected(TaskType type,
                                      TaskErrorRecord record,
                                      JavaPlugin plugin) {
        return new TerminalTaskTicket<>(TaskResult.rejected(
            Objects.requireNonNull(record, "record")), plugin, type);
    }

    private static long currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @Override
    public void cancel() {
        // 已終結：取消不改變終態（once 語意）。
        future.complete(TaskResult.cancelled(null));
    }

    @Override
    public boolean isCancelled() {
        TaskResult<T> settled = future.getNow(null);
        return settled != null && (settled.isCancelled() || settled.isRejected());
    }

    @Override
    public CompletionStage<TaskResult<T>> stage() {
        return future.minimalCompletionStage();
    }

    @Override
    public TaskResult<T> await(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        if (timeout <= 0L) {
            throw new IllegalArgumentException("timeout must be > 0, got: " + timeout);
        }
        Objects.requireNonNull(unit, "unit");
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.ExecutionException impossible) {
            throw new IllegalStateException("terminal completion completed exceptionally",
                impossible);
        }
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public TaskTicket<T> whenComplete(Consumer<? super TaskResult<T>> action) {
        Objects.requireNonNull(action, "action");
        future.whenComplete((result, failure) -> action.accept(result));
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
