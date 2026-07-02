package com.smile.acelib.platform;

import java.util.Objects;

/**
 * 平台能力描述（immutable record）。
 *
 * <p>用於讓後續插件明確知道目前執行環境支援哪些能力，而不必自己反射
 * classpath。本 record 與 {@link Platform} 解耦，使 {@link Platform}
 * 保持單純的列舉語意（僅代表偵測分類），把「該平台能做什麼」留給此 record。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #regionScheduling} — Folia regionized 排程能力</li>
 *   <li>{@link #globalScheduler} — Paper 全域 scheduler 能力</li>
 *   <li>{@link #bukkitApi} — Bukkit/Paper API 可用</li>
 *   <li>{@link #foliaThreadedRegionsApi} — Folia RegionizedServer /
 *       GlobalRegionScheduler API 可用</li>
 * </ul>
 *
 * <h2>保守策略</h2>
 * 對應 Plan §六 Phase 1 邊界條件「保守策略：部分 Paper 特徵 + 缺失能力時不會假設功能完整」，
 * {@link #forPlatform(Platform)} 對 {@link Platform#UNKNOWN} 一律回傳全 false，
 * 確保外部插件在不明環境下不會誤觸發受限能力。
 *
 * @see Platform
 * @see PlatformDetector
 * @since Phase 1 (Plan §六)
 */
public record PlatformCapability(
    boolean regionScheduling,
    boolean globalScheduler,
    boolean bukkitApi,
    boolean foliaThreadedRegionsApi
) {

    /**
     * 依指定平台回傳對應的 capability 集合。
     *
     * <ul>
     *   <li>{@link Platform#FOLIA}：四個欄位皆 true</li>
     *   <li>{@link Platform#PAPER}：{@code globalScheduler} 與 {@code bukkitApi} 為 true，
     *       region 排程為 false（Folia 獨有能力）</li>
     *   <li>{@link Platform#UNKNOWN}：四個欄位皆 false（保守降級）</li>
     * </ul>
     *
     * <p>注意：本方法實作為 {@code if-else} identity 比較而非
     * {@code switch} 表達式，因為 {@code Platform.values()} 在 enum 靜態初始化
     * 期間尚未填入 {@code $VALUES}，會丟 {@link NullPointerException}。
     * 維持 {@code if-else} 鏈可避免遞迴初始化錯誤。</p>
     *
     * @param p 偵測結果平台；不可為 null
     * @return 對應的 {@link PlatformCapability}
     * @throws NullPointerException 當 {@code p} 為 null
     */
    public static PlatformCapability forPlatform(Platform p) {
        Objects.requireNonNull(p, "platform");
        if (p == Platform.FOLIA) {
            return new PlatformCapability(true, true, true, true);
        }
        if (p == Platform.PAPER) {
            return new PlatformCapability(false, true, true, false);
        }
        // 任何其他列舉（含 UNKNOWN）皆保守降級
        return new PlatformCapability(false, false, false, false);
    }
}
