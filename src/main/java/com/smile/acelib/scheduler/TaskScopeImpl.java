package com.smile.acelib.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link TaskScope} 的內部實作（Internal）。
 *
 * <p>一個實例綁定一位玩家或一個實體，持有群組內尚未結束的票據：
 * {@link #cancelAll()} 取消它們但保持可用；{@link #deactivate(TaskErrorRecord)}
 * 永久關閉作用域（退服／退休／停用），之後的新派送一律拒派。</p>
 *
 * <p>退服與退休的「主動」通知來自 {@link ScopeListener}（退服事件、
 * 實體移除事件）；事件非同步送達的空窗由每次派送前與回呼執行前的
 * {@link #checkAvailable(TaskType)} 補上，退服後保證不執行使用者程式。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class TaskScopeImpl implements TaskScope {

    /**
     * 作用域擁有者種類。
     */
    enum Kind {
        PLAYER,
        ENTITY
    }

    private final SafeSchedulerImpl scheduler;
    private final JavaPlugin plugin;
    private final Kind kind;
    private final UUID ownerId;
    private final Player playerRef;
    private final Entity entityRef;
    private final Set<TicketTask<?>> live = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private volatile TaskErrorRecord closeRecord;

    TaskScopeImpl(SafeSchedulerImpl scheduler, JavaPlugin plugin, Kind kind,
                  Player player, Entity entity, boolean active, TaskErrorRecord closeRecord) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind == Kind.PLAYER) {
            this.playerRef = Objects.requireNonNull(player, "player");
            this.entityRef = null;
            this.ownerId = Objects.requireNonNull(player.getUniqueId(), "player uuid");
        } else {
            this.playerRef = null;
            this.entityRef = Objects.requireNonNull(entity, "entity");
            this.ownerId = Objects.requireNonNull(entity.getUniqueId(), "entity uuid");
        }
        this.active = active;
        this.closeRecord = closeRecord;
    }

    private TaskType ownerType() {
        return kind == Kind.PLAYER ? TaskType.PLAYER : TaskType.ENTITY;
    }

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public TaskTicket<Void> run(Runnable action) {
        Objects.requireNonNull(action, "action");
        return scheduler.dispatchScoped(ownerType(), playerRef, entityRef,
            () -> {
                action.run();
                return null;
            }, this);
    }

    @Override
    public <T> TaskTicket<T> supply(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return scheduler.dispatchScoped(ownerType(), playerRef, entityRef, action, this);
    }

    @Override
    public <T> TaskTicket<T> supplyAsync(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return scheduler.dispatchScoped(TaskType.ASYNC, null, null, action, this);
    }

    @Override
    public <T, R> TaskTicket<R> pipeline(Supplier<T> read,
                                        Function<? super T, ? extends R> compute,
                                        Consumer<? super R> reply) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(compute, "compute");
        Objects.requireNonNull(reply, "reply");
        TaskType replyType = ownerType();
        TaskCompletionSource<R> out = new TaskCompletionSource<>();
        TicketTask<R> flow =
            new TicketTask<>(plugin, replyType, SafeSchedulerImpl.currentTick(), out);
        flow.setOnSettle(() -> live.remove(flow));
        if (!active) {
            // 失活群組的拒派：關閉紀錄必不為 null（deactivate 先寫紀錄再標失活）；
            // 防禦性 fallback 避免任何重排序窗口把 null 紀錄傳給拒派工廠。
            TaskErrorRecord closed = closeRecordOrNull();
            if (closed == null) {
                closed = TaskErrorRecord.cancelled(
                    replyType, SafeSchedulerImpl.ERR_PLUGIN_DISABLED,
                    "scheduler is disabled");
                scheduler.recordAndNotify(closed);
            }
            out.tryComplete(TaskResult.rejected(closed));
            return flow;
        }
        TaskErrorRecord unavailable = checkAvailable(replyType);
        if (unavailable != null) {
            scheduler.recordAndNotify(unavailable);
            out.tryComplete(TaskResult.rejected(unavailable));
            return flow;
        }
        AtomicReference<TaskTicket<?>> current = new AtomicReference<>();
        flow.setOnCancel(() -> {
            TaskTicket<?> stage = current.get();
            if (stage != null) {
                stage.cancel();
            }
        });
        live.add(flow);
        TaskTicket<T> readTicket =
            scheduler.dispatchScoped(TaskType.ASYNC, null, null, read, this);
        trackStage(current, out, readTicket);
        readTicket.stage().whenComplete((readResult, readFailure) -> {
            if (readResult == null) {
                // 不可達：完成單元永遠只寫入正常終態；防禦性返回避免流程懸空。
                return;
            }
            if (readResult.outcome() != TaskOutcome.COMPLETED) {
                out.tryComplete(downcast(readResult));
                return;
            }
            if (!stageClear(out, replyType)) {
                return;
            }
            TaskTicket<R> computeTicket = scheduler.dispatchScoped(TaskType.ASYNC, null, null,
                () -> compute.apply(readResult.value()), this);
            trackStage(current, out, computeTicket);
            computeTicket.stage().whenComplete((computeResult, computeFailure) -> {
                if (computeResult == null) {
                    // 不可達：完成單元永遠只寫入正常終態；防禦性返回避免流程懸空。
                    return;
                }
                if (computeResult.outcome() != TaskOutcome.COMPLETED) {
                    out.tryComplete(computeResult);
                    return;
                }
                if (!stageClear(out, replyType)) {
                    return;
                }
                R computed = computeResult.value();
                TaskTicket<R> replyTicket = scheduler.dispatchScoped(replyType, playerRef,
                    entityRef,
                    () -> {
                        reply.accept(computed);
                        return computed;
                    }, this);
                trackStage(current, out, replyTicket);
                replyTicket.stage().whenComplete((replyOutcome, replyFailure) -> {
                    if (replyOutcome != null) {
                        out.tryComplete(replyOutcome);
                    }
                });
            });
        });
        return flow;
    }

    @Override
    public synchronized void cancelAll() {
        List<TicketTask<?>> snapshot = new ArrayList<>(live);
        for (TicketTask<?> ticket : snapshot) {
            ticket.cancel();
        }
        prune();
    }

    @Override
    public int pendingCount() {
        prune();
        return live.size();
    }

    /**
     * 永久關閉作用域（退服／退休／停用路徑）。
     *
     * <p>先統一以關閉紀錄寫入所有存活票據的取消終態，再停掉底層任務：
     * 兩階段確保票據攜帶的是關閉原因，而非通用的無紀錄取消。
     * 重複呼叫無效（第一次關閉保留）。關閉後向 scheduler 除名。</p>
     *
     * <p>寫入順序：先寫關閉紀錄、再標失活。兩次寫入皆為 {@code volatile}，
     * 依程式順序對其他執行緒可見；觀察到失活的呼叫必能讀到非 null 的關閉紀錄，
     * 派送期的拒派分支才不會寫入空紀錄。</p>
     *
     * <p>持鎖注意：本方法持有作用域內部鎖，並在其保護下同步觸發
     * 終態下游回呼（{@code CompletableFuture} 的 {@code whenComplete} 鏈）。
     * 回呼實際跑在呼叫本方法的執行緒上（退服／退休事件執行緒或停用執行緒）；
     * 回呼內避免阻塞等待其他執行緒完成「需要同一把作用域鎖」的路徑
     * （例如 {@code await} 另一個正在呼叫本作用域 {@code cancelAll()}／
     * 關閉路徑的執行緒）；同一執行緒在回呼內直接呼叫 {@code cancelAll()}
     * 不會死鎖（鎖可重入，且此時存活集合已清空，為 no-op）。
     * 需要回到擁有者執行緒的操作請重新派送。</p>
     *
     * @param record 關閉原因紀錄；不可為 null
     */
    synchronized void deactivate(TaskErrorRecord record) {
        Objects.requireNonNull(record, "record");
        if (!active) {
            return;
        }
        closeRecord = record;
        active = false;
        scheduler.recordAndNotify(record);
        List<TicketTask<?>> snapshot = new ArrayList<>(live);
        live.clear();
        for (TicketTask<?> ticket : snapshot) {
            ticket.settleCancelled(record);
        }
        for (TicketTask<?> ticket : snapshot) {
            ticket.cancelUnderlying();
        }
        scheduler.forgetScope(this);
    }

    /**
     * 檢查擁有者當下是否可用（派送前與回呼執行前的守衛）。
     *
     * @param type 任務類型（供錯誤紀錄使用）
     * @return 可用為 null；不可用為對應的取消紀錄
     */
    TaskErrorRecord checkAvailable(TaskType type) {
        try {
            if (kind == Kind.PLAYER) {
                if (playerRef.isOnline()) {
                    return null;
                }
                return TaskErrorRecord.cancelled(type,
                    SafeSchedulerImpl.ERR_PLAYER_OFFLINE,
                    "player is offline (uuid=" + ownerId + ")");
            }
            if (!entityRef.isDead() && entityRef.isValid()) {
                return null;
            }
            return TaskErrorRecord.cancelled(type,
                SafeSchedulerImpl.ERR_ENTITY_INVALID,
                "entity is retired (uuid=" + ownerId + ")");
        } catch (Throwable failure) {
            // 可用性檢查本身失敗時保守視為不可用，交由呼叫端記錄對應代碼。
            return TaskErrorRecord.cancelled(type,
                kind == Kind.PLAYER
                    ? SafeSchedulerImpl.ERR_PLAYER_OFFLINE
                    : SafeSchedulerImpl.ERR_ENTITY_INVALID,
                "owner availability check failed: " + failure);
        }
    }

    /**
     * 關閉紀錄（失活時必不為 null）。
     *
     * @return 關閉原因紀錄；作用域仍可用時為 null
     */
    TaskErrorRecord closeRecordOrNull() {
        return closeRecord;
    }

    /**
     * 指定玩家是否為本作用域擁有者（退服事件分派用）。
     *
     * @param id 事件中的玩家識別碼
     * @return 擁有者相符為 true
     */
    boolean ownsPlayer(UUID id) {
        try {
            return kind == Kind.PLAYER && ownerId.equals(id);
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 指定實體是否為本作用域擁有者（退休事件分派用）。
     *
     * @param id 事件中的實體識別碼
     * @return 擁有者相符為 true
     */
    boolean ownsEntity(UUID id) {
        try {
            return kind == Kind.ENTITY && ownerId.equals(id);
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 登記存活票據（派送成功後）。
     *
     * @param ticket 存活票據；不可為 null
     */
    void track(TicketTask<?> ticket) {
        live.add(Objects.requireNonNull(ticket, "ticket"));
    }

    /**
     * 解除票據追蹤（終態寫入時由收尾觸發）。
     *
     * @param ticket 已結束的票據
     */
    void untrack(TicketTask<?> ticket) {
        live.remove(ticket);
    }

    private void prune() {
        live.removeIf(TicketTask::isDone);
    }

    /**
     * 階段登記後的複查：登記並立刻檢查流程是否已結束。
     *
     * <p>關閉回呼（{@code onCancel}）讀到的永遠是「上一個」階段：
     * 流程取消若正好落在「新階段派送返回、登記完成」之間，
     * 回呼來不及取消新階段。登記後立刻複查流程終態，
     * 已結束就直接取消剛登記的階段——該階段以取消收斂，
     * 不覆寫流程既有終態；使用者程式保證不執行
     * （底層取消擋下待執行回呼，執行前守衛擋下漏網執行）。</p>
     *
     * @param current 已登記階段持有者（取消回呼讀取用）
     * @param out     流程完成單元
     * @param staged  剛派送的階段票據
     */
    private static void trackStage(AtomicReference<TaskTicket<?>> current,
                                   TaskCompletionSource<?> out,
                                   TaskTicket<?> staged) {
        current.set(staged);
        if (out.isDone()) {
            staged.cancel();
        }
    }

    /**
     * 階段銜接前的守衛：流程已結束或作用域已關閉時寫入取消終態並停止銜接。
     *
     * @param out       流程完成單元
     * @param ownerType 擁有者任務類型（供紀錄使用）
     * @param <R> 流程值型別
     * @return 可繼續銜接為 true
     */
    private <R> boolean stageClear(TaskCompletionSource<R> out, TaskType ownerType) {
        if (out.isDone()) {
            return false;
        }
        if (!active) {
            out.tryComplete(TaskResult.cancelled(closeRecord));
            return false;
        }
        TaskErrorRecord unavailable = checkAvailable(ownerType);
        if (unavailable != null) {
            scheduler.recordAndNotify(unavailable);
            out.tryComplete(TaskResult.cancelled(unavailable));
            return false;
        }
        return true;
    }

    /**
     * 非完成終態跨值型別傳遞（流程階段失敗／取消／拒派時）。
     *
     * @param result 非完成終態；不可為 null 且不得為完成
     * @param <T> 來源值型別
     * @param <R> 目標值型別
     * @return 同語意的目標型別終態
     */
    private static <T, R> TaskResult<R> downcast(TaskResult<T> result) {
        Objects.requireNonNull(result, "result");
        return switch (result.outcome()) {
            case FAILED -> TaskResult.failed(result.cause(), result.errorRecord());
            case CANCELLED -> TaskResult.cancelled(result.errorRecord());
            case REJECTED -> TaskResult.rejected(result.errorRecord());
            case COMPLETED -> throw new IllegalArgumentException(
                "completed result carries a value and cannot propagate as-is");
        };
    }
}
