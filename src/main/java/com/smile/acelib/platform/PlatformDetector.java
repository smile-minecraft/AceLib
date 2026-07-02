package com.smile.acelib.platform;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;

/**
 * 透過 classpath reflection 判定執行平台（Folia / Paper / Unknown）。
 *
 * <p>判定順序：</p>
 * <ol>
 *   <li>若能找到 {@code io.papermc.paper.threadedregions.RegionizedServer}，視為 Folia</li>
 *   <li>否則若能找到 {@code org.bukkit.Bukkit}，視為 Paper / CraftBukkit 相容</li>
 *   <li>否則視為 Unknown</li>
 * </ol>
 *
 * <p>注意：本類別不依賴 Bukkit 靜態呼叫，可透過測試 seam 在純 classpath 隔離的
 * 單元測試中運作；版本偵測方法（{@link #detectMinecraftVersion(Server)}、
 * {@link #detectJavaVersion()}）則可以選擇性注入 {@link Server}。</p>
 *
 * @see Platform
 * @see PlatformCapability
 * @since Phase 0；{@link #isFoliaClasspathAvailable()}、
 *      {@link #isPaperClasspathAvailable()}、
 *      {@link #detectMinecraftVersion(Server)}、
 *      {@link #detectJavaVersion()}、
 *      {@link #detectCapability(Platform)} 自 Phase 1 加入（Plan §六）。
 */
public final class PlatformDetector {

    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";
    private static final String PAPER_MARKER = "org.bukkit.Bukkit";

    /** 版本偵測失敗時的 fallback 字串。 */
    private static final String VERSION_UNKNOWN = "unknown";

    /** 偵測方法內部使用的 fallback logger。 */
    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final ClassLoader classLoader;

