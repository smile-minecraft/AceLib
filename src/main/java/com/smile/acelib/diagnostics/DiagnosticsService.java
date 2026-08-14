package com.smile.acelib.diagnostics;

import com.smile.acelib.AceLibVersion;
import com.smile.acelib.context.DebugMode;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.TaskErrorRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 統一診斷服務。
 *
 * <p>提供：</p>
 * <ul>
 *   <li>查詢目前狀態：version / platform / capability / ready / debug</li>
 *   <li>缺失模組安全降級（未實作模組標記 NOT_INITIALIZED）</li>
 *   <li>排程錯誤摘要（依 code 合併計數）</li>
 *   <li>debug 模式開關委派給 {@link DebugMode}</li>
 *   <li>同類錯誤節流（{@link ErrorThrottler}）</li>
 *   <li>不可變快照與報告（{@link DiagnosticSnapshot} / {@link DiagnosticReport}）</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <ul>
 *   <li>{@link #bindPlugin} 採 {@link AtomicBoolean#compareAndSet} 拒絕重複 bind</li>
 *   <li>{@link #registeredModules} 為 {@link ConcurrentHashMap}，支援多 region 並行註冊</li>
 *   <li>其他狀態（{@code version} / {@code platform} / {@code capability} / {@code ready}）為
 *       {@code volatile}，支援單寫多讀 visibility</li>
 *   <li>{@link #throttler} 內部已 lock-free</li>
 * </ul>
 *
 * @see DiagnosticSnapshot
 * @see DiagnosticReport
 * @see ErrorThrottler
 * @since 1.0.0
 */
public final class DiagnosticsService {

    /** 預設模組識別。 */
    static final String MODULE_SCHEDULER = "scheduler";
    static final String MODULE_CONFIG = "config";
    static final String MODULE_LANG = "lang";
    static final String MODULE_INTEGRATION = "integration";
    static final String MODULE_DATA = "data";

    /** 預設模組缺席時的 detail。 */
    private static final Map<String, String> DEFAULT_DETAILS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(MODULE_SCHEDULER, "尚未綁定 SafeScheduler");
        m.put(MODULE_CONFIG, "尚未綁定 AceLibConfig");
        m.put(MODULE_LANG, "尚未綁定 AceLibConfig");
        m.put(MODULE_INTEGRATION, "Phase 13 未實作");
        m.put(MODULE_DATA, "Phase 8 未實作");
        DEFAULT_DETAILS = Map.copyOf(m);
    }

    /** 排程器停用時的錯誤代碼（SafeSchedulerImpl.ERR_PLUGIN_DISABLED）。 */
    private static final String SCHED_ERR_PLUGIN_DISABLED = "ACELIB-SCHED-006";

    private final Clock clock;
    private final ErrorThrottler throttler;
    private final ConcurrentHashMap<String, ModuleState> registeredModules = new ConcurrentHashMap<>();
    private final AtomicBoolean bound = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile SafeSchedulerImpl boundScheduler;
    private volatile String version;
    private volatile Platform platform;
    private volatile PlatformCapability capability;

    /**
     * 建構子。
     *
     * <p>內部 {@link ErrorThrottler} 採 <strong>duplicate suppression 語意</strong>：
     * 視窗內同 code 第二次起 SUPPRESSED（{@code maxPerWindow = 1}）。
     * 此策略獨立於 {@link ErrorThrottler#DEFAULT_MAX_PER_WINDOW} 的通用節流語意
     * （預設 = 5，視窗內前 5 次都放行），是 DiagnosticsService 對應
     * 「同類錯誤不無限制洗版」之 service-level 行為；
     * 若外部 caller 想要通用 N 次節流，請直接使用 {@link ErrorThrottler}。</p>
     *
     * @param clock 時鐘來源；不可為 null
     * @throws NullPointerException 當 {@code clock} 為 null
     */
    public DiagnosticsService(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        this.clock = clock;
        // 顯式使用 max=1（duplicate suppression），不沿用 ErrorThrottler 通用預設 5。
        this.throttler = new ErrorThrottler(
            clock, 1, ErrorThrottler.DEFAULT_WINDOW_MS);
    }

    // -----------------------------------------------------------------
    // 插件綁定與核心狀態
    // -----------------------------------------------------------------

    /**
     * 綁定 plugin 的基本資訊（一次性）。
     *
     * <p>重複呼叫會拋 {@link IllegalStateException}（不可變契約）。</p>
     *
     * @param version    對外版本字串；不可為 null
     * @param platform   偵測平台；不可為 null
     * @param capability 對應 capability；不可為 null
     * @throws NullPointerException 當任一參數為 null
     * @throws IllegalStateException 當 {@code bindPlugin} 已經被呼叫過
     */
    public void bindPlugin(String version, Platform platform, PlatformCapability capability) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(capability, "capability");
        if (!bound.compareAndSet(false, true)) {
            throw new IllegalStateException("DiagnosticsService already bound to plugin");
        }
        this.version = version;
        this.platform = platform;
        this.capability = capability;
    }

    /**
     * 取得當前對外版本字串。
     *
     * <p>未 bind 時回傳 {@link AceLibVersion#VERSION}（向後相容既有呼叫）。</p>
     *
     * @return 對外版本字串；永遠不為 null
     */
    public String getVersion() {
        return version != null ? version : AceLibVersion.VERSION;
    }

    /**
     * 取得當前平台。
     *
     * <p>未 bind 時回傳 {@link Platform#UNKNOWN}。</p>
     *
     * @return 當前 {@link Platform}；永遠不為 null
     */
    public Platform getPlatform() {
        return platform != null ? platform : Platform.UNKNOWN;
    }

    /**
     * 取得當前 platform capability。
     *
     * <p>未 bind 時依 {@link Platform#UNKNOWN} 推導（保守降級）。</p>
     *
     * @return 對應的 {@link PlatformCapability}；永遠不為 null
     */
    public PlatformCapability getPlatformCapability() {
        if (capability != null) {
            return capability;
        }
        return PlatformCapability.forPlatform(Platform.UNKNOWN);
    }

    /**
     * 當前是否標記為 ready（plugin 自身的生命週期旗標）。
     *
     * @return true 表示已 ready
     */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * 切換 ready 旗標。
     *
     * @param ready 新的 ready 值
     */
    public void setReady(boolean ready) {
        this.ready.set(ready);
    }

    /**
     * 當前除錯模式是否開啟（委派給 {@link DebugMode#isEnabled()}）。
     *
     * @return true 表示 debug 已開
     */
    public boolean isDebugEnabled() {
        return DebugMode.isEnabled();
    }

    // -----------------------------------------------------------------
    // 模組狀態註冊
    // -----------------------------------------------------------------

    /**
     * 註冊或覆寫單一模組狀態。
     *
     * @param name  模組名稱；不可為 null
     * @param state 模組狀態；不可為 null
     * @throws NullPointerException 任一參數為 null
     */
    public void registerModuleState(String name, ModuleState state) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        registeredModules.put(name, state);
    }

    /**
     * 取消註冊單一模組（後續 buildSnapshot 將回退為 NOT_INITIALIZED 預設 detail）。
     *
     * @param name 模組名稱；不可為 null
     * @throws NullPointerException 當 {@code name} 為 null
     */
    public void unregisterModuleState(String name) {
        Objects.requireNonNull(name, "name");
        registeredModules.remove(name);
    }

    // -----------------------------------------------------------------
    // 排程、配置、語言綁定
    // -----------------------------------------------------------------

    /**
     * 綁定排程器；同時將 scheduler 模組標記為 READY / FAILED / NOT_INITIALIZED。
     *
     * <p>當傳入非 null 的 {@link SafeSchedulerImpl} 時，
     * 此方法會自動呼叫
     * {@code scheduler.getRecorder().setRecordSink(this::onRecorderSink)}
     * 注入一個 <strong>recorder-level</strong> listener；無論錯誤來自
     * {@code SafeSchedulerImpl} 內部 {@code recordAndNotify} 還是外部
     * caller 直接呼叫 {@code scheduler.getRecorder().record(...)}，
     * 都會同步觸發 {@link #reportSchedulerError(String, String)} 走節流路徑。
     * 傳入 null 時會解除前一個綁定的 listener，避免殘留舊綁定。</p>
     *
     * <p>為何改用 recorder-level sink（而非原本的
     * {@code SafeSchedulerImpl#setRecordSink(BiConsumer)}）？
     * 外部 caller 經常直接呼叫 {@code sched.getRecorder().record(...)}
     * 寫入測試或自訂錯誤；recorder-level sink 可確保 diagnostics 收到
     * <em>所有</em>寫入事件，而非只有 scheduler 內部走 {@code recordAndNotify}
     * 流程的事件。</p>
     *
     * <ul>
     *   <li>傳入 null → 解除 listener，模組標記 NOT_INITIALIZED（unbind）</li>
     *   <li>傳入已 {@link SafeSchedulerImpl#onPluginDisable() disabled} 的 scheduler
     *       → 模組標記 FAILED with {@code ACELIB-SCHED-006}</li>
     *   <li>否則 → 模組標記 READY with {@code "tracked=N"} detail</li>
     * </ul>
     *
     * @param scheduler 排程器；可為 null
     */
    public void bindScheduler(SafeSchedulerImpl scheduler) {
        SafeSchedulerImpl previous = this.boundScheduler;
        // 解除前一綁定的 recorder listener（避免 plugin reload 時舊 scheduler
        // 的 recorder 仍回呼本 service）
        if (previous != null && previous != scheduler) {
            previous.getRecorder().clearRecordSink();
        }
        this.boundScheduler = scheduler;
        if (scheduler == null) {
            registerModuleState(MODULE_SCHEDULER,
                ModuleState.notInitialized(MODULE_SCHEDULER,
                    DEFAULT_DETAILS.get(MODULE_SCHEDULER)));
            return;
        }
        // 注入 recorder-level listener：recorder.record 後同步走 reportSchedulerError
        scheduler.getRecorder().setRecordSink(this::onRecorderSink);
        if (scheduler.isDisabled()) {
            registerModuleState(MODULE_SCHEDULER,
                ModuleState.failed(MODULE_SCHEDULER,
                    "scheduler has been disabled",
                    SCHED_ERR_PLUGIN_DISABLED));
            return;
        }
        registerModuleState(MODULE_SCHEDULER,
            ModuleState.ready(MODULE_SCHEDULER,
                "tracked=" + scheduler.getTrackedTaskCount()));
    }

    /**
     * Recorder listener adapter：把 {@link TaskErrorRecord} 拆成
     * {@code (code, detail)} 後走 {@link #reportSchedulerError(String, String)}
     * 節流路徑。傳入 {@code null} 視為 no-op（避免上游 NPE）。
     *
     * <p>此方法設計為 {@link Consumer} lambda 形式（method reference），
     * 由 {@link #bindScheduler(SafeSchedulerImpl)} 透過
     * {@link com.smile.acelib.scheduler.TaskErrorRecorder#setRecordSink(Consumer)}
     * 注入；listener 拋例外由 recorder 端吞掉並 FINE-level log，
     * 不影響 diagnostics 主流程。</p>
     *
     * @param record 從 recorder 傳入的紀錄；可為 null
     */
    private void onRecorderSink(TaskErrorRecord record) {
        if (record == null) {
            return;
        }
        // reportSchedulerError 已對 code 為 null 拋 NPE；code 由 TaskErrorRecord 工廠方法保證非 null。
        reportSchedulerError(record.code(), record.detail());
    }

    /**
     * 在不替換 {@link DiagnosticsService} instance 的前提下更新版本/平台/capability。
     *
     * <p>對應 reload 流程：reload 不會建立新的 service，而是
     * 對既有 service 重新寫入 plugin 版本/平台/capability 欄位。
     * 後續 {@link #buildSnapshot()} 立即反映新值，確保
     * 「既有 reference 仍可觀測到 reload 後狀態」的契約。</p>
     *
     * <p>呼叫後 {@link #bound} 標記為 {@code true}（idempotent）：
     * 對於尚未 bind 的 safe-default instance，呼叫此方法等同於
     * 「首次 bind」並升級為完整 service。對已 bind 的 instance，
     * 則單純覆寫版本/平台/capability 欄位。</p>
     *
     * <p>此方法與 {@link #bindPlugin(String, Platform, PlatformCapability)}
     * 的差異在於：後者僅允許首次呼叫（重複會拋 {@link IllegalStateException}），
     * 而 {@code rebindPlugin} 總是允許覆寫。執行緒安全：
     * {@code version} / {@code platform} / {@code capability} 為
     * {@code volatile} 寫入，呼叫端讀者可見一致的更新。</p>
     *
     * @param version    對外版本字串；不可為 null
     * @param platform   偵測平台；不可為 null
     * @param capability 對應 capability；不可為 null
     * @throws NullPointerException 當任一參數為 null
     * @since 1.0.0
     */
    public void rebindPlugin(String version, Platform platform, PlatformCapability capability) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(capability, "capability");
        this.version = version;
        this.platform = platform;
        this.capability = capability;
        // idempotent：safe-default instance 也能透過此方法升級為「已 bind」狀態
        this.bound.set(true);
    }

    /**
     * 還原版本/平台/capability metadata 至先前 snapshot（reload rollback）。
     *
     * <p>公開 API（since 1.0.0），用於 {@code AceLibPlugin.reload()}
     * 的 rollback 流程：當 reload 在 {@link #rebindPlugin} 寫入新 metadata
     * 之後、commit 前失敗時，呼叫端必須能將 metadata 還原為 reload 前值，
     * 避免留下「scheduler reference 雖未 commit、但 diagnostics 內容已是新平台」
     * 的 partial commit 狀態。</p>
     *
     * <p><strong>僅供 reload rollback 使用</strong>：一般 plugin lifecycle 應走
     * {@link #bindPlugin(String, Platform, PlatformCapability)}（首次 bind）或
     * {@link #rebindPlugin(String, Platform, PlatformCapability)}（reload commit）；
     * 直接呼叫 {@code restoreMetadata} 僅在「reload commit 已成功寫入新 metadata、
     * 但後續 bindScheduler/hook 失敗需要還原」的特殊情境下使用。誤用此方法
     * 可能導致 diagnostics 與實際 platform 不一致的隱性 bug。</p>
     *
     * <p>與 {@link #rebindPlugin(String, Platform, PlatformCapability)} 的差異：
     * <ul>
     *   <li>{@code rebindPlugin} 是 commit 路徑（reload 寫入新偵測結果），
     *       並把 {@link #bound} 標記為 {@code true}（idempotent）</li>
     *   <li>{@code restoreMetadata} 是 rollback 路徑（還原先前 snapshot），
     *       <strong>不</strong>改動 {@link #bound}、{@link #ready} 或 scheduler 綁定</li>
     * </ul>
     * 兩者使用相同 {@code volatile} 寫入路徑，故執行緒安全。
     *
     * <p>呼叫端責任：restore 完 metadata 後仍須透過既有 API 處理
     * {@link #setReady(boolean)} 與 {@link #bindScheduler(SafeSchedulerImpl)} —
     * 本方法僅負責 metadata 欄位，不接管其他 lifecycle 動作。</p>
     *
     * @param version    先前 snapshot 的 version；不可為 null
     * @param platform   先前 snapshot 的 platform；不可為 null
     * @param capability 先前 snapshot 的 capability；不可為 null
     * @throws NullPointerException 當任一參數為 null
     * @since 1.0.0
     */
    public void restoreMetadata(String version, Platform platform, PlatformCapability capability) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(capability, "capability");
        this.version = version;
        this.platform = platform;
        this.capability = capability;
        // 不翻轉 bound.set(true) — restore 為「取消先前 commit」語意，
        // 不應變更既有 lifecycle 旗標（reload 進入前 bound 已為 true）。
    }

    /**
     * 標記 scheduler 模組為 FAILED（含 {@code ACELIB-SCHED-006} 錯誤代碼）。
     *
     * <p>對應 {@code AceLibPlugin.onDisable()}：plugin disable 後，
     * 既有 {@link DiagnosticsService} reference 仍須可由外部（如
     * 管理員命令、測試 seam）查得；其 scheduler 模組狀態須明確降級為
     * FAILED + {@code ACELIB-SCHED-006}，而非 {@code NOT_INITIALIZED}
     * （NOT_INITIALIZED 語意為「從未 bind」，與「曾 bind 但現已 disable」
     * 不同）。</p>
     *
     * <p>Safe-default（未 bind plugin）的 instance 不會被改動 —
     * 若從未 bind 過 scheduler，仍維持 {@code NOT_INITIALIZED}
     * 語意；此方法只對 <strong>曾有 boundScheduler</strong> 的 instance
     * 生效。透過比對 {@link #boundScheduler} 是否為 null 來區分。</p>
     *
     * <p>冪等：重複呼叫不丟例外，模組狀態保持 FAILED + SCHED-006。</p>
     *
     * @since 1.0.0
     */
    public void markSchedulerDisabled() {
        // safe-default 場景：boundScheduler == null 表示從未綁定過 scheduler，
        // 維持預設 NOT_INITIALIZED（不「誤升級」為 FAILED）
        if (boundScheduler == null) {
            return;
        }
        registerModuleState(MODULE_SCHEDULER,
            ModuleState.failed(MODULE_SCHEDULER,
                "scheduler has been disabled",
                SCHED_ERR_PLUGIN_DISABLED));
    }

    /**
     * 僅注入 sink，不改動模組狀態。
     *
     * <p>提供給希望在 <em>不</em>覆寫先前 {@code bindScheduler(...)} 設定的前提下
     * 注入 sink 的 caller（例如只想測試 sink 路�）。一般 plugin lifecycle 走
     * {@link #bindScheduler(SafeSchedulerImpl)} 即可。</p>
     *
     * <p>sink 注入改走
     * {@link com.smile.acelib.scheduler.TaskErrorRecorder#setRecordSink(Consumer)}
     * （recorder-level），確保外部直接呼叫 {@code scheduler.getRecorder().record(...)}
     * 也會被觀察到。</p>
     *
     * @param scheduler 排程器；可為 null（等同 no-op）
     */
    public void bindSchedulerSink(SafeSchedulerImpl scheduler) {
        if (scheduler == null) {
            return;
        }
        scheduler.getRecorder().setRecordSink(this::onRecorderSink);
    }

    /**
     * 對外公開的「scheduler 錯誤回報」API（wiring 入口）。
     *
     * <p>將一筆排程錯誤事件送入 diagnostics 節流路徑：
     * <ul>
     *   <li>視窗內同 code 第二次起 → {@link ThrottleDecision.Kind#SUPPRESSED}</li>
     *   <li>視窗內首次 → {@link ThrottleDecision.Kind#ALLOWED}</li>
     * </ul>
     *
     * <p>由 {@link #bindScheduler(SafeSchedulerImpl)} 自動注入的 sink
     * 內部呼叫；外部 caller（例如自訂錯誤監聽）也可直接呼叫以享受同樣節流。</p>
     *
     * @param code   錯誤代碼；不可為 null
     * @param detail 詳細訊息；可為 null 或空字串
     * @return 對應的 {@link ThrottleDecision}；永遠不為 null
     * @throws NullPointerException 當 {@code code} 為 null
     */
    public ThrottleDecision reportSchedulerError(String code, String detail) {
        // 直接委派給 recordError；保留「recordError 內部 throttle 變更」的唯一入口
        return recordError(code, detail);
    }

    /**
     * 綁定設定檔模組狀態。
     *
     * <ul>
     *   <li>{@code ready=true} → config 模組標記 READY</li>
     *   <li>{@code ready=false} + {@code errorCode} 非 null → FAILED 攜帶 errorCode</li>
     *   <li>{@code ready=false} + {@code errorCode} null → UNAVAILABLE</li>
     * </ul>
     *
     * @param configClass 設定檔入口 class（保留識別用，內容不驗證）；可為 null
     * @param ready       是否成功
     * @param errorCode   失敗時的錯誤代碼；可為 null
     */
    public void bindConfig(Class<?> configClass, boolean ready, String errorCode) {
        registerModuleState(MODULE_CONFIG, buildModuleState(MODULE_CONFIG, ready, errorCode));
    }

    /**
     * 綁定語言檔模組狀態（語意同 {@link #bindConfig}）。
     *
     * @param langClass 語言檔入口 class；可為 null
     * @param ready     是否成功
     * @param errorCode 失敗時的錯誤代碼；可為 null
     */
    public void bindLang(Class<?> langClass, boolean ready, String errorCode) {
        registerModuleState(MODULE_LANG, buildModuleState(MODULE_LANG, ready, errorCode));
    }

    private static ModuleState buildModuleState(String name, boolean ready, String errorCode) {
        if (ready) {
            return ModuleState.ready(name, "bound to " + name);
        }
        if (errorCode != null) {
            return ModuleState.failed(name, "reload failed: " + errorCode, errorCode);
        }
        return ModuleState.unavailable(name, "module not bound");
    }

    // -----------------------------------------------------------------
    // 錯誤記錄與節流
    // -----------------------------------------------------------------

    /**
     * 透過 {@link ErrorThrottler} 記錄一筆錯誤事件（視窗內 ALLOWED/suppressed 規則）。
     *
     * @param code   錯誤代碼；不可為 null
     * @param detail 詳細訊息；可為 null 或空字串
     * @return 對應的 {@link ThrottleDecision}；永遠不為 null
     * @throws NullPointerException 當 {@code code} 為 null
     */
    public ThrottleDecision recordError(String code, String detail) {
        Objects.requireNonNull(code, "code");
        return throttler.tryRecord(code, detail);
    }

    /**
     * 重置節流狀態（測試或 reload 時使用）。
     */
    public void resetThrottler() {
        throttler.reset();
    }

    // -----------------------------------------------------------------
    // 快照與報告
    // -----------------------------------------------------------------

    /**
     * 建立當下的不可變診斷快照。
     *
     * @return 新的 {@link DiagnosticSnapshot}；永遠不為 null
     */
    public DiagnosticSnapshot buildSnapshot() {
        Map<String, ModuleState> modules = new LinkedHashMap<>();
        // 預設 5 個模組
        for (Map.Entry<String, String> entry : DEFAULT_DETAILS.entrySet()) {
            modules.put(entry.getKey(),
                ModuleState.notInitialized(entry.getKey(), entry.getValue()));
        }
        // 覆寫已註冊的模組
        modules.putAll(registeredModules);

        List<ErrorSummaryLine> summary = collectSchedulerErrorSummary();

        // 使用 ErrorThrottler.snapshotStats() 取得一致的 throttle 快照，
        // 避免「trackedKeys() + 個別 getStats()」之間被 reset() 介入而得到 null stats。
        Map<String, ThrottleStats> throttleStats = throttler.snapshotStats();

        return DiagnosticSnapshot.builder()
            .timestampMillis(clock.currentTimeMillis())
            .version(getVersion())
            .platform(getPlatform())
            .capability(getPlatformCapability())
            .ready(ready.get())
            .debugEnabled(isDebugEnabled())
            .modules(modules)
            .recentErrors(summary)
            .throttleSnapshot(throttleStats)
            .build();
    }

    /**
     * 建立當下的不可變診斷報告。
     *
     * @return 新的 {@link DiagnosticReport}；永遠不為 null
     */
    public DiagnosticReport buildReport() {
        return DiagnosticReport.from(buildSnapshot());
    }

    /**
     * 從 {@link SafeSchedulerImpl#getRecorder()} 收集錯誤並依 code 合併為摘要。
     *
     * @return 錯誤摘要清單（永遠不為 null）
     */
    private List<ErrorSummaryLine> collectSchedulerErrorSummary() {
        SafeSchedulerImpl sched = boundScheduler;
        if (sched == null) {
            return List.of();
        }
        List<TaskErrorRecord> records = sched.getRecorder().getRecentErrors(
            Integer.MAX_VALUE);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> lastDetail = new LinkedHashMap<>();
        for (TaskErrorRecord r : records) {
            counts.merge(r.code(), 1, Integer::sum);
            lastDetail.put(r.code(), r.detail());
        }
        List<ErrorSummaryLine> result = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String code = entry.getKey();
            int count = entry.getValue();
            String detail = lastDetail.getOrDefault(code, "");
            ErrorCategory category = ErrorCodeRegistry.categorize(code);
            result.add(new ErrorSummaryLine(code, detail, count, category));
        }
        return result;
    }
}
