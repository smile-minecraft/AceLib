/**
 * 上下文安全檢查與執行（Supported）。
 *
 * <h2>套件內容</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.context.ThreadContext} — 執行緒/區域上下文列舉</li>
 *   <li>{@link com.smile.acelib.context.OperationType} — 操作類型列舉</li>
 *   <li>{@link com.smile.acelib.context.ContextInspector} — 無狀態檢查器：
 *       上下文 × 平台 × 操作 → {@link com.smile.acelib.context.ContextCheckResult}</li>
 *   <li>{@link com.smile.acelib.context.SafeExecutor} — 自動選擇正確 scheduler
 *       並在錯誤上下文主動攔截 mutate 的統一入口</li>
 *   <li>{@link com.smile.acelib.context.ContextException} — 違反上下文安全時
 *       拋出的例外（含 {@code ACELIB-CTX-*} 錯誤代碼）</li>
 *   <li>{@link com.smile.acelib.context.DebugMode} — 除錯模式開關</li>
 * </ul>
 *
 * <h2>使用原則</h2>
 * <ul>
 *   <li>操作玩家/實體/世界時使用 {@link com.smile.acelib.context.SafeExecutor} 的
 *       {@code executeOnRegion(...)} 系列方法，由 AceLib 依平台選擇正確的
 *       scheduler（Folia entity/region scheduler、Paper main thread）</li>
 *   <li>非同步流程完成後不要直接 mutate 遊戲物件；{@code executeAsync} 只接受
 *       {@code READ_ONLY}，對 mutate 操作會拋 {@code ACELIB-CTX-002}</li>
 *   <li>違反上下文安全時拋出的 {@link com.smile.acelib.context.ContextException}
 *       攜帶 {@code ACELIB-CTX-001} ~ {@code ACELIB-CTX-004} 錯誤代碼，
 *       可用於診斷與追蹤</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.context.ContextCheckResult} 為不可變 record；
 *       {@link com.smile.acelib.context.ContextException} 為不可變例外
 *       （攜帶的 reference 不可再變更），可在任何 thread 安全讀取。</li>
 *   <li>{@link com.smile.acelib.context.ContextInspector} 為無自身 mutable state
 *       的 static utility：不持有欄位、不輸出 log，可在任何 thread 呼叫；
 *       但 {@code currentContext(...)} 會讀取目前執行緒 / Bukkit 全域狀態
 *       （如 regionized server、主執行緒），因此回傳值依賴呼叫當下的環境，
 *       不是嚴格意義的純函式。</li>
 *   <li>{@link com.smile.acelib.context.SafeExecutor} 亦為 static utility
 *       （無自身 mutable field），但每次呼叫會建立新的
 *       {@link com.smile.acelib.scheduler.SafeSchedulerImpl}（具備 disabled/tracked
 *       等內部狀態）並派送任務，且有 side effect（log）；不屬「不可變」，
 *       方法本身可跨 thread 安全呼叫，但派送結果為有狀態實例。</li>
 *   <li>{@link com.smile.acelib.context.DebugMode} 為受控的 mutable static state：
 *       {@code setEnabled}/{@code clearExplicit}/{@code clearCache} 會修改全域狀態，
 *       同一時間只有一個值生效；內部以 {@link java.util.concurrent.atomic.AtomicReference}
 *       與 {@code synchronized} 保護，可跨 thread 安全讀寫，但狀態本身是 process-global，
 *       呼叫端需自行管理「誰設定、何時重設」（測試建議於 teardown 重置）。</li>
 *   <li>{@link com.smile.acelib.context.ThreadContext}、{@link com.smile.acelib.context.OperationType}
 *       為不可變列舉。</li>
 * </ul>
 *
 * <h2>相容性承諾</h2>
 * <p>本套件為 v1 對外契約的一部分；public 型別簽章與語意在 v1 穩定版本內
 * 不破壞性變更。列舉常數順序（序列化相容）凍結，不得更動。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.context;
