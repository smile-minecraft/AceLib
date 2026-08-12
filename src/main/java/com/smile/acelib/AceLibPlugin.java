package com.smile.acelib;

import com.smile.acelib.command.AceLibStatusHandler;
import com.smile.acelib.command.BukkitCommandBridge;
import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.JsonCodec;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.player.PlayerDataService;
import com.smile.acelib.player.PlayerStateException;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.world.BukkitWorldBackend;
import com.smile.acelib.world.WorldBackend;
import com.smile.acelib.world.WorldService;
import com.smile.acelib.world.WorldServiceImpl;
import com.smile.acelib.world.WorldServiceUnavailableImpl;
import com.smile.acelib.world.WorldErrorCode;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AceLib 主類別 — Folia-first 基礎函式庫插件。
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>{@link #onEnable()} — 由 Bukkit/Paper/Folia 伺服器呼叫；內部委派給
 *       {@link #onEnable(Server, PlatformDetector, Clock)} 方便單元測試</li>
 *   <li>{@link #onDisable()} — 釋放所有資源；{@link #isReady()} 回傳 false</li>
 *   <li>{@link #reload()} — 重新偵測平台並發佈新的 {@link AceLibApi} instance</li>
 * </ul>
 *
 * <h2>Phase 1 範圍</h2>
 * 本類別在 Phase 1（Plan §六）加入：
 * <ul>
 *   <li>平台偵測結果為 {@link Platform#UNKNOWN} 時輸出 warning log（ACELIB-PLAT-004）</li>
 *   <li>平台偵測為 {@link Platform#PAPER} 且 classpath 無 Folia 時輸出 fine-level 提示</li>
 *   <li>{@link #getPlatformCapability()} — 對外暴露 platform capability profile</li>
 *   <li>AceLibApi 已升級為 5 參數 ready(...)，攜帶實際偵測的 capability</li>
 * </ul>
 *
 * <h2>Phase 14 範圍</h2>
 * 本類別在 Phase 14（Plan §十九）加入 production wiring：
 * <ul>
 *   <li>{@code onEnable} 建立並綁定 {@link SafeSchedulerImpl} 與
 *       {@link DiagnosticsService}（使用可注入的 {@link Clock}），並透過
 *       {@code diagnostics.bindScheduler(...)} 自動注入 recordSink</li>
 *   <li>{@code onDisable} 安全降級：scheduler {@code onPluginDisable()}、
 *       diagnostics 解除綁定並重置 throttler；不留殘留 lifecycle 資源</li>
 *   <li>{@code reload} 重新偵測 platform/capability，重建 scheduler 並重新
 *       綁定 diagnostics；不殘留舊綁定</li>
 *   <li>{@link #getDiagnosticsService()} 與 {@link #buildDiagnosticsReport()}
 *       作為管理員/後續命令的查詢入口</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * 狀態欄位使用 {@code volatile} 與 {@code synchronized} 保護；
 * Folia 的 regionized 環境下 reload 通常由 main thread 觸發，但仍須具備 thread-safe 行為。
 */
public class AceLibPlugin extends JavaPlugin {

    /** Plugin 標籤，用於 fallback logger。 */
    private static final String LOG_NAME = "AceLib";

    /** Plan §七 §三 (6) 規範的平台偵測錯誤代碼（未知環境警告）。 */
    private static final String PLATFORM_UNKNOWN_ERROR_CODE = "ACELIB-PLAT-004";

    /**
     * Phase 14 failure-path 交易語意：reload 流程中遇到 diagnostics/scheduler
     * 重綁錯誤時輸出的錯誤代碼（既有 {@code ACELIB-DBG-001} =
     * 「診斷模組自身錯誤」）。
     */
    private static final String RELOAD_DIAGNOSTICS_FAILURE_CODE = "ACELIB-DBG-001";

    private volatile boolean ready = false;
    private volatile Server server;
    private volatile PlatformDetector platformDetector;
    private volatile AceLibApi api;
    /**
     * Phase 14：當前綁定的 SafeSchedulerImpl。
     *
     * <p>在 onEnable 建立、onDisable 標記 disabled；reload 時重建。
     * 即使 plugin 已被 disable，仍提供 reference 供測試與診斷使用
     * （state 已降級為 disabled）。</p>
     */
    private volatile SafeSchedulerImpl scheduler;
    /**
     * Phase 14：當前綁定的 DiagnosticsService。
     *
     * <p>在 onEnable 建立並 bind plugin 版本/平台/capability；
     * onDisable 時解除 scheduler 綁定並 reset throttler；reload 時重建並重新
     * 綁定（不殘留舊綁定）。即使 plugin 未 onEnable，仍回傳 safe default
     * instance（{@link DiagnosticsService} 本身支援 unbind 查詢）。</p>
     */
    private volatile DiagnosticsService diagnostics;
    /**
     * 當前綁定的 {@link PlayerDataService}。
     *
     * <p>於 onEnable 建立並 register Bukkit
     * {@code PlayerJoinEvent}/{@code PlayerQuitEvent} listener 將事件委派給 service。
     * onDisable 時 shutdown service（flush dirty 資料、reject new work、清除 in-flight
     * tracking），reload 時 shutdown + 重建（保留既有 API 語意）。</p>
     *
     * <p>未 onEnable 時為 null；onEnable 之前呼叫 {@link #getPlayerDataService()}
     * 必須回傳 null（safe-default）。</p>
     */
    private volatile PlayerDataService playerDataService;
    /**
     * 當前 {@link PlayerDataService} 使用的 listener。
     * 持有 reference 是為了 onDisable 時可透過 {@link HandlerList#unregister(Listener)}
     * 確保 listener 不殘留於 Bukkit HandlerList。
     */
    private volatile PlayerLifecycleListener playerLifecycleListener;
    private volatile boolean playerLifecycleRegistered;

    /**
     * v0.1.0 管理指令（{@code /acelib status}）使用的 {@link CommandRegistryImpl}。
     *
     * <p>於 onEnable 建立並註冊 {@code /acelib} 主指令（含 {@code status} 子指令）；
     * 透過 {@link BukkitCommandBridge#attach} 把 executor / tabCompleter 綁到
     * {@link PluginCommand}，使 Bukkit 端派送最終走到 AceLib 的
     * {@link com.smile.acelib.command.CommandRegistry}。reload 不重建此 registry
     * — register 只發生一次，handler 透過 {@code Supplier<DiagnosticsService>}
     * 反映 reload 後的最新 metadata。</p>
     */
    private volatile CommandRegistryImpl commandRegistry;
    /**
     * v0.1.0：attach 到 {@link PluginCommand} 的 {@link BukkitCommandBridge}；
     * onDisable 時把 executor / tabCompleter 設回 null，避免 Bukkit 在
     * plugin disabled 後仍派送到 AceLib 的 dispatcher。
     */
    private volatile BukkitCommandBridge commandBridge;
    /**
     * Phase 10: world/block/entity/teleport 安全 facade。
     *
     * <p>於 {@link #bindWorldService(Server)} 建立並透過 {@link #unbindWorldService()}
     * shutdown。onEnable 之前若被取得，一律回 unavailable facade（{@link WorldErrorCode#NOT_READY}）。
     * reload 期間以 commit-or-rollback 語意同步重建。
     */
    private volatile WorldService worldService;

    /**
     * Package-private 測試 seam：reload 流程中可在「舊 scheduler teardown 之後」
     * 注入受控失敗，模擬 {@code SafeSchedulerImpl.onPluginDisable()} 拋錯的罕見
     * 路徑。Production 預設為 null；正常 reload 不會觸發。
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 保證測試可在 {@code synchronized reload()} 之外安全
     * 寫入；reload 內部於 synchronized 區塊內讀取。</p>
     *
     * @since Phase 14 (Plan §十九, M-14-04)
     */
    volatile Runnable reloadOldTeardownFailureHook = null;

    /**
     * Package-private 測試 seam：reload 流程中可在「diagnostics rebind 完成、
     * commit 前」注入受控失敗，模擬 {@code DiagnosticsService.bindScheduler(...)}
     * 內部不一致或外部 listener 拋錯的罕見路徑。Production 預設為 null；正常
     * reload 不會觸發。
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 同上。</p>
     *
     * @since Phase 14 (Plan §十九, M-14-04)
     */
    volatile Runnable reloadRebindFailureHook = null;

    /**
     * Package-private 測試 seam：reload 流程中可在「建立新
     * {@link SafeSchedulerImpl} 之前」注入受控失敗，模擬
     * {@code new SafeSchedulerImpl(this, platform, capability)} 建構子內部
     * 拋錯的罕見路徑（classpath 不一致、Folia scheduler 工廠拒絕等）。Production
     * 預設為 null；正常 reload 不會觸發。
     *
     * <p>此 hook 讓測試能在不依賴 call stack 入侵或 reflection 注入 constructor
     * 例外的情況下，明確驗證 Phase B 失敗路徑（M-14-04 補強）：
     * reload 必須回傳 false 並與 Phase A 一致進入 FAILED/non-ready policy。</p>
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 同上。</p>
     *
     * @since Phase 14 (Plan §十九, M-14-04 補強)
     */
    volatile Runnable reloadNewSchedulerConstructionFailureHook = null;

    /** Package-private test seam for a controlled player-service shutdown failure. */
    volatile Runnable reloadPlayerShutdownFailureHook = null;

    public AceLibPlugin() {
        // 預先放一個 uninitialized facade，避免 getApi() 在 onEnable 前丟例外
        this.api = AceLibApi.uninitialized();
        // DiagnosticsService 預設 instance：尚未 bind plugin，但允許 buildSnapshot()
        // 查詢（會以 AceLibVersion.VERSION / Platform.UNKNOWN / not ready 呈現）
        this.diagnostics = new DiagnosticsService(Clock.system());
        // Phase 10: worldService 的 NOT_READY unavailable facade；於 onEnable 後被 bindWorldService() 替換。
        this.worldService = new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY);
    }

    // ---------------------------------------------------------------------
    // Bukkit 生命週期
    // ---------------------------------------------------------------------

    /**
     * Bukkit/Paper/Folia 伺服器呼叫的進入點。
     *
     * <p>內部委派給 {@link #onEnable(Server, PlatformDetector, Clock)}，
     * 保持單一初始化路徑，方便測試。</p>
     *
     * <p>注意：此方法刻意標記為 {@code non-final}，允許測試子類別覆寫以模擬
     * 「Bukkit 尚未呼叫 onEnable」的初始狀態。</p>
     */
    @Override
    public void onEnable() {
        Server s = getServer();
        PlatformDetector d = new PlatformDetector(getClass().getClassLoader());
        onEnable(s, d, Clock.system());
        onPluginReady();
    }

    /**
     * 對外測試 seam：直接接收 Server 與 PlatformDetector，跳過 Bukkit 內部呼叫。
     *
     * <p>內部委派給 {@link #onEnable(Server, PlatformDetector, Clock)}，
     * 使用 {@link Clock#system()} 作為時鐘來源；既有單元測試（不需 deterministic
     * clock）繼續呼叫此方法即可。</p>
     *
     * @param s           當前 server（測試情境下可為 mock）
     * @param detector    平台偵測器（測試情境下可注入固定回傳）
     */
    public synchronized void onEnable(Server s, PlatformDetector detector) {
        onEnable(s, detector, Clock.system());
    }

    /**
     * Phase 14：對外測試 seam，允許注入 deterministic {@link Clock}。
     *
     * <p>建立並綁定 {@link SafeSchedulerImpl} + {@link DiagnosticsService}；
     * 兩者皆透過 {@link Clock} 取得時間，避免測試依賴系統時鐘。冪等
     * （重複呼叫不爆）。</p>
     *
     * @param s        當前 server（測試情境下可為 mock）
     * @param detector 平台偵測器（測試情境下可注入固定回傳）
     * @param clock    時鐘來源；不可為 null
     * @since Phase 14 (Plan §十九)
     */
    public synchronized void onEnable(Server s, PlatformDetector detector, Clock clock) {
        if (ready) {
            logFine("AceLib.onEnable() called when already ready; idempotent skip.");
            return;
        }
        Objects.requireNonNull(clock, "clock");
        this.server = s;
        this.platformDetector = detector;

        // 1. 偵測平台（含失敗情境 logging）
        Platform detected = detector.detect();
        logPlatformStatus(detected, detector);

        // 2. 推導 capability profile
        PlatformCapability capability = detector.detectCapability(detected);

        // 3. 建立 scheduler（Phase 14 統一管理 lifecycle）
        SafeSchedulerImpl newScheduler = new SafeSchedulerImpl(this, detected, capability);

        // 4. 建立 diagnostics service（Phase 14 統一入口），並 bind 版本/平台/capability
        DiagnosticsService newDiagnostics = new DiagnosticsService(clock);
        newDiagnostics.bindPlugin(AceLibVersion.VERSION, detected, capability);
        newDiagnostics.setReady(true);
        newDiagnostics.bindScheduler(newScheduler);

        this.scheduler = newScheduler;
        this.diagnostics = newDiagnostics;

        // 5. 發佈 facade（5 參數版本，攜帶實際 capability）
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            detected,
            capability,
            this.worldService,
            () -> ready,
            () -> reload()
        );

        // 建立玩家資料服務與事件 listener；註冊延後到 Bukkit 確認 plugin enabled。
        bindPlayerDataService(s);

        // Phase 10: 建立 world 服務（在 player 服務與管理指令之後、最後）。
        bindWorldService(s);

        // v0.1.0：建立管理指令系統（/acelib status 等）。在 player listener 註冊
        // 之前先建立並 attach bridge — PluginCommand 的取得來自 plugin.yml，
        // 與 player listener 註冊時機無依賴關係。
        bindCommandFramework();

        this.ready = true;
        logInfo("AceLib {0} enabled on {1} (capability={2})",
            api.getVersion(), api.getPlatform().getDisplayName(), capability);
    }

    @Override
    public synchronized void onDisable() {
        if (!ready) {
            logFine("AceLib.onDisable() called before onEnable; safe no-op.");
            return;
        }
        SafeSchedulerImpl oldScheduler = this.scheduler;
        DiagnosticsService oldDiagnostics = this.diagnostics;
        PlayerDataService oldPlayerService = this.playerDataService;
        PlayerLifecycleListener oldListener = this.playerLifecycleListener;

        // 解除已綁定的 SafeEventRegistry lifecycle；放在 scheduler / diagnostics
        // teardown 之前，避免 listener 在 scheduler 模組標記 FAILED 之後才被
        // dispatch（此時 recorder sink 已清除，會丟 NPE）。
        // AceLibEvents.unbind 內部會呼叫 SafeEventRegistryImpl.onPluginDisable，
        // 後者真的解除 Bukkit HandlerList 上的 bridge listener。
        try {
            com.smile.acelib.event.AceLibEvents.unbind(this);
        } catch (Throwable t) {
            logFine("AceLibEvents.unbind failed (ignored): " + t.getMessage());
        }

        // 先 unregister Bukkit listener 再 shutdown service。
        // 順序理由：listener unregister 後 Bukkit 不再 dispatch join/quit；
        // shutdown service 會 flush dirty 並 terminate；此後即使有人持有
        // service reference 也無法新增工作。
        if (oldListener != null) {
            HandlerList.unregisterAll(oldListener);
            this.playerLifecycleListener = null;
        }
        this.playerLifecycleRegistered = false;

        // v0.1.0：解除管理指令綁定。先把 PluginCommand 的 executor / tabCompleter
        // 設為 null（Bukkit 端不再派送），再 disable registry 內部狀態，
        // 確保 disable 後任何殘留的 in-flight dispatch 都會回
        // {@code ACELIB-CMD-009 REGISTRY_DISABLED} 而非靜默執行。
        unbindCommandFramework();

        if (oldPlayerService != null) {
            try {
                oldPlayerService.shutdown();
            } catch (PlayerStateException failure) {
                logSevereWithCode(failure.getCode(),
                    "player data shutdown failed during plugin disable: " + failure.getMessage());
            } finally {
                this.playerDataService = null;
            }
        }

        // Phase 10: world 服務 shutdown（標記 stopped、取消 in-flight handle、
        // 註冊 FAILED module state）。順序置於 player 與 scheduler 卸載之後，
        // 確保任何 in-flight teleport 不會被殘留 scheduler 接走。
        unbindWorldService();

        this.ready = false;
        this.server = null;
        this.platformDetector = null;
        // 保留 SHUTDOWN worldService reference，避免 double-fork 既有 contract。
        this.api = AceLibApi.shutDown(this.worldService);

        // 安全降級：
        // 1. scheduler 標記 disabled（解除其 recorder listener 避免 disable 後仍收到通知）
        // 2. diagnostics 保留同一 reference；scheduler 模組降級為 FAILED + ACELIB-SCHED-006，
        //    ready 設為 false，throttler 重置。供既有 reference（管理員命令、測試 seam）
        //    仍可查詢「曾 bind 但現已 disable」的狀態。
        // Phase 10: 先 shutdown 既有的 worldService（標記 stopped），
        // 確保 reload 期間 in-flight handle 不會被舊 backend 殘留繼續執行。
        if (worldService != null) {
            try {
                worldService.shutdown();
            } catch (Throwable t) {
                logFine("reload: old worldService shutdown failed (ignored): " + t.getMessage());
            }
        }

        if (oldScheduler != null) {
            try {
                oldScheduler.getRecorder().clearRecordSink();
                oldScheduler.onPluginDisable();
            } catch (Throwable t) {
                logFine("scheduler onPluginDisable failed (ignored): " + t.getMessage());
            }
        }
        if (oldDiagnostics != null) {
            try {
                oldDiagnostics.markSchedulerDisabled();
                oldDiagnostics.setReady(false);
                oldDiagnostics.resetThrottler();
            } catch (Throwable t) {
                logFine("diagnostics teardown failed (ignored): " + t.getMessage());
            }
        }

        logInfo("AceLib disabled");
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * 當前 plugin 是否已通過 {@link #onEnable()}。
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 取得對外 API facade。在 onEnable 之前後都可呼叫，永不回傳 null。
     *
     * @return 不可變的 {@link AceLibApi} 實例
     */
    public AceLibApi getApi() {
        return api;
    }

    /**
     * 取得當前偵測到的 platform capability profile。
     *
     * <p>若 plugin 尚未 onEnable，回傳 {@link AceLibApi#uninitialized()}
     * 內含的 {@link PlatformCapability#forPlatform(Platform) UNKNOWN capability}（全 false）。</p>
     *
     * <p>後續插件可讀此方法以決定是否啟用 Folia regionized scheduler、
     * Paper global scheduler、或降級為不可用。</p>
     *
     * @return 永遠不為 null 的 {@link PlatformCapability}
     * @since Phase 1 (Plan §六)
     */
    public PlatformCapability getPlatformCapability() {
        return api.getPlatformCapability();
    }

    /**
     * 取得當前綁定的 {@link DiagnosticsService}（Phase 14 統一診斷入口）。
     *
     * <p>永遠不為 null：</p>
     * <ul>
     *   <li>onEnable 之前 → 建構子預先建立的 safe default instance（未 bind、not ready）</li>
     *   <li>onEnable 之後 → 已 bind plugin 版本/平台/capability 且已 ready 的實例</li>
     *   <li>onDisable 之後 → 同一 reference；{@code isReady} 回傳 false，scheduler 模組
     *       狀態降級為 FAILED（不�例外）</li>
     * </ul>
     *
     * <p>此 getter 供管理員命令（後續 plugin）或測試 seam 取得 diagnostics 物件；
     * 不會回傳 {@code null}，呼叫端可放心 chain 呼叫。</p>
     *
     * @return 永遠不為 null 的 {@link DiagnosticsService}
     * @since Phase 14 (Plan §十九)
     */
    public DiagnosticsService getDiagnosticsService() {
        return diagnostics;
    }

    /**
     * 取得當前綁定的 {@link SafeSchedulerImpl}（Phase 14 diagnostics wiring 入口）。
     *
     * <p>onEnable 之前回傳 null；onEnable 之後回傳當前綁定的 scheduler。
     * 即使 onDisable 後仍回傳 reference（已 disabled），供測試驗證 lifecycle。</p>
     *
     * @return 當前 scheduler，可能為 null（plugin 未啟用）
     * @since Phase 14 (Plan §十九)
     */
    public SafeSchedulerImpl getSchedulerForDiagnostics() {
        return scheduler;
    }

    /**
     * 便捷方法：建立當下不可變 {@link DiagnosticReport}（Phase 14 對外查詢入口）。
     *
     * <p>內部委派給 {@link DiagnosticsService#buildReport()}。
     * 等同 {@code getDiagnosticsService().buildReport()}。</p>
     *
     * @return 新的 {@link DiagnosticReport}；永遠不為 null
     * @since Phase 14 (Plan §十九)
     */
    public DiagnosticReport buildDiagnosticsReport() {
        return diagnostics.buildReport();
    }

    /**
     * 取得當前綁定的 {@link PlayerDataService}（玩家資料服務）。
     *
     * <p>於 onEnable 建立；reload 時 shutdown 舊 service + 建立新 service；onDisable
     * 時 shutdown。呼叫端可用此 service 查詢 / 修改玩家資料，或測試驗證 lifecycle
     * 整合。</p>
     *
     * <p>onEnable 之前回傳 null；onDisable 之後回傳 null。reload 失敗時可能仍
     * 回傳既有 service（recoverable failure，service 仍可用）。</p>
     *
     * @return 當前 {@link PlayerDataService}；可能為 null（plugin 未啟用）
     */
    PlayerDataService getPlayerDataService() {
        return playerDataService;
    }

    /**
     * 重新偵測平台並發佈新 API 實例（Phase 14：既有 diagnostics reference in-place 重綁）。
     *
     * <h2>交易式失敗語意（M-14-04）</h2>
     * <p>reload 採四階段 commit 流程，<strong>任一階段失敗 → 中止 commit、回傳
     * {@code false}、不發布半完成狀態</strong>：</p>
     * <ol>
     *   <li><strong>Phase A：解除舊 scheduler</strong> — 解除 recorder listener
     *       並標記 {@code oldScheduler.onPluginDisable()}。失敗 → log
     *       SEVERE + {@code ACELIB-DBG-001}、return false（不繼續，避免留
     *       下「舊 scheduler 半 disabled + 新 scheduler 已綁」混合狀態）。</li>
     *   <li><strong>Phase B：建立新 scheduler</strong> — 若
     *       {@link SafeSchedulerImpl} 建構子拋錯 → log SEVERE、return false。</li>
     *   <li><strong>Phase C：diagnostics in-place 重綁</strong> — 既有
     *       {@link DiagnosticsService} reference 透過 {@code rebindPlugin} +
     *       {@code bindScheduler(new)} 更新版本/平台/capability 並綁定新
     *       scheduler。失敗 → rollback：新 scheduler 標記 disabled、既有
     *       diagnostics 重新綁回舊 scheduler（自動標記 FAILED +
     *       {@code ACELIB-SCHED-006}，語意「曾 bind 但 reload 失敗」）；
     *       log SEVERE + {@code ACELIB-DBG-001}、return false。
     *       <strong>此階段不修改 {@code this.scheduler} / {@code this.api}</strong>。</li>
     *   <li><strong>Phase D：commit</strong> — 全部成功才寫入
     *       {@code this.scheduler}、{@code this.api}、輸出 reload info log、回傳
     *       {@code true}。</li>
     * </ol>
     *
     * <p>與舊版差異：</p>
     * <ul>
     *   <li>舊版 catch {@code Throwable} 後僅 FINE 記錄並回 true — 半完成新狀態
     *       會被視為「reload 成功」，管理員無法察覺。</li>
     *   <li>新版採 commit-or-rollback 語意，失敗時保留既有
     *       {@code this.scheduler} / {@code this.api} reference；既有 diagnostics
     *       reference 仍可查得（scheduler 模組明確標記 FAILED）。</li>
     * </ul>
     *
     * <p>既有契約保留：成功 reload 時既有 {@code DiagnosticsService} reference
     * 仍為同一物件、scheduler 模組為 READY、舊 scheduler 已 disabled。</p>
     *
     * @return 若 plugin 已啟用且 reload 成功則回傳 true；未啟用時回傳 false
     */
    public synchronized boolean reload() {
        if (!ready || platformDetector == null) {
            return false;
        }
        Platform reDetected = platformDetector.detect();
        PlatformCapability reCapability = platformDetector.detectCapability(reDetected);

        SafeSchedulerImpl oldScheduler = this.scheduler;
        DiagnosticsService ds = this.diagnostics;
        PlayerDataService oldPlayerService = this.playerDataService;
        PlayerLifecycleListener oldListener = this.playerLifecycleListener;

        // -----------------------------------------------------------------
        // Phase A：解除舊 scheduler（recorder listener + onPluginDisable）
        // 失敗 → 中止整個 reload（避免「舊 scheduler 半 disabled + 新 scheduler 已
        // 綁」的混合狀態），明確降級 plugin / diagnostics 為 FAILED 狀態，
        // 回傳 false。
        // -----------------------------------------------------------------
        if (oldScheduler != null) {
            try {
                oldScheduler.getRecorder().clearRecordSink();
                oldScheduler.onPluginDisable();
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload: old scheduler teardown failed; downgrading plugin to "
                        + "FAILED state to avoid leaving a half-applied scheduler. "
                        + "Cause: " + t);
                downgradeAfterReloadPhaseAFailure(ds);
                return false;
            }
            // 測試 seam：允許注入受控失敗
            if (reloadOldTeardownFailureHook != null) {
                try {
                    reloadOldTeardownFailureHook.run();
                } catch (Throwable t) {
                    logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                        "reload: old scheduler teardown hook failed; downgrading "
                            + "plugin to FAILED state. Cause: " + t);
                    downgradeAfterReloadPhaseAFailure(ds);
                    return false;
                }
            }
        }

        // -----------------------------------------------------------------
        // Phase B：建立新 SafeSchedulerImpl
        // 失敗 → 舊 scheduler 已 disabled（Phase A），不需額外 rollback；
        // 但為避免「diagnostics 顯示 READY 但實際 scheduler 半失效」的假象，
        // 必須與 Phase A 一致明確降級 diagnostics 與 plugin 為 FAILED 狀態，
        // 保留 scheduler / api reference。M-14-04 補強。
        // -----------------------------------------------------------------
        SafeSchedulerImpl newScheduler;
        try {
            // 測試 seam：允許在建構前注入受控失敗，模擬 new SafeSchedulerImpl(...)
            // 建構子罕見拋錯路徑；hook 預設 null，正常 reload 不會觸發。
            if (reloadNewSchedulerConstructionFailureHook != null) {
                reloadNewSchedulerConstructionFailureHook.run();
            }
            newScheduler = new SafeSchedulerImpl(this, reDetected, reCapability);
        } catch (Throwable t) {
            logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                "reload: failed to construct new SafeSchedulerImpl; downgrading "
                    + "plugin to FAILED state to avoid leaving a half-applied "
                    + "scheduler. Cause: " + t);
            downgradeAfterReloadPhaseAFailure(ds);
            return false;
        }

        // -----------------------------------------------------------------
        // Phase C：diagnostics in-place 重綁（保留既有 DiagnosticsService reference）
        // 失敗 → rollback：newScheduler 標記 disabled；既有 ds 重新綁回舊 scheduler
        // （oldScheduler 已 disabled，bindScheduler 內部會將模組標記為 FAILED +
        // ACELIB-SCHED-006，語意「曾 bind 但 reload 失敗」）。
        // this.scheduler / this.api 不被修改（保留原值，rollback 完成）。
        //
        // M-14-04 補強：Phase C 開始前先 snapshot 既有 version/platform/capability/
        // ready metadata；rollback 時完整還原（restoreMetadata + setReady），
        // 避免留下「scheduler reference 雖未 commit、但 diagnostics 內容已
        // 是新平台」的 partial commit 假狀態。
        // -----------------------------------------------------------------
        DiagnosticsMetadataSnapshot oldMeta = ds == null ? null : DiagnosticsMetadataSnapshot.capture(ds);
        if (ds != null) {
            // 1. 快照既有 metadata（reload 前值）；rollback 時以此還原
            try {
                ds.rebindPlugin(AceLibVersion.VERSION, reDetected, reCapability);
                ds.setReady(true);
                ds.bindScheduler(newScheduler);
                // 測試 seam：允許在 commit 前注入受控失敗
                if (reloadRebindFailureHook != null) {
                    reloadRebindFailureHook.run();
                }
            } catch (Throwable t) {
                // rollback：釋放 newScheduler + 還原既有 ds 的 metadata 與 ready，
                // 再把 ds 重新綁回舊 scheduler（語意「曾 bind 但 reload 失敗」）
                rollbackReload(newScheduler, ds, oldMeta, oldScheduler);
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload: diagnostics rebind failed; rolled back to previous binding "
                        + "(metadata + scheduler restored). Cause: " + t);
                return false;
            }
        }

        // -----------------------------------------------------------------
        // Phase D：commit（全部階段成功才執行）
        // -----------------------------------------------------------------
        if (oldPlayerService != null) {
            try {
                if (reloadPlayerShutdownFailureHook != null) {
                    reloadPlayerShutdownFailureHook.run();
                }
                oldPlayerService.shutdown();
            } catch (Throwable failure) {
                rollbackReload(newScheduler, ds, oldMeta, oldScheduler);
                if (oldListener != null) {
                    HandlerList.unregisterAll(oldListener);
                }
                this.playerLifecycleRegistered = false;
                this.ready = false;
                if (ds != null) {
                    try {
                        ds.setReady(false);
                    } catch (Throwable readyFailure) {
                        logFine("reload player failure: diagnostics degrade failed: "
                            + readyFailure.getMessage());
                    }
                }
                String code = failure instanceof PlayerStateException playerFailure
                    ? playerFailure.getCode() : "ACELIB-PLAYER-003";
                logSevereWithCode(code,
                    "reload: player data shutdown failed; plugin degraded without commit. Cause: "
                        + failure);
                return false;
            }
        }
        if (oldListener != null) {
            HandlerList.unregisterAll(oldListener);
        }
        this.playerLifecycleRegistered = false;
        bindPlayerDataService(this.server);
        // Phase 10: reload 成功後重新建立 world 服務（既有 worldService 已 shutdown）。
        bindWorldService(this.server);

        this.scheduler = newScheduler;
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            reDetected,
            reCapability,
            this.worldService,
            () -> ready,
            () -> reload()
        );
        onPluginReady();
        logInfo("AceLib reloaded on {0}", reDetected.getDisplayName());
        return true;
    }

    /**
     * Phase C rebind 失敗時的 rollback 輔助方法（M-14-04 補強：metadata 還原）。
     *
     * <p>動作順序：</p>
     * <ol>
     *   <li>釋放已建立的 {@code newScheduler}（標記 disabled，避免背景 task 殘留）</li>
     *   <li>還原既有 {@code ds} 的 version/platform/capability metadata 至
     *       reload 前 snapshot（{@link DiagnosticsService#restoreMetadata}）；
     *       避免留下「scheduler reference 雖未 commit、但 diagnostics 內容已
     *       是新平台」的 partial commit 假狀態</li>
     *   <li>還原既有 {@code ds} 的 ready 旗標至 reload 前 snapshot；
     *       Phase C 正常路徑會 {@code setReady(true)}，若 old snapshot 並非
     *       ready（例如先前已被 downgrade），rollback 必須還原其原值</li>
     *   <li>把既有 {@code ds} 重新綁回 {@code oldScheduler}；若 {@code oldScheduler}
     *       已被 Phase A disable，{@link DiagnosticsService#bindScheduler}
     *       會自動將 scheduler 模組標記為 {@code FAILED + ACELIB-SCHED-006}，
     *       語意「曾 bind 但 reload 失敗」</li>
     * </ol>
     *
     * <p>restore 順序刻意安排在 bindScheduler <strong>之前</strong>：
     * metadata 還原與 scheduler 模組狀態（FAILED）互相獨立，但若 restore 失敗
     * 仍應確保 scheduler 模組正確標記 FAILED（{@code bindScheduler(disabled)}
     * 是觸發 FAILED 的唯一路徑）。</p>
     *
     * <p>rollback 為 best-effort：每一步獨立 try/catch，失敗不拋出 —
     * 我們已經在「主要失敗」之後，再失敗無法進一步處理；繼續完成 rollback
     * 能做的部分即可。</p>
     *
     * @param newScheduler  Phase B 剛建立、尚未 commit 的新 scheduler
     * @param ds            既有 diagnostics reference（必須與 this.diagnostics 同一）
     * @param oldMeta       Phase C 開始前快照的 metadata；可為 null（ds == null 時）
     * @param oldScheduler  Phase A 已 disabled 的舊 scheduler；可為 null
     */
    private void rollbackReload(SafeSchedulerImpl newScheduler,
                                 DiagnosticsService ds,
                                 DiagnosticsMetadataSnapshot oldMeta,
                                 SafeSchedulerImpl oldScheduler) {
        // 1. 釋放 newScheduler
        if (newScheduler != null) {
            try {
                newScheduler.onPluginDisable();
            } catch (Throwable t) {
                logFine("reload rollback: newScheduler disable failed (best-effort): "
                    + t.getMessage());
            }
        }
        // 2. 還原 metadata + ready，再 bind scheduler
        if (ds != null) {
            if (oldMeta != null) {
                // 2a. 還原 version/platform/capability（partial commit 防護）
                try {
                    ds.restoreMetadata(oldMeta.version, oldMeta.platform, oldMeta.capability);
                } catch (Throwable t) {
                    logFine("reload rollback: metadata restore failed (best-effort): "
                        + t.getMessage());
                }
                // 2b. 還原 ready 旗標（若 old snapshot 並非 ready）
                try {
                    ds.setReady(oldMeta.ready);
                } catch (Throwable t) {
                    logFine("reload rollback: setReady restore failed (best-effort): "
                        + t.getMessage());
                }
            }
            // 3. 重新綁回舊 scheduler（disabled → 模組標記 FAILED + ACELIB-SCHED-006）
            SafeSchedulerImpl rebindTarget = oldScheduler; // null → unbind（safe-default 場景）
            try {
                ds.bindScheduler(rebindTarget);
            } catch (Throwable t) {
                logFine("reload rollback: rebind to old scheduler failed (best-effort): "
                    + t.getMessage());
            }
        }
    }

    /**
     * Diagnostics metadata snapshot（Phase 14 reload rollback 內部使用）。
     *
     * <p>於 Phase C 開始前捕獲既有 {@link DiagnosticsService} 的
     * version/platform/capability/ready；當 Phase C rebind 失敗時，
     * {@link #rollbackReload} 以此 snapshot 還原 metadata，避免 partial commit。</p>
     *
     * <p>此 record 為套件私有（private），僅供 {@code AceLibPlugin.reload()}
     * 內部使用；不對外暴露，也不進入 L1 記憶。</p>
     *
     * @param version    既有 version；不可為 null
     * @param platform   既有 platform；不可為 null
     * @param capability 既有 capability；不可為 null
     * @param ready      既有 ready 旗標
     */
    private record DiagnosticsMetadataSnapshot(
            String version,
            Platform platform,
            PlatformCapability capability,
            boolean ready) {

        /**
         * 從既有 {@link DiagnosticsService} 快照當下 metadata。
         *
         * <p>null-safe：當 {@code ds} 為 null 時回傳 null（呼叫端
         * {@link #rollbackReload} 內部以 {@code if (oldMeta != null)} 保護，
         * 不會 dereference）。</p>
         *
         * @param ds 既有 diagnostics；可為 null
         * @return 對應 snapshot；ds 為 null 時回傳 null
         */
        static DiagnosticsMetadataSnapshot capture(DiagnosticsService ds) {
            if (ds == null) {
                return null;
            }
            return new DiagnosticsMetadataSnapshot(
                ds.getVersion(),
                ds.getPlatform(),
                ds.getPlatformCapability(),
                ds.isReady()
            );
        }
    }

    /**
     * Phase A 失敗後的狀態降級（M-14-04 補強：避免 READY 假象）。
     *
     * <p>Phase A 任一步驟（{@code clearRecordSink} / {@code onPluginDisable} /
     * 測試 seam {@code reloadOldTeardownFailureHook}）失敗時，oldScheduler
     * 可能已半 disabled、無法安全還原其既有 READY 狀態。為避免
     * 「diagnostics 顯示 READY 但實際 scheduler 半失效」的假象，本方法明確
     * 把 diagnostics 與 plugin 同步降級為 FAILED 狀態。</p>
     *
     * <p>降級動作：</p>
     * <ol>
     *   <li>diagnostics scheduler 模組標 FAILED + {@code ACELIB-SCHED-006}
     *       （透過 {@link DiagnosticsService#markSchedulerDisabled()}）</li>
     *   <li>diagnostics.ready = false（plugin layer ready 旗標）</li>
     *   <li>{@code this.ready = false}（plugin 本體 not ready —
     *       reload 之後不能再使用，須由 caller 重新 {@link #onEnable()}）</li>
     *   <li>{@code this.scheduler} 不修改（保留 reference，狀態已 disabled，
     *       供測試與診斷查得）</li>
     *   <li>{@code this.api} 不修改（保留 reference；其 {@code readyCheck}
     *       callback 已會回傳 false）</li>
     * </ol>
     *
     * <p>此方法為 best-effort：任一步驟失敗不拋出（已是最壞情況）。
     * 不得修改 {@code this.scheduler} / {@code this.api}。</p>
     *
     * @param ds 既有 diagnostics reference（不可為 null；傳入時須保證非 null，
     *            內部仍以 null-guard 保護）
     * @since Phase 14 (Plan §十九, M-14-04 補強)
     */
    private void downgradeAfterReloadPhaseAFailure(DiagnosticsService ds) {
        if (ds != null) {
            try {
                // scheduler 模組 → FAILED + ACELIB-SCHED-006
                ds.markSchedulerDisabled();
            } catch (Throwable ignore) {
                logFine("reload downgrade: markSchedulerDisabled failed (ignored): "
                    + ignore.getMessage());
            }
            try {
                // diagnostics plugin ready 旗標 → false
                ds.setReady(false);
            } catch (Throwable ignore) {
                logFine("reload downgrade: setReady(false) failed (ignored): "
                    + ignore.getMessage());
            }
        }
        // plugin 本體 not ready — 之後 reload() 會因 !ready 提早 return false
        this.ready = false;
        // this.scheduler / this.api 保留 reference（狀態已 disabled，callback 回 false）
    }

    // ---------------------------------------------------------------------
    // 平台狀態輸出（Phase 1 新增；對應 §六 驗收標準 #3 / #4）
    // ---------------------------------------------------------------------

    /**
     * 依偵測結果輸出適當的 log。
     *
     * <ul>
     *   <li>{@link Platform#UNKNOWN} → warning，附 {@code ACELIB-PLAT-004} 錯誤代碼</li>
     *   <li>{@link Platform#PAPER} 且 Folia classpath 不可用 → fine-level 提示</li>
     *   <li>{@link Platform#FOLIA} → 靜默（功能最齊全，不需額外提示）</li>
     * </ul>
     */
    private void logPlatformStatus(Platform detected, PlatformDetector detector) {
        if (detected == Platform.UNKNOWN) {
            // §六 驗收標準 #3：不支援環境下給出明確警告，不誤判為 Folia
            safeLogger().log(Level.WARNING,
                "AceLib could not detect a Folia or Paper classpath; "
                    + "some features may be unavailable. " + PLATFORM_UNKNOWN_ERROR_CODE);
            return;
        }
        if (detected == Platform.PAPER && !detector.isFoliaClasspathAvailable()) {
            // §六 邊界條件「保守策略」：明確告知 caller 此環境不支援 Folia 專屬能力
            safeLogger().log(Level.FINE,
                "(non-Folia environment detected; RegionizedServer API unavailable)");
        }
    }

    // ---------------------------------------------------------------------
    // Logger 適配：在 Bukkit 環境使用 JavaPlugin.getLogger()，在純單元測試環境
    // 退回到 java.util.logging.Logger，避免測試實例尚未 init 時 NPE。
    // ---------------------------------------------------------------------

    private Logger safeLogger() {
        try {
            Logger l = getLogger();
            return l != null ? l : Logger.getLogger(LOG_NAME);
        } catch (Throwable t) {
            return Logger.getLogger(LOG_NAME);
        }
    }

    private void logInfo(String pattern, Object... args) {
        safeLogger().log(Level.INFO, pattern, args);
    }

    private void logFine(String msg) {
        safeLogger().log(Level.FINE, msg);
    }

    /**
     * 輸出含 {@code ACELIB-<AREA>-<CODE>} 錯誤代碼的 WARNING/SEVERE 等級 log。
     *
     * <p>Phase 14 failure-path（M-14-04）規範：reload 流程中的可追蹤錯誤必須
     * 以 WARNING/SEVERE + 結構化 code 形式輸出，禁止吞錯或僅 FINE 記錄。</p>
     *
     * @param code    錯誤代碼（不可為 null；必須為 {@code ACELIB-*} 格式）
     * @param message 詳細訊息
     */
    private void logSevereWithCode(String code, String message) {
        safeLogger().log(Level.SEVERE, "[" + code + "] " + message);
    }

    // ---------------------------------------------------------------------
    // v0.1.0 管理指令 lifecycle（/acelib status 等）
    // ---------------------------------------------------------------------

    /** v0.1.0 管理指令主指令名稱（必須與 plugin.yml 的 commands 區塊對應）。 */
    private static final String ADMIN_COMMAND_NAME = "acelib";

    /**
     * 建立 {@code /acelib} 管理指令系統：registry + bridge + Bukkit PluginCommand
     * attach。設計原則：
     *
     * <ul>
     *   <li>register 只在 onEnable 呼叫一次；reload 不重建 registry — handler
     *       透過 {@code Supplier<DiagnosticsService>} 反映 reload 後的 metadata</li>
     *   <li>{@code plugin.yml} 缺少對應 commands 宣告時，attach 回 null；此時
     *       bridge 不掛上 PluginCommand，指令無法被觸發 — 我們以 SEVERE log
     *       攜帶 {@code ACELIB-CMD-012} 提示，但 plugin 其他功能不受影響</li>
     *   <li>permission 由 CommandSpec 設定（{@code acelib.admin}）；玩家權限
     *       缺失時由 {@link CommandRegistryImpl#dispatch} 統一回
     *       {@code ACELIB-CMD-003} NO_PERMISSION</li>
     *   <li>ReplySink 的 {@link com.smile.acelib.command.BukkitReplySink.SafeExecutorBackend}
     *       在 {@code bindCommandFramework} 階段建立，{@code isReady()} 旗標此時尚未
     *       翻轉（{@code ready = true} 在本方法之後才設）。為了避免 backend
     *       在 {@code isReady() = false} 時被偵測為「不可用」並回拒絕例外，
     *       此處顯式注入 eager backend（dispatch 時直接呼叫
     *       {@code SafeExecutor.executeOnRegion}），繞過 backend 的
     *       {@code isReady()} 預檢。registry 的 {@code disabled} 旗標仍由
     *       {@code unbindCommandFramework} 設定，可擋下 disable 後任何
     *       殘留 in-flight dispatch。</li>
     * </ul>
     *
     * <p>此方法在 onEnable 內（建立 diagnostics / player service 之後）呼叫；
     * 不在 onPluginReady 才呼叫 — PluginCommand 的取得依賴 plugin.yml 載入，
     * 與 Bukkit {@code isEnabled()} 狀態無關，提早 attach 反而減少 race
     * window。</p>
     */
    private void bindCommandFramework() {
        if (this.commandRegistry != null) {
            // 已在 onEnable 註冊過（idempotent — 防 reload 場景重複）
            return;
        }
        // 顯式 eager backend：繞過 BukkitReplySink.detect 的 isReady() 預檢，
        // 因為 bindCommandFramework 在 onEnable 的 ready=true 之前執行。
        // 安全保證：backend 只在 command dispatch 時被呼叫，而 dispatch 只在
        // plugin enabled（即 ready=true）時發生。disable 之後的 dispatch
        // 會在 CommandRegistryImpl.onPluginDisable 階段被擋下。
        com.smile.acelib.command.BukkitReplySink.SafeExecutorBackend eagerBackend =
            (p, player, runnable) -> {
                var api = AceLibPlugin.this.getApi();
                com.smile.acelib.context.SafeExecutor.executeOnRegion(
                    p, api.getPlatform(), api.getPlatformCapability(), player, runnable);
            };
        CommandRegistryImpl registry = new CommandRegistryImpl(
            new BukkitReplySink(this, eagerBackend));

        SubCommandSpec statusSpec = SubCommandSpec.builder("status")
            .description("查詢 AceLib 當前狀態（版本、平台、ready、模組摘要、錯誤統計）")
            .handler(new AceLibStatusHandler(this::getDiagnosticsService))
            .build();

        CommandSpec rootSpec = CommandSpec.builder(ADMIN_COMMAND_NAME)
            .description("AceLib 管理指令根節點")
            .usage("/acelib <status>")
            .permission("acelib.admin")
            .subCommand(statusSpec)
            .build();
        registry.register(rootSpec);

        BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
        PluginCommand attached = bridge.attach(this, ADMIN_COMMAND_NAME);
        if (attached == null) {
            logSevereWithCode("ACELIB-CMD-012",
                "bindCommandFramework: plugin.yml 缺少 '" + ADMIN_COMMAND_NAME
                    + "' 指令宣告；/acelib status 等管理指令將無法被觸發。");
        }
        this.commandRegistry = registry;
        this.commandBridge = bridge;
    }

    /**
     * 解除 {@code /acelib} 管理指令綁定。動作：
     *
     * <ol>
     *   <li>把 Bukkit {@link PluginCommand} 的 executor / tabCompleter 設為
     *       null（避免 plugin disabled 後 Bukkit 仍派送到 AceLib dispatcher）</li>
     *   <li>呼叫 {@link CommandRegistryImpl#onPluginDisable}（標記 disabled，
     *       後續 dispatch 會回 {@code ACELIB-CMD-009} REGISTRY_DISABLED）</li>
     *   <li>解除 reference，協助 GC</li>
     * </ol>
     *
     * <p>此方法在 onDisable 內、player service shutdown 之前呼叫 — 確保
     * disable 流程結束後任何殘留的 dispatch 都不會觸發 player service 或
     * scheduler 內部 callback。</p>
     */
    private void unbindCommandFramework() {
        BukkitCommandBridge bridge = this.commandBridge;
        CommandRegistryImpl registry = this.commandRegistry;
        // 1. Bukkit 端解除
        if (bridge != null) {
            try {
                PluginCommand cmd = getCommand(ADMIN_COMMAND_NAME);
                if (cmd != null) {
                    cmd.setExecutor(null);
                    cmd.setTabCompleter(null);
                }
            } catch (Throwable t) {
                logFine("unbindCommandFramework: clear Bukkit executor failed (ignored): "
                    + t.getMessage());
            }
        }
        // 2. registry 內部標記 disabled（後續 dispatch 拒絕）
        if (registry != null) {
            try {
                registry.onPluginDisable();
                registry.unregister(ADMIN_COMMAND_NAME);
            } catch (Throwable t) {
                logFine("unbindCommandFramework: registry disable failed (ignored): "
                    + t.getMessage());
            }
        }
        this.commandRegistry = null;
        this.commandBridge = null;
    }

    // ---------------------------------------------------------------------
    // PlayerDataService lifecycle binding
    // ---------------------------------------------------------------------

    /**
     * 建立並綁定 {@link PlayerDataService} 與其 listener。
     *
     * <p>綁定內容：</p>
     * <ol>
     *   <li>於 {@code plugins/<pluginFolder>/player-data.json} 建立
     *       {@link JsonFileDataStore}（不存在則自動 init；存在則 migrate）</li>
     *   <li>建立 {@link PlayerDataService}，內部 serial executor 為單一 daemon
     *       thread；對 store 的 root()/save() 存取皆序列化</li>
     *   <li>準備 {@link PlayerLifecycleListener}，供 enabled plugin 完成 Bukkit
     *       {@code PlayerJoinEvent}/{@code PlayerQuitEvent} 註冊（MONITOR priority）</li>
     * </ol>
     *
     * <p>listener <strong>不持有 Player reference</strong> — 僅以 UUID + name 快照
     * 委派 service，避免跨執行緒保留 Bukkit entity reference。</p>
     *
     * @param server 當前 server；不可為 null
     */
    private void bindPlayerDataService(Server server) {
        Objects.requireNonNull(server, "server");
        // 使用 plugin.getDataFolder() 確保路徑正確（測試環境下可能為自訂路徑）
        Path dataFile;
        try {
            dataFile = getDataFolder().toPath().resolve("player-data.json");
        } catch (Throwable t) {
            // 非標準環境下 getDataFolder 可能不可用；fallback 維持可預期的 plugin 路徑
            logFine("bindPlayerDataService: getDataFolder failed, using fallback path: "
                + t.getMessage());
            dataFile = Path.of("plugins", "AceLib", "player-data.json");
        }

        // 建立 DataStore（init 時若檔案不存在則新建；存在則讀取既有資料）
        DataStore playerStore;
        try {
            JsonCodec codec = new JsonCodecImpl();
            playerStore = new JsonFileDataStore("acelib-player-data", dataFile,
                SchemaVersion.V1_0, codec);
            playerStore.init();
        } catch (Throwable t) {
            logSevereWithCode("ACELIB-PLAYER-006",
                "bindPlayerDataService: failed to initialize DataStore at "
                    + dataFile + ": " + t.getMessage());
            // DataStore 建立失敗時保留 plugin 其他功能，但不暴露半初始化的 service。
            this.playerDataService = null;
            this.playerLifecycleListener = null;
            return;
        }

        // 建立 service（內部 serial executor 為單一 daemon thread）
        PlayerDataService service = new PlayerDataService(playerStore,
            createPlayerIoExecutor());
        PlayerLifecycleListener listener = new PlayerLifecycleListener(service);
        this.playerDataService = service;
        this.playerLifecycleListener = listener;
    }

    /**
     * Phase 10：建立 world 服務與其 diagnostics 綁定。
     *
     * <p>於 onEnable / reload commit 階段呼叫，建立 {@link BukkitWorldBackend} +
     * {@link WorldServiceImpl} 並委派 {@code WorldServiceImpl} 於
     * {@link DiagnosticsService} 註冊 {@code READY} 模組狀態。</p>
     *
     * <p>既有 {@code this.worldService} 若仍是 NOT_READY unavailable facade，
     * 直接覆寫；如果已經是 SHUTDOWN unavailable（reload 情況），同樣覆寫。</p>
     *
     * @param server 當前 Bukkit/Paper/Folia server；不可為 null
     * @since Phase 10 (Plan §十九 §二十一)
     */
    private void bindWorldService(Server server) {
        Objects.requireNonNull(server, "server");
        WorldBackend backend = new BukkitWorldBackend(server);
        WorldService newService = new WorldServiceImpl(backend, diagnostics);
        this.worldService = newService;
        logFine("Phase 10 world service bound to server=" + server.getName());
    }

    /**
     * Phase 10：解除並 shutdown world 服務。
     *
     * <p>呼叫現有 {@code worldService.shutdown()}（idempotent），然後把
     * {@code this.worldService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 reload 後繼續讀到「服務已停用」的訊號，
     * 也保證 AceLibApi 的 worldService 永不為 null。</p>
     *
     * @since Phase 10 (Plan §十九 §二十一)
     */
    private void unbindWorldService() {
        WorldService old = this.worldService;
        if (old != null) {
            try {
                old.shutdown();
            } catch (Throwable t) {
                logFine("worldService.shutdown failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.worldService = new WorldServiceUnavailableImpl(WorldErrorCode.SHUTDOWN);
    }

    /**
     * Completes listener registration after Bukkit has marked this plugin enabled.
     * Package-private so lifecycle tests can exercise the same idempotent seam
     * without invoking MockBukkit's automatic enable path.
     */
    synchronized void onPluginReady() {
        if (!isEnabled() || playerLifecycleRegistered || server == null
                || playerLifecycleListener == null) {
            return;
        }
        server.getPluginManager().registerEvents(playerLifecycleListener, this);
        playerLifecycleRegistered = true;
    }

    /**
     * 建立 PlayerDataService 的 io executor — 使用 cached thread pool，daemon
     * threads，任務快速結束後 thread 可回收。
     */
    private java.util.concurrent.ExecutorService createPlayerIoExecutor() {
        final AtomicLong counter = new AtomicLong(0);
        ThreadFactory tf = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "acelib-player-io-"
                    + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newCachedThreadPool(tf);
    }

    // ---------------------------------------------------------------------
    // Bukkit event listener — join/quit 委派給 PlayerDataService
    // ---------------------------------------------------------------------

    /**
     * {@link PlayerJoinEvent} / {@link PlayerQuitEvent} listener。
     *
     * <p>僅以 {@link UUID} + name 快照呼叫對應 service API；listener 本身
     * <strong>不保留 Player reference</strong>。priority 為 {@link EventPriority#MONITOR} —
     * 表示我們只在事件流程最後觀察，不取消亦不修改事件。</p>
     *
     * <p>於 onDisable / reload 時透過 {@link HandlerList#unregisterAll(Listener)}
     * 解除註冊，確保 listener 不殘留於 Bukkit HandlerList。</p>
     */
    private static final class PlayerLifecycleListener implements Listener {

        private final PlayerDataService service;

        PlayerLifecycleListener(PlayerDataService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String name = player.getName();
            // 立即 snapshot UUID/name；listener 不保留 Player reference
            service.onPlayerJoin(uuid, name);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            // quit 觸發時 player 即將離線；此處取 UUID 即足夠，
            // service 內部已有 name snapshot。
            service.onPlayerQuit(uuid);
        }
    }
}
