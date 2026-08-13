package com.smile.acelib;

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
 * 對外 API facade。
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
 *       {@link #getWorldService()} 回傳 {@code NOT_READY} facade</li>
 *   <li>已啟用（ready）— 由
 *       {@link #ready(String, Platform, PlatformCapability, WorldService, BooleanSupplier, Runnable)}
 *       建立</li>
 * </ul>
 *
 * @see PlatformCapability
 * @see WorldService
 * @since Phase 0；{@link #getPlatformCapability()} 自 Phase 1 加入；{@link #getWorldService()} 自 Phase 10 加入（Plan §十五 §二十一）；{@link #getGuiService()} 自 Phase 11 加入（Plan §十六 §二十一）
 */
public final class AceLibApi {

    private final String version;
    private final Platform platform;
    private final PlatformCapability capability;
    private final WorldService worldService;
    private final GuiService guiService;
    private final BooleanSupplier readyCheck;
    private final Runnable onReload;

    private AceLibApi(String version,
                      Platform platform,
                      PlatformCapability capability,
                      WorldService worldService,
                      GuiService guiService,
                      BooleanSupplier readyCheck,
                      Runnable onReload) {
        this.version = Objects.requireNonNull(version, "version");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.worldService = Objects.requireNonNull(worldService, "worldService");
        this.guiService = Objects.requireNonNull(guiService, "guiService");
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
     * @since Phase 10 (Plan §二十一)
     */
    public static AceLibApi shutDown(WorldService worldService) {
        Objects.requireNonNull(worldService, "worldService");
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            worldService,
            GuiService.forUnavailable(GuiErrorCode.SHUTDOWN),
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 停用狀態的 facade（攜帶既有 worldService + guiService）。
     *
     * <p>Phase 11 起的 canonical 重載：保留兩個 service 的不可變 reference，
     * 確保既有的診斷報告查詢可在 plugin disable 後仍能看到一致的 service 物件。</p>
     *
     * @param worldService 已 shutdown 的 worldService；不可為 null
     * @param guiService   已 shutdown 的 guiService；不可為 null
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since Phase 11 (Plan §十六 §二十一)
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
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 已啟用狀態的 instance（canonical 7 參數簽章，Phase 11+ 推薦使用）。
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
     * @since Phase 11 (Plan §十六 §二十一)
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   WorldService worldService,
                                   GuiService guiService,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, worldService, guiService,
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
     * @since Phase 10
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
            readyCheck, onReload
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
     * @since Phase 0
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
            readyCheck, onReload
        );
    }

    /**
     * 已啟用狀態的 instance（4 參數舊版簽章；為相容既有內部呼叫而保留）。
     *
     * @deprecated 推薦改用 7 參數版本。
     * @since Phase 0
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
            readyCheck, onReload
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
     * @since Phase 1 (Plan §六)
     */
    public PlatformCapability getPlatformCapability() {
        return capability;
    }

    /**
     * 取得對外 {@link WorldService} facade（Plan §十五 Phase 10 canonical public API）。
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
     * @since Phase 10 (Plan §十五 §二十一)
     */
    public WorldService getWorldService() {
        return worldService;
    }

    /**
     * 取得對外 {@link GuiService} facade（Plan §十六 Phase 11 canonical public API）。
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
     * @since Phase 11 (Plan §十六 §二十一)
     */
    public GuiService getGuiService() {
        return guiService;
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
     * <p>委派給 {@link AceLibPlugin#reload()} 的 callback；因此若 plugin 尚未 onEnable，
     * 此方法為 no-op（不回傳值；如需明確失敗偵測請改用 {@link AceLibPlugin#reload()}）。</p>
     */
    public void reload() {
        onReload.run();
    }
}
