package com.smile.acelib.scheduler;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

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
 * {@link FoliaSchedulerBackend}（regionized，直接呼叫
 * {@code io.papermc.paper.threadedregions.scheduler.*}）與
 * {@link PaperSchedulerBackend}（全域 {@code BukkitScheduler}）。backend 選擇
 * 只依 {@link PlatformCapability} profile（{@code regionScheduling()} → Folia、
 * {@code globalScheduler()} → Paper、兩者皆無 → 無 backend），<strong>不</strong>
 * 做版本字串 switch。當目前平台無法提供對應的排程 API（例如以 Folia capability
 * 執行但 API 不存在）時，{@link SchedulerBackend#dispatch} 會拋出例外，由本類別
 * 統一以 {@code ACELIB-SCHED-005} 記錄並回傳 no-op task（fail-closed，絕不退回
 * unsafe 的 global scheduler）。</p>
 *
 * <h2>任務追蹤生命週期</h2>
 * <p>{@link #tracked} 只包含「仍有可能執行或正在執行」的任務：</p>
 * <ul>
 *   <li>一次性任務（global / async / later / player / entity / location）在
 *       runnable 執行結束後解除追蹤；</li>
 *   <li>Folia entity 任務在實體退役（retired）時解除追蹤，因為此時 runnable
 *       永遠不會執行；</li>
 *   <li>任何任務被 {@code cancel()} 或 {@link #cancelAll()} 取消後解除追蹤；</li>
 *   <li>週期任務（timer）在重複執行期間維持被追蹤，直到被取消。</li>
 * </ul>
 * <p>這讓長時間運行的 scheduler 不會累積已完成任務，且 {@link #cancelAll()} 的
 * 掃描成本維持在「活著任務數」而非「歷史派送總數」。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆可在多 region 並行環境下使用。
 * {@link #tracked} 使用 {@link ConcurrentHashMap#newKeySet()}；
 * {@link #disabled} 為 {@code volatile}。任務解除追蹤透過
 * {@link Set#remove(Object)}（冪等）達成，並以
 * {@code finished.compareAndSet} 避免重複收尾。</p>
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
    /**
     * 存活的作用域群組（退服／退休／停用時關閉，{@link #cancelAll()} 時連帶取消）。
     *
     * <p>已關閉的作用域由 {@link #forgetScope(TaskScopeImpl)} 除名；
     * 結束的票據由作用域自行解除追蹤，因此集合規模維持在「活著群組數」。</p>
     */
    private final Set<TaskScopeImpl> scopes = ConcurrentHashMap.newKeySet();
    /**
     * 作用域關閉事件監聽（退服／實體移除）。
     *
     * <p>第一個作用域建立時註冊、停用時解除註冊；{@code volatile} 搭配
     * 同步的雙重檢查，確保多執行緒下只註冊一次。</p>
     */
    private volatile ScopeListener scopeListener;
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
        if (!isChunkLoaded(location)) {
            recordAndNotify(TaskErrorRecord.cancelled(
                TaskType.LOCATION, ERR_CHUNK_UNAVAILABLE,
                "chunk not loaded (world=" + safeWorld(location) + ", x=" + location.getBlockX()
                    + ", z=" + location.getBlockZ() + ")"));
            return new NoOpScheduledTask(plugin, TaskType.LOCATION);
        }
        return dispatch(TaskType.LOCATION, runnable, null, location, 0L, 0L, false);
    }

    @Override
    public TaskScope scopeFor(Player player) {
        Objects.requireNonNull(player, "player");
        if (disabled) {
            return new TaskScopeImpl(this, plugin, TaskScopeImpl.Kind.PLAYER,
                player, null, false, disabledRecord());
        }
        ensureScopeListener();
        TaskScopeImpl scope = new TaskScopeImpl(this, plugin, TaskScopeImpl.Kind.PLAYER,
            player, null, true, null);
        scopes.add(scope);
        return scope;
    }

    @Override
    public TaskScope scopeFor(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (disabled) {
            return new TaskScopeImpl(this, plugin, TaskScopeImpl.Kind.ENTITY,
                null, entity, false, disabledRecord());
        }
        ensureScopeListener();
        TaskScopeImpl scope = new TaskScopeImpl(this, plugin, TaskScopeImpl.Kind.ENTITY,
            null, entity, true, null);
        scopes.add(scope);
        return scope;
    }

    /**
     * 檢查 location 所在 chunk 是否已載入，且<strong>不</strong>產生載入副作用。
     *
     * <p>不可用 {@code Location#getChunk()} / {@code World#getChunkAt(...)} 做為
     * 檢查：兩者都會在 chunk 未載入時把 chunk 載入（或生成）進世界。排程前
     * 順手載入 chunk 會改變世界狀態、佔用記憶體，且讓「chunk 未載入」的
     * fail-closed 分支永遠不會被走到。{@link World#isChunkLoaded(int, int)} 只讀
     * 既有狀態，是唯一正確的檢查方式。</p>
     *
     * @param location 目標位置
     * @return chunk 已載入為 true；world 為 null 或 API 拋錯時保守回 false
     */
    private static boolean isChunkLoaded(Location location) {
        try {
            World world = location.getWorld();
            return world != null
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } catch (Throwable t) {
            // 檢查本身失敗時保守視為未載入，交由呼叫端記錄 SCHED-004。
            return false;
        }
    }

    @Override
    public void cancelAll() {
        // 快照後逐一取消，不可再以 tracked.clear() 收尾：dispatch 可能正好在
        // 迭代與 clear 之間把新任務登記進 tracked，clear 會把尚未取消的任務
        // 直接移除，讓底層任務繼續執行卻不再被追蹤。逐一 cancel 讓每個任務自行
        // 經由 tracked.remove 解除追蹤；快照之後才登記的新任務屬於新派送，
        // 留在 tracked 繼續存活，不可一併清除。
        // 外層 try/catch 保留：即使某個任務的 cancel 拋出，也不能讓其他任務
        // 留在排程器裡繼續執行（plugin disable 情境特別重要）。
        List<ScheduledTask> snapshot = new ArrayList<>(tracked);
        for (ScheduledTask t : snapshot) {
            try {
                t.cancel();
            } catch (Throwable t2) {
                LOGGER.log(Level.FINE, "cancelAll: task cancel failed: " + safeMessage(t2), t2);
            }
        }
        // 作用域票據的底層句柄已在上輪快照中取消；此處補上終態寫入
        // （單純取消不留紀錄，與既有 cancelAll 語意一致）。
        for (TaskScopeImpl scope : new ArrayList<>(scopes)) {
            try {
                scope.cancelAll();
            } catch (Throwable failure) {
                LOGGER.log(Level.FINE,
                    "cancelAll: scope cancel failed: " + safeMessage(failure), failure);
            }
        }
    }

    @Override
    public List<TaskErrorRecord> getRecorderErrors(int max) {
        return recorder.getRecentErrors(max);
    }

    @Override
    public void clearRecorderErrors() {
        recorder.clear();
    }

    @Override
    public int configureErrorCapacity(int capacity) {
        return recorder.configureCapacity(capacity);
    }

    // -----------------------------------------------------------------
    // 生命週期與診斷輔助（介面外額外提供）
    // -----------------------------------------------------------------

    /**
     * 通知 scheduler 插件已停用：取消所有任務並標記為 disabled。
     *
     * <p>呼叫後任何後續 {@code runXxx(...)} 都會回傳 no-op task，
     * 並留下 {@code ACELIB-SCHED-006} 紀錄。作用域群組同步關閉
     * （各票據收到取消終態），退服監聽解除註冊。
     * 重複呼叫不丟例外。</p>
     */
    public void onPluginDisable() {
        this.disabled = true;
        TaskErrorRecord shutdown = TaskErrorRecord.cancelled(
            TaskType.GLOBAL, ERR_PLUGIN_DISABLED,
            "scheduler is disabled; scoped tasks cancelled");
        for (TaskScopeImpl scope : new ArrayList<>(scopes)) {
            try {
                scope.deactivate(shutdown);
            } catch (Throwable failure) {
                LOGGER.log(Level.FINE,
                    "onPluginDisable: scope deactivate failed: " + safeMessage(failure),
                    failure);
            }
        }
        cancelAll();
        unregisterScopeListener();
    }

    /**
     * 確保作用域關閉監聽已註冊（第一個作用域建立時呼叫一次）。
     *
     * <p>plugin 未啟用時無法註冊監聽：此時退服自動取消無從實現，
     * fail-fast 拒絕建群組，避免靜默失去核心語意。</p>
     *
     * @throws IllegalStateException 當 plugin 未啟用
     */
    private void ensureScopeListener() {
        ScopeListener current = scopeListener;
        if (current != null) {
            return;
        }
        synchronized (this) {
            if (scopeListener != null) {
                return;
            }
            if (!plugin.isEnabled()) {
                throw new IllegalStateException(
                    "scopeFor 需要已啟用的 plugin（退服／退休監聽無法註冊）");
            }
            ScopeListener created = new ScopeListener(this);
            Bukkit.getPluginManager().registerEvents(created, plugin);
            scopeListener = created;
        }
    }

    /**
     * 解除作用域關閉監聽註冊（停用路徑；server 在 plugin disable 時亦會自動移除）。
     */
    private void unregisterScopeListener() {
        ScopeListener current;
        synchronized (this) {
            current = scopeListener;
            scopeListener = null;
        }
        if (current != null) {
            try {
                HandlerList.unregisterAll(current);
            } catch (Throwable failure) {
                LOGGER.log(Level.FINE,
                    "unregisterScopeListener failed: " + safeMessage(failure), failure);
            }
        }
    }

    /**
     * 玩家退服時關閉其作用域（{@link ScopeListener} 呼叫）。
     *
     * @param id 退服玩家識別碼；可為 null（直接忽略）
     */
    void onPlayerQuit(UUID id) {
        if (id == null) {
            return;
        }
        TaskErrorRecord quit = TaskErrorRecord.cancelled(
            TaskType.PLAYER, ERR_PLAYER_OFFLINE, "player quit (uuid=" + id + ")");
        for (TaskScopeImpl scope : new ArrayList<>(scopes)) {
            try {
                if (scope.ownsPlayer(id)) {
                    scope.deactivate(quit);
                }
            } catch (Throwable failure) {
                LOGGER.log(Level.FINE,
                    "onPlayerQuit: scope deactivate failed: " + safeMessage(failure), failure);
            }
        }
    }

    /**
     * 實體退休時關閉其作用域（{@link ScopeListener} 呼叫）。
     *
     * @param id 退休實體識別碼；可為 null（直接忽略）
     */
    void onEntityRetired(UUID id) {
        if (id == null) {
            return;
        }
        TaskErrorRecord retired = TaskErrorRecord.cancelled(
            TaskType.ENTITY, ERR_ENTITY_INVALID, "entity retired (uuid=" + id + ")");
        for (TaskScopeImpl scope : new ArrayList<>(scopes)) {
            try {
                if (scope.ownsEntity(id)) {
                    scope.deactivate(retired);
                }
            } catch (Throwable failure) {
                LOGGER.log(Level.FINE,
                    "onEntityRetired: scope deactivate failed: " + safeMessage(failure), failure);
            }
        }
    }

    /**
     * 作用域關閉後除名（{@link TaskScopeImpl#deactivate(TaskErrorRecord)} 呼叫）。
     *
     * @param scope 已關閉的作用域
     */
    void forgetScope(TaskScopeImpl scope) {
        scopes.remove(scope);
    }

    /**
     * 停用狀態的作用域關閉紀錄（新建失活群組用，不寫入 recorder）。
     */
    private TaskErrorRecord disabledRecord() {
        return TaskErrorRecord.cancelled(
            TaskType.GLOBAL, ERR_PLUGIN_DISABLED, "scheduler is disabled");
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
     * 統一 dispatch 入口（既有 8 種任務方法共用，行為保持不變）。
     *
     * @param type         任務類型
     * @param runnable     使用者提供的程式；不可為 null
     * @param player       玩家目標（PLAYER / PLAYER_LATER）；其他型別為 null
     * @param entityOrLoc  實體或位置目標（ENTITY / LOCATION）；其他型別為 null
     * @param delayTicks   延遲 tick（runLater / runTimer / runForPlayerLater）
     * @param periodTicks  週期間隔（runTimer）；&gt;0 表示週期任務
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
        // 一次性任務與週期任務的收尾條件不同：一次性任務 runnable 跑完就結束，
        // 週期任務要持續被追蹤直到被取消。兩者的解除追蹤都收斂到
        // BukkitScheduledTask.finish()，由 cancel()、一次性執行結束與
        // Folia entity 退役三條路徑觸發。
        BukkitScheduledTask scheduled = new BukkitScheduledTask(
            plugin, type, currentTick(), tracked, periodTicks > 0L);
        Runnable wrapped = () -> {
            if (disabled || scheduled.isCancelRequested()) {
                scheduled.finish();
                return;
            }
            wrap(type, runnable);
            scheduled.finish();
        };
        return dispatchCore(type, scheduled, wrapped, scheduled::finish,
            player, entityOrLoc, delayTicks, periodTicks, async, null);
    }

    /**
     * 派送共用核心（既有任務方法與作用域任務方法共用）。
     *
     * <p>收斂所有「派送期」分支：停用、無 backend、null 句柄、實體退休例外、
     * 底層拋錯，以及先登記後派送的空窗補取消。作用域路徑經由
     * {@code rejectionListener} 得知派送期拒絕（以寫入拒派終態）；
     * 既有路徑傳 null，行為與重構前完全一致。</p>
     *
     * @param type               任務類型
     * @param scheduled          已建立的追蹤任務（呼叫端依一次性／週期區分建立）
     * @param wrapped            已包裝使用者程式的 wrapper
     * @param retired            任務在執行前就被平台判定為不可能執行時的收尾通知
     * @param player             玩家目標；其他型別為 null
     * @param entityOrLoc        實體或位置目標；其他型別為 null
     * @param delayTicks         延遲 tick
     * @param periodTicks        週期間隔；&gt;0 表示週期任務
     * @param async              是否走 async pool
     * @param rejectionListener  派送期拒絕通知（作用域寫入拒派終態用）；可為 null
     * @return 派送成功的追蹤任務，或已取消的 no-op
     */
    private ScheduledTask dispatchCore(TaskType type,
                                       BukkitScheduledTask scheduled,
                                       Runnable wrapped,
                                       Runnable retired,
                                       Player player,
                                       Object entityOrLoc,
                                       long delayTicks,
                                       long periodTicks,
                                       boolean async,
                                       Consumer<TaskErrorRecord> rejectionListener) {
        if (disabled) {
            TaskErrorRecord rejected = TaskErrorRecord.cancelled(
                type, ERR_PLUGIN_DISABLED, "scheduler is disabled");
            recordAndNotify(rejected);
            notifyRejection(rejectionListener, rejected);
            return new NoOpScheduledTask(plugin, type);
        }

        // backend 選擇在建構時依 capability profile 完成；null 表示
        // regionScheduling 與 globalScheduler 皆不支援（UNKNOWN）。
        if (backend == null) {
            TaskErrorRecord rejected = TaskErrorRecord.cancelled(
                type, ERR_PLATFORM_UNSUPPORTED,
                "platform capability does not include regionScheduling nor globalScheduler");
            recordAndNotify(rejected);
            notifyRejection(rejectionListener, rejected);
            return new NoOpScheduledTask(plugin, type);
        }

        // 先登記再派送：backend.dispatch 可能阻塞（或被 latch 測試刻意擋住），
        // disable / cancelAll 可能正好插入這段空窗。先登記讓並行的 cancelAll
        // 能經由 cancelRequested 看到飛行中的任務；派送返回後再以 disabled /
        // cancelRequested 補一次取消，確保晚返回的真實句柄最終一定被取消。
        tracked.add(scheduled);

        try {
            PlatformTaskHandle handle = backend.dispatch(
                type, wrapped, retired,
                player, entityOrLoc, delayTicks, periodTicks, async);
            if (handle == null) {
                tracked.remove(scheduled);
                TaskErrorRecord rejected;
                if (type == TaskType.ENTITY || type == TaskType.PLAYER
                    || type == TaskType.PLAYER_LATER) {
                    rejected = TaskErrorRecord.cancelled(
                        type, ERR_ENTITY_INVALID,
                        "entity retired before dispatch (backend returned null)");
                } else {
                    rejected = TaskErrorRecord.threw(
                        type, ERR_PLATFORM_UNSUPPORTED,
                        "dispatch returned null handle",
                        new IllegalStateException("null handle"));
                }
                recordAndNotify(rejected);
                notifyRejection(rejectionListener, rejected);
                return new NoOpScheduledTask(plugin, type);
            }
            scheduled.attach(handle);
            if (disabled || scheduled.isCancelRequested()) {
                scheduled.cancel();
            } else if (scheduled.hasFinished()) {
                // backend 可能在其 dispatch 內部就同步執行了 runnable（例如測試用的
                // scheduler stub，或 delay=0 的 region runnable）。此時 finish() 早於
                // 登記發生，先前的 tracked.remove() 沒作用，任務會被登記成已完成卻仍
                // 存活。只在「確實已完成」時補一次解除追蹤；尚未執行的任務必須留在
                // tracked，否則 cancelAll / plugin disable 取消不到它。
                tracked.remove(scheduled);
            }
            return scheduled;
        } catch (EntityRetiredException retiredFailure) {
            tracked.remove(scheduled);
            TaskErrorRecord rejected = TaskErrorRecord.cancelled(
                type, ERR_ENTITY_INVALID,
                "entity retired before dispatch: " + safeMessage(retiredFailure));
            recordAndNotify(rejected);
            notifyRejection(rejectionListener, rejected);
            return new NoOpScheduledTask(plugin, type);
        } catch (Throwable failure) {
            tracked.remove(scheduled);
            // backend 派發失敗（Folia API 不可用、不支援的組合、底層拋例外等）
            // fail-closed：記錄 SCHED-005 並回 no-op task，絕不退回 unsafe scheduler。
            TaskErrorRecord rejected = TaskErrorRecord.threw(
                type, ERR_PLATFORM_UNSUPPORTED,
                "dispatch failed: " + safeMessage(failure), failure);
            recordAndNotify(rejected);
            notifyRejection(rejectionListener, rejected);
            return new NoOpScheduledTask(plugin, type);
        }
    }

    /**
     * 轉發派送期拒絕通知（作用域終態用；既有路徑傳 null 即不通知）。
     */
    private static void notifyRejection(Consumer<TaskErrorRecord> listener,
                                        TaskErrorRecord rejected) {
        if (listener != null) {
            listener.accept(rejected);
        }
    }

    /**
     * 作用域任務派送（package-private，{@link TaskScopeImpl} 呼叫）。
     *
     * <p>與既有 {@link #dispatch} 共用 {@link #dispatchCore} 的派送期分支，
     * 差別只在回呼包裝：執行成功攜值完成、拋錯記 SCHED-001 並失敗、
     * 守衛觸發（停用／取消／作用域關閉／擁有者失效／退休）記對應代碼並取消。
     * 派送期拒絕經由拒絕通知寫入拒派終態。</p>
     *
     * @param type         任務類型
     * @param player       玩家目標；其他型別為 null
     * @param entityOrLoc  實體或位置目標；其他型別為 null
     * @param action       使用者提供的程式（可攜回值、可拋錯）；不可為 null
     * @param scope        所屬作用域；不可為 null
     * @param <T> 值型別
     * @return 可觀察終態的票據；拒派時為已完成的拒派票據
     */
    <T> TaskTicket<T> dispatchScoped(TaskType type,
                                     Player player,
                                     Object entityOrLoc,
                                     Supplier<T> action,
                                     TaskScopeImpl scope) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(scope, "scope");
        long creationTick = currentTick();
        TaskCompletionSource<T> source = new TaskCompletionSource<>();

        if (disabled || !scope.isActive()) {
            TaskErrorRecord rejected = scope.closeRecordOrNull() != null
                ? scope.closeRecordOrNull()
                : TaskErrorRecord.cancelled(
                    type, ERR_PLUGIN_DISABLED, "scheduler is disabled");
            recordAndNotify(rejected);
            return rejectedTicket(type, creationTick, source, rejected);
        }
        TaskErrorRecord unavailable = scope.checkAvailable(type);
        if (unavailable != null) {
            recordAndNotify(unavailable);
            return rejectedTicket(type, creationTick, source, unavailable);
        }

        boolean async = type == TaskType.ASYNC;
        BukkitScheduledTask scheduled =
            new BukkitScheduledTask(plugin, type, creationTick, tracked, false);
        TicketTask<T> ticket = new TicketTask<>(plugin, type, creationTick, source);
        ticket.setOnSettle(() -> scope.untrack(ticket));
        Runnable retired = () -> {
            scheduled.finish();
            TaskErrorRecord retiredRecord = TaskErrorRecord.cancelled(
                type, ERR_ENTITY_INVALID, "entity retired before execution");
            recordAndNotify(retiredRecord);
            ticket.trySettle(TaskResult.cancelled(retiredRecord));
        };
        Runnable wrapped = () -> {
            if (disabled || scheduled.isCancelRequested() || scheduled.hasFinished()
                || !scope.isActive()) {
                // 關閉當下已由關閉路徑記錄；此處只寫終態、不重複記錄。
                ticket.trySettle(TaskResult.cancelled(scope.closeRecordOrNull()));
                scheduled.cancel();
                return;
            }
            TaskErrorRecord gone = scope.checkAvailable(type);
            if (gone != null) {
                // 擁有者在派送後、執行前失效（例如退服事件尚在派送途中）：
                // 此處是唯一的紀錄點，必須記錄。
                recordAndNotify(gone);
                ticket.trySettle(TaskResult.cancelled(gone));
                scheduled.cancel();
                return;
            }
            T value;
            try {
                value = action.get();
            } catch (Throwable failure) {
                TaskErrorRecord threw = TaskErrorRecord.threw(
                    type, ERR_TASK_EXCEPTION,
                    "user task threw exception: " + safeMessage(failure), failure);
                recordAndNotify(threw);
                ticket.trySettle(TaskResult.failed(failure, threw));
                scheduled.finish();
                return;
            }
            if (!scope.isActive() || disabled) {
                ticket.trySettle(TaskResult.cancelled(scope.closeRecordOrNull()));
            } else {
                ticket.trySettle(TaskResult.completed(value));
            }
            scheduled.finish();
        };
        ScheduledTask delegate = dispatchCore(type, scheduled, wrapped, retired,
            player, entityOrLoc, 0L, 0L, async, record ->
                ticket.trySettle(TaskResult.rejected(record)));
        ticket.attach(delegate);
        if (source.isDone()) {
            // backend 在派送內同步執行（測試 stub）：終態已寫入，不追蹤。
            return ticket;
        }
        scope.track(ticket);
        if (!scope.isActive() || disabled) {
            // 派送期間作用域關閉或停用（關閉快照未含此票據）：
            // 在此補上取消終態，否則票據永遠沒有終態。
            // 關閉紀錄優先（退服／退休／停用原因）；尚無紀錄時以無紀錄取消收斂。
            ticket.trySettle(TaskResult.cancelled(scope.closeRecordOrNull()));
            scheduled.cancel();
        } else if (delegate.isCancelled()) {
            // 派送返回前就被取消（空窗補取消）：接受後取消，終態為取消。
            ticket.trySettle(TaskResult.cancelled(null));
        }
        return ticket;
    }

    /**
     * 建立已完成的拒派票據（派送期拒絕用，不追蹤）。
     */
    private <T> TaskTicket<T> rejectedTicket(TaskType type,
                                             long creationTick,
                                             TaskCompletionSource<T> source,
                                             TaskErrorRecord rejected) {
        TicketTask<T> ticket = new TicketTask<>(plugin, type, creationTick, source);
        ticket.trySettle(TaskResult.rejected(rejected));
        ticket.attach(new NoOpScheduledTask(plugin, type));
        return ticket;
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
     * 或 recorder 內部狀態。package-private：作用域實作共用同一條紀錄路徑。</p>
     */
    void recordAndNotify(TaskErrorRecord record) {
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

    static long currentTick() {
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
     *
     * <p>持有底層 {@link PlatformTaskHandle}，使 {@link #cancel()}、
     * {@code cancelAll()} 與 plugin disable 都能真正作用到底層任務。同時負責
     * 解除追蹤：一次性任務執行結束、Folia entity 退役、或被取消時，
     * {@link #finish()} 會把自己從 {@code tracked} 移除。</p>
     */
    static final class BukkitScheduledTask implements ScheduledTask {
        private final JavaPlugin plugin;
        private final TaskType type;
        private final long creationTick;
        private final Set<ScheduledTask> tracked;
        private final boolean repeating;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        /**
         * 是否曾被呼叫 {@link #cancel()}。
         *
         * <p>狀態查詢失敗時唯一的可信依據：它是「我們確實下過取消指令」的事實，
         * 不是對平台狀態的推測。</p>
         */
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile PlatformTaskHandle handle;

        BukkitScheduledTask(JavaPlugin plugin, TaskType type, long creationTick,
                            Set<ScheduledTask> tracked, boolean repeating) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.type = Objects.requireNonNull(type, "type");
            this.creationTick = creationTick;
            this.tracked = Objects.requireNonNull(tracked, "tracked");
            this.repeating = repeating;
        }

        /**
         * 綁定底層句柄。
         *
         * <p>句柄必須在 dispatch 成功後才存在，但解除追蹤的時機可能早於
         * dispatch 回傳（例如 backend 在 dispatch 內同步執行了 runnable），
         * 因此把兩者拆成「先建立 task → 後綁定句柄」。</p>
         */
        void attach(PlatformTaskHandle handle) {
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        /**
         * 本任務是否已完成收尾（一次性任務已執行結束或已被取消）。
         *
         * <p>供 dispatch 在「backend 於 dispatch 內部就同步執行 runnable」時，
         * 判斷解除追蹤是否已先於登記發生。週期任務永遠回 false：它必須持續
         * 被追蹤到被取消為止。</p>
         */
        boolean hasFinished() {
            return !repeating && finished.get();
        }

        /**
         * 是否曾被要求取消（飛行中任務的可見取消訊號）。
         *
         * <p>供 dispatch 在 backend 阻塞期間被 {@link #cancel()} 或
         * {@link SafeSchedulerImpl#onPluginDisable()} 插入時，於句柄晚返回後
         * 補一次底層取消；亦供已排入的回呼在停用後直接跳過使用者程式，避免
         * 停用後仍產生效果。
         */
        boolean isCancelRequested() {
            return cancelRequested.get();
        }

        /**
         * 解除追蹤（冪等）。
         *
         * <p>只在一次性任務呼叫；週期任務必須持續被追蹤，否則 disable 時
         * 無法取消還在跑的重複任務。{@code finished} 讓重複呼叫（例如取消後
         * 又收到執行結束通知）不重複做事。</p>
         */
        void finish() {
            if (repeating) {
                return;
            }
            if (finished.compareAndSet(false, true)) {
                tracked.remove(this);
            }
        }

        @Override
        public void cancel() {
            cancelRequested.set(true);
            PlatformTaskHandle current = handle;
            if (current != null) {
                try {
                    current.cancel();
                } catch (Throwable t) {
                    // cancel 必須冪等、不影響其他任務，但失敗不可完全無聲：
                    // 底層任務可能仍在執行，記錄下來供診斷追查。
                    LOGGER.log(Level.FINE,
                        "task cancel failed (type=" + type + "): " + safeMessage(t), t);
                }
            }
            finished.set(true);
            tracked.remove(this);
        }

        @Override
        public boolean isCancelled() {
            PlatformTaskHandle current = handle;
            if (current == null) {
                // dispatch 尚未完成（理論上不會對外暴露）時，以本地收尾狀態為準。
                return finished.get() || cancelRequested.get();
            }
            try {
                return current.isCancelled() || cancelRequested.get();
            } catch (Throwable t) {
                // 狀態查詢失敗不代表已取消：isCancelled() 是上層判定「派送被接受」
                // 或「被拒絕」的依據（見 SafeSchedulerPlayerContextExecutor），把查詢
                // 失敗當成已取消會讓成功派送被誤判為拒絕。此時只回報我們確實做過的
                // 事：是否曾被要求取消。
                LOGGER.log(Level.FINE,
                    "task state query failed (type=" + type + "): " + safeMessage(t), t);
                return cancelRequested.get();
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
}