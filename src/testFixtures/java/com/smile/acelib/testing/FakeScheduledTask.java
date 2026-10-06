package com.smile.acelib.testing;

import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskType;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 假任務句柄（{@link FakeSafeScheduler} 回傳）。
 *
 * <p>語意與 production {@link ScheduledTask} 對齊：{@code cancel()} 冪等；
 * 已取消（含派送前被拒）的任務 {@code isCancelled()} 為 true；執行完成的
 * 一次性任務不算取消。</p>
 *
 * @since 1.4.0
 */
public final class FakeScheduledTask implements ScheduledTask {

    private final JavaPlugin plugin;
    private final TaskType type;
    private final long creationTick;
    private final Runnable onCancel;
    private volatile boolean cancelled;
    private volatile boolean done;

    FakeScheduledTask(JavaPlugin plugin, TaskType type, long creationTick,
            Runnable onCancel) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.type = Objects.requireNonNull(type, "type");
        this.creationTick = creationTick;
        this.onCancel = onCancel;
    }

    /** 派送前即被拒絕的 cancelled no-op 任務（玩家離線／實體退休／停用等）。 */
    static FakeScheduledTask rejected(JavaPlugin plugin, TaskType type, long creationTick) {
        FakeScheduledTask task = new FakeScheduledTask(plugin, type, creationTick, null);
        task.cancelled = true;
        return task;
    }

    /** 標記一次性任務已執行完成（完成不算取消）。 */
    void markDone() {
        done = true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (onCancel != null) {
            onCancel.run();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** @return 任務是否已執行完成（一次性任務） */
    public boolean isDone() {
        return done;
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