    /**
     * 建構子。
     *
     * @param classLoader 用於 classpath 探測的 classloader；不可為 null
     * @throws IllegalArgumentException 若 {@code classLoader} 為 null
     */
    public PlatformDetector(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader must not be null");
        }
        this.classLoader = classLoader;
    }

    /**
     * 執行偵測並回傳對應 {@link Platform}。
     */
    public Platform detect() {
        if (isPresent(FOLIA_MARKER)) {
            return Platform.FOLIA;
        }
        if (isPresent(PAPER_MARKER)) {
            return Platform.PAPER;
        }
        return Platform.UNKNOWN;
    }

    /**
     * 取得人類可讀的平台名稱（如 "Folia" / "Paper" / "Unknown"）。
     * 為 null-safe 的便利方法。
     */
    public String getDisplayName() {
        Platform p = detect();
        return p == null ? Platform.UNKNOWN.getDisplayName() : p.getDisplayName();
    }

    // ---------------------------------------------------------------------
    // Phase 1 新增方法（Plan §六）
    // ---------------------------------------------------------------------

    /**
     * 顯式判定目前 classloader 是否包含 Folia marker class。
     *
     * <p>與 {@link #detect()} 的差異：本方法允許外部 caller 在不跑完整 {@code detect()}
     * 流程的情況下單獨查詢 Folia 可用性；{@link #detect()} 隱含 PAPER fallback。</p>
     *
     * @return 若能找到 {@code io.papermc.paper.threadedregions.RegionizedServer} 則 {@code true}
     * @since Phase 1 (Plan §六)
     */
    public boolean isFoliaClasspathAvailable() {
        return isPresent(FOLIA_MARKER);
    }

    /**
     * 顯式判定目前 classloader 是否包含 Paper / Bukkit marker class。
     *
     * <p>對 Folia 環境同樣會回傳 {@code true}（Folia 內含 Bukkit API）。</p>
     *
     * @return 若能找到 {@code org.bukkit.Bukkit} 則 {@code true}
     * @since Phase 1 (Plan §六)
     */
    public boolean isPaperClasspathAvailable() {
        return isPresent(PAPER_MARKER);
    }

    /**
     * 從 {@link Server} 取得 Minecraft 版本字串。
     *
     * <p>優先順序：</p>
     * <ol>
     *   <li>{@link Server#getMinecraftVersion()}（Paper 26.1+ 提供；null-safe）</li>
     *   <li>{@link org.bukkit.Bukkit#getBukkitVersion()}（較舊 API）</li>
     *   <li>{@code "unknown"} fallback，並輸出一行 debug log</li>
     * </ol>
     *
     * <p>任何環節 null 都視為失敗並回傳 {@code "unknown"}，不丟例外。</p>
     *
     * @param server 當前 server，可為 null；傳入 null 時直接回傳 {@code "unknown"}
     * @return 找到的 Minecraft 版本字串；失敗時回傳 {@code "unknown"}（永遠不為 null）
     * @since Phase 1 (Plan §六)
     */
    public String detectMinecraftVersion(Server server) {
        if (server == null) {
            LOGGER.log(Level.FINE,
                "detectMinecraftVersion called with null Server; returning {0}",
                VERSION_UNKNOWN);
            return VERSION_UNKNOWN;
        }
        try {
            // 路徑 1：Paper 26.1+ 提供的方法
            String viaServer = server.getMinecraftVersion();
            if (viaServer != null && !viaServer.isBlank()) {
                return viaServer;
            }
        } catch (Throwable t) {
            // 較舊 API 無此方法（NoSuchMethodError）或 Mock 環境未實作
            // — 落入下一段 fallback，不丟例外
            LOGGER.log(Level.FINE, "Server.getMinecraftVersion() unavailable: {0}",
                t.getMessage());
        }
        try {
            // 路徑 2：透過 Bukkit 靜態呼叫（測試環境下可能沒初始化 Bukkit）
            String viaBukkit = org.bukkit.Bukkit.getBukkitVersion();
            if (viaBukkit != null && !viaBukkit.isBlank()) {
                return viaBukkit;
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Bukkit.getBukkitVersion() unavailable: {0}",
                t.getMessage());
        }
        LOGGER.log(Level.FINE,
            "Minecraft version detection failed; returning {0}",
            VERSION_UNKNOWN);
        return VERSION_UNKNOWN;
    }

    /**
     * 從 {@link System#getProperty(String) java.version} 取得 Java 版本字串。
     *
     * <p>失敗時回傳 {@code "unknown"} 並輸出一行 fine log，不丟例外。</p>
     *
     * @return 找到的 Java 版本字串；失敗時回傳 {@code "unknown"}（永遠不為 null）
     * @since Phase 1 (Plan §六)
     */
    public String detectJavaVersion() {
        try {
            String v = System.getProperty("java.version");
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (Throwable t) {
            // SecurityException 在受限沙箱環境可能發生；保守視為失敗
            LOGGER.log(Level.FINE, "System.getProperty(java.version) unavailable: {0}",
                t.getMessage());
        }
        return VERSION_UNKNOWN;
    }

    /**
     * 依指定的 {@link Platform} 回傳對應 capability profile。
     *
     * <p>本方法目前直接委派給 {@link PlatformCapability#forPlatform(Platform)}；
     * 保留此 indirection 是為了日後可在不破壞 API 的前提下，依「實際 classpath 探測」
     * 微調 capability（例如偵測到 Bukkit 但缺少關鍵 interface 時降級）。</p>
     *
     * @param platform 偵測結果；不可為 null
     * @return 對應的 {@link PlatformCapability}
     * @throws NullPointerException 當 {@code platform} 為 null
     * @since Phase 1 (Plan §六)
     */
    public PlatformCapability detectCapability(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        // 委派給 PlatformCapability factory；後續若需依 classpath 微調，在此擴充
        return PlatformCapability.forPlatform(platform);
    }

    /**
     * Classpath 反射探測的內部邏輯。
     *
     * <p>保守處理三類例外：</p>
     * <ul>
     *   <li>{@link ClassNotFoundException} — class 真的不在 classpath</li>
     *   <li>{@link IllegalStateException} — classloader 內部狀態異常
     *       （MockBukkit 的 {@code MockBukkitConfiguredPluginClassLoader} 會在此情境拋）</li>
     *   <li>{@link SecurityException} — 沙箱環境拒絕 classloader 探測</li>
     * </ul>
     */
    private boolean isPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, classLoader);
            return true;
        } catch (ClassNotFoundException | IllegalStateException e) {
            // ClassNotFoundException: class 真的不在 classpath
            // IllegalStateException: classloader 內部狀態異常（例如 MockBukkit
            // 的 MockBukkitConfiguredPluginClassLoader 在找不到目標 class 卻
            // 已初始化時拋出 "No jar file selected"）
            // 兩者皆保守視為「不在」，符合本類別在純單元測試中運作的契約。
            return false;
        } catch (SecurityException e) {
            // 沙箱環境拒絕 classloader 探測：保守視為「不在」
            return false;
        }
    }
}
