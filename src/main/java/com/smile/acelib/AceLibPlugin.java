package com.smile.acelib;

import com.smile.acelib.command.AceLibStatusHandler;
import com.smile.acelib.bedrock.BedrockService;
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
import com.smile.acelib.diagnostics.ModuleState;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalIntegrationServiceImpl;
import com.smile.acelib.external.FloodgateIntegrationAdapter;
import com.smile.acelib.external.IntegrationRegistry;
import com.smile.acelib.external.LuckPermsIntegrationAdapter;
import com.smile.acelib.external.PlaceholderApiIntegrationAdapter;
import com.smile.acelib.external.VaultIntegrationAdapter;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiService;
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
        // bedrockService 的 NOT_READY unavailable facade；於 onEnable 後被 bindBedrockService() 替換。
        this.bedrockService = BedrockService.forUnavailable(BedrockService.NOT_READY);
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

        // 建立外部整合服務（world/gui 之後），並向 diagnostics 註冊 integration 模組狀態。
        bindExternalService(s);

        // v0.1.0：建立管理指令系統（/acelib status 等）。在 player listener 註冊
        // 之前先建立並 attach bridge；PluginCommand 的取得來自 plugin.yml，
        // 與 player listener 註冊時機無依賴關係。
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
            () -> ready,
            () -> reload()
        );

        this.ready = true;

        // 對外正式取得入口：於 facade 就緒後註冊 provider（disabled 之後
        // onDisable 會解除註冊，reload 期間不解除）。
        registerApiProvider(s);

        logInfo("AceLib {0} enabled on {1} (capability={2})",
            api.getVersion(), api.getPlatform().getDisplayName(), capability);
    }

    @Override
    public synchronized void onDisable() {
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

        // world 服務 shutdown（標記 stopped、取消 in-flight handle、
        // 註冊 FAILED module state）。順序置於 player 與 scheduler 卸載之後，
        // 確保任何 in-flight teleport 不會被殘留 scheduler 接走。
        unbindWorldService();

        // GUI 服務 shutdown（清除 listener + 移除所有 session）。
        unbindGuiService();

        // 外部整合服務 shutdown（釋放 registry 資源）並解除 integration 模組狀態註冊。
        unbindExternalService();

        // 基岩服務 shutdown（external registry 之後；查詢改為 SHUTDOWN 拒絕）。
        unbindBedrockService();

        this.ready = false;
        this.server = null;
        this.platformDetector = null;
        // 保留 SHUTDOWN worldService 與 guiService reference，避免 double-fork 既有 contract。
        this.api = AceLibApi.shutDown(this.worldService, this.guiService);
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
            teardownRuntimeOnIncompatibleReload(ds);
            return false;
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
            return false;
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
                return false;
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
                if (this.diagnostics != null) {
                    try {
                        this.diagnostics.unregisterModuleState(MODULE_INTEGRATION);
                    } catch (Throwable ignore) {
                        logFine("reload external bind failure: integration module unregister "
                            + "failed (ignored): " + ignore.getMessage());
                    }
                }
                rollbackReload(newScheduler, ds, oldMeta, oldScheduler);
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload: external service bind failed; rolled back to previous binding "
                        + "(scheduler/diagnostics restored). Cause: " + t);
                return false;
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
        // 先 commit 新 scheduler 至 this.scheduler，再 bind GUI service。
        // 順序理由：bindGuiService() 內部讀取 this.scheduler 來建立 SafeSchedulerPlayerContextExecutor，
        // 若 scheduler 仍指向 Phase A 已 disabled 的舊 scheduler，新 GUI service 會
        // 捕獲 disabled scheduler，導致 reload 後 openInventory 一律回
        // ACELIB-GUI-013 SCHEDULER_REJECTED 即為此順序錯誤的具體症狀。
        this.scheduler = newScheduler;
        // reload 成功後重新建立 world 服務（既有 worldService 已 shutdown）。
        bindWorldService(this.server);
        // reload 成功後重新建立 GUI 服務（既有 guiService 已 shutdown）。
        // 必須在 this.scheduler = newScheduler 之後呼叫。
        bindGuiService(this.server);

        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            reDetected,
            reCapability,
            this.worldService,
            this.guiService,
            this.externalService,
            this.bedrockService,
            () -> ready,
            () -> reload()
        );
        // 更新 provider 的 facade reference，使已持有 provider 的呼叫端讀到新 facade。
        updateApiProvider(this.api);
        onPluginReady();
        logInfo("AceLib reloaded on {0}", reDetected.getDisplayName());
        return true;
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
     * INCOMPATIBLE reload 路徑的 runtime 資源 teardown。
     *
     * <p>與 {@link #onDisable()} / Phase A 相同的「停用即釋放」語意：舊 scheduler 標記
     * disabled、player lifecycle listener 解除、各服務 shutdown 並替換為 SHUTDOWN facade。
     * 每個步驟獨立 try/catch，失敗只記錄並繼續，最終由
     * {@link #downgradeAfterReloadPhaseAFailure} 統一降級為 FAILED；不拋出未捕捉例外，
     * 也不重複進 Phase A（此路徑在 gate 失敗後直接 return）。</p>
     *
     * @param ds 既有 diagnostics reference（可為 null；內部以 null-guard 保護）
     */
    private void teardownRuntimeOnIncompatibleReload(DiagnosticsService ds) {
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
            logFine("reload(INCOMPATIBLE): AceLibEvents.unbind failed (ignored): " + t.getMessage());
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
                    "reload(INCOMPATIBLE): old scheduler teardown failed (ignored): " + t);
            }
            // 測試 seam：允許注入受控失敗（與 Phase A 共用同一 hook 語意）
            if (reloadOldTeardownFailureHook != null) {
                try {
                    reloadOldTeardownFailureHook.run();
                } catch (Throwable t) {
                    logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                        "reload(INCOMPATIBLE): old scheduler teardown hook failed (ignored): " + t);
                }
            }
        }
        // 3. player lifecycle listener 解除
        if (oldListener != null) {
            try {
                HandlerList.unregisterAll(oldListener);
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload(INCOMPATIBLE): player lifecycle listener unbind failed (ignored): " + t);
            }
            this.playerLifecycleListener = null;
            this.playerLifecycleRegistered = false;
        }
        // 4. 管理指令框架解除（與 onDisable 同序：listener 解除後、player 服務 shutdown 前）。
        //    unbindCommandFramework 內部已 try/catch。
        unbindCommandFramework();
        // 5. player 服務 shutdown
        if (oldPlayerService != null) {
            try {
                oldPlayerService.shutdown();
            } catch (Throwable t) {
                logSevereWithCode(RELOAD_DIAGNOSTICS_FAILURE_CODE,
                    "reload(INCOMPATIBLE): player data shutdown failed (ignored): " + t);
            }
            this.playerDataService = null;
        }
        // 6. 其餘服務 shutdown + SHUTDOWN facade 替換（內部已 try/catch）
        try { unbindWorldService(); } catch (Throwable t) { logFine("reload(INCOMPATIBLE): world unbind failed (ignored): " + t); }
        try { unbindGuiService(); } catch (Throwable t) { logFine("reload(INCOMPATIBLE): gui unbind failed (ignored): " + t); }
        try { unbindExternalService(); } catch (Throwable t) { logFine("reload(INCOMPATIBLE): external unbind failed (ignored): " + t); }
        try { unbindBedrockService(); } catch (Throwable t) { logFine("reload(INCOMPATIBLE): bedrock unbind failed (ignored): " + t); }

        // 7. 切換 cached facade 為 shutdown，並讓已持有 provider 的呼叫端讀到 shutdown 語意
        //    （與 onDisable 末尾一致：updateApiProvider 更新 provider 內部快照，再清 reference）。
        this.api = AceLibApi.shutDown(this.worldService, this.guiService);
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
     * 建立 world 服務與其 diagnostics 綁定。
     *
     * <p>於 onEnable / reload commit 階段呼叫，建立 {@link BukkitWorldBackend} +
     * {@link WorldServiceImpl} 並委派 {@code WorldServiceImpl} 於
     * {@link DiagnosticsService} 註冊 {@code READY} 模組狀態。</p>
     *
     * <p>既有 {@code this.worldService} 若仍是 NOT_READY unavailable facade，
     * 直接覆寫；如果已經是 SHUTDOWN unavailable（reload 情況），同樣覆寫。</p>
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
     * 直接覆寫；如果已經是 SHUTDOWN unavailable（reload 情況），同樣覆寫。</p>
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
     * 解除並 shutdown GUI 服務。
     *
     * <p>解除 listener 註冊（{@link HandlerList#unregisterAll(Listener)}），
     * 然後呼叫現有 {@code guiService.shutdown()}（idempotent），最後把
     * {@code this.guiService} 替換為 {@code SHUTDOWN} unavailable facade。
     * 這個替換保證既有 caller 在 reload 後繼續讀到「服務已停用」的訊號，
     * 也保證 AceLibApi 的 guiService 永不為 null。</p>
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
        if (old != null) {
            try {
                old.shutdown();
            } catch (Throwable t) {
                logFine("guiService.shutdown failed during unbind (ignored): "
                    + t.getMessage());
            }
        }
        this.guiService = GuiService.forUnavailable(GuiErrorCode.SHUTDOWN);
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
        registry.register(new VaultIntegrationAdapter(classLoader, pluginManager));
        registry.register(new LuckPermsIntegrationAdapter(classLoader, pluginManager));
        registry.register(new PlaceholderApiIntegrationAdapter(classLoader, pluginManager));
        FloodgateIntegrationAdapter floodgateAdapter =
            new FloodgateIntegrationAdapter(classLoader, pluginManager);
        registry.register(floodgateAdapter);
        registry.initializeAll();
        ExternalIntegrationServiceImpl impl = new ExternalIntegrationServiceImpl(registry);
        this.diagnostics.registerModuleState(MODULE_INTEGRATION, impl.toModuleState());
        this.externalService = impl;
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
