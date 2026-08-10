package com.smile.acelib.platform;

/**
 * 執行平台列舉。
 *
 * <ul>
 *   <li>{@link #FOLIA} — Folia（Paper 的 regionized 分支）</li>
 *   <li>{@link #PAPER} — Paper / Bukkit 相容伺服器</li>
 *   <li>{@link #UNKNOWN} — 無法判定（classpath 完全沒有 Bukkit API）</li>
 * </ul>
 *
 * <p>Phase 0 僅需正確分類；Phase 1（§六）起，每個 enum value 攜帶對應的
 * {@link PlatformCapability} profile，方便後續插件依平台差異選擇正確路徑。</p>
 *
 * <h2>序列化相容</h2>
 * 列舉常數順序（FOLIA / PAPER / UNKNOWN）凍結，不得更動。
 *
 * @see PlatformCapability
 * @see PlatformDetector
 */
public enum Platform {

    FOLIA("Folia"),
    PAPER("Paper"),
    UNKNOWN("Unknown");

    private final String displayName;
    private final PlatformCapability capability;

    Platform(String displayName) {
        this.displayName = displayName;
        // 於 enum 建構期計算 capability，確保每個 enum value 都有確定的 profile
        //
        // 注意：不可在此呼叫 PlatformCapability.forPlatform(this)。
        // Java enum 靜態初始化是依宣告順序呼叫 constructor：建構 PAPER 時
        // `Platform.PAPER` 靜態欄位本身尚未被賦值（仍是 null），factory
        // 內的 `p == Platform.PAPER` reference 比較會回 false，導致
        // PAPER 落入「全 false」保守降級，capability profile 與實際不符。
        // 改以 `name()`（已在 constructor 內可安全讀取的字串）分流，
        // 即可完全規避此 enum 初始化順序問題，語意與 factory 等價。
        this.capability = switch (name()) {
            case "FOLIA" -> new PlatformCapability(true, true, true, true);
            case "PAPER" -> new PlatformCapability(false, true, true, false);
            case "UNKNOWN" -> new PlatformCapability(false, false, false, false);
            // 新增列舉值時保守降級（與 PlatformCapability.forPlatform 預設行為一致）
            default -> new PlatformCapability(false, false, false, false);
        };
    }

    /**
     * 對外顯示名稱（log、訊息、status 命令使用）。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 取得對應的 capability profile（immutable record）。
     *
     * <p>後續插件可改讀 {@link #getCapabilityProfile()} 而非反射 classpath，
     * 依平台能力決定啟用哪些功能。Phase 1+ 的 scheduler / event 模組會用到。</p>
     *
     * @return 此平台對應的 {@link PlatformCapability}，永遠不為 null
     * @since Phase 1 (Plan §六)
     */
    public PlatformCapability getCapabilityProfile() {
        return capability;
    }

    /**
     * 是否支援 Folia regionized 排程（RegionizedServer / EntityScheduler / RegionScheduler）。
     *
     * @return {@code true} 僅在 {@link #FOLIA}，其餘 false
     * @since Phase 1 (Plan §六)
     */
    public boolean supportsRegionScheduling() {
        return capability.regionScheduling();
    }

    /**
     * 是否支援 Paper 全域 scheduler（BukkitScheduler / GlobalRegionScheduler on Folia）。
     *
     * @return {@code true} 對 {@link #FOLIA} 與 {@link #PAPER}；{@link #UNKNOWN} 一律 false
     * @since Phase 1 (Plan §六)
     */
    public boolean supportsGlobalScheduler() {
        return capability.globalScheduler();
    }
}
