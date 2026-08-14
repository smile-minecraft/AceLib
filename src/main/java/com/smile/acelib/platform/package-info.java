/**
 * 平台偵測與能力描述（Supported）。
 *
 * <h2>套件內容</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.platform.Platform} — 執行平台列舉
 *       （FOLIA / PAPER / UNKNOWN）</li>
 *   <li>{@link com.smile.acelib.platform.PlatformCapability} — 平台能力 profile
 *       （immutable record），描述該平台支援哪些 scheduler / Bukkit API</li>
 *   <li>{@link com.smile.acelib.platform.PlatformDetector} — 以 classpath
 *       reflection 判定目前平台並推導 capability</li>
 * </ul>
 *
 * <h2>取得方式</h2>
 * <p>一般下游插件不需直接建立 {@link com.smile.acelib.platform.PlatformDetector}；
 * 改從 {@link com.smile.acelib.AceLibApi#getPlatform()} 與
 * {@link com.smile.acelib.AceLibApi#getPlatformCapability()} 讀取目前平台與
 * 能力。需要獨立偵測（測試、工具類）時才建立 {@code PlatformDetector}。</p>
 *
 * <h2>能力分流語意</h2>
 * <ul>
 *   <li>{@code regionScheduling} 為 true 表示可走 Folia regionized 排程
 *       （RegionizedServer / EntityScheduler / RegionScheduler）</li>
 *   <li>{@code globalScheduler} 為 true 表示可走 Paper 全域 scheduler
 *       （BukkitScheduler / GlobalRegionScheduler on Folia）</li>
 *   <li>{@link com.smile.acelib.platform.Platform#UNKNOWN} 對應全 false capability，
 *       保守降級，不誤觸發受限能力</li>
 * </ul>
 *
 * <h2>執行緒安全與 side effects</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.platform.Platform} 與
 *       {@link com.smile.acelib.platform.PlatformCapability} 為不可變 value types，
 *       可在任何 thread 安全使用。</li>
 *   <li>{@link com.smile.acelib.platform.PlatformDetector} 持有 final classloader
 *       reference（無 mutable 欄位），可在任何 thread 安全呼叫；但它會讀取
 *       classpath / Bukkit 全域狀態（如 {@code Bukkit.getBukkitVersion()}）與
 *       system property，並輸出 fine-level debug log（有 side effect），
 *       因此不是嚴格意義的純函式；回傳值依賴建構時注入的 classloader 與
 *       呼叫當下的環境。</li>
 * </ul>
 *
 * <h2>相容性承諾</h2>
 * <p>本套件為 v1 對外契約的一部分；public 型別簽章與語意在 v1 穩定版本內
 * 不破壞性變更。列舉常數順序（序列化相容）凍結，不得更動。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.platform;
