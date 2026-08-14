/**
 * 診斷服務（Supported / SPI API）。
 *
 * <p>本套件提供統一的診斷入口 {@link com.smile.acelib.diagnostics.DiagnosticsService}：
 * 查詢版本 / 平台 / capability / ready / debug、註冊模組狀態、彙整錯誤摘要，
 * 以及對同類錯誤做視窗式節流（{@link com.smile.acelib.diagnostics.ErrorThrottler}）。
 * 快照與報告皆為不可變（{@link com.smile.acelib.diagnostics.DiagnosticSnapshot} /
 * {@link com.smile.acelib.diagnostics.DiagnosticReport}），執行緒安全。</p>
 *
 * <h2>快照一致性</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.diagnostics.DiagnosticsService#buildSnapshot} 在
 *       單一呼叫內建立一致的版本 / 平台 / 模組 / 錯誤 / 節流快照；節流統計使用
 *       {@link com.smile.acelib.diagnostics.ErrorThrottler#snapshotStats} 併發安全快照。</li>
 *   <li>所有集合欄位於建構時 wrap 為不可變視圖；呼叫端不得修改。</li>
 * </ul>
 *
 * <h2>錯誤節流契約</h2>
 * <p>{@link com.smile.acelib.diagnostics.ErrorThrottler#tryRecord} 依視窗回傳
 * {@link com.smile.acelib.diagnostics.ThrottleDecision}：視窗內前
 * {@code maxPerWindow} 次 ALLOWED，其後 SUPPRESSED；跨視窗視為新事件。
 * {@code DiagnosticsService} 預設採 duplicate suppression（視窗內只放行一次）。</p>
 *
 * <h2>錯誤代碼</h2>
 * <p>{@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 提供
 * {@code ACELIB-<AREA>-<CODE>} 到 {@link com.smile.acelib.diagnostics.ErrorCategory}
 * 的映射；{@link com.smile.acelib.diagnostics.ErrorCategory} 為唯一分類來源。</p>
 *
 * <h2>SPI</h2>
 * <p>{@link com.smile.acelib.diagnostics.Clock} 為可注入時鐘（SPI / 測試用）；
 * 測試全程使用 deterministic clock，禁止 sleep。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.diagnostics;
