package com.smile.acelib.testing;

import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskTicket;
import com.smile.acelib.scheduler.TaskType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 假任務票據（{@link FakeTaskScope} 回傳）。
 *
 * <p>語意與生產側 {@code TaskTicket} 對齊：終態只完成一次；
 * 完成後取消不改變終態；拒派票據建立當下即已完成。</p>
 */
final class FakeTaskTicket<T> implements TaskTicket<T> {

    private final JavaPlugin plugin;
    private final TaskType type;
    private final long creationTick;
    private final CompletableFuture<TaskResult<T>> future = new CompletableFuture<>();

    FakeTaskTicket(JavaPlugin plugin, TaskType type, long creationTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.type = Objects.requireNonNull(type, "type");
        this.creationTick = creationTick;
    }

    /**
     * 寫入終態（只完成一次，後寫者被丟棄）。
     *
     * @param result 終態；不可為 null
     * @return 本次寫入生效為 true
     */
    boolean trySettle(TaskResult<T> result) {
        return future.complete(Objects.requireNonNull(result, "result"));
    }

    @Override
    public void cancel() {
        future.complete(TaskResult.cancelled(null));
    }

    @Override
    public boolean isCancelled() {
        TaskResult<T> settled = future.getNow(null);
        if (settled != null) {
            return settled.isCancelled() || settled.isRejected();
        }
        return false;
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
        } catch (java.util.concurrent.ExecutionException impossible) {
            throw new IllegalStateException("fake completion completed exceptionally", impossible);
        }
    }

    @Override
    public boolean isDone() {
        return future.isDone();
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
