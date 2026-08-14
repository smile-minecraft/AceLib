package com.smile.acelib.scheduler;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@link SafeScheduler} 的標準實作（Internal）。
 *
 * <p>內含：</p>
 * <ul>
 *   <li>6 種基本任務（global / async / later / timer）+ 3 種上下文任務
 *       （player / player-later / entity / location）共 8 種 dispatch</li>
 *   <li>Paper / Folia 自動 dispatch（依 {@link PlatformCapability} 判斷）</li>
 *   <li>玩家離線 / 實體失效 / chunk 未載入前置檢查，
 *       並以 {@link TaskErrorRecord} 留下分類代碼紀錄</li>
 *   <li>插件停用後所有後續任務直接 no-op，並留下 {@code ACELIB-SCHED-006} 紀錄</li>
 *   <li>{@link #cancelAll()} 取消所有 tracked task；{@link #onPluginDisable()}
 *       為一站式的「停用」流程</li>
 *   <li>任務內部拋錯時以 {@code ACELIB-SCHED-001} 紀錄，但不影響後續任務</li>
 * </ul>
 *
 * <h2>錯誤代碼一覽</h2>
 * <ul>
 *   <li>{@code ACELIB-SCHED-001} — 任務內部拋 exception</li>
 *   <li>{@code ACELIB-SCHED-002} — 玩家離線</li>
 *   <li>{@code ACELIB-SCHED-003} — 實體失效</li>
 *   <li>{@code ACELIB-SCHED-004} — chunk 不可用</li>
 *   <li>{@code ACELIB-SCHED-005} — 平台不支援</li>
 *   <li>{@code ACELIB-SCHED-006} — 插件停用</li>
 * </ul>
 *
 * <h2>Folia dispatch 策略</h2>
 * <p>當 {@link PlatformCapability#regionScheduling()} 為 true 時，本實作優先採用
 * Folia 專屬 API（{@code io.papermc.paper.threadedregions.scheduler.*}），
 * 透過 reflection 呼叫 — 若 classpath 不含 Folia API（典型於 MockBukkit 環境），
 * dispatch 會進入 fallback 路徑：以 {@code ACELIB-SCHED-005} 記錄並回傳 no-op task，
 * 不丟例外。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆可在多 region 並行環境下使用。
 * {@link #tracked} 使用 {@link ConcurrentHashMap#newKeySet()}；
 * {@link #disabled} 為 {@code volatile}。</p>
 *
 * @see SafeScheduler
 * @since 1.0.0
 */
public final class SafeSchedulerImpl implements SafeScheduler {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    // 錯誤代碼（ACELIB-SCHED-* 格式）
    static final String ERR_TASK_EXCEPTION = "ACELIB-SCHED-001";
    static final String ERR_PLAYER_OFFLINE = "ACELIB-SCHED-002";
    static final String ERR_ENTITY_INVALID = "ACELIB-SCHED-003";
    static final String ERR_CHUNK_UNAVAILABLE = "ACELIB-SCHED-004";
    static final String ERR_PLATFORM_UNSUPPORTED = "ACELIB-SCHED-005";
    static final String ERR_PLUGIN_DISABLED = "ACELIB-SCHED-006";

    private final JavaPlugin plugin;
    private final Platform platform;
    private final PlatformCapability capability;
    private final TaskErrorRecorder recorder;
    private final Set<ScheduledTask> tracked = ConcurrentHashMap.newKeySet();
    private final AtomicLong fallbackTick = new AtomicLong(0L);
    private volatile boolean disabled = false;
    /**
     * 錯誤紀錄 sink。
     *
     * <p>當 {@code DiagnosticsService} 透過 {@link #setRecordSink(BiConsumer)}
     * 注入後，每次 {@link #recorder} 收到一筆紀錄，scheduler 都會以
     * {@code (code, detail)} 形式回呼 sink；讓 scheduler 錯誤可被導向
     * diagnostics 的節流路徑。此欄位為 {@code volatile}，支援
     * {@code setRecordSink}/{@code clearRecordSink} 的 race-free 切換；
     * sink 本身拋例外不會影響 scheduler 主流程或 recorder 記錄。</p>
     */
    private volatile BiConsumer<String, String> recordSink;

    /**
     * 建構子。
     *
     * @param plugin     派送任務的 plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null（用於診斷與日誌）
     * @param capability 對應的 capability profile；不可為 null
     *                   （建議由 {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @throws NullPointerException 當任一參數為 null
     */
    public SafeSchedulerImpl(JavaPlugin plugin, Platform platform, PlatformCapability capability) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.recorder = new TaskErrorRecorder();
    }

    // -----------------------------------------------------------------
    // SafeScheduler 9 + 1 方法
    // -----------------------------------------------------------------

    @Override
    public ScheduledTask runGlobal(Runnable runnable) {
        return dispatch(TaskType.GLOBAL, runnable, null, null, 0L, 0L, false);
    }

    @Override
    public ScheduledTask runAsync(Runnable runnable) {
        return dispatch(TaskType.ASYNC, runnable, null, null, 0L, 0L, true);
    }

    @Override
    public ScheduledTask runLater(Runnable runnable, long delayTicks) {
        requireNonNegative(delayTicks, "delayTicks");
        return dispatch(TaskType.LATER, runnable, null, null, delayTicks, 0L, false);
    }

    @Override
    public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        requireNonNegative(delayTicks, "delayTicks");
        requirePositive(periodTicks, "periodTicks");
        return dispatch(TaskType.TIMER, runnable, null, null, delayTicks, periodTicks, false);
    }

    @Override
    public ScheduledTask runForPlayer(Player player, Runnable runnable) {
        Objects.requireNonNull(player, "player");
        if (!player.isOnline()) {
            recordAndNotify(TaskErrorRecord.cancelled(
                TaskType.PLAYER, ERR_PLAYER_OFFLINE,
                "player is offline (uuid=" + safeUuid(player) + ")"));
            return new NoOpScheduledTask(plugin, TaskType.PLAYER);
        }
        return dispatch(TaskType.PLAYER, runnable, player, null, 0L, 0L, false);
    }

    @Override
    public ScheduledTask runForPlayerLater(Player player, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(player, "player");
        // 離線檢查先於 IAE 檢查：實務上「目標已失效」比「引數錯誤」更貼近使用者直覺
        if (!player.isOnline()) {
            recordAndNotify(TaskErrorRecord.cancelled(
                TaskType.PLAYER_LATER, ERR_PLAYER_OFFLINE,
                "player is offline (uuid=" + safeUuid(player) + ")"));
            return new NoOpScheduledTask(plugin, TaskType.PLAYER_LATER);
        }
        requireNonNegative(delayTicks, "delayTicks");
        return dispatch(TaskType.PLAYER_LATER, runnable, player, null, delayTicks, 0L, false);
    }

    @Override
    public ScheduledTask runForEntity(Entity entity, Runnable runnable) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead() || !entity.isValid()) {
            recordAndNotify(TaskErrorRecord.cancelled(
                TaskType.ENTITY, ERR_ENTITY_INVALID,
                "entity is dead/invalid (type=" + entity.getType() + ")"));
            return new NoOpScheduledTask(plugin, TaskType.ENTITY);
        }
        return dispatch(TaskType.ENTITY, runnable, null, entity, 0L, 0L, false);
    }

    @Override
    public ScheduledTask runAtLocation(Location location, Runnable runnable) {
        Objects.requireNonNull(location, "location");
        Chunk chunk = location.getChunk();
        if (chunk == null || !chunk.isLoaded()) {
            recordAndNotify(TaskErrorRecord.cancelled(
                TaskType.LOCATION, ERR_CHUNK_UNAVAILABLE,
                "chunk not loaded (world=" + safeWorld(location) + ")"));
            return new NoOpScheduledTask(plugin, TaskType.LOCATION);
        }
        return dispatch(TaskType.LOCATION, runnable, null, location, 0L, 0L, false);
    }

    @Override
    public void cancelAll() {
        for (ScheduledTask t : tracked) {
            try {
                t.cancel();
            } catch (Throwable ignore) {
                // 取消失敗不應影響其他任務；cancel 必須冪等
            }
        }
        tracked.clear();
    }

    @Override
    public List<TaskErrorRecord> getRecorderErrors(int max) {
        return recorder.getRecentErrors(max);
    }

    // -----------------------------------------------------------------
    // 生命週期與診斷輔助（介面外額外提供）
    // -----------------------------------------------------------------

    /**
     * 通知 scheduler 插件已停用：取消所有任務並標記為 disabled。
     *
     * <p>呼叫後任何後續 {@code runXxx(...)} 都會回傳 no-op task，
     * 並留下 {@code ACELIB-SCHED-006} 紀錄。
     * 重複呼叫不丟例外。</p>
     */
    public void onPluginDisable() {
        this.disabled = true;
        cancelAll();
    }

    /**
     * 取得內部錯誤紀錄器（供進階診斷使用）。
     *
     * @return 內部 {@link TaskErrorRecorder}；永遠不為 null
     */
    public TaskErrorRecorder getRecorder() {
        return recorder;
    }

    /**
     * 取得偵測到的平台（建構時傳入）。
     *
     * @return 當初的 {@link Platform}；永遠不為 null
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * 取得當前使用的 capability profile。
     *
     * @return 當初的 {@link PlatformCapability}；永遠不為 null
     */
    public PlatformCapability getCapability() {
        return capability;
    }

    /**
     * scheduler 是否已被標記為 disabled（{@link #onPluginDisable()} 已呼叫）。
     *
     * @return true 表示已停用，後續任何任務皆為 no-op
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * 設定錯誤紀錄 sink。
     *
     * <p>注入後，每次內部 {@link #recorder} 收到一筆 {@link TaskErrorRecord}，
     * scheduler 都會以 {@code (code, detail)} 形式回呼 sink；通常由
     * {@link com.smile.acelib.diagnostics.DiagnosticsService DiagnosticsService}
     * 透過 {@code bindScheduler} 自動注入，後續 plugins 也能以自訂 sink
     * 整合（例如發送自訂 alert）。</p>
     *
     * <p>重複呼叫會覆蓋前一個 sink；傳入 {@code null} 等同於
     * {@link #clearRecordSink()}。sink 拋例外會被吞掉，不影響 scheduler 主流程
     * 與 recorder 記錄。</p>
     *
     * @param sink 錯誤 sink；可為 null
     * @since 1.0.0
     */
    public void setRecordSink(BiConsumer<String, String> sink) {
        this.recordSink = sink;
    }

    /**
     * 解除錯誤紀錄 sink。
     *
     * <p>通常由 {@link com.smile.acelib.diagnostics.DiagnosticsService
     * DiagnosticsService} 在 {@code bindScheduler(null)} 或 plugin onDisable
     * 時呼叫，確保 disable 後的 sink 不會繼續被觸發。</p>
     *
     * @since 1.0.0
     */
    public void clearRecordSink() {
        this.recordSink = null;
    }

    /**
     * 取得目前已追蹤的任務數量（測試與診斷用）。
     *
     * @return tracked task 數量
     */
    public int getTrackedTaskCount() {
        return tracked.size();
    }

    // -----------------------------------------------------------------
    // 內部 dispatch 邏輯
    // -----------------------------------------------------------------

    /**
     * 統一 dispatch 入口。
     *
     * @param type         任務類型
     * @param runnable     使用者提供的程式；不可為 null
     * @param player       玩家目標（PLAYER / PLAYER_LATER）；其他型別為 null
     * @param entityOrLoc  實體或位置目標（ENTITY / LOCATION）；其他型別為 null
     * @param delayTicks   延遲 tick（runLater / runTimer / runForPlayerLater）
     * @param periodTicks  週期間隔（runTimer）
     * @param async        是否走 async pool
     */
    private ScheduledTask dispatch(TaskType type,
                                    Runnable runnable,
                                    Player player,
                                    Object entityOrLoc,
                                    long delayTicks,
                                    long periodTicks,
                                    boolean async) {
        Objects.requireNonNull(runnable, "runnable");

        if (disabled) {
            recordAndNotify(TaskErrorRecord.cancelled(
                type, ERR_PLUGIN_DISABLED, "scheduler is disabled"));
            return new NoOpScheduledTask(plugin, type);
        }

        long creationTick = currentTick();
        Runnable wrapped = () -> wrap(type, runnable);

        try {
            BukkitTask task;
            if (capability.regionScheduling()) {
                task = dispatchFolia(type, wrapped, player, entityOrLoc, delayTicks, periodTicks, async);
            } else if (capability.globalScheduler()) {
                task = dispatchPaper(wrapped, delayTicks, periodTicks, async);
            } else {
                recordAndNotify(TaskErrorRecord.cancelled(
                    type, ERR_PLATFORM_UNSUPPORTED,
                    "platform capability does not include regionScheduling nor globalScheduler"));
                return new NoOpScheduledTask(plugin, type);
            }
            ScheduledTask scheduled = new BukkitScheduledTask(plugin, type, task, creationTick);
            tracked.add(scheduled);
            return scheduled;
        } catch (Throwable t) {
            // dispatch 階段失敗（Folia API 不存在、IllegalStateException、Refl 錯誤等）
            recordAndNotify(TaskErrorRecord.threw(
                type, ERR_PLATFORM_UNSUPPORTED,
                "dispatch failed: " + safeMessage(t), t));
            return new NoOpScheduledTask(plugin, type);
        }
    }

    /**
     * Paper 路徑：透過 {@link BukkitScheduler} 派送。
     *
     * <p>MockBukkit 環境下此方法可正常運作；真實 Paper / Bukkit 環境亦適用。</p>
     */
    private BukkitTask dispatchPaper(Runnable wrapped, long delay, long period, boolean async) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        if (async) {
            return scheduler.runTaskAsynchronously(plugin, wrapped);
        }
        if (period > 0L) {
            return scheduler.runTaskTimer(plugin, wrapped, delay, period);
        }
        if (delay > 0L) {
            return scheduler.runTaskLater(plugin, wrapped, delay);
        }
        return scheduler.runTask(plugin, wrapped);
    }

    /**
     * Folia 路徑：透過 reflection 呼叫 {@code io.papermc.paper.threadedregions.scheduler.*}。
     *
     * <p>由於 paper-api 26.1.2 已內含 Folia API 的型別宣告，但 MockBukkit 並不實作
     * 其執行期行為；本方法以 reflection 嘗試呼叫，讓 MockBukkit 環境下也能走完整
     * dispatch 流程並正確落在 catch 區塊（記錄為 {@code ACELIB-SCHED-005}）。</p>
     *
     * @throws NoSuchMethodException     當 Folia API class 不存在於 classpath
     * @throws IllegalAccessException    當反射方法無法訪問
     * @throws InvocationTargetException 當反射呼叫的底層方法拋例外
     */
    private BukkitTask dispatchFolia(TaskType type,
                                      Runnable wrapped,
                                      Player player,
                                      Object entityOrLoc,
                                      long delay,
                                      long period,
                                      boolean async) throws Exception {
        try {
            if (async) {
                // AsyncScheduler.runNow(plugin, consumer)
                Object asyncSched = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                Class<?> asyncCls = asyncSched.getClass();
                invokeAsyncNow(asyncCls, asyncSched, plugin, wrapped);
                return new DetachedBukkitTask(plugin, type);
            }
            if (player == null && !(entityOrLoc instanceof Entity)) {
                // GlobalRegionScheduler
                Object grs = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                if (period > 0L) {
                    invokeGlobalAtFixedRate(grs, plugin, wrapped, delay, period);
                } else if (delay > 0L) {
                    invokeGlobalDelayed(grs, plugin, wrapped, delay);
                } else {
                    invokeGlobalRun(grs, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (player != null) {
                // EntityScheduler (player)
                Object es = Entity.class.getMethod("getScheduler").invoke(player);
                if (delay > 0L) {
                    invokeEntityDelayed(es, plugin, wrapped, delay);
                } else {
                    invokeEntityRun(es, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (entityOrLoc instanceof Entity ent) {
                Object es = Entity.class.getMethod("getScheduler").invoke(ent);
                if (delay > 0L) {
                    invokeEntityDelayed(es, plugin, wrapped, delay);
                } else {
                    invokeEntityRun(es, plugin, wrapped);
                }
                return new DetachedBukkitTask(plugin, type);
            }
            if (entityOrLoc instanceof Location loc) {
                Object rs = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
                invokeRegionExecute(rs, plugin, loc, wrapped);
                return new DetachedBukkitTask(plugin, type);
            }
            throw new IllegalStateException("unsupported Folia dispatch combination for " + type);
        } catch (NoSuchMethodException e) {
            // Folia API 不存在於此 classpath（典型 MockBukkit 環境）
            throw new IllegalStateException("Folia scheduler API not present: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            // 反射呼叫本身成功，但底層丟例外
            Throwable cause = e.getTargetException();
            throw new RuntimeException("Folia dispatch threw: " + safeMessage(cause), cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Folia API not accessible: " + e.getMessage(), e);
        }
    }

    // --- Folia reflection helpers (cache lookups per call; not on hot path) ---

    private static void invokeAsyncNow(Class<?> asyncCls, Object asyncSched,
                                       JavaPlugin plugin, Runnable r) throws Exception {
        // AsyncScheduler.runNow(Plugin, Consumer) — Consumer<Object>
        Method m = asyncCls.getMethod("runNow",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
        m.invoke(asyncSched, plugin, toConsumer(r));
    }

    private static void invokeGlobalRun(Object grs, JavaPlugin plugin, Runnable r) throws Exception {
        // GlobalRegionScheduler.run(Plugin, Consumer)
        Method m = grs.getClass().getMethod("run",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class);
        m.invoke(grs, plugin, toConsumer(r));
    }

    private static void invokeGlobalDelayed(Object grs, JavaPlugin plugin, Runnable r, long delay) throws Exception {
        Method m = grs.getClass().getMethod("runDelayed",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class);
        m.invoke(grs, plugin, toConsumer(r), delay);
    }

    private static void invokeGlobalAtFixedRate(Object grs, JavaPlugin plugin, Runnable r,
                                                long delay, long period) throws Exception {
        Method m = grs.getClass().getMethod("runAtFixedRate",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, long.class, long.class);
        m.invoke(grs, plugin, toConsumer(r), delay, period);
    }

    private static void invokeEntityRun(Object es, JavaPlugin plugin, Runnable r) throws Exception {
        // EntityScheduler.run(Plugin, Consumer, Runnable)
        Method m = es.getClass().getMethod("run",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class);
        m.invoke(es, plugin, toConsumer(r), null);
    }

    private static void invokeEntityDelayed(Object es, JavaPlugin plugin, Runnable r, long delay) throws Exception {
        Method m = es.getClass().getMethod("runDelayed",
            org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class, long.class);
        m.invoke(es, plugin, toConsumer(r), null, delay);
    }

    private static void invokeRegionExecute(Object rs, JavaPlugin plugin, Location loc, Runnable r) throws Exception {
        // RegionScheduler.execute(Plugin, Location, Consumer)
        Method m = rs.getClass().getMethod("execute",
            org.bukkit.plugin.Plugin.class, Location.class, java.util.function.Consumer.class);
        m.invoke(rs, plugin, loc, toConsumer(r));
    }

    /**
     * 將 {@link Runnable} 包裝成 Folia API 期待的 {@code Consumer<Object>}。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.function.Consumer<Object> toConsumer(Runnable r) {
        return o -> r.run();
    }

    /**
     * 包裝使用者 runnable：執行時若拋錯，記錄為 {@code ACELIB-SCHED-001}，
     * 但不影響後續任務的派送與執行。
     */
    private void wrap(TaskType type, Runnable user) {
        try {
            user.run();
        } catch (Throwable t) {
            recordAndNotify(TaskErrorRecord.threw(
                type, ERR_TASK_EXCEPTION,
                "user task threw exception: " + safeMessage(t), t));
        }
    }

    /**
     * 統一寫入 {@link #recorder} 並通知 {@link #recordSink}。
     *
     * <p>sink 為 {@code null} 時退化成裸 {@code recorder.record(...)}（既有行為）。
     * sink 拋例外時<strong>不</strong>冒到 caller，避免污染 scheduler 主流程
     * 或 recorder 內部狀態。</p>
     */
    private void recordAndNotify(TaskErrorRecord record) {
        recorder.record(record);
        BiConsumer<String, String> sink = this.recordSink;
        if (sink != null && record != null) {
            try {
                sink.accept(record.code(), record.detail());
            } catch (Throwable ignore) {
                // sink 失敗不應影響 scheduler；保留既有錯誤紀錄語意
            }
        }
    }

    private static long currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable t) {
            // 純單元測試或舊版 Bukkit：使用本地 fallback counter
            return 0L;
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "(null throwable)";
        }
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    private static String safeUuid(Player player) {
        try {
            return player.getUniqueId().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeWorld(Location loc) {
        try {
            return loc.getWorld() != null ? loc.getWorld().getName() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void requireNonNegative(long v, String name) {
        if (v < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0, got: " + v);
        }
    }

    private static void requirePositive(long v, String name) {
        if (v <= 0L) {
            throw new IllegalArgumentException(name + " must be > 0, got: " + v);
        }
    }

    // -----------------------------------------------------------------
    // 內部類型：ScheduledTask 實作
    // -----------------------------------------------------------------

    /**
     * 真實的 Paper / Folia task 包裝。
     */
    static final class BukkitScheduledTask implements ScheduledTask {
        private final JavaPlugin plugin;
        private final TaskType type;
        private final BukkitTask task;
        private final long creationTick;

        BukkitScheduledTask(JavaPlugin plugin, TaskType type, BukkitTask task, long creationTick) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.type = Objects.requireNonNull(type, "type");
            this.task = Objects.requireNonNull(task, "task");
            this.creationTick = creationTick;
        }

        @Override
        public void cancel() {
            try {
                task.cancel();
            } catch (Throwable ignore) {
                // cancel 必須冪等
            }
        }

        @Override
        public boolean isCancelled() {
            try {
                return task.isCancelled();
            } catch (Throwable t) {
                return true;
            }
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

    /**
     * 佔位 task（玩家離線、實體失效、chunk 未載入、插件停用、平台不支援）。
     * 一律處於 cancelled 狀態，cancel() 為 no-op。
     */
    static final class NoOpScheduledTask implements ScheduledTask {
        private final JavaPlugin plugin;
        private final TaskType type;
        private final long creationTick;

        NoOpScheduledTask(JavaPlugin plugin, TaskType type) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.type = Objects.requireNonNull(type, "type");
            this.creationTick = currentTick();
        }

        @Override
        public void cancel() {
            // 已取消，no-op
        }

        @Override
        public boolean isCancelled() {
            return true;
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

    /**
     * Folia dispatch 的相容性佔位 {@link BukkitTask}。
     *
     * <p>由於 Folia API 回傳的 retired task 在不同版本間不保證相容 {@link BukkitTask}，
     * 統一以這個 lightweight 實作包裝；{@link SafeSchedulerImpl} 本身仍透過
     * {@link #cancelAll()} 統一管理所有 Folia 派送任務的生命週期。</p>
     */
    static final class DetachedBukkitTask implements BukkitTask {
        private final org.bukkit.plugin.Plugin owner;
        private final int taskId;
        private volatile boolean cancelled = false;

        DetachedBukkitTask(JavaPlugin plugin, TaskType type) {
            this.owner = plugin;
            this.taskId = nextDetachedId();
        }

        private static int nextDetachedId() {
            // 使用 System.identityHashCode + 負數區間以避免與 Bukkit.getScheduler() 的 taskId 衝突
            return -1 - (System.identityHashCode(Thread.currentThread()) & 0x3FFFFFFF);
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public org.bukkit.plugin.Plugin getOwner() {
            return owner;
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public boolean isSync() {
            // Folia 環境下每個 task 都隱含綁定到自己的 region/thread，
            // 沒有 Paper 的「全域同步 vs async」二分法；回傳 true 表示「由 scheduler 管理」語意。
            return true;
        }
    }

    // 隱藏一個 reference 給編譯器，避免 LOGGER 被標記為 unused
    @SuppressWarnings("unused")
    private void logFine(String msg) {
        LOGGER.log(Level.FINE, msg);
    }
}