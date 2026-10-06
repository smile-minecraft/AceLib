package com.smile.acelib.testing;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 可控假排程器（下游單元測試用）。
 *
 * <p>取代各下游自寫的排程 stub：以虛擬 tick 驅動延遲／週期任務，全程同步、
 * deterministic，不依賴平台排程器。生命週期語意與 production
 * {@code SafeSchedulerImpl} 對齊：</p>
 * <ul>
 *   <li>{@code runGlobal}／{@code runAsync} 立即同步執行</li>
 *   <li>{@code runLater}／{@code runTimer}／{@code runForPlayerLater} 進入虛擬
 *       佇列，由 {@link #advanceTicks(long)} 推進（{@code delay = 0} 視為下一個 tick）</li>
 *   <li>玩家離線 → cancelled no-op＋{@code ACELIB-SCHED-002}；
 *       實體退休 → cancelled no-op＋{@code ACELIB-SCHED-003}；
 *       chunk 未載入 → cancelled no-op＋{@code ACELIB-SCHED-004}；
 *       停用後派送 → cancelled no-op＋{@code ACELIB-SCHED-006}</li>
 * </ul>
 *
 * <h2>在線／退休／載入判定</h2>
 * <p>顯式標記（{@link #markPlayerOffline}、{@link #retire}、
 * {@link #setChunkLoaded}）優先；未標記時讀取 Bukkit 即時狀態（與 production
 * 一致）。注意 Mockito 預設 stub：未 stub 的 {@code Player} 其
 * {@code isOnline()} 回 false、未 stub 的 {@code Entity} 其 {@code isValid()}
 * 回 false——此類 mock 會被視為離線／退休，請顯式 stub 或改用標記。</p>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 *
 * @since 1.4.0
 */
public final class FakeSafeScheduler implements SafeScheduler {

    /** 一個 tick 對應的毫秒數（推進 tick 時同步推進時鐘）。 */
    public static final long MILLIS_PER_TICK = 50L;

    private final JavaPlugin plugin;
    private final FakeClock clock;
    private final List<TaskErrorRecord> errors = new ArrayList<>();
    private final List<PendingEntry> queue = new ArrayList<>();
    private final Set<UUID> offlinePlayers = new HashSet<>();
    private final Set<UUID> retiredEntities = new HashSet<>();
    private final Map<String, Boolean> chunkOverrides = new HashMap<>();
    private long tick;
    private boolean disabled;

    /**
     * @param plugin 任務 owner；不可為 null（测试可用 Mockito mock 或 MockBukkit plugin）
     * @param clock  虛擬時鐘；不可為 null（推進 tick 時同步推進此鐘）
     */
    public FakeSafeScheduler(JavaPlugin plugin, FakeClock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -----------------------------------------------------------------
    // 虛擬時間
    // -----------------------------------------------------------------

    /** @return 目前虛擬 tick（millis／tick 起點皆為時鐘初值對應的 0） */
    public long currentTick() {
        return tick;
    }

    /**
     * 推進虛擬 tick 並執行到期任務（含週期任務的當輪觸發）。
     *
     * @param ticks 推進 tick 數；必須 {@code >= 0}
     * @throws IllegalArgumentException tick 數為負
     */
    public void advanceTicks(long ticks) {
        if (ticks < 0L) {
            throw new IllegalArgumentException("ticks must be >= 0, got: " + ticks);
        }
        for (long i = 0L; i < ticks; i++) {
            tick++;
            clock.advanceMillis(MILLIS_PER_TICK);
            runDue();
        }
    }

    /** @return 目前佇列中待執行的任務數（已取消的不計入） */
    public int pendingTaskCount() {
        int count = 0;
        for (PendingEntry entry : queue) {
            if (!entry.task.isCancelled()) {
                count++;
            }
        }
        return count;
    }

    // -----------------------------------------------------------------
    // 生命週期與狀態標記
    // -----------------------------------------------------------------

    /**
     * 停用排程器（模擬 plugin disable）：後續派送一律回 cancelled no-op 並記
     * {@code ACELIB-SCHED-006}；已排入的待執行任務保持待執行（與 production
     * {@code cancelAll} 語意區分——停用本身不等於取消既有任務）。
     */
    public void disable() {
        disabled = true;
    }

    /** @return 是否已停用 */
    public boolean isDisabled() {
        return disabled;
    }

    /** 標記玩家離線（後續該玩家任務回 cancelled＋SCHED-002）。 */
    public void markPlayerOffline(UUID playerUuid) {
        offlinePlayers.add(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    /** 解除玩家離線標記。 */
    public void markPlayerOnline(UUID playerUuid) {
        offlinePlayers.remove(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    /**
     * 標記實體退休（後續該實體任務回 cancelled＋SCHED-003，模擬 Folia
     * entity scheduler 回 null／retired callback 語意）。
     */
    public void retire(Entity entity) {
        retiredEntities.add(Objects.requireNonNull(entity, "entity").getUniqueId());
    }

    /** 解除實體退休標記。 */
    public void revive(Entity entity) {
        retiredEntities.remove(Objects.requireNonNull(entity, "entity").getUniqueId());
    }

    /**
     * 覆寫指定位置所在 chunk 的載入狀態。
     *
     * @param location 目標位置；不可為 null
     * @param loaded   是否視為已載入
     */
    public void setChunkLoaded(Location location, boolean loaded) {
        chunkOverrides.put(chunkKey(Objects.requireNonNull(location, "location")), loaded);
    }

    // -----------------------------------------------------------------
    // SafeScheduler
    // -----------------------------------------------------------------

    @Override
    public ScheduledTask runGlobal(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        if (disabled) {
            return reject(TaskType.GLOBAL, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        FakeScheduledTask task = new FakeScheduledTask(plugin, TaskType.GLOBAL, tick, null);
        runGuarded(TaskType.GLOBAL, runnable);
        task.markDone();
        return task;
    }

    @Override
    public ScheduledTask runAsync(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        if (disabled) {
            return reject(TaskType.ASYNC, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        // 假排程器不開執行緒：同步執行，保證 deterministic。
        FakeScheduledTask task = new FakeScheduledTask(plugin, TaskType.ASYNC, tick, null);
        runGuarded(TaskType.ASYNC, runnable);
        task.markDone();
        return task;
    }

    @Override
    public ScheduledTask runLater(Runnable runnable, long delayTicks) {
        Objects.requireNonNull(runnable, "runnable");
        requireNonNegative(delayTicks, "delayTicks");
        if (disabled) {
            return reject(TaskType.LATER, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        return enqueue(TaskType.LATER, runnable, tick + Math.max(delayTicks, 1L), 0L);
    }

    @Override
    public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(runnable, "runnable");
        requireNonNegative(delayTicks, "delayTicks");
        if (periodTicks <= 0L) {
            throw new IllegalArgumentException(
                "periodTicks must be > 0, got: " + periodTicks);
        }
        if (disabled) {
            return reject(TaskType.TIMER, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        return enqueue(TaskType.TIMER, runnable, tick + Math.max(delayTicks, 1L), periodTicks);
    }

    @Override
    public ScheduledTask runForPlayer(Player player, Runnable runnable) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(runnable, "runnable");
        if (isOffline(player)) {
            record(TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002",
                "player is offline (uuid=" + safeUuid(player) + ")"));
            return FakeScheduledTask.rejected(plugin, TaskType.PLAYER, tick);
        }
        if (disabled) {
            return reject(TaskType.PLAYER, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        FakeScheduledTask task = new FakeScheduledTask(plugin, TaskType.PLAYER, tick, null);
        runGuarded(TaskType.PLAYER, runnable);
        task.markDone();
        return task;
    }

    @Override
    public ScheduledTask runForPlayerLater(Player player, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(runnable, "runnable");
        if (isOffline(player)) {
            record(TaskErrorRecord.cancelled(TaskType.PLAYER_LATER, "ACELIB-SCHED-002",
                "player is offline (uuid=" + safeUuid(player) + ")"));
            return FakeScheduledTask.rejected(plugin, TaskType.PLAYER_LATER, tick);
        }
        requireNonNegative(delayTicks, "delayTicks");
        if (disabled) {
            return reject(TaskType.PLAYER_LATER, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        return enqueue(TaskType.PLAYER_LATER, runnable, tick + Math.max(delayTicks, 1L), 0L);
    }

    @Override
    public ScheduledTask runForEntity(Entity entity, Runnable runnable) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(runnable, "runnable");
        if (isRetired(entity)) {
            record(TaskErrorRecord.cancelled(TaskType.ENTITY, "ACELIB-SCHED-003",
                "entity is retired (uuid=" + safeEntityUuid(entity) + ")"));
            return FakeScheduledTask.rejected(plugin, TaskType.ENTITY, tick);
        }
        if (disabled) {
            return reject(TaskType.ENTITY, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        FakeScheduledTask task = new FakeScheduledTask(plugin, TaskType.ENTITY, tick, null);
        runGuarded(TaskType.ENTITY, runnable);
        task.markDone();
        return task;
    }

    @Override
    public ScheduledTask runAtLocation(Location location, Runnable runnable) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(runnable, "runnable");
        if (!isChunkLoaded(location)) {
            record(TaskErrorRecord.cancelled(TaskType.LOCATION, "ACELIB-SCHED-004",
                "chunk not loaded (world=" + safeWorld(location) + ")"));
            return FakeScheduledTask.rejected(plugin, TaskType.LOCATION, tick);
        }
        if (disabled) {
            return reject(TaskType.LOCATION, "ACELIB-SCHED-006", "scheduler is disabled");
        }
        FakeScheduledTask task = new FakeScheduledTask(plugin, TaskType.LOCATION, tick, null);
        runGuarded(TaskType.LOCATION, runnable);
        task.markDone();
        return task;
    }

    @Override
    public List<TaskErrorRecord> getRecorderErrors(int max) {
        if (max <= 0) {
            return List.of();
        }
        int from = Math.max(0, errors.size() - max);
        return Collections.unmodifiableList(new ArrayList<>(errors.subList(from, errors.size())));
    }

    @Override
    public TaskScope scopeFor(Player player) {
        Objects.requireNonNull(player, "player");
        return new FakeTaskScope(this, plugin, player);
    }

    @Override
    public TaskScope scopeFor(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return new FakeTaskScope(this, plugin, entity);
    }

    /**
     * 作用域紀錄寫入（{@link FakeTaskScope} 共用同一條錯誤清單）。
     *
     * @param record 要寫入的紀錄；不可為 null
     */
    void recordForScope(TaskErrorRecord record) {
        record(Objects.requireNonNull(record, "record"));
    }

    /**
     * 玩家離線判定（{@link FakeTaskScope} 派送前檢查用）。
     *
     * @param player 目標玩家；不可為 null
     * @return 離線為 true
     */
    boolean isOfflineForScope(Player player) {
        return isOffline(player);
    }

    /**
     * 實體退休判定（{@link FakeTaskScope} 派送前檢查用）。
     *
     * @param entity 目標實體；不可為 null
     * @return 退休為 true
     */
    boolean isRetiredForScope(Entity entity) {
        return isRetired(entity);
    }

    @Override
    public void cancelAll() {
        List<PendingEntry> snapshot = new ArrayList<>(queue);
        for (PendingEntry entry : snapshot) {
            try {
                entry.task.cancel();
            } catch (RuntimeException cancelFailure) {
                record(TaskErrorRecord.cancelled(entry.task.getType(), "ACELIB-SCHED-006",
                    "cancelAll: task cancel failed: " + cancelFailure.getMessage()));
            }
        }
        queue.removeIf(entry -> entry.task.isCancelled());
    }

    // -----------------------------------------------------------------
    // 內部
    // -----------------------------------------------------------------

    private FakeScheduledTask enqueue(TaskType type, Runnable runnable, long dueTick, long period) {
        PendingEntry entry = new PendingEntry(runnable, dueTick, period);
        FakeScheduledTask task = new FakeScheduledTask(plugin, type, tick,
            () -> queue.remove(entry));
        entry.task = task;
        queue.add(entry);
        return task;
    }

    private void runDue() {
        List<PendingEntry> due = new ArrayList<>();
        for (PendingEntry entry : queue) {
            if (!entry.task.isCancelled() && entry.dueTick <= tick) {
                due.add(entry);
            }
        }
        for (PendingEntry entry : due) {
            if (entry.task.isCancelled()) {
                continue;
            }
            runGuarded(entry.task.getType(), entry.runnable);
            if (entry.periodTicks > 0L && !entry.task.isCancelled()) {
                entry.dueTick = tick + entry.periodTicks;
            } else {
                entry.task.markDone();
                queue.remove(entry);
            }
        }
        queue.removeIf(entry -> entry.task.isCancelled() && entry.periodTicks <= 0L);
    }

    private void runGuarded(TaskType type, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException | Error taskFailure) {
            record(TaskErrorRecord.threw(type, "ACELIB-SCHED-001",
                "user task threw: " + taskFailure.getMessage(), taskFailure));
        }
    }

    private FakeScheduledTask reject(TaskType type, String code, String detail) {
        record(TaskErrorRecord.cancelled(type, code, detail));
        return FakeScheduledTask.rejected(plugin, type, tick);
    }

    private void record(TaskErrorRecord record) {
        errors.add(record);
    }

    private boolean isOffline(Player player) {
        try {
            if (offlinePlayers.contains(player.getUniqueId())) {
                return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        try {
            return !player.isOnline();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean isRetired(Entity entity) {
        try {
            if (retiredEntities.contains(entity.getUniqueId())) {
                return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        try {
            return entity.isDead() || !entity.isValid();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean isChunkLoaded(Location location) {
        Boolean override = chunkOverrides.get(chunkKey(location));
        if (override != null) {
            return override;
        }
        try {
            var world = location.getWorld();
            return world != null
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String chunkKey(Location location) {
        var world = location.getWorld();
        String worldName;
        try {
            worldName = world == null ? "<null>" : world.getName();
        } catch (RuntimeException ignored) {
            worldName = "<unknown>";
        }
        return worldName + ":" + (location.getBlockX() >> 4) + "," + (location.getBlockZ() >> 4);
    }

    private static String safeUuid(Player player) {
        try {
            return String.valueOf(player.getUniqueId());
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    private static String safeEntityUuid(Entity entity) {
        try {
            return String.valueOf(entity.getUniqueId());
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    private static String safeWorld(Location location) {
        try {
            var world = location.getWorld();
            return world == null ? "<null>" : world.getName();
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0, got: " + value);
        }
    }

    private static final class PendingEntry {
        final Runnable runnable;
        final long periodTicks;
        long dueTick;
        FakeScheduledTask task;

        PendingEntry(Runnable runnable, long dueTick, long periodTicks) {
            this.runnable = runnable;
            this.dueTick = dueTick;
            this.periodTicks = periodTicks;
        }
    }
}
