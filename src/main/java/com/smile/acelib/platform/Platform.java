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
 * Phase 0 僅需正確分類；後續 phase 會依此分流排程與事件 API。
 */
public enum Platform {

    FOLIA("Folia"),
    PAPER("Paper"),
    UNKNOWN("Unknown");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 對外顯示名稱（log、訊息、status 命令使用）。
     */
    public String getDisplayName() {
        return displayName;
    }
}