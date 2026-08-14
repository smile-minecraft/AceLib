/**
 * Folia-safe 排程（Supported）。
 *
 * <h2>套件內容</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.scheduler.SafeScheduler} — 對外排程介面，
 *       封裝 Bukkit/Folia 各種 scheduler API，自動依平台能力分流</li>
 *   <li>{@link com.smile.acelib.scheduler.AceLibScheduler} — 靜態 factory /
 *       生命週期綁定 / 錯誤紀錄查詢的對外入口</li>
 *   <li>{@link com.smile.acelib.scheduler.ScheduledTask} — 已派送任務的控制代碼</li>
 *   <li>{@link com.smile.acelib.scheduler.TaskType} — 任務類型列舉</li>
 *   <li>{@link com.smile.acelib.scheduler.TaskErrorRecord} /
 *       {@link com.smile.acelib.scheduler.TaskErrorRecorder} — 可追蹤的錯誤紀錄</li>
 *   <li>{@link com.smile.acelib.scheduler.SafeSchedulerImpl}（Internal）— 標準實作</li>
 * </ul>
 *
 * <h2>使用原則</h2>
 * <ul>
 *   <li>下游插件一律透過 {@link com.smile.acelib.scheduler.SafeScheduler} 派送，
 *       不直接操作 Bukkit/Folia 原生 scheduler</li>
 *   <li>操作玩家/實體/方塊的同步任務請使用對應的上下文方法
 *       （{@code runForPlayer} / {@code runForEntity} / {@code runAtLocation}），
 *       由 AceLib 依平台選擇正確的 region / entity / global scheduler</li>
 *   <li>玩家離線、實體失效、chunk 未載入、平台不支援、插件停用等情境不會丟例外，
 *       而是回傳 {@code isCancelled() == true} 的 no-op task，
 *       並在 {@link com.smile.acelib.scheduler.TaskErrorRecorder} 留下
 *       {@code ACELIB-SCHED-001} ~ {@code ACELIB-SCHED-006} 錯誤代碼紀錄</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 public 方法皆可在 Folia 多 region 並行環境下安全使用；
 * 錯誤紀錄器內部為 thread-safe。</p>
 *
 * <h2>相容性承諾</h2>
 * <p>本套件（不含 Internal 的 {@code SafeSchedulerImpl}）為 v1 對外契約的一部分；
 * 簽章與語意在 v1 穩定版本內不破壞性變更。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.scheduler;
