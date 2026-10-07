package com.smile.acelib;

import com.smile.acelib.command.AceLibStatusHandler;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.command.BrigadierRegistrar;
import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.CatalogMeta;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.PlayerDataConverter;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.diagnostics.ModuleState;
import com.smile.acelib.external.EconomyProvider;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalIntegrationServiceImpl;
import com.smile.acelib.external.FloodgateIntegrationAdapter;
import com.smile.acelib.external.IntegrationRegistry;
import com.smile.acelib.external.LuckPermsIntegrationAdapter;
import com.smile.acelib.external.PermissionProvider;
import com.smile.acelib.external.PlaceholderApiIntegrationAdapter;
import com.smile.acelib.external.PlaceholderProvider;
import com.smile.acelib.external.VaultIntegrationAdapter;
import com.smile.acelib.display.DisplayErrorCode;
import com.smile.acelib.display.DisplayService;
import com.smile.acelib.display.DisplayServiceControl;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiServiceControl;
import com.smile.acelib.lifecycle.LifecycleResult;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AceLib 主類別（Internal）— Folia-first 基礎函式庫插件。
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>{@link #onEnable()} — 由 Bukkit/Paper/Folia 伺服器呼叫；內部委派給
 *       {@link #onEnable(Server, PlatformDetector, Clock)} 方便單元測試</li>
 *   <li>{@link #onDisable()} — 釋放所有資源；{@link #isReady()} 回傳 false</li>
 *   <li>{@link #reload()} — 重新偵測平台並嘗試發佈新的 {@link AceLibApi} instance；
 *       只有成功 commit 時才會發布新 facade，失敗可能進入 FAILED 狀態或 rollback
 *       保留既有 facade</li>
 * </ul>
 *
 * <h2>功能範圍</h2>
 * <ul>
 *   <li>平台偵測：結果為 {@link Platform#UNKNOWN} 時輸出 warning log（ACELIB-PLAT-004）；
 *       偵測為 {@link Platform#PAPER} 且 classpath 無 Folia 時輸出 fine-level 提示</li>
 *   <li>{@link #getPlatformCapability()} — 對外暴露 platform capability profile</li>
 *   <li>production wiring：{@code onEnable} 建立並綁定 {@link SafeSchedulerImpl}、
 *       {@link DiagnosticsService}（使用可注入的 {@link Clock}）、玩家資料、
 *       world / gui / external service 與管理指令，並透過
 *       {@code diagnostics.bindScheduler(...)} 自動注入 recordSink</li>
 *   <li>{@code onDisable} 安全降級：scheduler {@code onPluginDisable()}、
 *       diagnostics 解除綁定並重置 throttler；不留殘留 lifecycle 資源</li>
 *   <li>{@code reload} 重新偵測 platform/capability，重建 scheduler 並重新
 *       綁定 diagnostics；成功 commit 後不留殘留舊綁定，失敗則可能降級為
 *       FAILED 或 rollback 至既有綁定（保留既有 facade）</li>
 *   <li>{@link #getDiagnosticsService()} 與 {@link #buildDiagnosticsReport()}
 *       作為管理員/後續命令的查詢入口</li>
 * </ul>
 *
 * <h2>對外取得方式</h2>
 * <p>下游插件不要直接依賴本類別；應透過 Bukkit/Paper {@code ServicesManager}
 * 取得 {@link AceLibApi.AceLibProvider}，再呼叫 {@code provider.api()}。</p>
 *
 * <h2>執行緒安全</h2>
 * 狀態欄位使用 {@code volatile} 與 {@code synchronized} 保護；
 * Folia 的 regionized 環境下 reload 通常由 main thread 觸發，但仍須具備 thread-safe 行為。
 *
 * @since 1.0.0
 */
public class AceLibPlugin extends JavaPlugin {

    /** Plugin 標籤，用於 fallback logger。 */
    private static final String LOG_NAME = "AceLib";

    /** 逐玩家資料的 SQLite 資料庫檔名（位於 plugin 資料夾）。 */
    private static final String PLAYER_DATA_DB_FILE = "players.db";
    /** 1.4.0 之前的玩家資料檔名；僅作為一次性轉換來源讀取。 */
    private static final String LEGACY_PLAYER_DATA_FILE = "player-data.json";
    /** 轉換備份與校驗報告的輸出子目錄（位於 plugin 資料夾）。 */
    private static final String PLAYER_DATA_BACKUP_DIR = "backup";

    /** 平台偵測錯誤代碼（未知環境警告）。 */
    private static final String PLATFORM_UNKNOWN_ERROR_CODE = "ACELIB-PLAT-004";

    /**
     * reload 流程中遇到 diagnostics/scheduler 重綁錯誤時輸出的錯誤代碼
     * （既有 {@code ACELIB-DBG-001} =「診斷模組自身錯誤」）。
     */
    private static final String RELOAD_DIAGNOSTICS_FAILURE_CODE = "ACELIB-DBG-001";

    /** 診斷模組名稱（對應 DiagnosticsService 內部 MODULE_INTEGRATION 常數）。 */
    private static final String MODULE_INTEGRATION = "integration";

    private volatile boolean ready = false;
    private volatile Server server;
    private volatile PlatformDetector platformDetector;
    private volatile AceLibApi api;
    /**
     * 當前綁定的 SafeSchedulerImpl。
     *
     * <p>在 onEnable 建立、onDisable 標記 disabled；reload 時重建。
     * 即使 plugin 已被 disable，仍提供 reference 供測試與診斷使用
     * （state 已降級為 disabled）。</p>
     */
    private volatile SafeSchedulerImpl scheduler;
    /**
     * 當前綁定的 DiagnosticsService。
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
     * listener 將 async pre-login、join 與 quit 事件委派給同一個 service。
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
     * 當前 {@link PlayerDataService} 使用的自建 I/O executor。
     *
     * <p>於 {@code bindPlayerDataService} 建立並指派；舊 executor 於 reload /
     * onDisable / INCOMPATIBLE teardown 釋放舊 player 服務時一併關閉。
     * {@link PlayerDataService#shutdown()} 不關閉外部注入的 executor，
     * 故生命週期由本 plugin 集中管理（不得關閉外部注入資源——本欄位只保存
     * {@link #createPlayerIoExecutor()} 的自建 pool）。</p>
     */
    private volatile ExecutorService playerIoExecutor;
    /**
     * 當前 {@link PlayerDataService} 使用的逐玩家 store。
     *
     * <p>持有 reference 是為了在 reload / onDisable 時能確實關閉底層連線：
     * {@link PlayerDataService#shutdown()} 只停止排程與 flush，<strong>不</strong>
     * 關閉它所注入的 store（store 屬注入資源，壽命由擁有者決定）。
     * 順序不可顛倒：先 shutdown 服務（flush 完成）再關 store，
     * 否則 flush 會撞上已關閉的連線。</p>
     */
    private volatile PlayerDataStore playerDataStore;

    /**
     * v1.4.0 管理指令（{@code /acelib status}）使用的型別化指令註冊器。
     *
     * <p>於 onEnable 建立並註冊 {@code /acelib} 型別化根指令（含
     * {@code status} 子指令）；Brigadier 節點經平台生命週期
     * （{@code LifecycleEvents.COMMANDS}）註冊，不再需要
     * {@code plugin.yml} 的 {@code commands} 宣告。reload 不重建、
     * 不重複註冊 — handler 透過 {@code Supplier<DiagnosticsService>}
     * 反映 reload 後的最新 metadata，節點由平台持有。</p>
     */
    private volatile BrigadierRegistrar commandRegistrar;
    /** 指令目錄實例：onEnable 建立並自我發布，reload 保留，onDisable 清空並標記不可用。 */
    private volatile CommandCatalog commandCatalog;
    /** 指令目錄撤下 listener reference：onDisable 時確保不殘留於 HandlerList。 */
    private volatile Listener catalogDisableListener;
    private volatile boolean catalogDisableListenerRegistered;
    /**
     * world/block/entity/teleport 安全 facade。
     *
     * <p>於 {@link #bindWorldService(Server)} 建立並透過 {@link #unbindWorldService()}
     * shutdown。onEnable 之前若被取得，一律回 unavailable facade（{@link WorldErrorCode#NOT_READY}）。
     * reload 期間以 commit-or-rollback 語意同步重建。</p>
     */
    private volatile WorldService worldService;
    /**
     * GUI service facade。
     *
     * <p>於 {@link #bindGuiService(Server)} 建立並透過 {@link #unbindGuiService()}
     * shutdown。onEnable 之前若被取得，一律回 unavailable facade
     * （{@link GuiErrorCode#NOT_READY}）。reload 期間以 commit-or-rollback 語意
     * 同步重建。</p>
     */
    private volatile GuiService guiService;
    /**
     * 當前 GUI service 對應的 Bukkit listener；註冊延後到
     * {@link #onPluginReady()}（比照 {@link PlayerLifecycleListener} 模式，
     * 避免 Bukkit 在 plugin is enabled 之前 allow register）。reload 時同步重建。
     */
    private volatile org.bukkit.event.Listener guiListener;
    private volatile boolean guiListenerRegistered;
    /**
     * 顯示 service facade（AceLib 自身擁有的實例）。
     *
     * <p>於 {@link #bindDisplayService(Server)} 建立並透過
     * {@link #unbindDisplayService()} shutdown。onEnable 之前若被取得，
     * 一律回 unavailable facade
     * （{@code ACELIB-DISP-001}）。reload 期間以 commit-or-rollback 語意
     * 同步重建。退服清理經 {@link #displayQuitListener} 委派
     * {@code handlePlayerQuit}。</p>
     */
    private volatile DisplayService displayService;
    /**
     * 顯示退服清理 listener；註冊延後到 {@link #onPluginReady()}
     *（比照 GUI listener 模式）。reload 時同步重建。
     */
    private volatile org.bukkit.event.Listener displayQuitListener;
    private volatile boolean displayQuitListenerRegistered;

    /**
     * 外部插件整合服務 facade。
     *
     * <p>於 {@link #bindExternalService(Server)} 建立並透過 {@link #unbindExternalService()}
     * shutdown。onEnable 之前若被取得，回傳 null（safe-default，與
     * {@link #getPlayerDataService()} 一致）。reload 期間先 shutdown 舊服務再建立新服務，
     * 失敗不新舊混用。</p>
     */
    private volatile ExternalIntegrationService externalService;

    /**
     * 基岩版玩家服務 facade。
     *
     * <p>於 {@link #bindExternalService(Server)} 成功後由 {@link #bindBedrockService()}
     * 建立：floodgate adapter 啟用時攜帶 typed lookup，缺席時攜帶
     * {@link BedrockService.PlayerLookup#absent()}（查詢安全回覆非基岩玩家，
     * 對呼叫端零影響）。onEnable 之前若被取得，一律回 unavailable facade
     * （{@link BedrockService#NOT_READY}）；disable 後為 SHUTDOWN facade。</p>
     */
    private volatile BedrockService bedrockService;

    /**
     * 對外正式取得入口：動態 provider（{@link AceLibApi.AceLibProvider}）。
     *
     * <p>於 onEnable 建立並透過 Bukkit/Paper {@code ServicesManager} 註冊；
     * reload 時更新同一 provider 的 facade reference（不回傳 stale facade）；
     * onDisable 時解除註冊並把 reference 切換為 shutdown facade。</p>
     *
     * <p>欄位為 {@code volatile}：reload / disable 可能在 main thread 觸發，
     * 但 provider 實作內部也以 volatile 快照目前 facade，任何 thread 讀取
     * {@code api()} 都是安全的。</p>
     */
    private volatile AceLibApi.AceLibProvider apiProvider;
    private final LifecycleHostImpl lifecycleHost = new LifecycleHostImpl(
        () -> apiProvider, Logger.getLogger(LOG_NAME));
    private boolean reloadInProgress;

    /**
     * Package-private 測試 seam：reload 流程中可在「舊 scheduler teardown 之後」
     * 注入受控失敗，模擬 {@code SafeSchedulerImpl.onPluginDisable()} 拋錯的罕見
     * 路徑。Production 預設為 null；正常 reload 不會觸發。
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 保證測試可在 {@code synchronized reload()} 之外安全
     * 寫入；reload 內部於 synchronized 區塊內讀取。</p>
     */
    volatile Runnable reloadOldTeardownFailureHook = null;

    /**
     * Package-private 測試 seam：跳過真實 capability probe，強制回傳指定相容性狀態。
     *
     * <p>Production 預設為 null；正常啟用 / reload 不會觸發。設定後，
     * {@code onEnable} / {@code reload} 不會執行真實 classpath 探測，直接採用
     * 此函式回傳的 {@link CompatibilityStatus}（用於驗證 INCOMPATIBLE / UNVERIFIED
     * 的 fail-closed 路徑，而不依賴真實缺失的 classpath）。</p>
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 保證測試可在 {@code synchronized} 區塊外安全寫入。</p>
     */
    volatile java.util.function.Function<Platform, CompatibilityStatus> compatibilityOverride = null;

    /**
     * Package-private 測試 seam：reload 流程中可在「diagnostics rebind 完成、
     * commit 前」注入受控失敗，模擬 {@code DiagnosticsService.bindScheduler(...)}
     * 內部不一致或外部 listener 拋錯的罕見路徑。Production 預設為 null；正常
     * reload 不會觸發。
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 同上。</p>
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
     * 例外的情況下，明確驗證建構失敗路徑：reload 必須回傳 false 並一致進入
     * FAILED/non-ready policy。</p>
     *
     * <p>僅供 {@code com.smile.acelib} 套件內測試使用；非測試 caller 應維持 null。
     * 此欄位為 volatile — 同上。</p>
     */
    volatile Runnable reloadNewSchedulerConstructionFailureHook = null;

    /** Package-private test seam for a controlled player-service shutdown failure. */
    volatile Runnable reloadPlayerShutdownFailureHook = null;

    /** Package-private test seam for a controlled external-service bind failure during reload. */
    volatile Runnable reloadExternalBindFailureHook = null;

    /** 受控模擬 reload 核心服務重建途中拋錯，驗證部分提交會 fail closed。 */
    volatile Runnable reloadCoreServiceBindFailureHook = null;

    public AceLibPlugin() {
        // 預先放一個 uninitialized facade，避免 getApi() 在 onEnable 前丟例外
        this.api = AceLibApi.uninitialized();
        // DiagnosticsService 預設 instance：尚未 bind plugin，但允許 buildSnapshot()
        // 查詢（會以 AceLibVersion.VERSION / Platform.UNKNOWN / not ready 呈現）
        this.diagnostics = new DiagnosticsService(Clock.system());
        // worldService 的 NOT_READY unavailable facade；於 onEnable 後被 bindWorldService() 替換。
        this.worldService = new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY);
        // guiService 的 NOT_READY unavailable facade；於 onEnable 後被 bindGuiService() 替換。
        this.guiService = GuiService.forUnavailable(GuiErrorCode.NOT_READY);
        // displayService 的 NOT_READY unavailable facade；於 onEnable 後被 bindDisplayService() 替換。
        this.displayService = DisplayService.forUnavailable(DisplayErrorCode.NOT_READY);
        // bedrockService 的 NOT_READY unavailable facade；於 onEnable 後被 bindBedrockService() 替換。
        this.bedrockService = BedrockService.forUnavailable(BedrockService.NOT_READY);
        // commandCatalog 的 unavailable 退回實例；於 onEnable 後被 bindCommandCatalog() 替換。
        this.commandCatalog = CommandCatalog.forUnavailable();
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
     * 對外測試 seam，允許注入 deterministic {@link Clock}。
     *
     * <p>建立並綁定 {@link SafeSchedulerImpl} + {@link DiagnosticsService}；
     * 兩者皆透過 {@link Clock} 取得時間，避免測試依賴系統時鐘。冪等
     * （重複呼叫不爆）。</p>
     *
     * @param s        當前 server（測試情境下可為 mock）
     * @param detector 平台偵測器（測試情境下可注入固定回傳）
     * @param clock    時鐘來源；不可為 null
     * @since 1.0.0
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

        // 3. 相容性 gate（fail-closed）：探測關鍵 capability shape，對照內建已驗證
        //    矩陣分類 SUPPORTED / UNVERIFIED / INCOMPATIBLE。INCOMPATIBLE 時不建立
        //    scheduler / 服務，標記 not ready 並回傳，避免「看起來 ready 但其實
        //    不支援」的假象。
        CompatibilityStatus compatibility;
        RuntimeFingerprint compatibilityFingerprint;
        if (compatibilityOverride != null) {
            compatibility = compatibilityOverride.apply(detected);
            compatibilityFingerprint = null;
        } else {
            ClassLoader probeLoader = getClass().getClassLoader();
            java.util.EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
                CapabilityProbe.probe(probeLoader, detected);
            compatibilityFingerprint = RuntimeFingerprint.capture(
                detected, detector.detectMinecraftVersion(s), detector.detectJavaVersion(), outcomes);
            compatibility = CompatibilityGate.decide(compatibilityFingerprint, outcomes);
        }

        // 4. 建立 diagnostics service（統一入口），並 bind 版本/平台/capability
        DiagnosticsService newDiagnostics = new DiagnosticsService(clock);
        newDiagnostics.bindPlugin(AceLibVersion.VERSION, detected, capability);
        newDiagnostics.setReady(true);
        publishCompatibility(newDiagnostics, compatibility, compatibilityFingerprint);

        // 5. INCOMPATIBLE：fail-closed，不建立 scheduler / 服務，標記 not ready 並回傳。
        if (!compatibility.isReady()) {
            this.diagnostics = newDiagnostics;
            newDiagnostics.setReady(false);
            this.ready = false;
            logSevereWithCode("ACELIB-PLAT-009",
                "AceLib runtime is INCOMPATIBLE; plugin not enabled. " + compatibility.reason);
            return;
        }
        if (compatibility.state == CompatibilityStatus.State.UNVERIFIED) {
            logWarningWithCode("ACELIB-PLAT-009",
                "AceLib runtime is UNVERIFIED (not in built-in verified matrix); "
                    + "proceeding best-effort. " + compatibility.reason);
        }

        // 6. 建立 scheduler（由 plugin 統一管理 lifecycle）
        SafeSchedulerImpl newScheduler = new SafeSchedulerImpl(this, detected, capability);
        newDiagnostics.bindScheduler(newScheduler);

        this.scheduler = newScheduler;
        this.diagnostics = newDiagnostics;

        // 建立玩家資料服務與事件 listener；註冊延後到 Bukkit 確認 plugin enabled。
        bindPlayerDataService(s);

        // 建立 world 服務（在 player 服務與管理指令之後）。
        bindWorldService(s);

        // 建立 GUI 服務（world 之後），並註冊 listener。
        bindGuiService(s);

        // 建立顯示服務（GUI 之後），退服清理 listener 註冊延後到 onPluginReady。
        bindDisplayService(s);

        // 建立外部整合服務（world/gui 之後），並向 diagnostics 註冊 integration 模組狀態。
        bindExternalService(s);

        // v1.4.0：建立管理指令系統（/acelib status 等，Brigadier 註冊器）。
        // 節點註冊由平台生命週期在命令同步時機執行，與 player listener
        // 註冊時機無依賴關係。
        bindCommandCatalog();
        bindCommandFramework();

        // 5. 發佈 facade（攜帶已 bind 的 worldService + guiService + externalService；對齊 reload 路徑）
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            detected,
            capability,
            this.worldService,
            this.guiService,
            this.externalService,
            this.bedrockService,
            this.commandCatalog,
            this.displayService,
            () -> ready,
            () -> reload()
        ).withLifecycleHost(lifecycleHost);

        this.ready = true;

        // 對外正式取得入口：於 facade 就緒後註冊 provider（disabled 之後
        // onDisable 會解除註冊，reload 期間不解除）。
        registerApiProvider(s);
        logLifecycleResult("activate", lifecycleHost.activate());

        logInfo("AceLib {0} enabled on {1} (capability={2})",
            api.getVersion(), api.getPlatform().getDisplayName(), capability);
    }

    @Override
    public synchronized void onDisable() {
        logLifecycleResult("shutdown", lifecycleHost.shutdown());
        if (!ready) {
            // INCOMPATIBLE enable 留下的半初始化狀態：onEnable 已建立 diagnostics 並
            // 註冊 compatibility 模組（FAILED），但 ready=false 早退。此處仍要清掉
            // compatibility module state，避免 diagnostics 殘留 READY/FAILED 假象；
            // 同時清空 server / platformDetector 欄位（與正常 disable 路徑一致）。
            // 注意：this.diagnostics 保留非 null，以維持 getDiagnosticsService() 的
            // 「永遠不為 null」契約（見其 javadoc）。
            if (diagnostics != null) {
                try {
                    diagnostics.unregisterModuleState("compatibility");
                } catch (Throwable t) {
                    logFine("onDisable: compatibility cleanup failed (ignored): " + t.getMessage());
                }
                this.server = null;
                this.platformDetector = null;
            }
            logFine("AceLib.onDisable() called before onEnable; safe no-op.");
            return;
        }
        // 先解除對外 provider registration，避免 disable 流程中仍有呼叫端
        // 新取得 provider；已持有 provider 的呼叫端稍後切換為 shutdown facade。
        unregisterApiProvider();

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
            oldListener.close();
            this.playerLifecycleListener = null;
        }
        this.playerLifecycleRegistered = false;

        // v1.4.0：解除管理指令綁定（內部 registry 標記 disabled 並清空；
        // 平台側 Brigadier 節點由平台在 plugin disable 時自動移除）。
        // 確保 disable 後任何殘留的 in-flight dispatch 都會回
        // {@code ACELIB-CMD-009 REGISTRY_DISABLED} 而非靜默執行。
        unbindCommandFramework();

        // 指令目錄：解除撤下 listener 並清空目錄、標記不可用（舊參考不可再寫入）。
        unbindCommandCatalog();

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
        // 自建 io pool 隨舊服務釋放（service.shutdown 不關閉外部注入的 executor，
        // 本欄位只追蹤自建 pool，可安全關閉）。
        shutdownPlayerIoExecutor();
        // store 在服務 shutdown（已 flush）之後才關閉：順序不可顛倒。
        closePlayerDataStore();

        // world 服務 shutdown（標記 stopped、取消 in-flight handle、
        // 註冊 FAILED module state）。順序置於 player 與 scheduler 卸載之後，
        // 確保任何 in-flight teleport 不會被殘留 scheduler 接走。
        unbindWorldService();

        // GUI 服務 shutdown（清除 listener + 移除所有 session）。
        unbindGuiService();

        // 顯示服務 shutdown（清除 quit listener + 移除自身追蹤的全部顯示）。
        unbindDisplayService();

        // 外部整合服務 shutdown（釋放 registry 資源）並解除 integration 模組狀態註冊。
        unbindExternalService();

        // 基岩服務 shutdown（external registry 之後；查詢改為 SHUTDOWN 拒絕）。
        unbindBedrockService();

        this.ready = false;
        this.server = null;
        this.platformDetector = null;
        // 保留 SHUTDOWN worldService 與 guiService reference，避免 double-fork 既有 contract。
        this.api = AceLibApi.shutDown(this.worldService, this.guiService, this.commandCatalog,
            this.displayService).withLifecycleHost(lifecycleHost);
        // 已持有 provider 的呼叫端改讀 shutdown facade（與 plugin.getApi() 一致），
        // 再清除 plugin 端 reference 協助 GC。
        updateApiProvider(this.api);
        this.apiProvider = null;

        // 安全降級：
        // 1. scheduler 標記 disabled（解除其 recorder listener 避免 disable 後仍收到通知）
        // 2. diagnostics 保留同一 reference；scheduler 模組降級為 FAILED + ACELIB-SCHED-006，
        //    ready 設為 false，throttler 重置。供既有 reference（管理員命令、測試 seam）
        //    仍可查詢「曾 bind 但現已 disable」的狀態。
        // 先 shutdown 既有的 worldService（標記 stopped），
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
                // 解除相容性模組註冊，避免 disable 後殘留過期 profile。
                oldDiagnostics.unregisterModuleState("compatibility");
            } catch (Throwable t) {
                logFine("diagnostics teardown failed (ignored): " + t.getMessage());
            }
        }

        logInfo("AceLib disabled");
    }

    // ---------------------------------------------------------------------
    // 對外 provider（AceLibApi.AceLibProvider）lifecycle
    // ---------------------------------------------------------------------

    /**
     * 建立動態 provider 並註冊到 Bukkit/Paper {@code ServicesManager}。
     *
     * <p>於 onEnable 最後（facade 已就緒）呼叫；reload 不重新註冊 —
     * 既有 registration 保留，reload 時只更新 provider 內的 facade reference。
     * 重複 onEnable 會因 {@code ready} 旗標提早 return，不會重複註冊。</p>
     *
     * @param server 當前 server；不可為 null
     */
    private void registerApiProvider(Server server) {
        Objects.requireNonNull(server, "server");
        AceLibApi.AceLibProvider provider = new AceLibProviderImpl(api);
        this.apiProvider = provider;
        server.getServicesManager().register(
            AceLibApi.AceLibProvider.class, provider, this, ServicePriority.Normal);
    }

    /**
     * 更新已註冊 provider 的目前 facade reference。
     *
     * <p>reload commit 成功後與 onDisable 末尾呼叫；使已持有 provider 的呼叫端
     * 讀到 reload 後的新 facade、或 disable 後的 shutdown facade。若 provider
     * 尚未建立（從未 onEnable），此方法為 no-op。</p>
     *
     * @param currentApi 目前 facade；不可為 null
     */
    private void updateApiProvider(AceLibApi currentApi) {
        AceLibApi.AceLibProvider provider = this.apiProvider;
        if (provider instanceof AceLibProviderImpl impl) {
            impl.updateApi(Objects.requireNonNull(currentApi, "currentApi"));
        }
    }

    /**
     * 解除對外 provider registration。
     *
     * <p>於 onDisable 開始時呼叫，確保 disable 流程中不會再有呼叫端新取得
     * provider。此處不更動 {@code apiProvider} reference —
     * 末尾的 {@link #updateApiProvider(AceLibApi)} 負責把 cached provider
     * 切換為 shutdown facade，再清除 plugin 端 reference。</p>
     */
    private void unregisterApiProvider() {
        Server s = this.server;
        if (s == null) {
            return;
        }
        try {
            // 解除本 plugin 註冊的全部 services（目前僅 provider 一項）；
            // disable 後 getRegistration(...) 回傳 null。
            s.getServicesManager().unregisterAll(this);
        } catch (Throwable t) {
            logFine("api provider unregister failed (ignored): " + t.getMessage());
        }
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
     * @since 1.0.0
     */
    public PlatformCapability getPlatformCapability() {
        return api.getPlatformCapability();
    }

    /**
     * 取得當前綁定的 {@link DiagnosticsService}（統一診斷入口）。
     *
     * <p>永遠不為 null：</p>
     * <ul>
     *   <li>onEnable 之前 → 建構子預先建立的 safe default instance（未 bind、not ready）</li>
     *   <li>onEnable 之後 → 已 bind plugin 版本/平台/capability 且已 ready 的實例</li>
     *   <li>onDisable 之後 → 同一 reference；{@code isReady} 回傳 false，scheduler 模組
     *       狀態降級為 FAILED（不會丟例外）</li>
     * </ul>
     *
     * <p>此 getter 供管理員命令（後續 plugin）或測試 seam 取得 diagnostics 物件；
     * 不會回傳 {@code null}，呼叫端可放心 chain 呼叫。</p>
     *
     * @return 永遠不為 null 的 {@link DiagnosticsService}
     * @since 1.0.0
     */
    public DiagnosticsService getDiagnosticsService() {
        return diagnostics;
    }

    /**
     * 取得當前綁定的 {@link SafeSchedulerImpl}（diagnostics wiring 入口）。
     *
     * <p>onEnable 之前回傳 null；onEnable 之後回傳當前綁定的 scheduler。
     * 即使 onDisable 後仍回傳 reference（已 disabled），供測試驗證 lifecycle。</p>
     *
     * @return 當前 scheduler，可能為 null（plugin 未啟用）
     * @since 1.0.0
     */
    public SafeSchedulerImpl getSchedulerForDiagnostics() {
        return scheduler;
    }

    /**
     * 便捷方法：建立當下不可變 {@link DiagnosticReport}（對外查詢入口）。
     *
     * <p>內部委派給 {@link DiagnosticsService#buildReport()}。
     * 等同 {@code getDiagnosticsService().buildReport()}。</p>
     *
     * @return 新的 {@link DiagnosticReport}；永遠不為 null
     * @since 1.0.0
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
     * 取得當前 player 服務使用的自建 I/O executor（package-private 測試 seam）。
     *
     * <p>僅供生命週期測試驗證舊 pool 已關閉、新 pool 已重建；非測試 caller
     * 不得關閉或提交任務。</p>
     *
     * @return 當前自建 io executor；onEnable 前為 null
     */
    ExecutorService getPlayerIoExecutor() {
        return playerIoExecutor;
    }

    /**
     * 取得管理指令框架的內部 {@link CommandRegistry}（dispatch／help／
     * tab complete 共用）。
     *
     * <p>於 onEnable 建立 {@code /acelib} 型別化根指令後回傳非 null；
     * onEnable 之前或 onDisable 之後回傳 null。reload 不重建
     * （同一 registry 沿用，冷卻狀態保留）。</p>
     *
     * @return 當前內部 registry；可能為 null（plugin 未啟用或已停用）
     * @since 1.4.0
     */
    public CommandRegistry getCommandRegistry() {
        BrigadierRegistrar registrar = this.commandRegistrar;
        return registrar == null ? null : registrar.getRegistry();
    }

    /**
     * 取得當前綁定的 {@link ExternalIntegrationService}（外部插件整合服務）。
     *
     * <p>於 onEnable 建立；reload 時 shutdown 舊 service + 建立新 service；onDisable 時
     * shutdown 並替換為 SHUTDOWN unavailable facade。onEnable 之前回傳 null。</p>
     *
     * @return 當前 external service；可能為 null（plugin 未啟用）
     */
    ExternalIntegrationService getExternalIntegrationService() {
        return externalService;
    }

    BedrockService getBoundBedrockServiceForTesting() {
        return bedrockService;
    }

    /**
     * 重新偵測平台並發佈新 API 實例（既有 diagnostics reference in-place 重綁）。
     *
     * <h4>交易式失敗語意</h4>
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
     *       {@code this.scheduler}、{@code this.api}，之後依拓樸重建下游模組。</li>
     * </ol>
     * <p>核心拆舊之前，宿主先反向停用下游模組。可安全重試的核心 rollback 會保留模組宣告，
     * 將宿主恢復為可重入狀態並記錄失敗結果；已關閉的 handle 保持未啟用，新註冊會拒絕，
     * 直到下一次 reload 重建成功。核心 fail-closed 或下游重建失敗則宿主維持 {@code FAILED}。</p>
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
        if (reloadInProgress) {
            logWarningWithCode("ACELIB-LIFE-008",
                "reload was rejected because another reload is already in progress");
            return false;
        }
        reloadInProgress = true;
        ReloadAttempt attempt = new ReloadAttempt(externalService, bedrockService);
        try {
            LifecycleResult stopped = lifecycleHost.beginReload();
            logLifecycleResult("reload module shutdown", stopped);
            if (!stopped.isSuccess()) {
                return false;
            }

            ReloadRuntimeOutcome outcome = reloadRuntime(attempt);
            if (outcome != ReloadRuntimeOutcome.COMMITTED) {
                cleanupUncommittedReloadIntegrations(attempt);
                if (outcome == ReloadRuntimeOutcome.RETRYABLE_FAILURE) {
                    lifecycleHost.retryableReloadFailure(null);
                } else {
                    lifecycleHost.failReload(null);
                }
                return false;
            }

            LifecycleResult rebuilt = lifecycleHost.finishReload();
            logLifecycleResult("reload module rebuild", rebuilt);
            return rebuilt.isSuccess();
        } catch (Throwable failure) {
            cleanupUncommittedReloadIntegrations(attempt);
            lifecycleHost.failReload(failure);
            try {
                teardownRuntimeOnReloadFailure(diagnostics, ReloadFailureKind.UNEXPECTED);
            } catch (Throwable teardownFailure) {
                logSevereWithCode("ACELIB-LIFE-009",
                    "reload teardown after an unexpected failure also failed: "
                        + teardownFailure);
                downgradeAfterReloadPhaseAFailure(diagnostics);
            }
            logSevereWithCode("ACELIB-LIFE-009",
                "reload failed after downstream lifecycle modules were stopped: " + failure);
            if (failure instanceof Error error) {
                throw error;
            }
            return false;
        } finally {
            reloadInProgress = false;
        }
    }

    private ReloadRuntimeOutcome reloadRuntime(ReloadAttempt attempt) {
        if (!ready || platformDetector == null) {
            return ReloadRuntimeOutcome.FAILED_CLOSED;
        }
        Platform reDetected = platformDetector.detect();
        PlatformCapability reCapability = platformDetector.detectCapability(reDetected);
        DiagnosticsService ds = this.diagnostics;

        // 相容性 gate（reload 路徑）：若 runtime 變為 INCOMPATIBLE，fail-closed 降級。
        CompatibilityStatus reloadCompatibility;
        RuntimeFingerprint reloadFingerprint;
        if (compatibilityOverride != null) {
            reloadCompatibility = compatibilityOverride.apply(reDetected);
            reloadFingerprint = null;
        } else {
            ClassLoader probeLoader = getClass().getClassLoader();
            java.util.EnumMap<CapabilityProbe.CapabilityKey, CapabilityProbe.ProbeOutcome> outcomes =
                CapabilityProbe.probe(probeLoader, reDetected);
            reloadFingerprint = RuntimeFingerprint.capture(
                reDetected, platformDetector.detectMinecraftVersion(server),
                platformDetector.detectJavaVersion(), outcomes);
            reloadCompatibility = CompatibilityGate.decide(reloadFingerprint, outcomes);
        }
        if (!reloadCompatibility.isReady()) {
            publishCompatibility(ds, reloadCompatibility, reloadFingerprint);
            logSevereWithCode("ACELIB-PLAT-009",
                "reload: runtime became INCOMPATIBLE; downgrading plugin. " + reloadCompatibility.reason);
            // 完整停用 runtime 資源（scheduler / listener / 服務），避免「plugin FAILED 但
            // runtime 資源仍活著」的不一致；teardown 內部失敗只記錄並繼續降級，不拋例外。
            teardownRuntimeOnReloadFailure(ds, ReloadFailureKind.INCOMPATIBLE);
            return ReloadRuntimeOutcome.FAILED_CLOSED;
        }
        if (reloadCompatibility.state == CompatibilityStatus.State.UNVERIFIED) {
            publishCompatibility(ds, reloadCompatibility, reloadFingerprint);
            logWarningWithCode("ACELIB-PLAT-009",
                "reload: runtime UNVERIFIED; proceeding best-effort. " + reloadCompatibility.reason);
        }

        SafeSchedulerImpl oldScheduler = this.scheduler;
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
                return ReloadRuntimeOutcome.FAILED_CLOSED;
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
                    return ReloadRuntimeOutcome.FAILED_CLOSED;
                }
            }
        }

        // -----------------------------------------------------------------
        // Phase B：建立新 SafeSchedulerImpl
        // 失敗 → 舊 scheduler 已 disabled（Phase A），不需額外 rollback；
        // 但為避免「diagnostics 顯示 READY 但實際 scheduler 半失效」的假象，
        // 必須與 Phase A 一致明確降級 diagnostics 與 plugin 為 FAILED 狀態，
        // 保留 scheduler / api reference。
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
            return ReloadRuntimeOutcome.FAILED_CLOSED;
        }

        // -----------------------------------------------------------------
        // Phase C：diagnostics in-place 重綁（保留既有 DiagnosticsService reference）
        // 失敗 → rollback：newScheduler 標記 disabled；既有 ds 重新綁回舊 scheduler
        // （oldScheduler 已 disabled，bindScheduler 內部會將模組標記為 FAILED +
        // ACELIB-SCHED-006，語意「曾 bind 但 reload 失敗」）。
        // this.scheduler / this.api 不被修改（保留原值，rollback 完成）。
        //
        // Phase C 開始前先 snapshot 既有 version/platform/capability/
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
                return ReloadRuntimeOutcome.RETRYABLE_FAILURE;
            }
        }

        // -----------------------------------------------------------------
        // Phase D：commit（全部階段成功才執行）
        // -----------------------------------------------------------------
        // 外部整合服務：先 shutdown 舊服務，再建立新服務（commit 階段）。
        // 失敗（reloadExternalBindFailureHook）時不建立新服務、保留舊服務已 shutdown 狀態，
        // 不會出現新舊同時 active 的混合狀態；此處位於 player/world/gui commit 之前，
        // 失敗時進入完整 rollbackReload 路徑（釋放 newScheduler、還原 ds metadata/ready、
        // 重新綁回已 disabled 舊 scheduler），避免 diagnostics rebind 階段已將 ds 綁定
        // newScheduler 卻未同步 this.scheduler 的不一致，再乾淨回傳 false。
        attempt.integrationPhaseStarted = true;
        ExternalIntegrationService oldExternal = this.externalService;
        if (oldExternal != null) {
            try {
                oldExternal.shutdown();
            } catch (Throwable t) {
                logFine("reload: old external service shutdown failed (ignored): "
                    + t.getMessage());
            }
        }
        // 舊基岩服務同步 shutdown：bindBedrockService 會在 commit 階段覆寫欄位，
        // 但若 external bind 失敗進入 rollback，欄位仍指向舊 impl——先 shutdown
        // 使其查詢轉為 SHUTDOWN 拒絕，避免 rollback 後殘留 READY 語意。
        BedrockService oldBedrock = this.bedrockService;
        if (oldBedrock != null) {
            try {
                oldBedrock.shutdown();
            } catch (Throwable t) {
                logFine("reload: old bedrock service shutdown failed (ignored): "
                    + t.getMessage());
            }
        }
        if (reloadExternalBindFailureHook != null) {
            try {
                reloadExternalBindFailureHook.run();
            } catch (Throwable t) {
                // 與 diagnostics rebind 失敗一致：進入完整 rollback 路徑，釋放 newScheduler、
                // 還原 ds metadata/ready 並重新綁回已 disabled 舊 scheduler（模組標記
                // FAILED + ACELIB-SCHED-006），確保 diagnostics 綁定的 scheduler 與
                // this.scheduler（仍指向舊 disabled scheduler）一致。external service 仍為
                // 舊 reference（已 shutdown），不新舊混用。
                //
                // 舊 external service 已在上方 shutdown，但 bindExternalService 註冊的
                // MODULE_INTEGRATION 模組狀態仍殘留（指向已失效的舊 impl）。rollback 前
                // 先解除該模組註冊，使 diagnostics 與 SHUTDOWN facade 的 external service
                // 語意一致（integration 模組回到 NOT_INITIALIZED），避免「diagnostics 顯示
                // FAILED 但實際 external service 已 SHUTDOWN」的假象。
                if (ds != null) {
                    try {
                        ds.unregisterModuleState(MODULE_INTEGRATION);
                    } catch (Throwable cleanupFailure) {
                        logFine("reload rollback: integration module unregister failed "
                            + "(best-effort): " + cleanupFailure.getMessage());
                    }
                }
                rollbackReload(newScheduler, ds, oldMeta, oldScheduler);
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload: external service bind failed; rolled back to previous binding "
                        + "(scheduler/diagnostics restored). Cause: " + t);
                return ReloadRuntimeOutcome.RETRYABLE_FAILURE;
            }
        }
        bindExternalService(this.server);

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
                    oldListener.close();
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
                return ReloadRuntimeOutcome.FAILED_CLOSED;
            }
        }
        if (oldListener != null) {
            HandlerList.unregisterAll(oldListener);
            oldListener.close();
        }
        this.playerLifecycleRegistered = false;
        // 舊 player 服務已 shutdown（flush 完成、in-flight 排空）：關閉其自建 io pool。
        // 失敗路徑（上方已 return）不關閉——舊服務仍存活且使用該 pool。
        shutdownPlayerIoExecutor();
        // 舊 store 在服務 shutdown（已完成最後一批 flush）與自建 io pool 關閉後才關閉，
        // 且必須早於 bindPlayerDataService：後者會覆寫 playerDataStore 欄位，先關才不會
        // 誤關新 store。順序：service flush → 關 io pool → 關 store → rebind。
        closePlayerDataStore();
        bindPlayerDataService(this.server);
        // 先釋放舊 world/gui 服務（unregister 舊 GUI listener + shutdown 舊 impl），
        // 再 commit 新 scheduler 並重建。順序理由：未釋放就覆寫會留下雙 listener
        // （舊 listener 仍在 HandlerList，下次 onPluginReady 又註冊新的）與仍 READY
        // 的舊 impl（getModuleStatus 假象）；unbind 後欄位先為 SHUTDOWN facade，
        // 若後續 bind 拋錯，既有 caller 讀到的狀態仍可判斷。
        unbindWorldService();
        unbindGuiService();
        unbindDisplayService();
        // 先 commit 新 scheduler 至 this.scheduler，再 bind GUI service。
        // 順序理由：bindGuiService() 內部讀取 this.scheduler 來建立 SafeSchedulerPlayerContextExecutor，
        // 若 scheduler 仍指向 Phase A 已 disabled 的舊 scheduler，新 GUI service 會
        // 捕獲 disabled scheduler，導致 reload 後 openInventory 一律回
        // ACELIB-GUI-013 SCHEDULER_REJECTED 即為此順序錯誤的具體症狀。
        this.scheduler = newScheduler;
        if (reloadCoreServiceBindFailureHook != null) {
            reloadCoreServiceBindFailureHook.run();
        }
        // reload 成功後重新建立 world 服務（既有 worldService 已 shutdown）。
        bindWorldService(this.server);
        // reload 成功後重新建立 GUI 服務（既有 guiService 已 shutdown）。
        // 必須在 this.scheduler = newScheduler 之後呼叫。
        bindGuiService(this.server);
        // reload 成功後重新建立顯示服務（既有 displayService 已 shutdown）。
        // 必須在 this.scheduler = newScheduler 之後呼叫。
        bindDisplayService(this.server);

        // 在線玩家重接：舊服務 shutdown 已把 dirty flush 回 store，
        // 新服務 registry 為空；逐一重建 session 並等待載入完成，
        // 使 reload 回傳時 getData/markDirty/quit 皆可用。
        rejoinOnlinePlayers();

        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            reDetected,
            reCapability,
            this.worldService,
            this.guiService,
            this.externalService,
            this.bedrockService,
            this.commandCatalog,
            this.displayService,
            () -> ready,
            () -> reload()
        ).withLifecycleHost(lifecycleHost);
        // 更新 provider 的 facade reference，使已持有 provider 的呼叫端讀到新 facade。
        updateApiProvider(this.api);
        attempt.coreCommitted = true;
        onPluginReady();
        logInfo("AceLib reloaded on {0}", reDetected.getDisplayName());
        return ReloadRuntimeOutcome.COMMITTED;
    }

    private void cleanupUncommittedReloadIntegrations(ReloadAttempt attempt) {
        if (!attempt.integrationPhaseStarted || attempt.coreCommitted) {
            return;
        }
        if (externalService != attempt.previousExternal) {
            unbindExternalService();
        }
        if (bedrockService != attempt.previousBedrock) {
            unbindBedrockService();
        }
        attempt.integrationPhaseStarted = false;
    }

    /**
     * Phase C rebind 失敗時的 rollback 輔助方法（metadata 還原）。
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
     * Diagnostics metadata snapshot（reload rollback 內部使用）。
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

    private static final class ReloadAttempt {

        private final ExternalIntegrationService previousExternal;
        private final BedrockService previousBedrock;

        private boolean integrationPhaseStarted;
        private boolean coreCommitted;

        private ReloadAttempt(ExternalIntegrationService previousExternal,
                BedrockService previousBedrock) {
            this.previousExternal = previousExternal;
            this.previousBedrock = previousBedrock;
        }
    }

    private enum ReloadFailureKind {
        INCOMPATIBLE,
        UNEXPECTED
    }

    private enum ReloadRuntimeOutcome {
        COMMITTED,
        RETRYABLE_FAILURE,
        FAILED_CLOSED
    }

    /**
     * Phase A 失敗後的狀態降級（避免 READY 假象）。
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

    /**
     * reload 無法繼續時釋放 runtime 資源並發布 shutdown facade。
     *
     * <p>與 {@link #onDisable()} / Phase A 相同的「停用即釋放」語意：舊 scheduler 標記
     * disabled、player lifecycle listener 解除、各服務 shutdown 並替換為 SHUTDOWN facade。
     * 每個步驟獨立 try/catch，失敗只記錄並繼續，最終由
     * {@link #downgradeAfterReloadPhaseAFailure} 統一降級為 FAILED；不重複進 Phase A。
     * 日誌會標示失敗原因，避免把一般 reload 例外誤報為 runtime 不相容。</p>
     *
     * @param ds 既有 diagnostics reference（可為 null；內部以 null-guard 保護）
     * @param kind teardown 原因，用來區分不相容 runtime 與非預期 reload 例外
     */
    private void teardownRuntimeOnReloadFailure(DiagnosticsService ds, ReloadFailureKind kind) {
        String logPrefix = "reload(" + kind + "): ";
        // 0. 先解除對外 provider registration（與 onDisable 同序）：避免 teardown 期間
        //    仍有呼叫端新取得 provider；已持有 provider 的呼叫端稍後切換為 shutdown facade。
        //    unregisterApiProvider 內部已 try/catch，此處不再包一層。
        unregisterApiProvider();

        // 1. 解除 SafeEventRegistry bridge listener（與 onDisable 同序）：放在 scheduler /
        //    diagnostics teardown 之前，避免 listener 在 scheduler 模組標記 FAILED 之後才
        //    dispatch（此時 recorder sink 已清除，會丟 NPE）。內部真的解除 Bukkit HandlerList
        //    上的 bridge listener。
        try {
            com.smile.acelib.event.AceLibEvents.unbind(this);
        } catch (Throwable t) {
            logFine(logPrefix + "AceLibEvents.unbind failed (ignored): " + t.getMessage());
        }

        SafeSchedulerImpl oldScheduler = this.scheduler;
        PlayerLifecycleListener oldListener = this.playerLifecycleListener;
        PlayerDataService oldPlayerService = this.playerDataService;

        // 2. 舊 scheduler：recorder listener 清除 + onPluginDisable（取消 in-flight 任務）
        if (oldScheduler != null) {
            try {
                oldScheduler.getRecorder().clearRecordSink();
                oldScheduler.onPluginDisable();
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    logPrefix + "old scheduler teardown failed (ignored): " + t);
            }
            // 測試 seam：允許注入受控失敗（與 Phase A 共用同一 hook 語意）
            if (reloadOldTeardownFailureHook != null) {
                try {
                    reloadOldTeardownFailureHook.run();
                } catch (Throwable t) {
                    logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                        logPrefix + "old scheduler teardown hook failed (ignored): " + t);
                }
            }
        }
        // 3. player lifecycle listener 解除
        if (oldListener != null) {
            try {
                HandlerList.unregisterAll(oldListener);
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    logPrefix + "player lifecycle listener unbind failed (ignored): " + t);
            }
            oldListener.close();
            this.playerLifecycleListener = null;
            this.playerLifecycleRegistered = false;
        }
        // 4. 管理指令框架解除（與 onDisable 同序：listener 解除後、player 服務 shutdown 前）。
        //    unbindCommandFramework 內部已 try/catch。
        unbindCommandFramework();
        // 指令目錄 listener 解除 + 目錄清空並標記不可用（與 onDisable 同序）。
        try { unbindCommandCatalog(); } catch (Throwable t) { logFine(logPrefix + "catalog unbind failed (ignored): " + t); }
        // 5. player 服務 shutdown
        if (oldPlayerService != null) {
            try {
                oldPlayerService.shutdown();
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    logPrefix + "player data shutdown failed (ignored): " + t);
            }
            this.playerDataService = null;
        }
        // 自建 io pool 隨舊服務釋放（best-effort，不中斷 teardown）。
        try {
            shutdownPlayerIoExecutor();
        } catch (Throwable t) {
            logFine(logPrefix + "player io executor shutdown failed (ignored): " + t.getMessage());
        }
        // store 在服務 shutdown（已 flush）之後關閉；降級路徑不會 rebind 新 service，
        // 因此此處是釋放 SQLite 連線的最後時機（closePlayerDataStore 內部冪等且
        // 已 try/catch，不會中斷 teardown）。
        closePlayerDataStore();
        // 6. 其餘服務 shutdown + SHUTDOWN facade 替換（內部已 try/catch）
        try { unbindWorldService(); } catch (Throwable t) { logFine(logPrefix + "world unbind failed (ignored): " + t); }
        try { unbindGuiService(); } catch (Throwable t) { logFine(logPrefix + "gui unbind failed (ignored): " + t); }
        try { unbindDisplayService(); } catch (Throwable t) { logFine(logPrefix + "display unbind failed (ignored): " + t); }
        try { unbindExternalService(); } catch (Throwable t) { logFine(logPrefix + "external unbind failed (ignored): " + t); }
        try { unbindBedrockService(); } catch (Throwable t) { logFine(logPrefix + "bedrock unbind failed (ignored): " + t); }

        // 7. 切換 cached facade 為 shutdown，並讓已持有 provider 的呼叫端讀到 shutdown 語意
        //    （與 onDisable 末尾一致：updateApiProvider 更新 provider 內部快照，再清 reference）。
        this.api = AceLibApi.shutDown(this.worldService, this.guiService, this.commandCatalog,
            this.displayService).withLifecycleHost(lifecycleHost);
        updateApiProvider(this.api);
        this.apiProvider = null;

        downgradeAfterReloadPhaseAFailure(ds);
    }

    // ---------------------------------------------------------------------
    // 平台狀態輸出
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
            // 不支援環境下給出明確警告，不誤判為 Folia
            safeLogger().log(Level.WARNING,
                "AceLib could not detect a Folia or Paper classpath; "
                    + "some features may be unavailable. " + PLATFORM_UNKNOWN_ERROR_CODE);
            return;
        }
        if (detected == Platform.PAPER && !detector.isFoliaClasspathAvailable()) {
            // 保守策略：明確告知 caller 此環境不支援 Folia 專屬能力
            safeLogger().log(Level.FINE,
                "(non-Folia environment detected; RegionizedServer API unavailable)");
        }
    }

    /**
     * 將相容性狀態發佈到 diagnostics 的 {@code "compatibility"} 模組。
     *
     * <p>SUPPORTED / UNVERIFIED 註冊為 READY（UNVERIFIED 附理由提示）；
     * INCOMPATIBLE 註冊為 FAILED + {@code ACELIB-PLAT-009}。</p>
     *
     * @param ds          diagnostics service；不可為 null
     * @param status      相容性狀態；不可為 null
     * @param fingerprint runtime fingerprint；可為 null（override seam 路徑下不探測，
     *                    此時摘要改取 {@code status.reason()}）
     */
    private void publishCompatibility(DiagnosticsService ds,
                                       CompatibilityStatus status,
                                       RuntimeFingerprint fingerprint) {
        String summary = fingerprint != null ? fingerprint.summary() : status.reason;
        switch (status.state) {
            case SUPPORTED -> ds.registerModuleState("compatibility",
                ModuleState.ready("compatibility", "SUPPORTED | " + summary));
            case UNVERIFIED -> ds.registerModuleState("compatibility",
                ModuleState.ready("compatibility",
                    "UNVERIFIED | " + status.reason + " | " + summary));
            case INCOMPATIBLE -> ds.registerModuleState("compatibility",
                ModuleState.failed("compatibility",
                    "INCOMPATIBLE | " + status.reason + " | " + summary, "ACELIB-PLAT-009"));
        }
    }

    private void logWarningWithCode(String code, String message) {
        safeLogger().log(Level.WARNING, "[" + code + "] " + message);
    }

    private void logLifecycleResult(String operation, LifecycleResult result) {
        if (result == null || result.isSuccess()) {
            return;
        }
        for (LifecycleResult.Problem problem : result.problems()) {
            String details = "lifecycle " + operation + ": " + problem.message()
                + (problem.moduleId() == null ? "" : " [" + problem.moduleId() + "]")
                + (problem.relatedModuleIds().isEmpty() ? ""
                    : " related=" + problem.relatedModuleIds());
            if (result.outcome() == LifecycleResult.Outcome.REJECTED) {
                logWarningWithCode(problem.code().code(), details);
            } else {
                logSevereWithCode(problem.code().code(), details);
            }
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
     * <p>reload 流程中的可追蹤錯誤必須以 WARNING/SEVERE + 結構化 code 形式輸出，
     * 禁止吞錯或僅 FINE 記錄。</p>
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
     * 建立 {@code /acelib} 管理指令系統：型別化根指令經
     * {@link com.smile.acelib.command.BrigadierRegistrar} 雙寫入
     * （內部 registry＋Brigadier 節點）。設計原則：
     *
     * <ul>
     *   <li>register 只在 onEnable 呼叫一次；reload 不重建、不重複註冊 —
     *       Brigadier 節點由平台持有（配合平台註冊時機），handler
     *       透過 {@code Supplier<DiagnosticsService>} 反映 reload 後的 metadata</li>
     *   <li>平台生命週期註冊器不可用時（例如在 {@code onEnable} 之外呼叫），
     *       以 SEVERE log 攜帶 {@code ACELIB-CMD-012} 提示，但 plugin
     *       其他功能不受影響；內部 registry 已寫入的部分會回滾</li>
     *   <li>permission 由根指令設定（{@code acelib.admin}）；玩家權限
     *       缺失時由 {@link CommandRegistryImpl#dispatch} 統一回
     *       {@code ACELIB-CMD-003} NO_PERMISSION，Brigadier 層另以
     *       {@code requires} 過濾客戶端可見結構</li>
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
     * lifecycle handler 的掛載必須在 {@code onEnable} 期間完成
     * （平台要求），實際節點註冊由平台在命令同步時機執行。</p>
     */
    private void bindCommandFramework() {
        if (this.commandRegistrar != null) {
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
        BrigadierRegistrar registrar = new BrigadierRegistrar(
            this, new BukkitReplySink(this, eagerBackend));

        com.smile.acelib.command.TypedCommand root =
            com.smile.acelib.command.TypedCommand.builder(ADMIN_COMMAND_NAME)
                .description("AceLib 管理指令根節點")
                .usage("/acelib <status>")
                .permission("acelib.admin")
                .aliases("alib")
                .subcommand(com.smile.acelib.command.TypedSubCommand.builder("status")
                    .description("查詢 AceLib 當前狀態（版本、平台、ready、模組摘要、錯誤統計）")
                    .executes(new AceLibStatusHandler(this::getDiagnosticsService))
                    .build())
                .build();
        try {
            registrar.register(root);
        } catch (Throwable t) {
            logSevereWithCode("ACELIB-CMD-012",
                "bindCommandFramework: /acelib Brigadier 註冊失敗（"
                    + t.getMessage() + "）；管理指令將無法被觸發。");
            return;
        }
        publishSelfToCatalog(root.toCommandSpec());
        this.commandRegistrar = registrar;
    }

    /**
     * 解除 {@code /acelib} 管理指令綁定。動作：
     *
     * <ol>
     *   <li>呼叫 {@link BrigadierRegistrar#shutdown}（內部 registry 標記
     *       disabled，後續 dispatch 拒絕；本地簿記清空）</li>
     *   <li>平台側 Brigadier 節點由平台在 plugin disable 時自動移除，
     *       此處不假設即時移除語意</li>
     *   <li>解除 reference，協助 GC</li>
     * </ol>
     *
     * <p>此方法在 onDisable 內、player service shutdown 之前呼叫 — 確保
     * disable 流程結束後任何殘留的 dispatch 都不會觸發 player service 或
     * scheduler 內部 callback。</p>
     */
    private void unbindCommandFramework() {
        BrigadierRegistrar registrar = this.commandRegistrar;
        // 2. registry 內部標記 disabled（後續 dispatch 拒絕）
        if (registrar != null) {
            try {
                registrar.shutdown();
            } catch (Throwable t) {
                logFine("unbindCommandFramework: registry disable failed (ignored): "
                    + t.getMessage());
            }
        }
        this.commandRegistrar = null;
    }

    /**
     * 建立指令目錄並準備撤下 listener。
     *
     * <p>於 onEnable（管理指令框架之前）呼叫，建立可用狀態的目錄實例與
     * {@link CatalogDisableListener}；listener 註冊延後到
     * {@link #onPluginReady()}。reload 不呼叫本方法（目錄純資料保留）。</p>
     */
    private void bindCommandCatalog() {
        this.commandCatalog = CommandCatalog.forProduction();
        this.catalogDisableListener = new CatalogDisableListener();
        this.catalogDisableListenerRegistered = false;
        logFine("command catalog bound");
    }

    /**
     * 解除指令目錄：先解除撤下 listener 註冊，再清空目錄並標記不可用。
     *
     * <p>清空後透過舊參考再發布一律回 {@code REJECTED}。本方法冪等，
     * 內部每一步獨立保護、不拋例外。</p>
     */
    private void unbindCommandCatalog() {
        Listener listener = this.catalogDisableListener;
        if (listener != null) {
            try {
                HandlerList.unregisterAll(listener);
            } catch (Throwable t) {
                logFine("catalogDisableListener unregister failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.catalogDisableListener = null;
        this.catalogDisableListenerRegistered = false;
        CommandCatalog catalog = this.commandCatalog;
        if (catalog != null) {
            try {
                catalog.shutdown();
            } catch (Throwable t) {
                logFine("commandCatalog.shutdown failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
    }

    /**
     * 把 {@code /acelib} 管理指令的同一份 {@link CommandSpec} 投影發布進目錄。
     *
     * <p>只取描述性欄位（名稱、別名、描述、用法、權限、分類、子指令），handler 與
     * completer 不進入目錄。{@code status} 子指令不需二次確認，故確認集合為空。
     * 本發布不改變 {@code /acelib} 的既有執行行為；失敗只記錄、不中斷啟用流程。</p>
     *
     * @param rootSpec {@link #bindCommandFramework()} 使用的同一份 root spec
     */
    private void publishSelfToCatalog(CommandSpec rootSpec) {
        CommandCatalog catalog = this.commandCatalog;
        if (catalog == null || rootSpec == null) {
            return;
        }
        CatalogMeta meta = new CatalogMeta("admin", Optional.empty(), Set.of());
        try {
            catalog.publish(this, rootSpec, meta);
        } catch (Throwable t) {
            logFine("catalog self-publish of /acelib failed (ignored): " + t.getMessage());
        }
    }

    /**
     * 插件停用時撤下該插件目錄描述的分派入口（package-private 測試 seam）。
     *
     * <p>永不拋例外：任何失敗只以 fine-level 記錄，確保不中斷其他 listener
     * 對同一事件的處理。{@code catalog} 或 {@code plugin} 為 null 時直接返回。</p>
     *
     * @param catalog 目錄實例；可為 null（null 時 no-op）
     * @param plugin  被停用的插件；可為 null（null 時 no-op）
     */
    static void handleCatalogPluginDisable(CommandCatalog catalog, Plugin plugin) {
        try {
            if (catalog == null || plugin == null) {
                return;
            }
            catalog.unpublishAll(plugin);
        } catch (Throwable t) {
            Logger.getLogger(LOG_NAME).log(Level.FINE,
                "catalog disable dispatch failed (ignored): " + t);
        }
    }

    /**
     * 任一插件停用時的集中分派 listener（MONITOR 觀察，不取消亦不修改事件）。
     *
     * <p>同時撤下該插件的指令目錄描述、關閉該插件的 GUI 作用域（結束其 GUI、移除登記），
     * 並由宿主撤銷該 plugin 的生命週期模組。三條分派各自永不拋例外，
     * 不中斷其他 listener。合併於單一註冊，避免 PluginDisableEvent
     * 出現多個 AceLib 內部註冊。</p>
     */
    private final class CatalogDisableListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        void onPluginDisable(PluginDisableEvent event) {
            Plugin disabledPlugin = event == null ? null : event.getPlugin();
            handleCatalogPluginDisable(commandCatalog,
                disabledPlugin);
            GuiScopes.handlePluginDisable(disabledPlugin);
            if (disabledPlugin != null) {
                logLifecycleResult("owner disable " + disabledPlugin.getName(),
                    lifecycleHost.ownerDisabled(disabledPlugin));
            }
        }
    }

    // ---------------------------------------------------------------------
    // PlayerDataService lifecycle binding
    // ---------------------------------------------------------------------

    /**
     * 建立並綁定 {@link PlayerDataService} 與其 listener。
     *
     * <p>綁定內容：</p>
     * <ol>
     *   <li>於 {@code plugins/<pluginFolder>/players.db} 建立 SQLite 逐玩家 store
     *       （不存在則自動 init）</li>
     *   <li>若舊版 {@code player-data.json} 仍存在且尚未轉換，執行一次性同步轉換
     *       （備份 → 寫入 → 三層校驗 → 寫 marker）；失敗則關閉 store 並停用玩家資料服務，
     *       來源與已建立的備份均保留，修復後可重跑轉換</li>
     *   <li>建立 {@link PlayerDataService}，啟用定期保存並以
     *       {@link PlayerDataService#DEFAULT_SAVE_INTERVAL_MS} 為週期</li>
     *   <li>準備 {@link PlayerLifecycleListener}，供 enabled plugin 完成 Bukkit
     *       {@code AsyncPlayerPreLoginEvent}/{@code PlayerJoinEvent}/{@code PlayerQuitEvent}
     *       註冊（MONITOR priority）</li>
     * </ol>
     *
     * <p>listener <strong>不持有 Player reference</strong> — 僅以 UUID + name 快照
     * 委派 service，避免跨執行緒保留 Bukkit entity reference。</p>
     *
     * @param server 當前 server；不可為 null
     */
    private void bindPlayerDataService(Server server) {
        Objects.requireNonNull(server, "server");
        Path dataFolder;
        try {
            dataFolder = getDataFolder().toPath();
        } catch (Throwable t) {
            // 非標準環境下 getDataFolder 可能不可用；fallback 維持可預期的 plugin 路徑
            logFine("bindPlayerDataService: getDataFolder failed, using fallback path: "
                + t.getMessage());
            dataFolder = Path.of("plugins", "AceLib");
        }

        Path databaseFile = dataFolder.resolve(PLAYER_DATA_DB_FILE);
        Path legacyJsonFile = dataFolder.resolve(LEGACY_PLAYER_DATA_FILE);

        // 建立 SQLite 逐玩家 store（init 時若檔案不存在則新建）。
        PlayerDataStore store;
        try {
            store = PlayerDataStores.sqlite(databaseFile, SchemaVersion.V1_0);
            store.init();
        } catch (Throwable t) {
            logSevereWithCode("ACELIB-PLAYER-006",
                "bindPlayerDataService: failed to initialize SQLite player store at "
                    + databaseFile + ": " + t.getMessage());
            // store 建立失敗時保留 plugin 其他功能，但不暴露半初始化的 service。
            this.playerDataService = null;
            this.playerLifecycleListener = null;
            this.playerDataStore = null;
            return;
        }

        if (!migrateLegacyPlayerDataIfPresent(store, legacyJsonFile, dataFolder)) {
            this.playerDataService = null;
            this.playerLifecycleListener = null;
            this.playerLifecycleRegistered = false;
            this.playerDataStore = null;
            try {
                store.close();
            } catch (Throwable closeFailure) {
                logSevereWithCode("ACELIB-PLAYER-006",
                    "bindPlayerDataService: failed to close player store after migration "
                        + "failure: " + closeFailure.getMessage());
            }
            return;
        }

        // 建立 service（內部 serial executor 為單一 daemon thread，並啟用定期保存）。
        // 自建 io executor 由 plugin 持有生命週期（reload / onDisable /
        // INCOMPATIBLE teardown 釋放舊服務時一併關閉舊 pool）；
        // service.shutdown() 不關閉外部注入的 executor，故不可在此丟失 reference。
        ExecutorService ioExecutor = createPlayerIoExecutor();
        this.playerIoExecutor = ioExecutor;
        PlayerDataService service = new PlayerDataService(store, ioExecutor,
            PlayerDataService.DEFAULT_SAVE_INTERVAL_MS);
        PlayerLifecycleListener listener = new PlayerLifecycleListener(service, safeLogger());
        this.playerDataStore = store;
        this.playerDataService = service;
        this.playerLifecycleListener = listener;
    }

    /**
     * 舊版 {@code player-data.json} 存在且尚未轉換時，執行一次性同步轉換。
     *
     * <p><strong>不刪除來源資料</strong>：轉換只寫入新的 SQLite store，並在
     * {@code backup/} 留下備份與校驗報告。任一步失敗都記錄並回報 false，呼叫端
     * 必須關閉目標 store、不啟動玩家資料服務；來源與已建立的備份保持原樣，
     * 修復後可重新執行轉換。</p>
     *
     * @param store         目標逐玩家 store；不可為 null
     * @param legacyJsonFile 舊版 JSON 檔路徑；不可為 null
     * @param dataFolder    plugin 資料夾；不可為 null
     */
    private boolean migrateLegacyPlayerDataIfPresent(PlayerDataStore store,
            Path legacyJsonFile, Path dataFolder) {
        if (!java.nio.file.Files.isRegularFile(legacyJsonFile)) {
            return true;
        }
        Path backupDir = dataFolder.resolve(PLAYER_DATA_BACKUP_DIR);
        try {
            PlayerDataConverter.Result result = PlayerDataConverter
                .fromLegacyJsonFile(legacyJsonFile, store, backupDir);
            if (result.skipped()) {
                logFine("bindPlayerDataService: legacy player data already converted; "
                    + "skipping (marker present)");
                return true;
            }
            logInfo("bindPlayerDataService: converted legacy player data — players="
                + result.convertedPlayers() + ", failed=" + result.failedPlayers()
                + ", backup=" + result.backupPath()
                + ", report=" + result.reportPath());
            return true;
        } catch (Throwable t) {
            String causeCode = t instanceof DataStoreException dse
                ? dse.getCode() : "unknown conversion error";
            logSevereWithCode("ACELIB-PLAYER-006",
                "bindPlayerDataService: legacy player data conversion failed; "
                    + "player data service will not start. Source retained at "
                    + legacyJsonFile + "; existing backups retained under " + backupDir
                    + "; fix the cause and rerun conversion. Cause [" + causeCode + "]: "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * 關閉當前逐玩家 store（冪等）。
     *
     * <p>必須在 {@link PlayerDataService#shutdown()} <strong>之後</strong>呼叫：
     * 服務 shutdown 會 flush 最後一批變更，那時仍需要可用的連線。</p>
     */
    private void closePlayerDataStore() {
        PlayerDataStore store = this.playerDataStore;
        this.playerDataStore = null;
        if (store == null || store.isClosed()) {
            return;
        }
        try {
            store.close();
        } catch (Throwable t) {
            logFine("closePlayerDataStore: closing player store failed (ignored): "
                + t.getMessage());
        }
    }

    /**
     * 在線玩家重接等待上限（毫秒）：所有在線玩家共用單一總時限。
     *
     * <p>reload commit 階段先為每位在線玩家重建 session，再以共用 deadline
     * 等待全部載入完成；總等待不隨在線人數線性成長。逾時（個別逾時或總時限
     * 耗盡）只記錄警告、不中斷 reload（該玩家稍後可經 join 事件重建）。</p>
     */
    private static final long PLAYER_REJOIN_TIMEOUT_MS = 10_000L;

    /**
     * Package-private 測試 seam：覆寫在線玩家重接的等待上限（毫秒）。
     *
     * <p>正數時取代 {@link #PLAYER_REJOIN_TIMEOUT_MS}；預設 {@code -1} 表示
     * 使用正式上限。僅供 {@code com.smile.acelib} 套件內測試使用。</p>
     */
    volatile long reloadRejoinTimeoutMsOverride = -1L;

    /**
     * Package-private 測試 seam：取代重接時對單一玩家的 join 提交。
     *
     * <p>非 null 時，重接流程以此函數的回傳值作為該玩家的載入 future，
     * 而不呼叫 {@code PlayerDataService.onPlayerJoin}；測試以此構造
     * 「載入全卡住」等受控情境。僅供 {@code com.smile.acelib} 套件內測試使用。</p>
     */
    volatile BiFunction<UUID, String, CompletableFuture<Void>> reloadRejoinJoinOverride = null;

    /**
     * 在線玩家重接等待上限（毫秒）的實際取值。
     *
     * <p>測試以 {@link #reloadRejoinTimeoutMsOverride} 注入較短的上限時，
     * 回傳注入值；否則回傳 {@link #PLAYER_REJOIN_TIMEOUT_MS}。</p>
     */
    private long effectiveRejoinTimeoutMs() {
        long override = reloadRejoinTimeoutMsOverride;
        return override > 0L ? override : PLAYER_REJOIN_TIMEOUT_MS;
    }

    /**
     * 關閉自建 player io executor。
     *
     * <p>只關閉 {@link #playerIoExecutor} 追蹤的自建 pool（{@link #createPlayerIoExecutor()}
     * 產物）；外部注入的 executor 絕不經此關閉。呼叫時機：舊 player 服務已
     * shutdown 之後（flush 完成、in-flight 排空），避免中斷進行中的保存。
     * 若舊服務 shutdown 失敗（服務仍存活），不得呼叫本方法。</p>
     */
    private void shutdownPlayerIoExecutor() {
        ExecutorService io = this.playerIoExecutor;
        this.playerIoExecutor = null;
        if (io == null) {
            return;
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(5L, TimeUnit.SECONDS)) {
                logFine("player io executor did not terminate within timeout; "
                    + "remaining daemon threads exit on JVM shutdown");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logFine("player io executor termination wait interrupted");
        }
    }

    /**
     * 為在線玩家在新 player 服務重建 session。
     *
     * <p>舊服務 shutdown 已把 dirty flush 回 store，新服務 registry 為空；
     * 對 {@code server.getOnlinePlayers()} 逐一呼叫
     * {@code onPlayerJoin}（含重連閉環），再以共用 deadline 等待全部載入完成，
     * 使 reload 回傳時 {@code getData}/{@code markDirty}/{@code onPlayerQuit}
     * 皆可用。等待總時限不隨在線人數成長；單一玩家重建失敗或逾時只記錄警告、
     * 不中斷 reload（reload 整體仍成功）。</p>
     */
    private void rejoinOnlinePlayers() {
        PlayerDataService current = this.playerDataService;
        Server currentServer = this.server;
        if (current == null || currentServer == null) {
            return;
        }
        Collection<? extends Player> online;
        try {
            online = currentServer.getOnlinePlayers();
        } catch (Throwable t) {
            logFine("reload: getOnlinePlayers failed, skipping rejoin: " + t.getMessage());
            return;
        }
        if (online.isEmpty()) {
            return;
        }
        List<RejoinPending> pending = new ArrayList<>(online.size());
        for (Player player : online) {
            UUID uuid;
            String name;
            try {
                uuid = player.getUniqueId();
                name = player.getName();
            } catch (Throwable t) {
                logFine("reload: online player snapshot failed, skipping rejoin: "
                    + t.getMessage());
                continue;
            }
            try {
                CompletableFuture<Void> joined = reloadRejoinJoinOverride != null
                    ? reloadRejoinJoinOverride.apply(uuid, name)
                    : current.onPlayerJoin(uuid, name);
                if (joined == null) {
                    logWarningWithCode("ACELIB-PLAYER-002",
                        "reload: online player rejoin join-seam returned null for uuid=" + uuid
                            + " name=" + name);
                    continue;
                }
                pending.add(new RejoinPending(joined, uuid, name));
            } catch (PlayerStateException rejected) {
                logWarningWithCode(rejected.getCode(),
                    "reload: online player rejoin rejected for uuid=" + uuid
                        + " name=" + name + ": " + rejected.getMessage());
            } catch (RuntimeException unexpected) {
                logWarningWithCode("ACELIB-PLAYER-002",
                    "reload: online player rejoin failed for uuid=" + uuid
                        + " name=" + name + ": " + unexpected);
            }
        }
        // 共用總時限等待全部 join：總等待以 budget 為界，不隨在線人數線性成長。
        // 總時限耗盡後，剩餘玩家不再等待、各記一則逾時警告，reload 照常成功。
        long budgetMs = effectiveRejoinTimeoutMs();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        for (RejoinPending rejoin : pending) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                logWarningWithCode("ACELIB-PLAYER-002",
                    "reload: online player rejoin timed out after " + budgetMs
                        + "ms shared budget for uuid=" + rejoin.uuid
                        + " name=" + rejoin.name);
                continue;
            }
            try {
                rejoin.future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logFine("reload: player rejoin wait interrupted");
                return;
            } catch (ExecutionException asyncFailure) {
                Throwable cause = asyncFailure.getCause();
                String code = cause instanceof PlayerStateException playerFailure
                    ? playerFailure.getCode() : "ACELIB-PLAYER-002";
                logWarningWithCode(code,
                    "reload: online player rejoin async load failed for uuid=" + rejoin.uuid
                        + " name=" + rejoin.name + ": " + cause);
            } catch (TimeoutException timeout) {
                logWarningWithCode("ACELIB-PLAYER-002",
                    "reload: online player rejoin timed out after " + budgetMs
                        + "ms shared budget for uuid=" + rejoin.uuid
                        + " name=" + rejoin.name + ": " + timeout);
            }
        }
    }

    /**
     * 重接等待中的單一玩家 join（載入 future + 身分快照）。
     *
     * <p>逾時警告必須能對應到玩家，故 future 與取樣時的 uuid/name 綁在一起；
     * 不直接保留 {@code Player}（reload 期間實體可能失效）。</p>
     */
    private static final class RejoinPending {
        final CompletableFuture<Void> future;
        final UUID uuid;
        final String name;

        RejoinPending(CompletableFuture<Void> future, UUID uuid, String name) {
            this.future = future;
            this.uuid = uuid;
            this.name = name;
        }
    }

    /**
     * 建立 world 服務與其 diagnostics 綁定。
     *
     * <p>於 onEnable / reload commit 階段呼叫，建立 {@link BukkitWorldBackend} +
     * {@link WorldServiceImpl} 並委派 {@code WorldServiceImpl} 於
     * {@link DiagnosticsService} 註冊 {@code READY} 模組狀態。</p>
     *
     * <p>既有 {@code this.worldService} 若仍是 NOT_READY unavailable facade，
     * 直接覆寫；reload 路徑先經 {@link #unbindWorldService()} 釋放舊 impl
     * （shutdown + SHUTDOWN facade 替換）再呼叫本方法重建，避免舊 impl 殘留 READY。</p>
     *
     * @param server 當前 Bukkit/Paper/Folia server；不可為 null
     */
    private void bindWorldService(Server server) {
        Objects.requireNonNull(server, "server");
        WorldBackend backend = new BukkitWorldBackend(server);
        WorldService newService = new WorldServiceImpl(backend, diagnostics);
        this.worldService = newService;
        logFine("world service bound to server=" + server.getName());
    }

    /**
     * 解除並 shutdown world 服務。
     *
     * <p>呼叫現有 {@code worldService.shutdown()}（idempotent），然後把
     * {@code this.worldService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 reload 後繼續讀到「服務已停用」的訊號，
     * 也保證 AceLibApi 的 worldService 永不為 null。</p>
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
     * 建立 GUI 服務。
     *
     * <p>於 onEnable / reload commit 階段呼叫，建立 {@link GuiService} 實作
     * （透過 {@code GuiService.forProduction} 隱藏內部實作類別）。
     * 內部透過 SafeSchedulerPlayerContextExecutor
     * 把 inventory mutation 派送到玩家 region context（Folia entity scheduler、
     * Paper main thread）。listener 註冊延後到 {@link #onPluginReady()}
     * （避免 Bukkit 在 plugin is enabled 之前 allow register）。</p>
     *
     * <p>既有 {@code this.guiService} 若仍是 NOT_READY unavailable facade，
     * 直接覆寫；reload 路徑先經 {@link #unbindGuiService()} 解除舊 listener
     * 註冊並 shutdown 舊 impl 再呼叫本方法重建，避免雙 listener 與舊 impl 殘留 READY。</p>
     *
     * @param server 當前 Bukkit/Paper/Folia server；不可為 null
     */
    private void bindGuiService(Server server) {
        Objects.requireNonNull(server, "server");
        // production 必須走 SafeScheduler：Paper main thread、Folia entity scheduler。
        // inventory mutation 透過既有 SafeExecutor/region-aware adapter。
        GuiService newService = GuiService.forProduction(this.scheduler);
        this.guiService = newService;
        this.guiListener = newService.getListener();
        this.guiListenerRegistered = false;
        logFine("gui service bound to server=" + server.getName());
    }

    /**
     * 解除並停用 GUI 服務。
     *
     * <p>解除 GUI listener 註冊（{@link HandlerList#unregisterAll(Listener)}），
     * 然後經內部生命週期停用現有服務（idempotent），最後把
     * {@code this.guiService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 reload 後繼續讀到「服務已停用」的訊號，
     * 也保證 AceLibApi 的 guiService 永不為 null。</p>
     *
     * <p>消費者作用域撤下 listener 由指令目錄的停用分派 listener 兼任，
     * 不在此處處理；公開 {@code GuiService} 契約不再提供關閉整個服務的方法
     * （1.4.0 破壞性變更），此處經 {@link GuiServiceControl} 停用，
     * 下游不得依賴該內部入口 — 結束 GUI 請關閉自己的
     * {@code GuiScope}。</p>
     */
    private void unbindGuiService() {
        org.bukkit.event.Listener oldListener = this.guiListener;
        if (oldListener != null) {
            try {
                HandlerList.unregisterAll(oldListener);
            } catch (Throwable t) {
                logFine("guiListener unregister failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.guiListener = null;
        this.guiListenerRegistered = false;
        GuiService old = this.guiService;
        if (old instanceof GuiServiceControl control) {
            try {
                control.shutdownService();
            } catch (Throwable t) {
                logFine("guiService.shutdownService failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.guiService = GuiService.forUnavailable(GuiErrorCode.SHUTDOWN);
    }

    /**
     * 建立並綁定顯示服務。
     *
     * <p>production 必須走 SafeScheduler：玩家操作派送到玩家上下文、
     * 全息字生成派送到位置上下文、實體操作派送到實體上下文。
     * quit 清理 listener 註冊延後到 {@link #onPluginReady()}
     * （避免 Bukkit 在 plugin is enabled 之前 allow register）。</p>
     *
     * <p>既有 {@code this.displayService} 若仍是 NOT_READY unavailable facade，
     * 直接覆寫；reload 路徑先經 {@link #unbindDisplayService()} shutdown 舊 impl
     * 再呼叫本方法重建，避免舊 impl 殘留 READY。</p>
     *
     * @param server 當前 Bukkit/Paper/Folia server；不可為 null
     */
    private void bindDisplayService(Server server) {
        Objects.requireNonNull(server, "server");
        DisplayService newService = DisplayService.forProduction(this, this.scheduler);
        this.displayService = newService;
        this.displayQuitListener = new DisplayQuitListener(newService);
        this.displayQuitListenerRegistered = false;
        logFine("display service bound to server=" + server.getName());
    }

    /**
     * 解除並停用顯示服務。
     *
     * <p>解除 quit listener 註冊，然後經內部生命週期停用現有服務
     * （清除自身追蹤的全部顯示，具冪等性），最後把
     * {@code this.displayService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 reload 後繼續讀到「服務已停用」的訊號，
     * 也保證對外 facade 永不為 null。不觸及其他 plugin 的顯示。</p>
     */
    private void unbindDisplayService() {
        org.bukkit.event.Listener oldListener = this.displayQuitListener;
        if (oldListener != null) {
            try {
                HandlerList.unregisterAll(oldListener);
            } catch (Throwable t) {
                logFine("displayQuitListener unregister failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.displayQuitListener = null;
        this.displayQuitListenerRegistered = false;
        DisplayService old = this.displayService;
        if (old instanceof DisplayServiceControl control) {
            try {
                control.shutdownService();
            } catch (Throwable t) {
                logFine("displayService.shutdownService failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.displayService = DisplayService.forUnavailable(DisplayErrorCode.SHUTDOWN);
    }

    /**
     * AceLib 自身顯示實例的退服清理 listener（MONITOR 觀察，不取消亦不修改事件）。
     *
     * <p>只清理 AceLib 自身 {@link DisplayService} 追蹤的該玩家顯示，
     * 不碰其他 plugin 的資源。分派永不拋例外，不中斷其他 listener。</p>
     */
    private final class DisplayQuitListener implements Listener {

        private final DisplayService owned;

        DisplayQuitListener(DisplayService owned) {
            this.owned = Objects.requireNonNull(owned, "owned");
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onPlayerQuit(PlayerQuitEvent event) {
            try {
                owned.handlePlayerQuit(event == null ? null
                    : event.getPlayer().getUniqueId());
            } catch (Throwable t) {
                logFine("display quit cleanup failed (ignored): " + t.getMessage());
            }
        }
    }

    /**
     * 建立並綁定外部整合服務，並向 diagnostics 註冊 integration 模組狀態。
     *
     * <p>於 onEnable / reload commit 階段呼叫，建立 {@link IntegrationRegistry} 並註冊四個
     * reflection-only adapter（Vault / LuckPerms / PlaceholderAPI），再以
     * {@link IntegrationRegistry#initializeAll()} 啟用，最後包裝為
     * {@link ExternalIntegrationServiceImpl} 並將其 {@code toModuleState()} 註冊到
     * {@link DiagnosticsService} 的 integration 模組。</p>
     *
     * @param server 當前 Bukkit/Paper/Folia server；不可為 null
     */
    /**
     * 外部整合探測使用的 classloader。
     *
     * <p>必須是 AceLib 自身的 plugin classloader，而非伺服器主 classloader。JVM 類別載入為
     * 父優先委派：伺服器主 classloader 是所有 plugin classloader 的父，對子（各插件 JAR
     * 提供的 API class）不可見；反之 AceLib 的 plugin classloader 會依 plugin.yml 的
     * depend/softdepend 委派到依賴插件的 classloader，因此能看見 floodgate / vault /
     * luckperms / PlaceholderAPI 等外部 API marker class。若改用
     * {@code server.getClass().getClassLoader()}，所有只由插件 JAR 提供的 marker class 都會
     * 永遠找不到，導致四個 adapter 全數 INIT_FAILED（ACELIB-EXT-001）。</p>
     *
     * @return 用於 classpath 探測的 classloader；永不為 null
     */
    ClassLoader externalProbeClassLoader() {
        return getClass().getClassLoader();
    }

    private void bindExternalService(Server server) {
        Objects.requireNonNull(server, "server");
        ClassLoader classLoader = externalProbeClassLoader();
        PluginManager pluginManager = server.getPluginManager();
        IntegrationRegistry registry = new IntegrationRegistry();
        VaultIntegrationAdapter vaultAdapter =
            new VaultIntegrationAdapter(classLoader, pluginManager);
        registry.register(vaultAdapter);
        LuckPermsIntegrationAdapter luckPermsAdapter =
            new LuckPermsIntegrationAdapter(classLoader, pluginManager);
        registry.register(luckPermsAdapter);
        PlaceholderApiIntegrationAdapter placeholderAdapter =
            new PlaceholderApiIntegrationAdapter(classLoader, pluginManager);
        registry.register(placeholderAdapter);
        FloodgateIntegrationAdapter floodgateAdapter =
            new FloodgateIntegrationAdapter(classLoader, pluginManager);
        registry.register(floodgateAdapter);
        registry.initializeAll();
        // 內建業務提供者：只有對應 adapter 探測 AVAILABLE（active）時才建立；
        // 缺席時傳 null，門面業務呼叫回明確不可用。LuckPerms／PlaceholderAPI 的
        // 外部型別只出現在 adapter／provider 持有者內，本方法不直接引用。
        EconomyProvider economyProvider = vaultAdapter.economyProvider();
        PermissionProvider permissionProvider = luckPermsAdapter.permissionProvider();
        PlaceholderProvider placeholderProvider =
            placeholderAdapter.placeholderProvider();
        ExternalIntegrationServiceImpl impl = new ExternalIntegrationServiceImpl(
            registry, economyProvider, permissionProvider, placeholderProvider, null);
        this.externalService = impl;
        this.diagnostics.registerModuleState(MODULE_INTEGRATION, impl.toModuleState());
        // 基岩服務綁定：floodgate 啟用 → typed lookup；缺席 → absent lookup（零影響）。
        bindBedrockService(floodgateAdapter);
    }

    /**
     * 依 floodgate adapter 狀態建立並綁定基岩版玩家服務。
     *
     * <p>adapter 啟用（探測 AVAILABLE）時攜帶其 typed lookup 與表單發送 seam；
     * 缺席 / 未啟用 / 版本不符時攜帶 {@link BedrockService.PlayerLookup#absent()}
     * 與 {@code FormService.FormSender.absent()}——查詢一律安全回覆「非基岩玩家」、
     * 表單發送以 {@code ACELIB-FORM-001} 明確拒絕，達成 Floodgate 缺席零影響
     * （不拋 NoClassDefFoundError）。本方法不拋例外：seam 建構只包裝 supplier，
     * 不做任何外部呼叫。</p>
     *
     * @param floodgateAdapter 已註冊並 initialize 過的 floodgate adapter；不可為 null
     */
    private void bindBedrockService(FloodgateIntegrationAdapter floodgateAdapter) {
        Objects.requireNonNull(floodgateAdapter, "floodgateAdapter");
        boolean active = floodgateAdapter.isActive();
        BedrockService.PlayerLookup lookup = active
            ? floodgateAdapter.playerLookup()
            : BedrockService.PlayerLookup.absent();
        com.smile.acelib.form.FormService.FormSender formSender = active
            ? floodgateAdapter.formSender()
            : com.smile.acelib.form.FormService.FormSender.absent();
        // 表單回應派送以 supplier 延遲綁定 scheduler（比照發送 seam 的延遲綁定先例）：
        // reload Phase D 於此處建立新 FormService 時 this.scheduler 仍指向 Phase A
        // 已停用的舊 scheduler，直到 commit 階段才覆寫；每次派送才讀取欄位，
        // commit 後自動取到新 scheduler，不捕獲已停用實例。
        com.smile.acelib.form.FormService formService = active
            ? com.smile.acelib.form.FormService.forProduction(formSender,
                () -> this.scheduler)
            : com.smile.acelib.form.FormService.forProduction(formSender);
        this.bedrockService = BedrockService.forProduction(lookup, formService);
    }

    /**
     * 解除並 shutdown 基岩版玩家服務。
     *
     * <p>呼叫現有 {@code bedrockService.shutdown()}（冪等），然後把欄位替換為
     * SHUTDOWN unavailable facade，保證既有 caller 在 disable 後讀到「已停用」訊號。</p>
     */
    private void unbindBedrockService() {
        BedrockService old = this.bedrockService;
        if (old != null) {
            try {
                old.shutdown();
            } catch (Throwable t) {
                logFine("bedrockService.shutdown failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.bedrockService = BedrockService.forUnavailable(BedrockService.SHUTDOWN);
    }

    /**
     * 解除並 shutdown 外部整合服務，並自 diagnostics 取消 integration 模組狀態註冊。
     *
     * <p>呼叫現有 {@code externalService.shutdown()}（idempotent），然後把
     * {@code this.externalService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 disable 後繼續讀到「服務已停用」的訊號，
     * 也保證 AceLibApi 的 externalService 永不為 null。</p>
     */
    private void unbindExternalService() {
        ExternalIntegrationService old = this.externalService;
        if (old != null) {
            try {
                old.shutdown();
            } catch (Throwable t) {
                logFine("externalService.shutdown failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        if (this.diagnostics != null) {
            try {
                this.diagnostics.unregisterModuleState(MODULE_INTEGRATION);
            } catch (Throwable t) {
                logFine("externalService module state unregister failed (ignored): "
                    + t.getMessage());
            }
        }
        this.externalService = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.SHUTDOWN);
    }

    /**
     * Completes listener registration after Bukkit has marked this plugin enabled.
     * Package-private so lifecycle tests can exercise the same idempotent seam
     * without invoking MockBukkit's automatic enable path.
     *
     * <p>同時註冊 player lifecycle listener 與 GUI listener。每一條 listener
     * 各自維護「已註冊」旗標，避免重複呼叫 registerEvents 造成 Bukkit 重複
     * dispatch 警告；reload 流程由 {@link #unbindGuiService()} 與既有
     * player listener 反註冊流程處理後，新的 service 實例會在下次
     * {@code onPluginReady} 呼叫時重新註冊。</p>
     */
    synchronized void onPluginReady() {
        if (!isEnabled() || server == null) {
            return;
        }
        if (!playerLifecycleRegistered && playerLifecycleListener != null) {
            server.getPluginManager().registerEvents(playerLifecycleListener, this);
            playerLifecycleRegistered = true;
        }
        if (!guiListenerRegistered && guiListener != null) {
            server.getPluginManager().registerEvents(guiListener, this);
            guiListenerRegistered = true;
        }
        if (!displayQuitListenerRegistered && displayQuitListener != null) {
            server.getPluginManager().registerEvents(displayQuitListener, this);
            displayQuitListenerRegistered = true;
        }
        if (!catalogDisableListenerRegistered && catalogDisableListener != null) {
            server.getPluginManager().registerEvents(catalogDisableListener, this);
            catalogDisableListenerRegistered = true;
        }
    }

    /**
     * Package-private 測試 seam：經由 plugin loader 的 {@code createRegisteredListeners}
     * 取得各事件對應的 {@link org.bukkit.plugin.RegisteredListener}，再逐一註冊到該事件的
     * {@link HandlerList}（繞過 Bukkit 的 {@code isEnabled()} 守門），使
     * {@link HandlerList#getRegisteredListeners} 真正反映已註冊的 player / gui listener。
     *
     * <p>本 repo 的測試環境（MockBukkit 4.x + plugin classloader）無法透過
     * {@code PluginManager.enablePlugin} 標記 plugin enabled（會觸發 classloader NPE），
     * 而 {@code isEnabled()} 為 final，故 {@code onPluginReady()} 的 {@code registerEvents}
     * 路徑在測試中永遠早退。此 seam 讓 lifecycle 測試能真正建立 listener 註冊（與
     * {@code onPluginReady} 等價的效果，且 RegisteredListener 關聯本 plugin），再斷言
     * teardown 確實解除，避免「listener 已解除」變成 vacuous assertion。僅供測試使用。</p>
     */
    void registerListenersForTest() {
        if (server == null) {
            return;
        }
        registerOneForTest(playerLifecycleListener);
        registerOneForTest(guiListener);
        registerOneForTest(displayQuitListener);
        if (!catalogDisableListenerRegistered) {
            registerOneForTest(catalogDisableListener);
        }
    }

    private void registerOneForTest(org.bukkit.event.Listener listener) {
        if (listener == null) {
            return;
        }
        java.util.Map<Class<? extends org.bukkit.event.Event>,
            java.util.Set<org.bukkit.plugin.RegisteredListener>> map =
            getPluginLoader().createRegisteredListeners(listener, this);
        for (java.util.Map.Entry<Class<? extends org.bukkit.event.Event>,
                java.util.Set<org.bukkit.plugin.RegisteredListener>> e : map.entrySet()) {
            try {
                java.lang.reflect.Method m = e.getKey().getMethod("getHandlerList");
                org.bukkit.event.HandlerList hl = (org.bukkit.event.HandlerList) m.invoke(null);
                for (org.bukkit.plugin.RegisteredListener rl : e.getValue()) {
                    hl.register(rl);
                }
            } catch (Exception ex) {
                logFine("registerListenersForTest: skip event " + e.getKey() + ": " + ex.getMessage());
            }
        }
        if (listener == playerLifecycleListener) {
            playerLifecycleRegistered = true;
        }
        if (listener == guiListener) {
            guiListenerRegistered = true;
        }
        if (listener == displayQuitListener) {
            displayQuitListenerRegistered = true;
        }
        if (listener == catalogDisableListener) {
            catalogDisableListenerRegistered = true;
        }
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
    // Bukkit event listener — login preload、join/quit 委派給 PlayerDataService
    // ---------------------------------------------------------------------

    /**
     * {@link AsyncPlayerPreLoginEvent} / {@link PlayerJoinEvent} / {@link PlayerQuitEvent}
     * listener。
     *
     * <p>Async pre-login 只讀 UUID/name 快照，透過既有 {@link PlayerDataService#onPlayerJoin}
     * 建立唯一 session 並啟動 store 載入；join 事件接管該 session，不另開載入路徑。
     * 預載失敗時 join 才重新呼叫同一 service API。listener 本身
     * <strong>不保留 Player reference</strong>。所有 handler 都在 MONITOR priority，
     * 不取消亦不修改 Bukkit 事件。</p>
     *
     * <p>失敗語意：join/quit 的同步拒絕（PLAYER-004/005/007）與非同步
     * 完成失敗（PLAYER-002/003）一律攔截並以 ACELIB-PLAYER 分類記入
     * logger，<strong>不向外拋</strong>，避免污染 Bukkit 事件流程。</p>
     *
     * <p>於 onDisable / reload 時透過 {@link HandlerList#unregisterAll(Listener)}
     * 解除註冊，確保 listener 不殘留於 Bukkit HandlerList。</p>
     */
    static final class PlayerLifecycleListener implements Listener {

        private static final long DEFAULT_PRELOGIN_LEASE_MILLIS = TimeUnit.SECONDS.toMillis(30);

        private final PlayerDataService service;
        private final Logger logger;
        private final long preloginLeaseMillis;
        private final ConcurrentMap<UUID, PendingPrelogin> preloginLoads =
            new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ScheduledThreadPoolExecutor preloginCleanupExecutor;

        PlayerLifecycleListener(PlayerDataService service) {
            this(service, Logger.getLogger(LOG_NAME));
        }

        PlayerLifecycleListener(PlayerDataService service, Logger logger) {
            this(service, logger, DEFAULT_PRELOGIN_LEASE_MILLIS);
        }

        PlayerLifecycleListener(PlayerDataService service, Logger logger,
                long preloginLeaseMillis) {
            this.service = Objects.requireNonNull(service, "service");
            this.logger = Objects.requireNonNull(logger, "logger");
            if (preloginLeaseMillis <= 0) {
                throw new IllegalArgumentException("preloginLeaseMillis 必須為正數");
            }
            this.preloginLeaseMillis = preloginLeaseMillis;
            this.preloginCleanupExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "acelib-player-prelogin-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            this.preloginCleanupExecutor.setRemoveOnCancelPolicy(true);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        synchronized void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
            UUID uuid = event.getUniqueId();
            String name = event.getName();
            if (closed.get()) {
                logRejected("onPlayerPreLogin", uuid, name,
                    new PlayerStateException("ACELIB-PLAYER-007",
                        "player lifecycle listener is closed"));
                return;
            }

            PendingPrelogin pending = new PendingPrelogin();
            if (preloginLoads.putIfAbsent(uuid, pending) != null) {
                return;
            }
            try {
                CompletableFuture<Void> load = service.onPlayerJoin(uuid, name);
                load.whenComplete((ignored, failure) -> completePrelogin(uuid, pending, failure));
            } catch (PlayerStateException rejected) {
                completePrelogin(uuid, pending, rejected);
            } catch (RuntimeException unexpected) {
                completePrelogin(uuid, pending, unexpected);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String name = player.getName();
            // 立即 snapshot UUID/name；listener 不保留 Player reference。
            PendingPrelogin pending = preloginLoads.get(uuid);
            if (pending != null) {
                if (pending.phase.compareAndSet(PreloginPhase.AVAILABLE,
                        PreloginPhase.JOINED)) {
                    preloginLoads.remove(uuid, pending);
                    pending.cancelExpiry();
                    pending.load.whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            startJoinLoad(uuid, name);
                        }
                    });
                    return;
                }
                if (pending.phase.get() == PreloginPhase.EXPIRING) {
                    pending.expiration.whenComplete((ignored, failure) -> startJoinLoad(uuid, name));
                    return;
                }
            }
            startJoinLoad(uuid, name);
        }

        private void startJoinLoad(UUID uuid, String name) {
            final CompletableFuture<Void> future;
            try {
                future = service.onPlayerJoin(uuid, name);
            } catch (PlayerStateException rejected) {
                logRejected("onPlayerJoin", uuid, name, rejected);
                return;
            } catch (RuntimeException unexpected) {
                logger.log(Level.WARNING,
                    "onPlayerJoin failed for uuid=" + uuid + " name=" + name + ": " + unexpected);
                return;
            }
            future.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logger.log(Level.WARNING,
                        "[" + codeOf(failure, "ACELIB-PLAYER-002") + "] onPlayerJoin "
                            + "async load failed for uuid=" + uuid + ": " + failure.getMessage());
                }
            });
        }

        private void completePrelogin(UUID uuid, PendingPrelogin pending, Throwable failure) {
            if (failure != null) {
                pending.load.completeExceptionally(failure);
                if (pending.phase.compareAndSet(PreloginPhase.AVAILABLE, PreloginPhase.FAILED)) {
                    preloginLoads.remove(uuid, pending);
                }
                Throwable cause = unwrapFailure(failure);
                logger.log(Level.WARNING,
                    "[" + codeOf(cause, "ACELIB-PLAYER-002")
                        + "] onPlayerPreLogin async load failed for uuid=" + uuid
                        + ": " + cause.getMessage());
                return;
            }

            pending.load.complete(null);
            if (pending.phase.get() != PreloginPhase.AVAILABLE || closed.get()) {
                return;
            }
            try {
                ScheduledFuture<?> expiry = preloginCleanupExecutor.schedule(
                    () -> expirePrelogin(uuid, pending), preloginLeaseMillis,
                    TimeUnit.MILLISECONDS);
                pending.expiry = expiry;
                if (pending.phase.get() != PreloginPhase.AVAILABLE
                        || preloginLoads.get(uuid) != pending) {
                    expiry.cancel(false);
                }
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                if (!closed.get()) {
                    logger.log(Level.WARNING,
                        "pre-login cleanup scheduling failed for uuid=" + uuid + ": " + rejected);
                    expirePrelogin(uuid, pending);
                }
            }
        }

        private void expirePrelogin(UUID uuid, PendingPrelogin pending) {
            if (!pending.phase.compareAndSet(PreloginPhase.AVAILABLE, PreloginPhase.EXPIRING)) {
                return;
            }
            if (service.isShutdown()) {
                finishPreloginExpiry(uuid, pending);
                return;
            }
            final CompletableFuture<Void> quit;
            try {
                quit = service.onPlayerQuit(uuid);
            } catch (RuntimeException rejected) {
                if (!"ACELIB-PLAYER-005".equals(codeOf(rejected, ""))) {
                    logger.log(Level.FINE,
                        "pre-login session cleanup rejected for uuid=" + uuid + ": " + rejected);
                }
                finishPreloginExpiry(uuid, pending);
                return;
            }
            quit.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logger.log(Level.WARNING,
                        "[" + codeOf(failure, "ACELIB-PLAYER-003")
                            + "] pre-login session cleanup failed for uuid=" + uuid
                            + ": " + failure.getMessage());
                }
                finishPreloginExpiry(uuid, pending);
            });
        }

        private void finishPreloginExpiry(UUID uuid, PendingPrelogin pending) {
            pending.phase.set(PreloginPhase.EXPIRED);
            preloginLoads.remove(uuid, pending);
            pending.expiration.complete(null);
        }

        private void logRejected(String operation, UUID uuid, String name,
                PlayerStateException rejected) {
            logger.log(Level.WARNING,
                "[" + rejected.getCode() + "] " + operation + " rejected for uuid=" + uuid
                    + " name=" + name + ": " + rejected.getMessage());
        }

        synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (Map.Entry<UUID, PendingPrelogin> entry : preloginLoads.entrySet()) {
                PendingPrelogin pending = entry.getValue();
                pending.cancelExpiry();
                if (service.isShutdown()) {
                    if (pending.phase.compareAndSet(PreloginPhase.AVAILABLE,
                            PreloginPhase.EXPIRED)) {
                        preloginLoads.remove(entry.getKey(), pending);
                        pending.expiration.complete(null);
                    }
                } else {
                    expirePrelogin(entry.getKey(), pending);
                }
            }
            preloginCleanupExecutor.shutdownNow();
        }

        int pendingPreloginCount() {
            return preloginLoads.size();
        }

        private static Throwable unwrapFailure(Throwable failure) {
            Throwable cause = failure;
            while ((cause instanceof CompletionException
                    || cause instanceof ExecutionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause;
        }

        private static final class PendingPrelogin {
            private final CompletableFuture<Void> load = new CompletableFuture<>();
            private final CompletableFuture<Void> expiration = new CompletableFuture<>();
            private final AtomicReference<PreloginPhase> phase =
                new AtomicReference<>(PreloginPhase.AVAILABLE);
            private volatile ScheduledFuture<?> expiry;

            private void cancelExpiry() {
                ScheduledFuture<?> scheduled = expiry;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
            }
        }

        private enum PreloginPhase {
            AVAILABLE,
            JOINED,
            EXPIRING,
            EXPIRED,
            FAILED
        }

        @EventHandler(priority = EventPriority.MONITOR)
        void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            // quit 觸發時 player 即將離線；此處取 UUID 即足夠，
            // service 內部已有 name snapshot。
            final CompletableFuture<Void> future;
            try {
                future = service.onPlayerQuit(uuid);
            } catch (PlayerStateException rejected) {
                logger.log(Level.WARNING,
                    "[" + rejected.getCode() + "] onPlayerQuit rejected for uuid=" + uuid
                        + ": " + rejected.getMessage());
                return;
            } catch (RuntimeException unexpected) {
                logger.log(Level.WARNING,
                    "onPlayerQuit failed for uuid=" + uuid + ": " + unexpected);
                return;
            }
            future.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logger.log(Level.WARNING,
                        "[" + codeOf(failure, "ACELIB-PLAYER-003") + "] onPlayerQuit "
                            + "async save failed for uuid=" + uuid + ": " + failure.getMessage());
                }
            });
        }

        private static String codeOf(Throwable failure, String fallback) {
            Throwable cause = unwrapFailure(failure);
            if (cause instanceof PlayerStateException playerFailure) {
                return playerFailure.getCode();
            }
            return fallback;
        }
    }
}
