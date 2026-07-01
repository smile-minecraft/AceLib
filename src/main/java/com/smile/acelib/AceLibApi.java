package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 對外 API facade。
 *
 * 設計原則：
 * <ul>
 *   <li>不可變（immutable）：一旦建立，版本與平台欄位不可變動</li>
 *   <li>{@link #isReady()} 透過 {@link BooleanSupplier} 反向查詢當前生命週期狀態</li>
 *   <li>{@link #reload()} 委派給 caller 提供的 callback，避免 facade 直接持有 plugin reference</li>
 * </ul>
 *
 * 對外暴露三種狀態的 instance：
 * <ul>
 *   <li>未啟用（uninitialized）— 由 {@link #uninitialized()} 建立</li>
 *   <li>已啟用（ready）— 由 {@link #ready(String, Platform, BooleanSupplier, Runnable)} 建立</li>
 * </ul>
 */
public final class AceLibApi {

    private final String version;
    private final Platform platform;
    private final BooleanSupplier readyCheck;
    private final Runnable onReload;

    private AceLibApi(String version, Platform platform, BooleanSupplier readyCheck, Runnable onReload) {
        this.version = Objects.requireNonNull(version, "version");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.readyCheck = Objects.requireNonNull(readyCheck, "readyCheck");
        this.onReload = Objects.requireNonNull(onReload, "onReload");
    }

    /**
     * 未啟用狀態的預設 instance。
     *
     * <ul>
     *   <li>version = {@link AceLibVersion#VERSION}</li>
     *   <li>platform = {@link Platform#UNKNOWN}</li>
     *   <li>isReady() = false</li>
     *   <li>reload() = no-op</li>
     * </ul>
     */
    public static AceLibApi uninitialized() {
        return new AceLibApi(
            AceLibVersion.VERSION,
            Platform.UNKNOWN,
            () -> false,
            () -> { /* no-op */ }
        );
    }

    /**
     * 已啟用狀態的 instance。
     */
    public static AceLibApi ready(String version,
                                   Platform platform,
                                   BooleanSupplier readyCheck,
                                   Runnable onReload) {
        return new AceLibApi(version, platform, readyCheck, onReload);
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
     * 當前 plugin 是否處於已啟用狀態。
     */
    public boolean isReady() {
        return readyCheck.getAsBoolean();
    }

    /**
     * 觸發 plugin 端的 reload 流程。
     *
     * 委派給 {@link AceLibPlugin#reload()} 的 callback，因此若 plugin 尚未 onEnable，
     * 此方法為 no-op（不回傳值；如需明確失敗偵測請改用 {@link AceLibPlugin#reload()}）。
     */
    public void reload() {
        onReload.run();
    }
}