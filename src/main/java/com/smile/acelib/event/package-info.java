/**
 * 安全事件系統（Supported + SPI）。
 *
 * <p>提供 Folia-safe 的事件註冊 / 解除 / 追蹤，避免 reload / disable 後
 * listener 殘留，並統一處理 listener 例外與錯誤紀錄。</p>
 *
 * <h2>取得方式</h2>
 * <p>透過 {@link com.smile.acelib.event.AceLibEvents} 建立 registry：
 * 推薦 {@code AceLibEvents.create(AceLibPlugin)}（自動綁定 lifecycle）；
 * 或 {@code create(JavaPlugin, Platform, PlatformCapability)} 手動組合。
 * 亦可由 {@link com.smile.acelib.AceLibApi} 取得既有 registry。</p>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.event.AceLibEvents}（Supported）— facade + factory</li>
 *   <li>{@link com.smile.acelib.event.SafeEventRegistry}（Supported）— 註冊 / 解除介面</li>
 *   <li>{@link com.smile.acelib.event.SafeEventListener}（SPI）— 消費者實作的
 *       Folia-safe listener 介面（identity / thread 責任見介面文件）</li>
 *   <li>{@link com.smile.acelib.event.EventRegistration}（Supported）— 註冊 handle</li>
 *   <li>{@link com.smile.acelib.event.EventErrorRecord} /
 *       {@link com.smile.acelib.event.EventErrorRecorder}（Supported）— 錯誤紀錄</li>
 *   <li>{@link com.smile.acelib.event.ListenerPolicy}（Supported）—
 *       Folia region 約束列舉（常數順序凍結）</li>
 * </ul>
 *
 * <h2>Folia 安全邊界</h2>
 * <ul>
 *   <li>listener 以 {@link com.smile.acelib.event.ListenerPolicy} 標記是否需要
 *       region-bound context；Folia 環境下非 region thread 呼叫
 *       {@code REQUIRES_REGION} listener 會被略過並記錄 {@code ACELIB-EVT-005}</li>
 *   <li>Paper / UNKNOWN 環境下 {@code REQUIRES_REGION} 等同
 *       {@code UNCONSTRAINED}</li>
 * </ul>
 *
 * <h2>生命週期</h2>
 * <p>disable / reload 時呼叫 {@link com.smile.acelib.event.SafeEventRegistry#onPluginDisable()}
 * 解除所有 Bukkit {@code HandlerList} 註冊並清空 tracked registration；
 * 之後 register 仍回傳 handle 但 listener 不會被 dispatch（記錄
 * {@code ACELIB-EVT-004}）。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆為 thread-safe，可在 Folia 多 region 並行環境使用。</p>
 *
 * <h2>錯誤代碼</h2>
 * <p>{@code ACELIB-EVT-001} ~ {@code ACELIB-EVT-006}，詳見
 * {@link com.smile.acelib.event.SafeEventRegistryImpl} 文件。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.event;
