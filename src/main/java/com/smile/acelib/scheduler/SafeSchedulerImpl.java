package com.smile.acelib.scheduler;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
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
import org.bukkit.scheduler.BukkitTask;

/**
 * {@link SafeScheduler} 的標準實作（Internal）。
 *
 * <p>內含：</p>
 * <ul>
 *   <li>6 種基本任務（global / async / later / timer）+ 3 種上下文任務
 *       （player / player-later / entity / location）共 8 種 dispatch</li>
 *   <li>Paper / Folia 自動 dispatch（依 {@link PlatformCapability} 選擇 internal
 *       {@link SchedulerBackend}，不依版本字串 switch）</li>
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
 * <h2>backend 選擇策略</h2>
 * <p>runtime-specific 派送已抽離至 package-private {@link SchedulerBackend}：
 * {@link FoliaSchedulerBackend}（regionized，reflection 呼叫
 * {@code io.papermc.paper.threadedregions.scheduler.*}）與
 * {@link PaperSchedulerBackend}（全域 {@code BukkitScheduler}）。backend 選擇
 * 只依 {@link PlatformCapability} profile（{@code regionScheduling()} → Folia、
 * {@code globalScheduler()} → Paper、兩者皆無 → 無 backend），<strong>不</strong>
 * 做版本字串 switch。當 classpath 不含 Folia API（典型 MockBukkit 環境）時，
 * {@link FoliaSchedulerBackend} 拋 {@link IllegalStateException}，由本類別統一以
 * {@code ACELIB-SCHED-005} 記錄並回傳 no-op task（fail-closed，絕不退回 unsafe
 * 的 global scheduler）。</p>
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
    private final SchedulerBackend backend;
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
     * 建構子（標準路徑）。
     *
     * <p>backend 選擇只依 {@link PlatformCapability} profile：
     * {@code regionScheduling()} → {@link FoliaSchedulerBackend}、
     * {@code globalScheduler()} → {@link PaperSchedulerBackend}、
     * 兩者皆無 → {@code null}（無 backend，後續任務回 cancelled + SCHED-005）。
     * 全程無版本字串 switch。</p>
     *
     * @param plugin     派送任務的 plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null（用於診斷與日誌）
     * @param capability 對應的 capability profile；不可為 null
     *                   （建議由 {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @throws NullPointerException 當任一參數為 null
     */
    public SafeSchedulerImpl(JavaPlugin plugin, Platform platform, PlatformCapability capability) {
        this(plugin, platform, capability, selectBackend(plugin, capability));
    }

    /**
     * 建構子（測試 / 受控注入 seam）。
     *
     * <p>允許直接注入一個 {@link SchedulerBackend}（含必定拋錯的 backend），
     * 以決定性驗證 fail-closed 行為。package-private，僅供同套件測試使用。</p>
     *
     * @param plugin     派送任務的 plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @param backend    要使用的 backend；可為 null（表示無 backend）
     * @throws NullPointerException 當 plugin / platform / capability 為 null
     */
    SafeSchedulerImpl(JavaPlugin plugin, Platform platform, PlatformCapability capability,
                      SchedulerBackend backend) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.backend = backend;
        this.recorder = new TaskErrorRecorder();
    }

    /**
     * 依 capability profile 選擇 backend（無版本字串 switch）。
     *
     * @return 對應的 {@link SchedulerBackend}；兩者皆不支援時回 null
     */
    private static SchedulerBackend selectBackend(JavaPlugin plugin, PlatformCapability capability) {
        if (capability.regionScheduling()) {
            return new FoliaSchedulerBackend(plugin);
        }
        if (capability.globalScheduler()) {
            return new PaperSchedulerBackend(plugin);
        }
        return null;
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
     * 取得目前選用的 internal backend（測試與診斷用）。
     *
     * <p>回傳值反映建構時依 capability profile 選擇的 backend；
     * UNKNOWN（regionScheduling 與 globalScheduler 皆 false）下為 {@code null}。</p>
     *
     * @return 目前的 {@link SchedulerBackend}；無 backend 時為 null
     */
    SchedulerBackend getBackend() {
        return backend;
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

        // backend 選擇在建構時依 capability profile 完成；null 表示
        // regionScheduling 與 globalScheduler 皆不支援（UNKNOWN）。
        if (backend == null) {
            recordAndNotify(TaskErrorRecord.cancelled(
                type, ERR_PLATFORM_UNSUPPORTED,
                "platform capability does not include regionScheduling nor globalScheduler"));
            return new NoOpScheduledTask(plugin, type);
        }

        try {
            BukkitTask task = backend.dispatch(
                type, wrapped, player, entityOrLoc, delayTicks, periodTicks, async);
            ScheduledTask scheduled = new BukkitScheduledTask(plugin, type, task, creationTick);
            tracked.add(scheduled);
            return scheduled;
        } catch (Throwable t) {
            // backend 派發失敗（Folia API 不存在、IllegalStateException、Refl 錯誤等）
            // fail-closed：記錄 SCHED-005 並回 no-op task，絕不退回 unsafe scheduler。
            recordAndNotify(TaskErrorRecord.threw(
                type, ERR_PLATFORM_UNSUPPORTED,
                "dispatch failed: " + safeMessage(t), t));
            return new NoOpScheduledTask(plugin, type);
        }
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

    // 隱藏一個 reference 給編譯器，避免 LOGGER 被標記為 unused
    @SuppressWarnings("unused")
    private void logFine(String msg) {
        LOGGER.log(Level.FINE, msg);
    }
}