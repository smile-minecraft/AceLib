package com.smile.acelib.player;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.MemoryRecord;
import com.smile.acelib.data.Record;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
 *   <li>{@code ACELIB-PLAYER-004}：session 重複登入</li>
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

    private final DataStore store;
    private final Executor ioExecutor;
    /**
     * 內部 per-store 序列 executor — 所有 {@code store.root()} / {@code store.save()}
     * 存取皆透過此單執行緒，確保對非 thread-safe 的 {@link DataStore}
     * 內部 map 不會發生 race。
     */
    private final java.util.concurrent.ExecutorService serialStoreExecutor;
    /** 內部 executor 的 graceful shutdown timeout。 */
    private static final long SERIAL_EXECUTOR_TERMINATION_MS = 2_000L;
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
    /** 當前 in-flight 非同步任務數（onPlayerJoin / onPlayerQuit / withLoadedData）。 */
    private final AtomicInteger inFlightOps = new AtomicInteger(0);
    /**
     * 內部 serial executor 終止旗標 — shutdown() 期間設為 true；後續 store I/O
     * 路徑若觀察到 true，會以 PLAYER-008 立即失敗（避免 executor 已 shutdown
     * 卻仍提交任務）。
     */
    private final AtomicBoolean serialExecutorTerminated = new AtomicBoolean(false);

    /**
     * 主要建構子。
     *
     * @param store      已初始化的 {@link DataStore}；不可為 null 且須 {@link DataStore#isInitialized()}
     * @param ioExecutor I/O 用的 executor；不可為 null
     * @throws NullPointerException     任何參數為 null
     * @throws PlayerStateException     {@code store} 未初始化（{@code ACELIB-PLAYER-006}）
     */
    public PlayerDataService(DataStore store, Executor ioExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        if (!store.isInitialized()) {
            throw new PlayerStateException("ACELIB-PLAYER-006",
                "DataStore must be initialized before constructing PlayerDataService; "
                    + "name=" + store.name() + ", isInitialized=" + store.isInitialized());
        }
        this.serialStoreExecutor = createSerialStoreExecutor(store);
    }

    /**
     * 建立 per-store 序列 executor — 單一 daemon thread，名稱含 store name 以利除錯。
     */
    private static java.util.concurrent.ExecutorService createSerialStoreExecutor(DataStore store) {
        final String storeName = store.name();
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong serial = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "acelib-player-store-serial-" + storeName + "-"
                    + serial.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
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
     *   <li>同步建立 session（state=LOADING）；重複 UUID 拋 PLAYER-004</li>
     *   <li>遞增 in-flight 計數；於 {@code ioExecutor} 上排程 task，task 內委派
     *       給 {@code serialStoreExecutor} 執行實際 store root() 載入</li>
     *   <li>成功：session 轉 READY，future 完成</li>
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
                records.put(uuid, view);
                session.transitionTo(PlayerSessionState.READY);
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
     *   <li>ENDED → 從 registry 移除</li>
     *   <li>任何保存失敗：future 以 PLAYER-003 失敗完成，並保留 dirty record 供 shutdown flush</li>
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
        inFlightOps.incrementAndGet();
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
                boolean savedOk = true;
                try {
                    if (view != null && view.dirty.get()) {
                        // 委派給 serialStoreExecutor 同步執行 root()/save()
                        // （saveToStoreSerial 內部已包含 store.save()，
                        //  故不需在外部再呼叫一次 — 否則會在 ioExecutor 執行
                        //  第二次 save，導致 race）
                        saveToStoreSerial(uuid, view);
                    }
                } catch (Throwable saveEx) {
                    savedOk = false;
                    if (view != null) {
                        // 保存失敗時不得丟失唯一仍含 dirty 資料的 view。
                        records.put(uuid, view);
                    }
                    Throwable cause = unwrap(saveEx);
                    PlayerStateException wrapped = new PlayerStateException(
                        "ACELIB-PLAYER-003",
                        "failed to save player data for uuid=" + uuid + ": " + cause.getMessage(),
                        cause);
                    future.completeExceptionally(wrapped);
                    return;
                }
                if (savedOk) {
                    session.transitionTo(PlayerSessionState.ENDED);
                    registry.endSession(uuid);
                    future.complete(null);
                }
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
        view.dirty.set(true);
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
     * 在指定玩家資料「就緒」後執行 callback（異步等待）。
     *
     * <p>行為：</p>
     * <ul>
     *   <li>session 已 READY → 立即於 caller 所在執行緒執行 callback</li>
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
     * <p><strong>無 late resurrection</strong>：在 in-flight task 完成寫回 cache 步驟
     * 之前，會再次檢查 shutdown flag；已 shutdown 時，task 不會將資料放回 records map，
     * 避免「以為已 shutdown、實際有殘留資料」的 race。</p>
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
        // 4. graceful 終止 serialStoreExecutor
        shutdownSerialExecutor();
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
     */
    private void flushAllDirtySync() {
        // 快照 dirty record（避免 ConcurrentModification）
        Map<UUID, PlayerRecordView> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerRecordView> e : records.entrySet()) {
            if (e.getValue().dirty.get()) {
                snapshot.put(e.getKey(), e.getValue());
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        // 同步委派給 serialStoreExecutor
        Future<?> flush;
        try {
            flush = serialStoreExecutor.submit(() -> {
                for (Map.Entry<UUID, PlayerRecordView> e : snapshot.entrySet()) {
                    saveToStoreInternal(e.getKey(), e.getValue());
                    store.save();
                }
            });
        } catch (RejectedExecutionException rejected) {
            throw flushFailure("ACELIB-PLAYER-008", snapshot,
                "serial flush task rejected", rejected);
        }
        try {
            flush.get(SERIAL_EXECUTOR_TERMINATION_MS, TimeUnit.MILLISECONDS);
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
            serialStoreExecutor.submit(() -> {
                saveToStoreInternal(uuid, view);
                store.save();
            }).get(5, TimeUnit.SECONDS);
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
            serialExecutorTerminated.set(true);
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
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Record root = store.root();
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
        Record root = store.root();
        Map<String, Object> snapshot = new LinkedHashMap<>(view.record.snapshotLocked());
        root.set(PLAYER_ROOT + "." + uuid.toString(), snapshot);
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
     * 等待 session 進入目標狀態（最多 {@code timeoutMillis}）。
     *
     * <p>使用短暫 sleep + state 檢查；測試可注入 clock 模擬長時間等待。
     * 回傳 true 表示進入目標狀態；false 表示逾時。</p>
     */
    private boolean waitForState(PlayerSession session,
                                 PlayerSessionState target,
                                 long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (session.getState() == target) {
                return true;
            }
            if (session.getState().isTerminal()) {
                return target == PlayerSessionState.ENDED;
            }
            try {
                Thread.sleep(10);
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
     * 玩家資料快取視圖：包裝 {@link LockedPlayerRecord} + dirty 旗標。
     */
    private static final class PlayerRecordView {
        final LockedPlayerRecord record;
        final AtomicBoolean dirty = new AtomicBoolean(false);

        PlayerRecordView(LockedPlayerRecord record) {
            this.record = record;
        }
    }
}
