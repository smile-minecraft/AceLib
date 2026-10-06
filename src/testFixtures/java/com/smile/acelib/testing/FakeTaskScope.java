package com.smile.acelib.testing;

import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskOutcome;
import com.smile.acelib.scheduler.TaskResult;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import com.smile.acelib.scheduler.TaskType;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 假作用域群組（{@link FakeSafeScheduler#scopeFor} 回傳，下游單元測試用）。
 *
 * <p>全程同步、deterministic：任務派送當下立即執行並完成；離線／退休／
 * 停用／失活一律即時拒派並記對應代碼。退服競態這類時序情境由生產實作的
 * MockBukkit 測試覆蓋，本類別鎖定可觀察語意的一致性。</p>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 *
 * @since 1.4.0
 */
public final class FakeTaskScope implements TaskScope {

    private final FakeSafeScheduler scheduler;
    private final JavaPlugin plugin;
    private final Player player;
    private final Entity entity;
    private boolean active = true;

    FakeTaskScope(FakeSafeScheduler scheduler, JavaPlugin plugin, Player player) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.player = Objects.requireNonNull(player, "player");
        this.entity = null;
    }

    FakeTaskScope(FakeSafeScheduler scheduler, JavaPlugin plugin, Entity entity) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.player = null;
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    /**
     * 模擬擁有者離場（退服／退休）：作用域永久失效，後續派送一律拒派。
     */
    public void deactivate() {
        active = false;
    }

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public boolean isActive() {
        return active && !scheduler.isDisabled();
    }

    @Override
    public TaskTicket<Void> run(Runnable action) {
        Objects.requireNonNull(action, "action");
        return supply(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> TaskTicket<T> supply(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return execute(TaskType.PLAYER, action);
    }

    @Override
    public <T> TaskTicket<T> supplyAsync(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        // 假排程器不開執行緒：同步執行，保證 deterministic。
        return execute(TaskType.ASYNC, action);
    }

    @Override
    public <T, R> TaskTicket<R> pipeline(Supplier<T> read,
                                        Function<? super T, ? extends R> compute,
                                        Consumer<? super R> reply) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(compute, "compute");
        Objects.requireNonNull(reply, "reply");
        FakeTaskTicket<R> flow = new FakeTaskTicket<>(plugin, TaskType.PLAYER, scheduler.currentTick());
        TaskTicket<T> readTicket = execute(TaskType.ASYNC, read);
        TaskResult<T> readResult = readTicket.stage().toCompletableFuture().join();
        if (readResult.outcome() != TaskOutcome.COMPLETED) {
            flow.trySettle(propagate(readResult));
            return flow;
        }
        T readValue = readResult.value();
        TaskTicket<R> computeTicket = execute(TaskType.ASYNC, () -> compute.apply(readValue));
        TaskResult<R> computeResult = computeTicket.stage().toCompletableFuture().join();
        if (computeResult.outcome() != TaskOutcome.COMPLETED) {
            flow.trySettle(computeResult);
            return flow;
        }
        R computed = computeResult.value();
        TaskTicket<R> replyTicket = execute(TaskType.PLAYER, () -> {
            reply.accept(computed);
            return computed;
        });
        flow.trySettle(replyTicket.stage().toCompletableFuture().join());
        return flow;
    }

    @Override
    public void cancelAll() {
        // 全同步執行：沒有待執行任務；作用域保持可用。
    }

    @Override
    public int pendingCount() {
        return 0;
    }

    private <T> TaskTicket<T> execute(TaskType type, Supplier<T> action) {
        FakeTaskTicket<T> ticket = new FakeTaskTicket<>(plugin, type, scheduler.currentTick());
        TaskErrorRecord refusal = refusalOrNull(type);
        if (refusal != null) {
            scheduler.recordForScope(refusal);
            ticket.trySettle(TaskResult.rejected(refusal));
            return ticket;
        }
        try {
            T value = action.get();
            ticket.trySettle(TaskResult.completed(value));
        } catch (RuntimeException | Error failure) {
            TaskErrorRecord threw = TaskErrorRecord.threw(
                type, "ACELIB-SCHED-001", "user task threw: " + failure.getMessage(), failure);
            scheduler.recordForScope(threw);
            ticket.trySettle(TaskResult.failed(failure, threw));
        }
        return ticket;
    }

    private TaskErrorRecord refusalOrNull(TaskType type) {
        if (!isActive()) {
            if (scheduler.isDisabled()) {
                return TaskErrorRecord.cancelled(
                    type, "ACELIB-SCHED-006", "scheduler is disabled");
            }
            return TaskErrorRecord.cancelled(
                type, player != null ? "ACELIB-SCHED-002" : "ACELIB-SCHED-003",
                "scope is closed");
        }
        if (player != null && scheduler.isOfflineForScope(player)) {
            return TaskErrorRecord.cancelled(type, "ACELIB-SCHED-002",
                "player is offline (uuid=" + safeId() + ")");
        }
        if (entity != null && scheduler.isRetiredForScope(entity)) {
            return TaskErrorRecord.cancelled(type, "ACELIB-SCHED-003",
                "entity is retired (uuid=" + safeId() + ")");
        }
        return null;
    }

    private String safeId() {
        try {
            if (player != null) {
                return String.valueOf(player.getUniqueId());
            }
            return String.valueOf(entity.getUniqueId());
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    private static <T, R> TaskResult<R> propagate(TaskResult<T> result) {
        return switch (result.outcome()) {
            case FAILED -> TaskResult.failed(result.cause(), result.errorRecord());
            case CANCELLED -> TaskResult.cancelled(result.errorRecord());
            case REJECTED -> TaskResult.rejected(result.errorRecord());
            case COMPLETED -> throw new IllegalArgumentException(
                "completed result carries a value and cannot propagate as-is");
        };
    }
}
