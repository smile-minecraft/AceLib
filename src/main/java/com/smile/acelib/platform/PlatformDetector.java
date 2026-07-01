package com.smile.acelib.platform;

/**
 * 透過 classpath reflection 判定執行平台（Folia / Paper / Unknown）。
 *
 * 判定順序：
 * <ol>
 *   <li>若能找到 {@code io.papermc.paper.threadedregions.RegionizedServer}，視為 Folia</li>
 *   <li>否則若能找到 {@code org.bukkit.Bukkit}，視為 Paper / CraftBukkit 相容</li>
 *   <li>否則視為 Unknown</li>
 * </ol>
 *
 * 注意：本類別不依賴 Bukkit API，因此可在純 classpath 隔離的單元測試中運作。
 */
public final class PlatformDetector {

    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";
    private static final String PAPER_MARKER = "org.bukkit.Bukkit";

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