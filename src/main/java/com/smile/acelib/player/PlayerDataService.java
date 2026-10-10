package com.smile.acelib.player;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.MemoryRecord;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.Record;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 玩家資料服務。
 *
 * <p>協調玩家 join / quit lifecycle、非同步載入 / 保存、session 與資料的
 * 對應，以及快速登入登出、名稱變更、reload / disable 的資源管理。</p>
 *
 * <h2>執行緒模型</h2>
 * <ul>
 *   <li>{@link #onPlayerJoin(UUID, String)} 同步建立 session（state=LOADING），
 *       並回傳 {@link CompletableFuture}，資料載入完成時 future 完成</li>
 *   <li>{@link #onPlayerQuit(UUID)} 同步標記 session 為 UNLOADING，
 *       並回傳 future；保存完成（或失敗）時 future 完成</li>
 *   <li>呼叫端提供的 {@link Executor} 用於 task queuing；實際 store I/O 由
 *       內部 <strong>per-store serial executor</strong> 執行，確保對
 *       {@link DataStore} 的 {@code root()} / {@code save()} 存取不論 caller
 *       executor 為單一或多執行緒，永遠序列化（避免非 thread-safe
 *       {@link DataStore} 內部 map 損壞）</li>
 * </ul>
 *
 * <h2>未就緒語意</h2>
 * <p>caller 可用 {@link #getData(UUID)} 取得當下資料；若 session 為
 * LOADING，回傳 {@link Optional#empty()}（caller 決定等待或拒絕）。</p>
 * <p>需要「等待資料就緒」可使用 {@link #withLoadedData(UUID, Function)} —
 * 該方法會在資料 READY 時執行 callback。</p>
 *
 * <h2>資料儲存格式</h2>
 * <p>底層 {@link DataStore} 使用 {@code "players.<uuid>.<key>"} 路徑表達
 * 玩家資料；每個玩家一個子 record（{@link Record}）。資料變更需透過
 * {@link #markDirty(UUID)} 標記（否則 quit 時不會觸發保存）。</p>
 *
 * <h2>名稱變更</h2>
 * <p>同一 UUID 不同名稱重新登入：舊 session 必須先 end；新 session 透過
 * {@link #onPlayerJoin(UUID, String)} 建立並以新 name 取代。</p>
 *
 * <h2>關閉語意</h2>
 * <ul>
 *   <li>設定 atomic shutdown flag；新 join/quit 立刻以 PLAYER-007 拒絕</li>
 *   <li>等待 in-flight tasks 完成（{@link #awaitInFlight(long, TimeUnit)}）；
 *       完成中任務於寫回 cache 前再次檢查 shutdown flag，避免 late resurrection</li>
 *   <li>flush 所有 dirty record 至 store（即使沒有 quit）</li>
 *   <li>清除 session registry 與 records map</li>
 *   <li>冪等；重複呼叫不丟例外</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-PLAYER-001}：資料尚未就緒（caller 主動查詢 LOADING session）</li>
 *   <li>{@code ACELIB-PLAYER-002}：資料載入失敗</li>
 *   <li>{@code ACELIB-PLAYER-003}：資料保存失敗</li>
 *   <li>{@code ACELIB-PLAYER-004}：session 重複登入（無 quit 進行中卻已有 session；
 *       舊 session 尚在 UNLOADING 保存時，新 join 改為鏈接舊 quit、完成後自動重試）</li>
 *   <li>{@code ACELIB-PLAYER-005}：session 未找到</li>
 *   <li>{@code ACELIB-PLAYER-006}：DataStore 未初始化</li>
 *   <li>{@code ACELIB-PLAYER-007}：服務已關閉</li>
 *   <li>{@code ACELIB-PLAYER-008}：內部 serial executor 終止失敗</li>
 * </ul>
 *
 * @see PlayerSessionRegistry
 * @since 1.0.0
 */
public final class PlayerDataService {

    private static final String PLAYER_ROOT = "players";

    /** Per-store 序列化的最大 in-flight 等待時間（避免無窮等）。 */
    private static final long SHUTDOWN_INFLIGHT_TIMEOUT_MS = 5_000L;
    /** Per-store 序列化的 in-flight 等待 poll 間隔。 */
    private static final long SHUTDOWN_POLL_INTERVAL_MS = 25L;
    /** {@link #waitForState} 的輪詢間隔。 */
    private static final long WAIT_FOR_STATE_POLL_MS = 10L;

    /** 逐玩家儲存後端；1.4.0 主要路徑。與 {@link #legacyStore} 互斥。 */
    private final PlayerDataStore playerStore;
    /**
     * 舊版整棵 record tree 儲存後端；僅為相容既有 {@code DataStore} 建構子而保留。
     * 與 {@link #playerStore} 互斥：兩者恰有一個非 null。
     */
    private final DataStore legacyStore;
    private final Executor ioExecutor;
    /** 定期保存週期（毫秒）。 */
    private final long saveIntervalMillis;
    /**
     * 定期保存排程器；舊版 {@link DataStore} 建構子不啟用（為 null）。
     *
     * <p>獨立於 {@link #serialStoreExecutor}：定時觸發不應佔用儲存序列化通道。</p>
     */
    private final ScheduledExecutorService autosaveExecutor;
    /** 定期保存排程句柄；{@link #shutdown()} 時取消。 */
    private final ScheduledFuture<?> autosaveFuture;
    /**
     * 資料就緒 listener；每次 {@link #addReadyListener} 加一項，解除時移除。
     *
     * <p>用 {@link CopyOnWriteArrayList}：讀取發生在 I/O executor，註冊／解除
     * 來自任意執行緒（多半是主執行緒），避免併發修改例外。</p>
     */
    private final CopyOnWriteArrayList<ReadyListenerEntry> readyListeners =
        new CopyOnWriteArrayList<>();
    /**
     * 內部 per-store 序列 executor — 所有 {@code store.root()} / {@code store.save()}
     * 存取皆透過此單執行緒，確保對非 thread-safe 的 {@link DataStore}
     * 內部 map 不會發生 race。
     */
    private final java.util.concurrent.ExecutorService serialStoreExecutor;
    /**
     * 每位玩家「上次成功落盤的欄位內容」，作為增量寫入的差分基準。
     *
     * <p>只在 {@link #serialStoreExecutor} 上讀寫，因此不需要額外同步；
     * 玩家離線並成功保存後移除，避免快取無限增長。</p>
     */
    private final ConcurrentMap<UUID, Map<String, Object>> persistedFields =
        new ConcurrentHashMap<>();
    /** 內部 executor 的 graceful shutdown timeout；同時是 flush 等待的預設上限。 */
    private static final long SERIAL_EXECUTOR_TERMINATION_MS = 2_000L;
    /** 單次 store 讀取（join 載入、離線讀取）的等待上限。 */
    private static final long LOAD_TIMEOUT_MS = 5_000L;
    /**
     * 單批 flush 的等待上限（毫秒）。
     *
     * <p>逾時不等於保存成功：{@link #runFlushBatch} 會取消任務並以
     * {@code ACELIB-PLAYER-008} 回報，dirty 全數保留供下次重試。預設值取
     * {@link #SERIAL_EXECUTOR_TERMINATION_MS}，讓 flush 與 executor 終止有同一個
     * 時間界；測試可注入更短的上限以取得決定性的逾時行為。</p>
     */
    private final long flushTimeoutMillis;
    /**
     * 定期保存預設週期（毫秒）。
     *
     * <p>60 秒是「伺服器異常結束時最多遺失一個保存週期」與寫入量之間的取捨：
     * 週期越短遺失越少，但每個週期都會產生交易。</p>
     */
    public static final long DEFAULT_SAVE_INTERVAL_MS = 60_000L;
    private final PlayerSessionRegistry registry = new PlayerSessionRegistry();
    /**
     * 玩家資料快取：uuid → PlayerRecordView（持有 LockedPlayerRecord + dirty flag）。
     *
     * <p>使用 {@link LockedPlayerRecord} 包裝而非直接持有 {@link MemoryRecord}，
     * 是為了在 caller 透過 {@link #getData(UUID)} 取得 Record 並 mutate 時，
     * 與 service 在 {@link #serialStoreExecutor} 上執行的 snapshot 序列化
     * （共用同一把 lock），避免非 thread-safe 的內部 {@code LinkedHashMap}
     * 產生 {@link java.util.ConcurrentModificationException}。</p>
     */
    private final ConcurrentMap<UUID, PlayerRecordView> records = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    /**
     * 進行中的 quit future：uuid → quit future。
     *
     * <p>UNLOADING 保存進行中重連的鏈接點 — 新 join 不再同步拋 PLAYER-004，
     * 而是鏈接此 future，quit 完成（成功或保存失敗皆可）後自動重試建 session。
     * quit task 於完成 future 前先移除本 entry（happens-before 保證重試鏈
     * 被喚醒時登記已撤），另有 whenComplete 兜底，不殘留。</p>
     */
    private final ConcurrentMap<UUID, CompletableFuture<Void>> pendingQuits = new ConcurrentHashMap<>();
    /**
     * 等待中的重試鏈：uuid → 共享的重試 future。
     *
     * <p>同 UUID 在 quit 完成前多次重連共享單一 pending chain，避免 quit
     * 完成瞬間多個 join 同時重建 session 造成風暴。重試 future 完成
     * （成功或失敗皆可）時立即移除，不殘留。</p>
     */
    private final ConcurrentMap<UUID, CompletableFuture<Void>> pendingRejoins = new ConcurrentHashMap<>();
    /** 當前 in-flight 非同步任務數（onPlayerJoin / onPlayerQuit / withLoadedData）。 */
    private final AtomicInteger inFlightOps = new AtomicInteger(0);
    /**
     * 內部 serial executor 終止旗標 — shutdown() 期間設為 true；後續 store I/O
     * 路徑若觀察到 true，會以 PLAYER-008 立即失敗（避免 executor 已 shutdown
     * 卻仍提交任務）。
     */
    private final AtomicBoolean serialExecutorTerminated = new AtomicBoolean(false);

    /**
     * 尚未完成的 async 離線讀取登錄 — {@link #shutdownSerialExecutor()}
     * 強制終止時，被移出佇列或被中斷的工作不會再執行，靠這本登錄簿逐一以
     * {@code ACELIB-PLAYER-008} 完成其 future 並扣回計數。
     */
    private final Set<PendingOfflineRead> pendingOfflineReads =
        ConcurrentHashMap.newKeySet();

    /**
     * 主要建構子。
     *
     * @param store      已初始化的 {@link DataStore}；不可為 null 且須 {@link DataStore#isInitialized()}
     * @param ioExecutor I/O 用的 executor；不可為 null
     * @throws NullPointerException     任何參數為 null
     * @throws PlayerStateException     {@code store} 未初始化（{@code ACELIB-PLAYER-006}）
     */
    public PlayerDataService(DataStore store, Executor ioExecutor) {
        this.legacyStore = Objects.requireNonNull(store, "store");
        this.playerStore = null;
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        if (!store.isInitialized()) {
            throw new PlayerStateException("ACELIB-PLAYER-006",
                "DataStore must be initialized before constructing PlayerDataService; "
                    + "name=" + store.name() + ", isInitialized=" + store.isInitialized());
        }
        this.serialStoreExecutor = createSerialStoreExecutor(store.name());
        this.autosaveExecutor = null;
        this.autosaveFuture = null;
        this.saveIntervalMillis = DEFAULT_SAVE_INTERVAL_MS;
        this.flushTimeoutMillis = SERIAL_EXECUTOR_TERMINATION_MS;
    }

    /**
     * 以逐玩家 store 建立服務，並啟用定期保存。
     *
     * <p>這是 1.4.0 的主要建構路徑：資料以「一位玩家一組欄位」落盤，
     * 定期保存只寫有變動的欄位。</p>
     *
     * @param playerStore       已 {@code init()} 的 {@link PlayerDataStore}；不可為 null
     * @param ioExecutor        I/O 用的 executor；不可為 null，且<strong>不會被本服務關閉</strong>
     * @param saveIntervalMillis 定期保存週期（毫秒）；必須為正數
     * @throws NullPointerException     任何參數為 null
     * @throws IllegalArgumentException {@code saveIntervalMillis} 非正數
     * @throws PlayerStateException     {@code playerStore} 尚未 {@code init()}
     *                              （{@code ACELIB-PLAYER-006}）
     */
    public PlayerDataService(PlayerDataStore playerStore, Executor ioExecutor,
            long saveIntervalMillis) {
        this(playerStore, ioExecutor, saveIntervalMillis,
            SERIAL_EXECUTOR_TERMINATION_MS);
    }

    /**
     * 以逐玩家 store 建立服務，啟用定期保存並自訂單批 flush 的等待上限。
     *
     * <p>逾時語意與三參數建構子相同：逾時以 {@code ACELIB-PLAYER-008} 回報且
     * <strong>不等於保存成功</strong>，dirty 保留供下次重試。</p>
     *
     * @param playerStore       已 {@code init()} 的 {@link PlayerDataStore}；不可為 null
     * @param ioExecutor        I/O 用的 executor；不可為 null，且<strong>不會被本服務關閉</strong>
     * @param saveIntervalMillis 定期保存週期（毫秒）；必須為正數
     * @param flushTimeoutMillis 單批 flush 的等待上限（毫秒）；必須為正數
     * @throws NullPointerException     任何參數為 null
     * @throws IllegalArgumentException {@code saveIntervalMillis} 或
     *                                  {@code flushTimeoutMillis} 非正數
     * @throws PlayerStateException     {@code playerStore} 尚未 {@code init()}
     *                              （{@code ACELIB-PLAYER-006}）
     * @since 1.4.0
     */
    public PlayerDataService(PlayerDataStore playerStore, Executor ioExecutor,
            long saveIntervalMillis, long flushTimeoutMillis) {
        this.playerStore = Objects.requireNonNull(playerStore, "playerStore");
        this.legacyStore = null;
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        if (!playerStore.isInitialized()) {
            throw new PlayerStateException("ACELIB-PLAYER-006",
                "PlayerDataStore must be initialized before constructing PlayerDataService; "
                    + "name=" + playerStore.name()
                    + ", isInitialized=" + playerStore.isInitialized());
        }
        if (saveIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                "saveIntervalMillis 必須為正數：" + saveIntervalMillis);
        }
        if (flushTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                "flushTimeoutMillis 必須為正數：" + flushTimeoutMillis);
        }
        this.saveIntervalMillis = saveIntervalMillis;
        this.flushTimeoutMillis = flushTimeoutMillis;
        this.serialStoreExecutor = createSerialStoreExecutor(playerStore.name());
        this.autosaveExecutor = createAutosaveExecutor();
        this.autosaveFuture = scheduleAutosave(saveIntervalMillis);
    }

    /**
     * 建立 per-store 序列 executor — 單一 daemon thread，名稱含 store name 以利除錯。
     */
    private static java.util.concurrent.ExecutorService createSerialStoreExecutor(
            String storeName) {
        final String name = storeName;
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong serial = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "acelib-player-store-serial-" + name + "-"
                    + serial.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    /**
     * 建立定期保存用的排程器 — 單一 daemon thread。
     *
     * <p>獨立於 serial store executor：定時觸發本身不應佔用儲存序列化通道，
     * 避免排程延遲時把儲存工作堵住。</p>
     */
    private static ScheduledExecutorService createAutosaveExecutor() {
        ThreadFactory tf = runnable -> {
            Thread thread = new Thread(runnable, "acelib-player-autosave");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(tf);
    }

    private ScheduledFuture<?> scheduleAutosave(long saveIntervalMillis) {
        return autosaveExecutor.scheduleWithFixedDelay(
            () -> runAutosaveCycle(), saveIntervalMillis, saveIntervalMillis,
            TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    /**
     * 玩家登入：建立 session 並啟動非同步資料載入。
     *
     * <p>行為：</p>
     * <ol>
     *   <li>同步檢查 shutdown flag；若已 shutdown 立刻拋 PLAYER-007</li>
     *   <li>若該 UUID 有 quit 進行中（舊 session 處於 UNLOADING 保存階段）：
     *       不拋 PLAYER-004，而是回傳鏈接舊 quit future 的重試 future —
     *       quit 完成（成功或保存失敗皆可）後自動重試建立 session，全程
     *       future 鏈接、不 sleep 輪詢；等待期間 shutdown 則重試以
     *       PLAYER-007 失敗，不建立 session</li>
     *   <li>無 quit 進行中卻已有 session（真正重複登入）：同步拋 PLAYER-004</li>
     *   <li>遞增 in-flight 計數；於 {@code ioExecutor} 上排程 task，task 內委派
     *       給 {@code serialStoreExecutor} 執行實際 store root() 載入</li>
     *   <li>成功：若前次 quit 保存失敗遺留 dirty 資料，將其整體合併回新載入資料
     *       （遺留優先，含已刪除 key 不復活）並保持 dirty；session 轉 READY，
     *       future 完成</li>
     *   <li>失敗：session 轉 ENDED，future 以 PLAYER-002 失敗完成</li>
     * </ol>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @param name 顯示名稱快照；不可為 null
     * @return 載入 future；完成時表示資料已就緒（成功）或失敗（future 內含例外）
     * @throws NullPointerException 任何參數為 null
     * @throws PlayerStateException 重複登入（{@code ACELIB-PLAYER-004}）或服務已關閉（{@code ACELIB-PLAYER-007}）
     */
    public CompletableFuture<Void> onPlayerJoin(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        ensureNotShutdown();
        CompletableFuture<Void> quitInFlight = pendingQuits.get(uuid);
        if (quitInFlight != null) {
            return joinAfterQuit(uuid, name, quitInFlight);
        }
        try {
            return startJoinLoad(uuid, name);
        } catch (PlayerStateException duplicate) {
            if (!"ACELIB-PLAYER-004".equals(duplicate.getCode())) {
                throw duplicate;
            }
            // 與舊 quit 的登記交錯：startSession 當下 pendingQuits 已有
            // quit 登記 — 改走重試鏈而非拒絕。
            CompletableFuture<Void> lateQuit = pendingQuits.get(uuid);
            if (lateQuit != null) {
                return joinAfterQuit(uuid, name, lateQuit);
            }
            throw duplicate;
        }
    }

    /**
     * 鏈接舊 quit future 的重試：quit 完成後自動重建 session。
     *
     * <p>以 {@link ConcurrentMap#putIfAbsent} 佔位保證同 UUID 在 quit 完成前
     * 多次重連只建一條鏈（併發重連共享同一 future）；只有佔位勝出者才在 map
     * 之外接鏈 — mapping function 內不做同步副作用，避免已完成的鏈在寫入前
     * 就地完成、留下永不清理的 completed entry。鏈接全由 future
     * 組合完成，不 sleep 輪詢。重試前再檢查 shutdown flag，等待期間
     * shutdown 不再建 session。</p>
     *
     * <p>清理先移除屬於自己的 entry（{@code remove(key, exposed)}，不誤刪
     * 後建的新鏈），再完成回傳的 future：caller 經由它觀察到的完成，
     * 必已移除本 entry（happens-before），可直接斷言不殘留。</p>
     *
     * @param uuid       玩家 UUID
     * @param name       顯示名稱快照
     * @param quitFuture 舊 quit 的 future；成功或失敗完成皆觸發重試
     * @return 重試 future；quit 完成且重建成功時完成
     */
    private CompletableFuture<Void> joinAfterQuit(UUID uuid, String name,
            CompletableFuture<Void> quitFuture) {
        CompletableFuture<Void> exposed = new CompletableFuture<>();
        CompletableFuture<Void> existing = pendingRejoins.putIfAbsent(uuid, exposed);
        if (existing != null) {
            return existing;
        }
        inFlightOps.incrementAndGet();
        CompletableFuture<Void> chained;
        try {
            chained = quitFuture
                .handle((ignored, quitFailure) -> null)
                .thenCompose(ignored -> {
                    if (shutdown.get()) {
                        throw new PlayerStateException("ACELIB-PLAYER-007",
                            "service has been shut down while waiting for quit to complete; "
                                + "rejoin aborted for uuid=" + uuid);
                    }
                    return startJoinLoad(uuid, name);
                });
        } catch (Throwable syncFailure) {
            // 建鏈本身同步失敗（理論上不應發生）：撤回佔位並回滾計數，不殘留。
            pendingRejoins.remove(uuid, exposed);
            inFlightOps.decrementAndGet();
            exposed.completeExceptionally(syncFailure);
            return exposed;
        }
        chained.whenComplete((ignored, failure) -> {
            // 先清理再完成 exposed（happens-before），且只移除自己佔的 entry。
            pendingRejoins.remove(uuid, exposed);
            inFlightOps.decrementAndGet();
            if (failure == null) {
                exposed.complete(null);
            } else {
                exposed.completeExceptionally(failure);
            }
        });
        return exposed;
    }

    /**
     * 建立 session 並啟動非同步載入（join 的實際執行體）。
     *
     * <p>重複 UUID（且無 quit 進行中）時同步拋 PLAYER-004。</p>
     */
    private CompletableFuture<Void> startJoinLoad(UUID uuid, String name) {
        ensureNotShutdown();
        PlayerSession session = registry.startSession(uuid, name);
        CompletableFuture<Void> future = new CompletableFuture<>();
        inFlightOps.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                // 委派給 serialStoreExecutor 執行實際 root() 讀取
                PlayerRecordView view = loadFromStoreSerial(uuid);
                // late resurrection guard：寫回 cache 前再次確認 shutdown 狀態
                if (shutdown.get()) {
                    // service 已 shutdown — 不可將資料寫回 cache
                    future.complete(null);
                    return;
                }
                mergeRetainedDirty(uuid, view);
                session.transitionTo(PlayerSessionState.READY);
                // 資料就緒通知排在 READY 之後：listener 觀察 session 狀態時看到的
                // 是已就緒的 session。通知失敗只記錄、不影響 session 與載入結果。
                notifyDataReady(uuid, view);
                future.complete(null);
            } catch (Throwable t) {
                // 載入失敗：標記 session ENDED 並移除
                try {
                    session.transitionTo(PlayerSessionState.ENDED);
                } catch (IllegalStateException ignore) {
                    // 已被 ENDED — 忽略
                }
                registry.endSession(uuid);
                Throwable cause = unwrap(t);
                PlayerStateException wrapped = new PlayerStateException(
                    "ACELIB-PLAYER-002",
                    "failed to load player data for uuid=" + uuid + ": " + cause.getMessage(),
                    cause);
                future.completeExceptionally(wrapped);
            } finally {
                inFlightOps.decrementAndGet();
            }
        });
        return future;
    }

    /**
     * 玩家離線：保存資料並結束 session。
     *
     * <p>行為：</p>
     * <ol>
     *   <li>同步查找 session；找不到拋 PLAYER-005；shutdown 後拋 PLAYER-007</li>
     *   <li>遞增 in-flight 計數；於 {@code ioExecutor} 上排程 task</li>
     *   <li>若 session 尚未 READY（仍在 LOADING）：等待 load 完成後才進入保存階段</li>
     *   <li>標記 UNLOADING → 委派給 {@code serialStoreExecutor} 同步寫回並 store.save()</li>
     *   <li>成功：ENDED → 從 registry 移除</li>
     *   <li>保存失敗：session 轉 ENDED 並從 registry 移除（不可卡在 UNLOADING
     *       阻擋後續 join）；dirty 資料保留於 cache，重登時合併取回或由
     *       shutdown flush 重試；future 以 PLAYER-003 失敗完成</li>
     * </ol>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 保存 future；完成時表示資料已落地（成功）或失敗
     * @throws NullPointerException  當 {@code uuid} 為 null
     * @throws PlayerStateException  session 不存在（{@code ACELIB-PLAYER-005}）或服務已關閉（{@code ACELIB-PLAYER-007}）
     */
    public CompletableFuture<Void> onPlayerQuit(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        ensureNotShutdown();
        Optional<PlayerSession> opt = registry.getSession(uuid);
        if (opt.isEmpty()) {
            throw new PlayerStateException("ACELIB-PLAYER-005",
                "no active session for uuid=" + uuid);
        }
        PlayerSession session = opt.get();
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingQuits.put(uuid, future);
        future.whenComplete((ignored, failure) -> pendingQuits.remove(uuid, future));
        inFlightOps.incrementAndGet();
        try {
            ioExecutor.execute(() -> {
            PlayerRecordView view = null;
            try {
                // 若仍在 LOADING，等待資料就緒
                if (session.getState() == PlayerSessionState.LOADING) {
                    waitForState(session, PlayerSessionState.READY, 5000L);
                }
                // shutdown guard：等待期間可能已 shutdown — 此時不應保存（已 flush）
                if (shutdown.get()) {
                    try {
                        session.transitionTo(PlayerSessionState.ENDED);
                    } catch (IllegalStateException ignore) {
                        // ignore
                    }
                    registry.endSession(uuid);
                    // 先撤登記再完成 future：重試鏈被喚醒時本 entry 必已移除
                    //（happens-before），caller 可直接斷言不殘留。
                    pendingQuits.remove(uuid, future);
                    future.complete(null);
                    return;
                }
                if (session.getState() == PlayerSessionState.READY) {
                    session.transitionTo(PlayerSessionState.UNLOADING);
                } else if (session.getState() != PlayerSessionState.UNLOADING) {
                    throw new PlayerStateException("ACELIB-PLAYER-003",
                        "cannot unload uuid=" + uuid + " from state=" + session.getState());
                }
                view = records.remove(uuid);
                try {
                    if (view != null && view.dirty()) {
                        // 委派給 serialStoreExecutor 同步執行 root()/save()
                        // （saveToStoreSerial 內部已包含 store.save()，
                        //  故不需在外部再呼叫一次 — 否則會在 ioExecutor 執行
                        //  第二次 save，導致 race）
                        saveToStoreSerial(uuid, view);
                    }
                } catch (Throwable saveEx) {
                    if (view != null) {
                        // 保存失敗時不得丟失唯一仍含 dirty 資料的 view —
                        // 保留於 records 供重登合併取回 / shutdown flush 重試。
                        records.put(uuid, view);
                    }
                    // 保存失敗仍須結束 session：轉 ENDED 並從 registry 移除，
                    // 否則 session 永久卡在 UNLOADING，後續所有 join 皆被
                    // PLAYER-004 拒絕。
                    try {
                        session.transitionTo(PlayerSessionState.ENDED);
                    } catch (IllegalStateException ignore) {
                        // 已為 ENDED — 忽略
                    }
                    registry.endSession(uuid);
                    pendingQuits.remove(uuid, future);
                    Throwable cause = unwrap(saveEx);
                    PlayerStateException wrapped = new PlayerStateException(
                        "ACELIB-PLAYER-003",
                        "failed to save player data for uuid=" + uuid + ": " + cause.getMessage()
                            + " (dirty data retained; rejoin to recover or retry on shutdown)",
                        cause);
                    future.completeExceptionally(wrapped);
                    return;
                }
                session.transitionTo(PlayerSessionState.ENDED);
                registry.endSession(uuid);
                pendingQuits.remove(uuid, future);
                // 玩家已離線且資料確實落盤：差分基準不再需要，移除避免快取
                // 隨累積玩家數無限增長。重登時會重新從 store 載入基準。
                persistedFields.remove(uuid);
                future.complete(null);
            } catch (Throwable t) {
                // 任何其他例外：仍需結束 session 避免殘留
                if (view != null) {
                    records.put(uuid, view);
                }
                try {
                    if (session.getState() != PlayerSessionState.ENDED) {
                        try {
                            session.transitionTo(PlayerSessionState.ENDED);
                        } catch (IllegalStateException ignore) {
                            // ignore
                        }
                    }
                } finally {
                    registry.endSession(uuid);
                }
                pendingQuits.remove(uuid, future);
                Throwable cause = unwrap(t);
                future.completeExceptionally(new PlayerStateException(
                    "ACELIB-PLAYER-003",
                    "unexpected error during onPlayerQuit(uuid=" + uuid + "): "
                        + cause.getMessage(),
                    cause));
            } finally {
                inFlightOps.decrementAndGet();
            }
        });
        } catch (RejectedExecutionException rejected) {
            // 外部 executor 拒絕派送：quit 未實際啟動 — 撤回登記並回滾計數，
            // 不殘留 pendingQuits entry 卡住後續 join。
            pendingQuits.remove(uuid, future);
            inFlightOps.decrementAndGet();
            throw rejected;
        }
        return future;
    }

    /**
     * 將指定玩家標記為 dirty（後續 quit 時需保存）。
     *
     * <p>caller 在修改 {@link #getData(UUID)} 回傳的 record 後必須呼叫此方法，
     * 否則 quit 時不會觸發保存。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @throws NullPointerException     當 {@code uuid} 為 null
     * @throws PlayerStateException     當 session 不存在（{@code ACELIB-PLAYER-005}）
     */
    public void markDirty(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        PlayerRecordView view = records.get(uuid);
        if (view == null) {
            throw new PlayerStateException("ACELIB-PLAYER-005",
                "no active session for uuid=" + uuid + " (cannot mark dirty)");
        }
        view.markDirty();
    }

    /**
     * 取得指定玩家的當下資料（若 session 已 READY）。
     *
     * <p>回傳的 {@link Record} 為 {@link LockedPlayerRecord} 包裝，
     * 所有 {@code set}/{@code get} 等操作皆會 lock，與 service 在
     * {@link #serialStoreExecutor} 上的 snapshot 共用同一把 lock —
     * caller 可安全地 mutate 而無需擔心 race。</p>
     *
     * <p>未找到 session 或 session 仍在 LOADING → 回傳
     * {@link Optional#empty()}。caller 可選擇：</p>
     * <ul>
     *   <li>等待：使用 {@link #withLoadedData(UUID, Function)}</li>
     *   <li>拒絕：依業務需求回傳錯誤給玩家</li>
     * </ul>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 對應 record；未就緒或未登入回傳 empty
     */
    public Optional<Record> getData(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Optional<PlayerSession> opt = registry.getSession(uuid);
        if (opt.isEmpty() || !opt.get().isReady()) {
            return Optional.empty();
        }
        PlayerRecordView view = records.get(uuid);
        if (view == null) {
            return Optional.empty();
        }
        return Optional.of(view.record);
    }

    /**
     * 若 session 已 READY 且 record 仍在 cache，回傳其 view（fast-path 用）。
     *
     * <p>任一條件不成立回傳 null，caller 改走非同步等待路徑。</p>
     */
    private PlayerRecordView readyViewOrNull(UUID uuid) {
        if (shutdown.get()) {
            return null;
        }
        Optional<PlayerSession> opt = registry.getSession(uuid);
        if (opt.isEmpty() || opt.get().getState() != PlayerSessionState.READY) {
            return null;
        }
        return records.get(uuid);
    }

    /**
     * 在指定玩家資料「就緒」後執行 callback（異步等待）。
     *
     * <p>行為：</p>
     * <ul>
     *   <li>session 已 READY → 於 caller 所在執行緒直接執行 callback
     *      （不經 {@code ioExecutor} 排程）</li>
     *   <li>session 仍在 LOADING → 在 ioExecutor 上週期輪詢直到 READY 為止，
     *       或達 timeout（{@code 5 秒}）</li>
     *   <li>session 不存在 → future 以 PLAYER-005 失敗完成</li>
     * </ul>
     *
     * @param uuid     玩家 UUID；不可為 null
     * @param callback 對 record 執行的轉換；不可為 null
     * @param <R>      callback 回傳型別
     * @return 執行結果的 future
     * @throws NullPointerException 任何參數為 null
     */
    public <R> CompletableFuture<R> withLoadedData(UUID uuid, Function<Record, R> callback) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(callback, "callback");
        // Fast path：session 已 READY → 於 caller 執行緒直接執行 callback，
        // 兌現 Javadoc 約定且不佔用 ioExecutor。
        PlayerRecordView readyView = readyViewOrNull(uuid);
        if (readyView != null) {
            CompletableFuture<R> immediate = new CompletableFuture<>();
            try {
                immediate.complete(callback.apply(readyView.record));
            } catch (Throwable t) {
                immediate.completeExceptionally(unwrap(t));
            }
            return immediate;
        }
        CompletableFuture<R> future = new CompletableFuture<>();
        inFlightOps.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                Optional<PlayerSession> opt = registry.getSession(uuid);
                if (opt.isEmpty()) {
                    future.completeExceptionally(new PlayerStateException(
                        "ACELIB-PLAYER-005",
                        "no active session for uuid=" + uuid));
                    return;
                }
                PlayerSession session = opt.get();
                if (!waitForState(session, PlayerSessionState.READY, 5000L)) {
                    future.completeExceptionally(new PlayerStateException(
                        "ACELIB-PLAYER-001",
                        "player data not ready within timeout for uuid=" + uuid
                            + " (state=" + session.getState() + ")"));
                    return;
                }
                // late resurrection guard：等到 READY 後，確認尚未 shutdown 才取資料
                if (shutdown.get()) {
                    future.completeExceptionally(new PlayerStateException(
                        "ACELIB-PLAYER-007",
                        "service has been shut down while waiting for uuid=" + uuid));
                    return;
                }
                PlayerRecordView view = records.get(uuid);
                if (view == null) {
                    future.completeExceptionally(new PlayerStateException(
                        "ACELIB-PLAYER-001",
                        "player record missing for uuid=" + uuid));
                    return;
                }
                R result = callback.apply(view.record);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(unwrap(t));
            } finally {
                inFlightOps.decrementAndGet();
            }
        });
        return future;
    }

    /**
     * 快照保存失敗遺留的 dirty 資料合併（join 成功路徑）。
     *
     * <p>quit 保存失敗後 session 已 ENDED 並從 registry 移除，但 dirty view
     * 保留於 {@link #records}。重登載入成功時，若 cache 仍有該 UUID 的
     * dirty 遺留，採<strong>遺留整體採用</strong>：遺留快照內容新於 store，
     * 將其回放覆寫新載入資料並保持 dirty，使下一次 quit/shutdown 重試保存。</p>
     *
     * <p>刪除鍵語意：遺留快照沒有、而新載入資料含有的頂層 key，視為 quit
     * 失敗前已 {@code remove} 的刪除，一併從新載入資料移除 — 已刪除的 key
     * 不得因 store 舊值在重登後復活。此與保存路徑的整節點取代
     * （{@code root.set("players.<uuid>", snapshot)}）一致：成功保存會寫下
     * 的內容，正是遺留快照的整體（含刪除）。巢狀路徑的修改落在頂層 key
     * 的值覆寫內，同樣由遺留版本勝出。</p>
     *
     * <p>以 {@link ConcurrentMap#merge} 原子執行，避免 join 載入與並發
     * quit-failure 寫回交錯遺失 dirty。</p>
     *
     * @param uuid  玩家 UUID
     * @param fresh 剛自 store 載入的 view
     */
    private void mergeRetainedDirty(UUID uuid, PlayerRecordView fresh) {
        records.merge(uuid, fresh, (orphan, loaded) -> {
            if (orphan.dirty()) {
                Map<String, Object> retained = orphan.record.snapshotLocked();
                for (String key : loaded.record.keys()) {
                    if (!retained.containsKey(key)) {
                        loaded.record.remove(key);
                    }
                }
                for (Map.Entry<String, Object> entry : retained.entrySet()) {
                    loaded.record.set(entry.getKey(), entry.getValue());
                }
                loaded.markDirty();
            }
            return loaded;
        });
    }

    /**
     * 取得 session 物件（測試 / 觀察用）。
     *
     * @param uuid 玩家 UUID
     * @return 對應 session；若不存在回傳 empty
     */
    public Optional<PlayerSession> getSession(UUID uuid) {
        return registry.getSession(uuid);
    }

    /**
     * 取得當前 active session 數（觀察用）。
     *
     * @return active session 數
     */
    public int activeSessionCount() {
        return registry.size();
    }

    /**
     * 關閉服務：封閉新工作、等待 in-flight 完成、flush dirty I/O、清除 state。
     *
     * <h4>語意</h4>
     * <ol>
     *   <li>設定 atomic shutdown flag；後續 {@link #onPlayerJoin} /
     *       {@link #onPlayerQuit} 立刻以 PLAYER-007 拒絕</li>
     *   <li>等待 in-flight tasks 完成（最多
     *       {@value #SHUTDOWN_INFLIGHT_TIMEOUT_MS} 毫秒；逾時以 PLAYER-008 回報）</li>
     *   <li>在 serialStoreExecutor 上同步 flush 所有 dirty record（即使玩家未 quit，
     *       也保證資料不遺失）</li>
     *   <li>清除 session registry 與 records map</li>
     *   <li>graceful 終止內部 serialStoreExecutor（最多
     *       {@value #SERIAL_EXECUTOR_TERMINATION_MS} 毫秒）</li>
     * </ol>
     *
     * <p><strong>冪等</strong>：重複呼叫不丟例外。</p>
     *
     * <p><strong>失敗可重試</strong>：flush 失敗（保存錯誤
     * {@code ACELIB-PLAYER-003}、逾時或中斷 {@code ACELIB-PLAYER-008}）
     * 時 dirty 全保留，shutdown flag 回滾，呼叫端可稍後重試
     * {@code shutdown()}；重試會重新快照並完整重寫。逾時只表示「等不到
     * flush 完成」，不等於 flush 成功 — 不得把逾時前的資料當成已落盤。
     * flush 等待上限與 serial executor 終止共用
     * {@value #SERIAL_EXECUTOR_TERMINATION_MS} 毫秒。</p>
     *
     * <p><strong>不提供的保證</strong>：本服務只在 quit 與 shutdown 時保存，
     * 沒有自動保存；程序崩潰時未落盤的 dirty 資料會遺失，不宣稱崩潰復原。</p>
     *
     * <p><strong>無 late resurrection</strong>：在 in-flight task 完成寫回 cache 步驟
     * 之前，會再次檢查 shutdown flag；已 shutdown 時，task 不會將資料放回 records map，
     * 避免「以為已 shutdown、實際有殘留資料」的 race。</p>
     *
     * <p><strong>未完成的 async 離線讀取</strong>：graceful 終止讓它們正常跑完；
     * 逾時被迫強制終止時，尚未完成的讀取一律以 {@code ACELIB-PLAYER-008}
     * 完成，不會永久 pending。</p>
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return; // 冪等
        }
        // 1. 等待 in-flight tasks 完成
        awaitInFlight(SHUTDOWN_INFLIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        // 2. flush 所有 dirty record（即使玩家未 quit）
        try {
            flushAllDirtySync();
        } catch (PlayerStateException failure) {
            // A failed flush is retryable: retain the live record and serial channel so a
            // recovered store can be flushed without publishing a partially shut down service.
            shutdown.set(false);
            throw failure;
        }
        // 3. 只有成功保存後才清除 session registry 與 records map。
        registry.clear();
        records.clear();
        // 4. 停止定期保存排程，再終止 serialStoreExecutor：
        //    順序不可顛倒，否則排程可能在 executor 終止後再投遞任務。
        stopAutosaveScheduler();
        shutdownSerialExecutor();
    }

    /**
     * 停止內部定期保存排程（冪等；未啟用定期保存時為 no-op）。
     */
    private void stopAutosaveScheduler() {
        if (autosaveExecutor == null) {
            return;
        }
        ScheduledFuture<?> pending = autosaveFuture;
        if (pending != null) {
            pending.cancel(false);
        }
        autosaveExecutor.shutdown();
        try {
            if (!autosaveExecutor.awaitTermination(
                    SERIAL_EXECUTOR_TERMINATION_MS, TimeUnit.MILLISECONDS)) {
                autosaveExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            autosaveExecutor.shutdownNow();
        }
    }

    /**
     * 判斷服務是否已 shutdown。
     *
     * @return true 表示已 shutdown
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * 內部 serial store executor 是否已終止（package-private test seam —
     * executor 邊界測試用：外部注入的 {@code ioExecutor} 不可被關閉，
     * 僅內部 serial executor 於 shutdown 時終止）。
     *
     * @return true 表示內部 serial executor 已終止
     */
    boolean isSerialExecutorTerminated() {
        return serialExecutorTerminated.get();
    }

    /**
     * 等待 in-flight ops 計數歸零（最多 {@code timeout} 毫秒）。
     *
     * @param timeout 最大等待時間
     * @param unit    時間單位
     */
    private void awaitInFlight(long timeout, TimeUnit unit) {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (inFlightOps.get() > 0 && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(SHUTDOWN_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 在 serialStoreExecutor 上同步 flush 所有 dirty record。
     *
     * <p>委派給 internal executor 是因為 store 本身非 thread-safe；此處以
     * 「同步委派、等待完成」的方式確保 flush 一定發生在 serial thread 上。</p>
     *
     * <p><strong>批次語意</strong>：先把全部 dirty snapshot 寫回 store，
     * 最後只呼叫一次 {@code store.save()}。單次 save 失敗即整批視為未落盤：
     * records 不清除、dirty 全保留，由 {@link #shutdown()} 回滾 flag 後重試；
     * 重試時重新快照並完整重寫，不做增量補寫，故重試是安全的。</p>
     *
     * <p><strong>Timeout 語意</strong>：等待上限與內部 executor 的 graceful
     * 終止共用 {@value #SERIAL_EXECUTOR_TERMINATION_MS} 毫秒。逾時以
     * {@code ACELIB-PLAYER-008} 失敗並取消 flush 任務，但底層
     * {@code store.save()} 可能仍在進行 — 逾時不等於 flush 成功，
     * 不得把舊資料當成已落盤；dirty 照樣保留，重試會完整重寫。</p>
     */
    private void flushAllDirtySync() {
        // 快照 dirty record（避免 ConcurrentModification）
        Map<UUID, PlayerRecordView> snapshot = dirtySnapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        runFlushBatch(snapshot, flushTimeoutMillis);
    }

    /**
     * 快照目前所有有未落盤變更的玩家。
     *
     * @return uuid → view（僅含 dirty）
     */
    private Map<UUID, PlayerRecordView> dirtySnapshot() {
        Map<UUID, PlayerRecordView> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerRecordView> e : records.entrySet()) {
            if (e.getValue().dirty()) {
                snapshot.put(e.getKey(), e.getValue());
            }
        }
        return snapshot;
    }

    /**
     * 在 serial store executor 上保存整批，並在成功後推進已落盤序號。
     *
     * <p>逐玩家提交（{@link PlayerDataStore} 的每筆變更自成一交易），整批所有寫入
     * 成功後才推進任何已落盤序號。任一玩家失敗時整個快照仍保持 dirty；已提交的
     * 逐玩家 store 寫入會在重試時由差分基準收斂為 no-op，再確認整批保存。</p>
     *
     * @param snapshot 待保存的玩家
     * @param timeoutMillis 等待上限
     * @throws PlayerStateException 保存失敗（{@code ACELIB-PLAYER-003}）、
     *                              逾時或中斷（{@code ACELIB-PLAYER-008}）
     */
    private void runFlushBatch(Map<UUID, PlayerRecordView> snapshot, long timeoutMillis) {
        Future<?> flush;
        try {
            flush = serialStoreExecutor.submit(() -> {
                // 舊版 DataStore：先把整批寫進記憶體 tree，再呼叫一次 save() 落盤，
                // 因此這裡只收集序號、不逐位 markSaved。
                List<Map.Entry<UUID, Long>> written = new ArrayList<>();
                for (Map.Entry<UUID, PlayerRecordView> e : snapshot.entrySet()) {
                    written.add(Map.entry(e.getKey(),
                        writePlayerToStore(e.getKey(), e.getValue())));
                }
                flushLegacyStoreIfNeeded();
                // 確認落盤後才推進序號：save() 失敗時整批仍為 dirty，下次完整重寫。
                for (Map.Entry<UUID, Long> entry : written) {
                    snapshot.get(entry.getKey()).markSaved(entry.getValue());
                }
            });
        } catch (RejectedExecutionException rejected) {
            throw flushFailure("ACELIB-PLAYER-008", snapshot,
                "serial flush task rejected", rejected);
        }
        try {
            flush.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            throw flushFailure("ACELIB-PLAYER-003", snapshot,
                "serial flush failed", cause);
        } catch (TimeoutException timeout) {
            flush.cancel(true);
            throw flushFailure("ACELIB-PLAYER-008", snapshot,
                "serial flush timed out", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            flush.cancel(true);
            throw flushFailure("ACELIB-PLAYER-008", snapshot,
                "serial flush interrupted", interrupted);
        }
    }

    /**
     * 把單一玩家的資料寫進 store，回傳「快照當下」的變更序號。
     *
     * <p>本方法<strong>不</strong>推進已落盤序號：呼叫端必須在確認資料真的
     * 落盤之後，才用回傳的序號呼叫 {@link PlayerRecordView#markSaved(long)}。
     * 舊版 {@link DataStore} 要等 {@link #flushLegacyStoreIfNeeded()} 成功才算
     * 落盤，提前 markSaved 會讓保存失敗的變更被當成已保存、永遠不再重試。</p>
     *
     * <p>必須在 {@link #serialStoreExecutor} 上呼叫。</p>
     *
     * @param uuid 玩家 UUID
     * @param view 該玩家的資料視圖
     * @return 寫入當下的 {@code changeSeq}，供稍後 {@code markSaved} 使用
     */
    private long writePlayerToStore(UUID uuid, PlayerRecordView view) {
        long seqAtSnapshot = view.changeSeq.get();
        saveToStoreInternal(uuid, view);
        persistedFields.put(uuid, view.record.snapshotLocked());
        return seqAtSnapshot;
    }

    /**
     * 舊版 {@link DataStore} 的一次完整保存：寫入、落盤、確認成功後才標記已保存。
     *
     * <p>必須在 {@link #serialStoreExecutor} 上呼叫。</p>
     *
     * @param uuid 玩家 UUID
     * @param view 該玩家的資料視圖
     */
    private void saveOnePlayer(UUID uuid, PlayerRecordView view) {
        long seqAtSnapshot = writePlayerToStore(uuid, view);
        // 逐玩家 store 的變更在 applyChanges 內自成一交易，這裡是 no-op；
        // 舊版 DataStore 才是真正把整棵 tree 寫進檔案的步驟。
        flushLegacyStoreIfNeeded();
        view.markSaved(seqAtSnapshot);
    }

    /**
     * 舊版 {@link DataStore} 路徑需要額外呼叫一次 {@code save()} 落盤整棵 tree；
     * 逐玩家 store 的變更在 {@code applyChanges} 內自成一交易，不必再呼叫。
     */
    private void flushLegacyStoreIfNeeded() {
        if (legacyStore != null) {
            legacyStore.save();
        }
    }

    private PlayerStateException flushFailure(String code,
                                               Map<UUID, PlayerRecordView> dirty,
                                               String reason,
                                               Throwable cause) {
        return new PlayerStateException(code,
            reason + "; dirtyCount=" + dirty.size() + "; uuids=" + dirty.keySet(), cause);
    }

    /**
     * 同步委派給 internal executor 從 store 載入單一玩家資料。
     *
     * <p>保證 {@link DataStore#root()} 與後續 {@link MemoryRecord} 操作都發生在
     * 同一個執行緒上，避免對非 thread-safe 的內部 map 造成 race。</p>
     */
    private PlayerRecordView loadFromStoreSerial(UUID uuid) {
        if (serialExecutorTerminated.get()) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "internal serial store executor has been terminated; "
                    + "cannot load uuid=" + uuid);
        }
        try {
            return serialStoreExecutor.submit(() -> loadFromStoreInternal(uuid))
                .get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ee) {
            // unwrap 內部例外
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new DataStoreException("ACELIB-DATA-006",
                "serial load failed for uuid=" + uuid + ": " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "serial load timed out for uuid=" + uuid);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "serial load interrupted for uuid=" + uuid);
        }
    }

    /**
     * 同步委派給 internal executor 將單一玩家資料寫回 store。
     */
    private void saveToStoreSerial(UUID uuid, PlayerRecordView view) {
        if (serialExecutorTerminated.get()) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "internal serial store executor has been terminated; "
                    + "cannot save uuid=" + uuid);
        }
        try {
            serialStoreExecutor.submit(() -> saveOnePlayer(uuid, view))
                .get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new DataStoreException("ACELIB-DATA-006",
                "serial save failed for uuid=" + uuid + ": " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "serial save timed out for uuid=" + uuid);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "serial save interrupted for uuid=" + uuid);
        }
    }

    /**
     * 終止 internal serialStoreExecutor（graceful + force fallback）。
     *
     * <p>強制終止後，以 {@code ACELIB-PLAYER-008} 完成所有尚未完成的 async
     * 離線讀取：被移出佇列的工作不會再執行，不靠這一步就會永久 pending。</p>
     */
    private void shutdownSerialExecutor() {
        serialStoreExecutor.shutdown();
        try {
            if (!serialStoreExecutor.awaitTermination(
                SERIAL_EXECUTOR_TERMINATION_MS, TimeUnit.MILLISECONDS)) {
                serialStoreExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            serialStoreExecutor.shutdownNow();
        } finally {
            failPendingOfflineReads();
            serialExecutorTerminated.set(true);
        }
    }

    /**
     * 以 {@code ACELIB-PLAYER-008} 完成所有尚未完成的 async 離線讀取。
     *
     * <p>每個 ticket 恰好扣回一次計數（CAS）：工作自身的 {@code finally} 與這裡
     * 不會重複扣回；已完成的 future 不受影響（{@code completeExceptionally}
     * 在已完成時為 no-op）。graceful 終止讓全部工作正常跑完時，這本登錄簿已空，
     * 此方法為 no-op。</p>
     */
    private void failPendingOfflineReads() {
        for (PendingOfflineRead ticket : new ArrayList<>(pendingOfflineReads)) {
            pendingOfflineReads.remove(ticket);
            ticket.future.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-008",
                "internal serial store executor terminated while reading; "
                    + "offline read did not complete"));
            ticket.settle();
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * 從 store 讀取既有 record；不存在則回傳新的空 record。
     *
     * <p><strong>執行緒合約</strong>：本方法必須從 {@link #serialStoreExecutor}
     * 內呼叫；保證 {@link DataStore#root()} 與 {@link MemoryRecord} 內部 map
     * 的存取永遠在單一執行緒上完成。回傳的 record 為
     * {@link LockedPlayerRecord} 包裝，caller mutation 與後續 save 的
     * snapshot 共用同一把 lock。</p>
     */
    private PlayerRecordView loadFromStoreInternal(UUID uuid) {
        if (playerStore != null) {
            Optional<Record> stored = playerStore.load(uuid);
            Map<String, Object> snapshot = copyToMap(stored);
            // 剛從 store 讀出的內容就是「已落盤的基準」：預先填入差分基準，
            // 讓第一次保存不必再讀一次 store。
            persistedFields.put(uuid, new LinkedHashMap<>(snapshot));
            return new PlayerRecordView(new LockedPlayerRecord(new MemoryRecord("", snapshot)));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Record root = legacyStore.root();
        Record playersNode = root.getRecord(PLAYER_ROOT, null);
        if (playersNode != null) {
            Object playerData = playersNode.get(uuid.toString());
            if (playerData instanceof Map<?, ?> playerMap) {
                // 將既有資料拷貝進新 record
                for (Map.Entry<?, ?> e : playerMap.entrySet()) {
                    if (e.getKey() instanceof String key) {
                        snapshot.put(key, e.getValue());
                    }
                }
            }
        }
        return new PlayerRecordView(new LockedPlayerRecord(new MemoryRecord("", snapshot)));
    }

    /**
     * 將 view 寫回 store。
     *
     * <p><strong>執行緒合約</strong>：本方法必須從 {@link #serialStoreExecutor}
     * 內呼叫。snapshot 透過 {@link LockedPlayerRecord#snapshotLocked()}
     * 取得，確保與 caller 正在進行的 mutate 操作互斥。</p>
     */
    private void saveToStoreInternal(UUID uuid, PlayerRecordView view) {
        Map<String, Object> snapshot = view.record.snapshotLocked();
        if (playerStore != null) {
            // 逐玩家 store：以快照差分只送變動欄位；值未變的欄位由 store 判斷後跳過。
            List<PlayerDataStore.FieldChange> changes = diffAgainstPersisted(uuid, snapshot);
            if (!changes.isEmpty()) {
                playerStore.applyChanges(changes);
            }
            return;
        }
        Record root = legacyStore.root();
        root.set(PLAYER_ROOT + "." + uuid.toString(), snapshot);
    }

    /**
     * 計算「目前快照」相對於「上次落盤內容」的欄位差分。
     *
     * <p>只送新增或值變了的欄位，以及需要移除的欄位；值相同的欄位完全不進批次，
     * 因此不會在底層產生寫入。</p>
     *
     * @param uuid     玩家 UUID
     * @param snapshot 目前快照（已於 record lock 下取得）
     * @return 欄位變更清單；無變更時為空
     */
    private List<PlayerDataStore.FieldChange> diffAgainstPersisted(UUID uuid,
            Map<String, Object> snapshot) {
        Map<String, Object> persisted = persistedFields.get(uuid);
        if (persisted == null) {
            persisted = loadPersistedFields(uuid);
        }
        List<PlayerDataStore.FieldChange> changes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if (!Objects.deepEquals(persisted.get(entry.getKey()), entry.getValue())) {
                changes.add(PlayerDataStore.FieldChange.upsert(
                    uuid, entry.getKey(), entry.getValue()));
            }
        }
        for (String field : persisted.keySet()) {
            if (!snapshot.containsKey(field)) {
                changes.add(PlayerDataStore.FieldChange.deletion(uuid, field));
            }
        }
        return changes;
    }

    /**
     * 讀出某位玩家目前在 store 中的欄位（作為差分基準）。
     *
     * <p>結果快取於 {@link #persistedFields}，並在該玩家保存成功後更新為新快照。</p>
     */
    private Map<String, Object> loadPersistedFields(UUID uuid) {
        Map<String, Object> fields = new LinkedHashMap<>();
        playerStore.load(uuid).ifPresent(record -> {
            for (String field : record.keys()) {
                fields.put(field, record.get(field));
            }
        });
        return fields;
    }

    // -----------------------------------------------------------------
    // 定期保存、離線讀取、資料就緒通知（1.4.0）
    // -----------------------------------------------------------------

    /**
     * 立即執行一次定期保存，把所有有未落盤變更的玩家寫回 store。
     *
     * <p>定期排程本來會在每個保存週期自動呼叫；本方法讓呼叫端（測試、維運指令、
     * 需要立即落盤的流程）能主動觸發同一條路徑。</p>
     *
     * <p><strong>成功語意</strong>：future 正常完成代表該次快照的所有玩家都已落盤，
     * 且已落盤序號已推進。<strong>失敗或逾時不代表成功</strong>：失敗者與尚未處理的
     * 玩家保留未落盤狀態，下次週期或重試會重寫。</p>
     *
     * @return 保存完成時完成的 future；失敗時以
     *         {@link PlayerStateException}（{@code ACELIB-PLAYER-003}）／
     *         {@link DataStoreException} 失敗完成，逾時為 {@code ACELIB-PLAYER-008}
     * @throws NullPointerException 不會（無參數）
     */
    public CompletableFuture<Void> autosaveNow() {
        Map<UUID, PlayerRecordView> snapshot = dirtySnapshot();
        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        inFlightOps.incrementAndGet();
        // 實際保存發生在 serialStoreExecutor；這裡把「等待完成」放在呼叫端的
        // I/O executor 上，讓子呼叫端可以在自己的執行緒 observe 結果。
        try {
            ioExecutor.execute(() -> {
                try {
                    runFlushBatch(snapshot, flushTimeoutMillis);
                    future.complete(null);
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                } finally {
                    inFlightOps.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlightOps.decrementAndGet();
            future.completeExceptionally(flushFailure("ACELIB-PLAYER-008", snapshot,
                "autosave dispatch rejected", rejected));
        }
        return future;
    }

    /**
     * 讀取離線玩家的資料。
     *
     * <p>不需要該玩家有 active session；資料來自 store 的實際內容，
     * 因此刪除過的欄位不會在此復活。</p>
     *
     * <p>讀取在內部 serial store executor 上執行（store 非 thread-safe），
     * 呼叫端會同步等待結果；因此不應在 region 執行緒上呼叫長時間的讀取。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 該玩家目前的資料；從未寫入過時為 empty
     * @throws NullPointerException 當 {@code uuid} 為 null
     * @throws PlayerStateException  服務已關閉（{@code ACELIB-PLAYER-007}）、
     *                               內部 executor 已終止或讀取失敗
     *                               （{@code ACELIB-PLAYER-008}）／
     *                               {@code ACELIB-PLAYER-002}
     */
    public Optional<Record> getOfflineData(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        ensureNotShutdown();
        if (playerStore == null) {
            throw new PlayerStateException("ACELIB-PLAYER-006",
                "getOfflineData requires a per-player PlayerDataStore; this service was "
                    + "constructed with a legacy DataStore, which cannot read a player "
                    + "without an active session");
        }
        if (serialExecutorTerminated.get()) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "internal serial store executor has been terminated; "
                    + "cannot read uuid=" + uuid);
        }
        try {
            Callable<Optional<Record>> read = () -> loadOfflineRecord(uuid);
            Optional<Record> loaded = serialStoreExecutor
                .submit(read)
                .get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return loaded;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new PlayerStateException("ACELIB-PLAYER-002",
                "failed to read offline player data for uuid=" + uuid + ": "
                    + cause.getMessage(), cause);
        } catch (TimeoutException te) {
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "offline read timed out for uuid=" + uuid);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PlayerStateException("ACELIB-PLAYER-008",
                "offline read interrupted for uuid=" + uuid);
        }
    }

    /**
     * 讀取離線玩家的資料（非同步）。
     *
     * <p>語意與 {@link #getOfflineData(UUID)} 逐字一致：不需要該玩家有 active
     * session；資料來自 store 的實際內容，因此刪除過的欄位不會在此復活。
     * 同玩家在線時拿到的是<strong>已持久化內容</strong>：session 內尚未
     * {@code flush} 的變更不在內，不得把回傳值當成「最新資料」。</p>
     *
     * <p>讀取在內部 serial store executor 上執行（store 非 thread-safe），
     * 與同步版共用同一個 executor 與同一個單一 session 機制；呼叫端不等待、
     * 不阻塞，因此可在 region 執行緒上呼叫。不新建執行緒池。</p>
     *
     * <p>與同步版不同，非同步版沒有等待逾時：讀取會在 serial executor 輪到它時
     * 執行並完成 future。若 {@code shutdown()} 在讀取完成前強制終止 executor，
     * 尚未完成的讀取一律以 {@code ACELIB-PLAYER-008} 完成（不會永久 pending）。</p>
     *
     * <p><strong>取消語意</strong>：呼叫端取消回傳的 future 不會中止 store 讀取；
     * 讀取仍會在 serial executor 上跑完，結果被丟棄。取消不影響 executor 與內部計數。</p>
     *
     * <p>回呼（例如 {@code thenAccept}）的執行緒不保證：future 已完成後才註冊的回呼，
     * 可能直接在呼叫端執行緒執行。要操作玩家、實體或世界，一律用安全排程送回正確上下文。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 讀取結果的 future；從未寫入過時完成為 empty
     * @throws NullPointerException 當 {@code uuid} 為 null（同步拋出）
     */
    public CompletableFuture<Optional<Record>> getOfflineDataAsync(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        if (shutdown.get()) {
            CompletableFuture<Optional<Record>> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-007",
                "PlayerDataService has been shut down; "
                    + "cannot read offline data for uuid=" + uuid));
            return rejected;
        }
        if (playerStore == null) {
            CompletableFuture<Optional<Record>> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-006",
                "getOfflineDataAsync requires a per-player PlayerDataStore; this service was "
                    + "constructed with a legacy DataStore, which cannot read a player "
                    + "without an active session"));
            return rejected;
        }
        if (serialExecutorTerminated.get()) {
            CompletableFuture<Optional<Record>> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-008",
                "internal serial store executor has been terminated; "
                    + "cannot read uuid=" + uuid));
            return rejected;
        }
        CompletableFuture<Optional<Record>> future = new CompletableFuture<>();
        PendingOfflineRead ticket = new PendingOfflineRead(future);
        pendingOfflineReads.add(ticket);
        inFlightOps.incrementAndGet();
        try {
            serialStoreExecutor.execute(() -> {
                try {
                    future.complete(loadOfflineRecord(uuid));
                } catch (Throwable failure) {
                    future.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-002",
                        "failed to read offline player data for uuid=" + uuid + ": "
                            + failure.getMessage(), failure));
                } finally {
                    pendingOfflineReads.remove(ticket);
                    ticket.settle();
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingOfflineReads.remove(ticket);
            ticket.settle();
            future.completeExceptionally(new PlayerStateException("ACELIB-PLAYER-008",
                "offline read dispatch rejected for uuid=" + uuid, rejected));
        }
        return future;
    }

    /**
     * 從逐玩家 store 載入單一玩家資料並轉成可獨立持有的視圖。
     *
     * <p>必須在 {@link #serialStoreExecutor} 上呼叫（store 非 thread-safe）。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 該玩家目前的資料；從未寫入過時為 empty
     */
    private Optional<Record> loadOfflineRecord(UUID uuid) {
        return playerStore.load(uuid)
            .map(found -> (Record) new MemoryRecord("", toFieldMap(found)));
    }

    /**
     * 把 record 的頂層欄位拷貝成可獨立持有的 map。
     *
     * <p>回傳新 map：record 內部的 map 不得被當成差分基準直接共用，
     * 否則後續對 record 的變更會回頭改寫「上次落盤的內容」。</p>
     *
     * @param record 來源 record；不可為 null
     * @return 欄位名 → 值的新 map
     */
    private static Map<String, Object> toFieldMap(Record record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : record.keys()) {
            fields.put(field, record.get(field));
        }
        return fields;
    }

    /**
     * {@link #toFieldMap(Record)} 的 Optional 版本；無資料時回傳空 map。
     *
     * @param record 來源 record；不可為 null
     * @return 欄位名 → 值的新 map；{@code record} 為 empty 時為空 map
     */
    private static Map<String, Object> copyToMap(Optional<Record> record) {
        return record.map(PlayerDataService::toFieldMap).orElseGet(LinkedHashMap::new);
    }

    /**
     * 註冊資料就緒 listener。
     *
     * <p>在玩家資料載入完成、session 進入 {@link PlayerSessionState#READY} 之後
     * 呼叫一次；載入失敗時不呼叫。回呼在 I/O executor 上執行，要操作玩家或世界
     * 必須再用安全排程送回正確上下文。</p>
     *
     * <p>為什麼不是 Bukkit Event：資料在 I/O 執行緒就緒，Paper 的同步 Event 須在
     * 主執行緒 dispatch、Folia 須在玩家所屬 region dispatch，兩者都不適用。</p>
     *
     * @param listener listener；不可為 null
     * @return 註冊 handle；{@link PlayerDataReadyListener.Registration#close()} 後不再收到通知
     * @throws NullPointerException 當 {@code listener} 為 null
     */
    public PlayerDataReadyListener.Registration addReadyListener(
            PlayerDataReadyListener listener) {
        Objects.requireNonNull(listener, "listener");
        ReadyListenerRegistration registration = new ReadyListenerRegistration();
        readyListeners.add(new ReadyListenerEntry(listener, registration));
        return new PlayerDataReadyListener.Registration() {
            @Override
            public void close() {
                registration.close();
                readyListeners.removeIf(
                    entry -> entry.registration() == registration);
            }

            @Override
            public boolean isClosed() {
                return registration.isClosed();
            }
        };
    }

    /**
     * 依序通知資料就緒 listener。
     *
     * <p>單一 listener 失敗只記錄、不中斷其餘 listener，也不影響 session 狀態。</p>
     */
    private void notifyDataReady(UUID uuid, PlayerRecordView view) {
        for (ReadyListenerEntry entry : readyListeners) {
            if (entry.registration().isClosed()) {
                continue;
            }
            try {
                entry.listener().onPlayerDataReady(uuid, view.record);
            } catch (Throwable failure) {
                Logger.getLogger("AceLib").log(Level.WARNING,
                    "[ACELIB-PLAYER-009] player data ready listener failed for uuid="
                        + uuid + ": " + failure);
            }
        }
    }

    /**
     * 定期保存排程觸發的執行體。
     *
     * <p>失敗只記錄（{@code ACELIB-PLAYER-009}）不中斷排程：下一個週期會重試，
     * 未落盤的資料仍保留在快取中。</p>
     */
    private void runAutosaveCycle() {
        if (shutdown.get()) {
            return;
        }
        Map<UUID, PlayerRecordView> snapshot = dirtySnapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            runFlushBatch(snapshot, flushTimeoutMillis);
        } catch (Throwable failure) {
            Logger.getLogger("AceLib").log(Level.WARNING,
                "[ACELIB-PLAYER-009] periodic save failed for " + snapshot.size()
                    + " player(s): " + failure.getMessage()
                    + "; changes stay dirty and will be retried on the next cycle");
        }
    }

    /**
     * 目前仍有未落盤變更的玩家數（package-private test seam）。
     *
     * @return dirty 玩家數
     */
    int dirtyPlayerCountForTest() {
        return dirtySnapshot().size();
    }

    /**
     * 內部定期保存排程器是否已終止（package-private test seam）。
     *
     * @return true 表示已終止；未啟用定期保存的建構子一律為 true
     */
    boolean isAutosaveTerminatedForTest() {
        return autosaveExecutor == null || autosaveExecutor.isShutdown();
    }

    /**
     * 取得當前服務持有的 registry（package-private test seam）。
     *
     * @return 不可為 null 的 registry
     */
    PlayerSessionRegistry getRegistryForTest() {
        return registry;
    }

    /**
     * 等待中的 quit/retry 交接數（package-private test seam —
     * 重試有界測試用：重試完成後 pendingQuits 與 pendingRejoins 皆須為空）。
     *
     * @return 尚未完成的 quit future 數與重試鏈數之和
     */
    int pendingReconnectCountForTest() {
        return pendingQuits.size() + pendingRejoins.size();
    }

    /**
     * 等待 session 進入目標狀態（最多 {@code timeoutMillis}）。
     *
     * <p>使用短暫 sleep + state 檢查；測試可經由
     * {@link #waitForState(PlayerSession, PlayerSessionState, long, WaitSleeper)}
     * 注入假 sleeper，決定性模擬長時間等待而不真實 sleep。</p>
     */
    private boolean waitForState(PlayerSession session,
                                 PlayerSessionState target,
                                 long timeoutMillis) {
        return waitForState(session, target, timeoutMillis, Thread::sleep);
    }

    /**
     * Sleep 可注入的等待 seam（package-private test seam）。
     */
    @FunctionalInterface
    interface WaitSleeper {
        /**
         * 睡眠指定毫秒。
         *
         * @param millis 睡眠毫秒數
         * @throws InterruptedException 若等待被中斷
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 等待 session 進入目標狀態（sleep 策略可注入）。
     *
     * @param session       等待的 session
     * @param target        目標狀態
     * @param timeoutMillis 最多等待毫秒數
     * @param sleeper       sleep 策略；不可為 null
     * @return true 表示進入目標狀態；false 表示逾時或被中斷
     */
    boolean waitForState(PlayerSession session,
                         PlayerSessionState target,
                         long timeoutMillis,
                         WaitSleeper sleeper) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sleeper, "sleeper");
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (session.getState() == target) {
                return true;
            }
            if (session.getState().isTerminal()) {
                return target == PlayerSessionState.ENDED;
            }
            try {
                sleeper.sleep(WAIT_FOR_STATE_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return session.getState() == target;
    }

    private void ensureNotShutdown() {
        if (shutdown.get()) {
            throw new PlayerStateException("ACELIB-PLAYER-007",
                "PlayerDataService has been shut down; no new joins accepted");
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 尚未完成的 async 離線讀取登錄項：future 本體加上「恰好扣回一次」的結算旗標。
     *
     * <p>工作自身的 {@code finally} 與 {@link #failPendingOfflineReads()}
     * 可能併發結算同一張 ticket（強制終止時被中斷的工作仍會跑完 {@code finally}），
     * 以 CAS 保證 {@code inFlightOps} 只扣一次。</p>
     */
    private final class PendingOfflineRead {
        final CompletableFuture<Optional<Record>> future;
        final AtomicBoolean settled = new AtomicBoolean(false);

        PendingOfflineRead(CompletableFuture<Optional<Record>> future) {
            this.future = future;
        }

        /** 扣回一次計數；重複呼叫為 no-op。 */
        void settle() {
            if (settled.compareAndSet(false, true)) {
                inFlightOps.decrementAndGet();
            }
        }
    }

    /**
     * 玩家資料快取視圖：包裝 {@link LockedPlayerRecord} + 變更序號。
     *
     * <p>{@link #changeSeq} 取代單純的 dirty 旗標：每次 {@code markDirty} 遞增，
     * 保存時記下當下的序號，提交成功後才把「已落盤序號」推到該值。這讓保存期間
     * 發生的新變更不會被這次提交誤清——舊旗標做不到這件事，結果是新值永遠不會落盤。</p>
     */
    private static final class PlayerRecordView {
        final LockedPlayerRecord record;
        final AtomicLong changeSeq = new AtomicLong();
        volatile long savedSeq = 0L;

        PlayerRecordView(LockedPlayerRecord record) {
            this.record = record;
        }

        /** 是否有尚未落盤的變更。 */
        boolean dirty() {
            return changeSeq.get() > savedSeq;
        }

        /** 標記一次變更。 */
        void markDirty() {
            changeSeq.incrementAndGet();
        }

        /**
         * 提交成功後推進已落盤序號。
         *
         * @param seq 保存開始時觀察到的序號；期間若再變更，序號已大於此值，
         *            因此 dirty 仍為 true
         */
        void markSaved(long seq) {
            savedSeq = seq;
        }
    }

    /**
     * 資料就緒 listener 的註冊項。
     *
     * @param listener   listener 本體
     * @param registration 對外的 handle
     */
    private record ReadyListenerEntry(PlayerDataReadyListener listener,
            ReadyListenerRegistration registration) {
    }

    /**
     * {@link PlayerDataReadyListener.Registration} 的實作。
     */
    private static final class ReadyListenerRegistration
            implements PlayerDataReadyListener.Registration {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }
}
