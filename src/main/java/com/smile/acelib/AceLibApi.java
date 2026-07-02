package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 對外 API facade。
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>不可變（immutable）：一旦建立，版本與平台欄位不可變動</li>
 *   <li>{@link #isReady()} 透過 {@link BooleanSupplier} 反向查詢當前生命週期狀態</li>
 *   <li>{@link #reload()} 委派給 caller 提供的 callback，避免 facade 直接持有 plugin reference</li>
 * </ul>
 *
 * <p>對外暴露三種狀態的 instance：</p>
 * <ul>
 *   <li>未啟用（uninitialized）— 由 {@link #uninitialized()} 建立</li>
 *   <li>已啟用（ready）— 由
 *       {@link #ready(String, Platform, PlatformCapability, BooleanSupplier, Runnable)} 建立</li>
 * </ul>
 *
 * @see PlatformCapability
 * @since Phase 0；{@link #getPlatformCapability()} 自 Phase 1 加入（Plan §六）。
 */
public final class AceLibApi {

    private final String version;
    private final Platform platform;
    private final PlatformCapability capability;
    private final BooleanSupplier readyCheck;
    private final Runnable onReload;

    private AceLibApi(String version,
                      Platform platform,
                      PlatformCapability capability,
                      BooleanSupplier readyCheck,
                      Runnable onReload) {
        this.version = Objects.requireNonNull(version, "version");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
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
     *   <li>isReady() = false</li>
     *   <li>reload() = no-op</li>
     * </ul>
     */
    public static AceLibApi uninitialized() {
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            PlatformCapability.forPlatform(Platform.UNKNOWN),
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 已啟用狀態的 instance（canonical 簽章，Phase 1+ 推薦使用）。
     *
     * @param version     plugin 版本字串
     * @param platform    偵測到的平台
     * @param capability  對應的 capability profile（不允許 null；請用
     *                    {@link PlatformCapability#forPlatform(Platform)} 推導）
     * @param readyCheck  當前 lifecycle 是否 ready 的 callback
     * @param onReload    reload 觸發時執行的 callback
     * @return 不可變的 {@link AceLibApi}
     * @throws NullPointerException 任何參數為 null
     * @since Phase 1 (Plan §六)
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   PlatformCapability capability,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, capability, readyCheck, onReload);
    }

    /**
     * 已啟用狀態的 instance（舊版 4 參數簽章；為相容既有內部呼叫而保留）。
     *
     * <p>本方法會自動以 {@link PlatformCapability#forPlatform(Platform)} 從
     * {@code platform} 推導 capability。若 caller 有更精確的 capability（例如依
     * 實際 classpath 探測降級），請改用 5 參數版本。</p>
     *
     * @deprecated 推薦改用
     *     {@link #ready(String, Platform, PlatformCapability, BooleanSupplier, Runnable)}，
     *     此方法將於 v1.0 移除。
     * @since Phase 0
     */
    @Deprecated
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(
            version,
            platform,
            PlatformCapability.forPlatform(platform),
            readyCheck,
            onReload
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
