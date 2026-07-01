package com.smile.acelib;

import com.smile.acelib.platform.Platform;
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
 * <h2>Phase 0 範圍</h2>
 * 本類別在 Phase 0 僅建立最小骨架：版本常數、平台偵測、API facade。
 * 後續 Phase 1~14 會依序加入 lifecycle manager、scheduler wrapper、
 * event bus、metrics 等模組；所有模組都透過 {@link #getApi()} 取得 facade。
 *
 * <h2>執行緒安全</h2>
 * 狀態欄位使用 {@code volatile} 與 {@code synchronized} 保護；
 * Folia 的 regionized 環境下 reload 通常由 main thread 觸發，但仍須具備 thread-safe 行為。
 */
public class AceLibPlugin extends JavaPlugin {

    /** Plugin 標籤，用於 fallback logger。 */
    private static final String LOG_NAME = "AceLib";

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
     * 內部委派給 {@link #onEnable(Server, PlatformDetector)}，
     * 保持單一初始化路徑，方便測試。
     *
     * 注意：此方法刻意標記為 {@code non-final}，允許測試子類別覆寫以模擬
     * 「Bukkit 尚未呼叫 onEnable」的初始狀態。
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
     * 此方法同時被 {@link #onEnable()} 與單元測試呼叫；保持冪等（重複呼叫不爆）。
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
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            detector.detect(),
            () -> ready,
            () -> reload()
        );
        this.ready = true;
        logInfo("AceLib {0} enabled on {1}", api.getVersion(), api.getPlatform().getDisplayName());
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
     * 重新偵測平台並發佈新 API 實例。
     *
     * @return 若 plugin 已啟用且 reload 成功則回傳 true；未啟用時回傳 false
     */
    public synchronized boolean reload() {
        if (!ready || platformDetector == null) {
            return false;
        }
        Platform reDetected = platformDetector.detect();
        this.api = AceLibApi.ready(
            AceLibVersion.VERSION,
            reDetected,
            () -> ready,
            () -> reload()
        );
        logInfo("AceLib reloaded on {0}", reDetected.getDisplayName());
        return true;
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