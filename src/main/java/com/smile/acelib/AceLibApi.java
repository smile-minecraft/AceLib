package com.smile.acelib;

import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.display.DisplayErrorCode;
import com.smile.acelib.display.DisplayService;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.world.WorldErrorCode;
import com.smile.acelib.world.WorldService;
import com.smile.acelib.world.WorldServiceUnavailableImpl;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 對外 API facade（Supported）。
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>不可變（immutable）：一旦建立，版本與平台欄位（含 worldService）皆不可變動</li>
 *   <li>{@link #isReady()} 透過 {@link BooleanSupplier} 反向查詢當前生命週期狀態</li>
 *   <li>{@link #reload()} 委派給 caller 提供的 callback，避免 facade 直接持有 plugin reference</li>
 * </ul>
 *
 * <p>對外暴露三種狀態的 instance：</p>
 * <ul>
 *   <li>未啟用（uninitialized）— 由 {@link #uninitialized()} 建立；
 *       {@link #getWorldService()} 回傳 {@code NOT_READY} facade，
 *       {@link #getCommandCatalog()} 回傳 unavailable 目錄實作</li>
 *   <li>已啟用（ready）— 由
 *       {@link #ready(String, Platform, PlatformCapability, WorldService, GuiService, ExternalIntegrationService, BedrockService, CommandCatalog, BooleanSupplier, Runnable)}
 *       建立</li>
 *   <li>停用（shutdown）— 由 {@link #shutDown(WorldService, GuiService, CommandCatalog)} 建立；
 *       {@code isReady()} 為 false，service 回傳 shutdown facade</li>
 * </ul>
 *
 * <p>取得方式：下游插件經由 Bukkit/Paper {@code ServicesManager} 取得
 * {@link AceLibApi.AceLibProvider}，再呼叫 {@code api()}；不要直接依賴
 * {@link AceLibPlugin} 或 static singleton。</p>
 *
 * @see PlatformCapability
 * @see WorldService
 * @since 1.0.0
 */
public final class AceLibApi {

    private final String version;
    private final Platform platform;
    private final PlatformCapability capability;
    private final WorldService worldService;
    private final GuiService guiService;
    private final DisplayService displayService;
    private final ExternalIntegrationService externalService;
    private final BedrockService bedrockService;
    private final CommandCatalog commandCatalog;
    private final BooleanSupplier readyCheck;
    private final Runnable onReload;

    private AceLibApi(String version,
                      Platform platform,
                      PlatformCapability capability,
                      WorldService worldService,
                      GuiService guiService,
                      DisplayService displayService,
                      ExternalIntegrationService externalService,
                      BedrockService bedrockService,
                      CommandCatalog commandCatalog,
                      BooleanSupplier readyCheck,
                      Runnable onReload) {
        this.version = Objects.requireNonNull(version, "version");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.worldService = Objects.requireNonNull(worldService, "worldService");
        this.guiService = Objects.requireNonNull(guiService, "guiService");
        this.displayService = Objects.requireNonNull(displayService, "displayService");
        this.externalService = Objects.requireNonNull(externalService, "externalService");
        this.bedrockService = bedrockService;
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
        this.readyCheck = Objects.requireNonNull(readyCheck, "readyCheck");
        this.onReload = Objects.requireNonNull(onReload, "onReload");
    }

    /**
     * 未啟用狀態的預設 instance。
     *
     * <ul>
     *   <li>version = {@link AceLibVersion#VERSION}</li>
     *   <li>platform = {@link Platform#UNKNOWN}</li>
     *   <li>capability = {@link PlatformCapability#forPlatform(Platform) PlatformCapability.forPlatform(UNKNOWN)}</li>
     *   <li>worldService = {@code NOT_READY} unavailable facade（永遠不為 null）</li>
     *   <li>guiService = {@code NOT_READY} unavailable facade（永遠不為 null）</li>
     *   <li>externalService = {@code NOT_READY} unavailable facade（永遠不為 null）</li>
     *   <li>isReady() = false</li>
     *   <li>reload() = no-op</li>
     * </ul>
     */
    public static AceLibApi uninitialized() {
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY),
            GuiService.forUnavailable(GuiErrorCode.NOT_READY),
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY),
            BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(),
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 停用狀態的 facade：攜帶既有 {@code worldService} 並標記 isReady()=false。
     *
     * <p>典型用途：plugin 在 {@code onDisable} 內已經把 {@code worldService} 替換成
     * {@code SHUTDOWN} unavailable facade，但不希望 facade 與既有 reference 完全不同
     * （這會影響診斷報告的 continuity）。此工廠保留 worldService 不可變參考，
     * 方便既有 caller 繼續觀察 shutdown 狀態。</p>
     *
     * <p>guiService 必須由呼叫端傳入；典型用法為
     * {@code AceLibApi.shutDown(worldService, guiService)}。
     * 對 backward-compat 既有呼叫，本方法也提供只帶 worldService 的重載，
     * 內部以 {@code SHUTDOWN} unavailable facade 取代。</p>
     *
     * @param worldService 已 shutdown 的 worldService；不可為 null
     * @return 不可變的 {@link AceLibApi}（isReady=false、worldService=傳入值）
     * @throws NullPointerException 當 {@code worldService} 為 null
 */
    public static AceLibApi shutDown(WorldService worldService) {
        Objects.requireNonNull(worldService, "worldService");
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            worldService,
            GuiService.forUnavailable(GuiErrorCode.SHUTDOWN),
            DisplayService.forUnavailable(DisplayErrorCode.SHUTDOWN),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.SHUTDOWN),
            BedrockService.forUnavailable(BedrockService.SHUTDOWN),
            CommandCatalog.forUnavailable(),
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 停用狀態的 facade（攜帶既有 worldService + guiService）。
     *
     * <p>canonical 重載：保留兩個 service 的不可變 reference，
     * 確保既有的診斷報告查詢可在 plugin disable 後仍能看到一致的 service 物件。</p>
     *
     * @param worldService 已 shutdown 的 worldService；不可為 null
     * @param guiService   已 shutdown 的 guiService；不可為 null
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
 */
    public static AceLibApi shutDown(WorldService worldService, GuiService guiService) {
        Objects.requireNonNull(worldService, "worldService");
        Objects.requireNonNull(guiService, "guiService");
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            worldService,
            guiService,
            DisplayService.forUnavailable(DisplayErrorCode.SHUTDOWN),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.SHUTDOWN),
            BedrockService.forUnavailable(BedrockService.SHUTDOWN),
            CommandCatalog.forUnavailable(),
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 停用狀態的 facade（攜帶既有 worldService + guiService + commandCatalog）。
     *
     * <p>canonical 重載：保留三個 service 的不可變 reference，
     * 確保既有的診斷報告查詢可在 plugin disable 後仍能看到一致的 service 物件。
     * 傳入的 {@code commandCatalog} 應為已 {@code shutdown()} 的實例；
     * 停用後透過舊參考再發布一律回 {@code REJECTED}。</p>
     *
     * @param worldService  已 shutdown 的 worldService；不可為 null
     * @param guiService    已 shutdown 的 guiService；不可為 null
     * @param commandCatalog 已停用的指令目錄；不可為 null
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since 1.3.0
     */
    public static AceLibApi shutDown(WorldService worldService, GuiService guiService,
                                     CommandCatalog commandCatalog) {
        Objects.requireNonNull(worldService, "worldService");
        Objects.requireNonNull(guiService, "guiService");
        Objects.requireNonNull(commandCatalog, "commandCatalog");
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            worldService,
            guiService,
            DisplayService.forUnavailable(DisplayErrorCode.SHUTDOWN),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.SHUTDOWN),
            BedrockService.forUnavailable(BedrockService.SHUTDOWN),
            commandCatalog,
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 停用狀態的 facade（攜帶既有 worldService + guiService + commandCatalog + displayService）。
     *
     * <p>canonical 重載：保留四個 service 的不可變 reference。
     * 傳入的 {@code displayService} 應為已 {@code shutdownService()} 的實例；
     * 停用後透過舊參考再操作一律回 {@code FAILED + ACELIB-DISP-002}。</p>
     *
     * @param worldService  已 shutdown 的 worldService；不可為 null
     * @param guiService    已 shutdown 的 guiService；不可為 null
     * @param commandCatalog 已停用的指令目錄；不可為 null
     * @param displayService 已停用的顯示服務；不可為 null
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since 1.4.0
     */
    public static AceLibApi shutDown(WorldService worldService, GuiService guiService,
                                     CommandCatalog commandCatalog,
                                     DisplayService displayService) {
        Objects.requireNonNull(worldService, "worldService");
        Objects.requireNonNull(guiService, "guiService");
        Objects.requireNonNull(commandCatalog, "commandCatalog");
        Objects.requireNonNull(displayService, "displayService");
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            worldService,
            guiService,
            displayService,
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.SHUTDOWN),
            BedrockService.forUnavailable(BedrockService.SHUTDOWN),
            commandCatalog,
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 已啟用狀態的 instance（canonical 7 參數簽章，推薦使用）。
     *
     * @param version     plugin 版本字串
     * @param platform    偵測到的平台
     * @param capability  對應的 capability profile（不允許 null；請用
     *                    {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param worldService 對外 {@link WorldService} facade（不允許 null；
     *                    plugin 端必須建立合適的 impl 並傳入）
     * @param guiService  對外 {@link GuiService} facade（不允許 null）；
     *                    plugin 端必須建立合適的 impl 並傳入
     * @param readyCheck  當前 lifecycle 是否 ready 的 callback
     * @param onReload    reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
 */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY),
            BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(), readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（8 參數簽章，推薦使用）。
     *
     * <p>與 7 參數版本的差異：額外接受對外 {@link ExternalIntegrationService}
     * facade，讓外部整合查詢可透過 canonical lookup
     * 取得；既有 7 參數版本以 {@code NOT_READY} unavailable facade 填補。</p>
     *
     * @param version          plugin 版本字串
     * @param platform         偵測到的平台
     * @param capability       對應的 capability profile（不允許 null；請用
     *                         {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param worldService     對外 {@link WorldService} facade（不允許 null）
     * @param guiService       對外 {@link GuiService} facade（不允許 null）
     * @param externalService  對外 {@link ExternalIntegrationService} facade
     *                         （不允許 null；plugin 端必須建立合適的 impl 並傳入）
     * @param readyCheck       當前 lifecycle 是否 ready 的 callback
     * @param onReload         reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
 */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   ExternalIntegrationService externalService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            externalService, BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(), readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（9 參數簽章，推薦使用）。
     *
     * @param version          plugin 版本字串
     * @param platform         偵測到的平台
     * @param capability       對應的 capability profile（不允許 null；請用
     *                         {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param worldService     對外 {@link WorldService} facade（不允許 null）
     * @param guiService       對外 {@link GuiService} facade（不允許 null）
     * @param externalService  對外 {@link ExternalIntegrationService} facade
     *                         （不允許 null；plugin 端必須建立合適的 impl 並傳入）
     * @param bedrockService   對外 {@link BedrockService} facade（不允許 null；
     *                         plugin 端必須建立合適的 impl 並傳入）
     * @param readyCheck       當前 lifecycle 是否 ready 的 callback
     * @param onReload         reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   ExternalIntegrationService externalService,
                                   BedrockService bedrockService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            externalService, bedrockService, CommandCatalog.forUnavailable(),
            readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（10 參數簽章，推薦使用）。
     *
     * <p>與 9 參數版本的差異：額外接受對外 {@link CommandCatalog}
     * facade，讓指令目錄查詢可透過 canonical lookup 取得；
     * 既有 9 參數版本以 unavailable 實作填補（發布一律回
     * {@code REJECTED}、快照為空）。</p>
     *
     * @param version          plugin 版本字串
     * @param platform         偵測到的平台
     * @param capability       對應的 capability profile（不允許 null；請用
     *                         {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param worldService     對外 {@link WorldService} facade（不允許 null）
     * @param guiService       對外 {@link GuiService} facade（不允許 null）
     * @param externalService  對外 {@link ExternalIntegrationService} facade
     *                         （不允許 null；plugin 端必須建立合適的 impl 並傳入）
     * @param bedrockService   對外 {@link BedrockService} facade（不允許 null；
     *                         plugin 端必須建立合適的 impl 並傳入）
     * @param commandCatalog   對外 {@link CommandCatalog} facade（不允許 null；
     *                         plugin 端必須以 {@link CommandCatalog#forProduction()}
     *                         建立並傳入）
     * @param readyCheck       當前 lifecycle 是否 ready 的 callback
     * @param onReload         reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since 1.3.0
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   ExternalIntegrationService externalService,
                                   BedrockService bedrockService,
                                   CommandCatalog commandCatalog,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            externalService, bedrockService, commandCatalog, readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（11 參數簽章，推薦使用）。
     *
     * <p>與 10 參數版本的差異：額外接受對外 {@link DisplayService}
     * facade，讓每玩家顯示可透過 canonical lookup 取得；
     * 既有 10 參數版本以 {@code NOT_READY} unavailable facade 填補。</p>
     *
     * @param version          plugin 版本字串
     * @param platform         偵測到的平台
     * @param capability       對應的 capability profile（不允許 null；請用
     *                         {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param worldService     對外 {@link WorldService} facade（不允許 null）
     * @param guiService       對外 {@link GuiService} facade（不允許 null）
     * @param externalService  對外 {@link ExternalIntegrationService} facade
     *                         （不允許 null；plugin 端必須建立合適的 impl 並傳入）
     * @param bedrockService   對外 {@link BedrockService} facade（不允許 null；
     *                         plugin 端必須建立合適的 impl 並傳入）
     * @param commandCatalog   對外 {@link CommandCatalog} facade（不允許 null；
     *                         plugin 端必須以 {@link CommandCatalog#forProduction()}
     *                         建立並傳入）
     * @param displayService   對外 {@link DisplayService} facade（不允許 null；
     *                         plugin 端必須以 {@link DisplayService#forProduction}
     *                         建立並傳入）
     * @param readyCheck       當前 lifecycle 是否 ready 的 callback
     * @param onReload         reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since 1.4.0
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   ExternalIntegrationService externalService,
                                   BedrockService bedrockService,
                                   CommandCatalog commandCatalog,
                                   DisplayService displayService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
            displayService, externalService, bedrockService, commandCatalog,
            readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（6 參數舊版簽章；保留以相容既有內部呼叫 — 例如尚未擁有
     * {@link WorldService} 的測試 seam）。
     *
     * <p>本方法會以 {@code NOT_READY} unavailable facade 作為 {@code worldService} —
     * 這代表舊 caller 無法透過此 facade 取得實際 world 操作；對於完整 production，
     * 請改用 7 參數版本。</p>
     *
     * @deprecated 推薦改用
     *     {@link #ready(String, Platform, PlatformCapability, WorldService, GuiService, BooleanSupplier, Runnable)}，
     *     此方法將於 v1.0 移除。
 */
    @Deprecated
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(
            version, platform, capability, worldService,
            GuiService.forUnavailable(GuiErrorCode.NOT_READY),
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY),
            BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(), readyCheck, onReload
        );
    }

    /**
     * 已啟用狀態的 instance（5 參數舊版簽章；保留以相容既有內部呼叫 — 例如尚未擁有
     * {@link WorldService} 的測試 seam）。
     *
     * <p>本方法會以 {@code NOT_READY} unavailable facade 作為 {@code worldService} —
     * 這代表舊 caller 無法透過此 facade 取得實際 world 操作；對於完整 production，
     * 請改用 7 參數版本。</p>
     *
     * @deprecated 推薦改用 7 參數版本。
     */
    @Deprecated
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(
            version, platform, capability,
            new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY),
            GuiService.forUnavailable(GuiErrorCode.NOT_READY),
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY),
            BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(), readyCheck, onReload
        );
    }

    /**
     * 已啟用狀態的 instance（4 參數舊版簽章；為相容既有內部呼叫而保留）。
     *
     * @deprecated 推薦改用 7 參數版本。
     */
    @Deprecated
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(
            version, platform,
            PlatformCapability.forPlatform(platform),
            new WorldServiceUnavailableImpl(WorldErrorCode.NOT_READY),
            GuiService.forUnavailable(GuiErrorCode.NOT_READY),
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY),
            ExternalIntegrationService.forUnavailable(ExternalIntegrationService.NOT_READY),
            BedrockService.forUnavailable(BedrockService.NOT_READY),
            CommandCatalog.forUnavailable(), readyCheck, onReload
        );
    }

    /**
     * 對外版本字串（語意與 plugin.yml 一致）。
     */
    public String getVersion() {
        return version;
    }

    /**
     * 對外平台資訊。
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * 對外平台 capability profile。
     *
     * <p>後續插件應優先讀此欄位而非反射 classpath；
     * 若 facade 為未啟用狀態，回傳的是 {@link Platform#UNKNOWN} 對應的全 false capability。</p>
     *
     * @return 永遠不為 null 的 {@link PlatformCapability}
 */
    public PlatformCapability getPlatformCapability() {
        return capability;
    }

    /**
     * 取得對外 {@link WorldService} facade（canonical public API）。
     *
     * <p>永不為 null：</p>
     * <ul>
     *   <li>未啟用時回傳 {@code NOT_READY} unavailable facade（每次操作回
     *       {@code REJECTED + ACELIB-WORLD-001}）</li>
     *   <li>已啟用且 plugin 尚未 disable 時回傳實際 {@code WorldServiceImpl}</li>
     *   <li>已 disable 時回傳 {@code SHUTDOWN} unavailable facade（每次操作回
     *       {@code REJECTED + ACELIB-WORLD-002}）</li>
     * </ul>
     *
     * <p>後續插件可放心呼叫所有方法，無需 null 判斷。</p>
     *
     * @return 永不為 null 的 {@link WorldService}
 */
    public WorldService getWorldService() {
        return worldService;
    }

    /**
     * 取得對外 {@link GuiService} facade（canonical public API）。
     *
     * <p>永不為 null：</p>
     * <ul>
     *   <li>未啟用時回傳 {@code NOT_READY} unavailable facade（每次操作回
     *       {@code FAILED + ACELIB-GUI-001}）</li>
     *   <li>已啟用且 plugin 尚未 disable 時回傳實際 {@code GuiServiceImpl}</li>
     *   <li>已 disable 時回傳 {@code SHUTDOWN} unavailable facade（每次操作回
     *       {@code FAILED + ACELIB-GUI-002}）</li>
     * </ul>
     *
     * <p>後續插件可放心呼叫所有方法，無需 null 判斷。</p>
     *
     * @return 永不為 null 的 {@link GuiService}
 */
    public GuiService getGuiService() {
        return guiService;
    }

    /**
     * 取得對外 {@link DisplayService} facade（canonical public API）。
     *
     * <p>永不為 null：</p>
     * <ul>
     *   <li>未啟用時回傳 {@code NOT_READY} unavailable facade（每次操作回
     *       {@code FAILED + ACELIB-DISP-001}）</li>
     *   <li>已啟用且 plugin 尚未 disable 時回傳實際 {@code DisplayServiceImpl}</li>
     *   <li>已 disable 時回傳 {@code SHUTDOWN} unavailable facade（每次操作回
     *       {@code FAILED + ACELIB-DISP-002}）</li>
     * </ul>
     *
     * <p>後續插件可放心呼叫所有方法，無需 null 判斷。</p>
     *
     * @return 永不為 null 的 {@link DisplayService}
     * @since 1.4.0
     */
    public DisplayService getDisplayService() {
        return displayService;
    }

    /**
 * 取得對外 {@link ExternalIntegrationService} facade（canonical public API）。
     *
     * <p>永不為 null：</p>
     * <ul>
     *   <li>未啟用時回傳 {@code NOT_READY} unavailable facade（查詢一律回
     *       {@code INIT_FAILED} 結果，模組狀態 {@code NOT_INITIALIZED}）</li>
     *   <li>已啟用且 plugin 尚未 disable 時回傳實際
     *       {@code ExternalIntegrationService} 實作</li>
     *   <li>已 disable 時回傳 {@code SHUTDOWN} unavailable facade（模組狀態
     *       {@code FAILED}）</li>
     * </ul>
     *
     * <p>後續插件可放心呼叫所有查詢方法，無需 null 判斷。</p>
     *
     * @return 永不為 null 的 {@link ExternalIntegrationService}
 */
    public ExternalIntegrationService getExternalIntegrationService() {
        return externalService;
    }

    /**
     * 取得對外 {@link BedrockService} facade（骨架）。
     */
    public BedrockService getBedrockService() {
        return bedrockService;
    }

    /**
     * 取得對外 {@link CommandCatalog} facade（canonical public API）。
     *
     * <p>永不為 null：</p>
     * <ul>
     *   <li>未啟用時回傳 unavailable 實作（{@code publish} 一律回
     *       {@code REJECTED + ACELIB-CMD-014}，{@code snapshot()} 回空清單，
     *       {@code unpublishAll} 為無害的 no-op）</li>
     *   <li>已啟用且 plugin 尚未 disable 時回傳實際目錄實例</li>
     *   <li>已 disable 時回傳已 {@code shutdown()} 的同一實例（發布一律回
     *       {@code REJECTED}，快照為空；透過舊參考仍不可再寫入）</li>
     * </ul>
     *
     * <p>後續插件可放心呼叫所有方法，無需 null 判斷。快照與 revision 為兩次
     * 獨立呼叫、不是原子配對：需要一致性判斷時，先讀 revision、再讀 snapshot、
     * 之後重讀 revision 檢查期間是否變動。</p>
     *
     * @return 永不為 null 的 {@link CommandCatalog}
     * @since 1.3.0
     */
    public CommandCatalog getCommandCatalog() {
        return commandCatalog;
    }

    /**
     * 當前 plugin 是否處於已啟用狀態。
     */
    public boolean isReady() {
        return readyCheck.getAsBoolean();
    }

    /**
     * 觸發 plugin 端的 reload 流程。
     *
     * <p>回傳型別為 {@code void}：此方法只觸發 reload callback；若 plugin 尚未
     * onEnable（例如 {@link #uninitialized()} instance），此方法為 no-op。</p>
     *
     * <p>Supported API（本 facade 與 {@link AceLibApi.AceLibProvider}）不提供
     * reload 成敗的 boolean 結果。可觀察的邊界如下：</p>
     * <ul>
     *   <li>{@code provider.api()} 回傳新 facade 只代表成功路徑已生效；若 reload
     *       rollback 至既有 binding，{@code api()} 可能維持舊 facade。</li>
     *   <li>{@link #isReady()} 只反映一般生命週期狀態（enable/disable），作為
     *       service 使用前的 guard；它不是 reload 成功/失敗的 outcome 指標。</li>
     * </ul>
     * <p>不要依賴任何 Internal class 來取得 reload 結果。</p>
     */
    public void reload() {
        onReload.run();
    }

    /**
     * 動態取得目前 {@link AceLibApi} 的正式 provider 契約。
     *
     * <p>下游 plugin 應經由 Bukkit/Paper {@code ServicesManager} 取得本 provider，
     * 而不是直接依賴 {@link AceLibPlugin} 或 static singleton：</p>
     * <pre>{@code
     * RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
     *     server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
     * AceLibApi api = registration == null ? null : registration.getProvider().api();
     * if (api != null && api.isReady()) {
     *     // 使用 api 提供的各 service
     * }
     * }</pre>
     *
     * <h2>Lifecycle 與 nullability</h2>
     * <ul>
     *   <li>plugin enable 後註冊、disable 時解除註冊；reload 不解除註冊。
     *       reload 成功 commit 時 {@link #api()} 才會反映新的 facade；
     *       若 reload rollback 至既有 binding，{@link #api()} 可能維持舊 facade。</li>
     *   <li>{@link #api()} 本身永不回傳 null；但回傳物件的 {@link AceLibApi#isReady()}
     *       可能為 false — 例如 disable 後仍持有 provider 的呼叫端，會取得
     *       shutdown facade。呼叫端應檢查 {@code api().isReady()} 再使用服務。</li>
     *   <li>registration 缺失時（例如 plugin 尚未 enable），
     *       {@code getRegistration(...)} 回傳 null，呼叫端必須自行處理。</li>
     * </ul>
     *
     * <h2>Thread context</h2>
     * <p>provider 實作內部以 {@code volatile} 快照目前 facade；
     * {@link #api()} 可在任何 thread 安全呼叫，且回傳的 {@link AceLibApi} 本身不可變。
     * Paper 與 Folia 環境行為一致。</p>
     */
    public interface AceLibProvider {

        /**
         * 目前對外 facade。
         *
         * @return 永不為 null 的 {@link AceLibApi}；plugin 尚未 enable 時不會有
         *         provider 可取得，disable 後則回傳 shutdown facade
         *         （{@link AceLibApi#isReady()} 為 false）
         */
        AceLibApi api();
    }
}
