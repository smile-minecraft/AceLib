package com.smile.acelib;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AceLib 主類別 — Folia-first 基礎函式庫插件。
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>{@link #onEnable()} — 由 Bukkit/Paper/Folia 伺服器呼叫；內部委派給
 *       {@link #onEnable(Server, PlatformDetector)} 方便單元測試</li>
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
 * <h2>執行緒安全</h2>
 * 狀態欄位使用 {@code volatile} 與 {@code synchronized} 保護；
 * Folia 的 regionized 環境下 reload 通常由 main thread 觸發，但仍須具備 thread-safe 行為。
 */
public class AceLibPlugin extends JavaPlugin {

    /** Plugin 標籤，用於 fallback logger。 */
    private static final String LOG_NAME = "AceLib";

    /** Plan §七 §三 (6) 規範的平台偵測錯誤代碼（未知環境警告）。 */
    private static final String PLATFORM_UNKNOWN_ERROR_CODE = "ACELIB-PLAT-004";

    private volatile boolean ready = false;
    private volatile Server server;
    private volatile PlatformDetector platformDetector;
    private volatile AceLibApi api;

    public AceLibPlugin() {
        // 預先放一個 uninitialized facade，避免 getApi() 在 onEnable 前丟例外
        this.api = AceLibApi.uninitialized();
    }

    // ---------------------------------------------------------------------
    // Bukkit 生命週期
    // ---------------------------------------------------------------------

    /**
     * Bukkit/Paper/Folia 伺服器呼叫的進入點。
     *
     * <p>內部委派給 {@link #onEnable(Server, PlatformDetector)}，
     * 保持單一初始化路徑，方便測試。</p>
     *
     * <p>注意：此方法刻意標記為 {@code non-final}，允許測試子類別覆寫以模擬
     * 「Bukkit 尚未呼叫 onEnable」的初始狀態。</p>
     */
    @Override
    public void onEnable() {
        Server s = getServer();
        PlatformDetector d = new PlatformDetector(getClass().getClassLoader());
        onEnable(s, d);
    }

    /**
     * 對外測試 seam：直接接收 Server 與 PlatformDetector，跳過 Bukkit 內部呼叫。
     *
     * <p>此方法同時被 {@link #onEnable()} 與單元測試呼叫；保持冪等（重複呼叫不爆）。</p>
     *
     * @param s           當前 server（測試情境下可為 mock）
     * @param detector    平台偵測器（測試情境下可注入固定回傳）
     */
    public synchronized void onEnable(Server s, PlatformDetector detector) {
        if (ready) {
            logFine("AceLib.onEnable() called when already ready; idempotent skip.");
            return;
        }
        this.server = s;
        this.platformDetector = detector;

        // 1. 偵測平台（含失敗情境 logging）
        Platform detected = detector.detect();
        logPlatformStatus(detected, detector);

        // 2. 推導 capability profile
        PlatformCapability capability = detector.detectCapability(detected);

        // 3. 發佈 facade（5 參數版本，攜帶實際 capability）
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            detected,
            capability,
            () -> ready,
            () -> reload()
        );
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
        this.ready = false;
        this.server = null;
        this.platformDetector = null;
        this.api = AceLibApi.uninitialized();
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
     * 取得對外 API facade。在 onEnable 前後都可呼叫，永不回傳 null。
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
     * 重新偵測平台並發佈新 API 實例。
     *
     * @return 若 plugin 已啟用且 reload 成功則回傳 true；未啟用時回傳 false
     */
    public synchronized boolean reload() {
        if (!ready || platformDetector == null) {
            return false;
        }
        Platform reDetected = platformDetector.detect();
        PlatformCapability reCapability = platformDetector.detectCapability(reDetected);
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            reDetected,
            reCapability,
            () -> ready,
            () -> reload()
        );
        logInfo("AceLib reloaded on {0}", reDetected.getDisplayName());
        return true;
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
}
