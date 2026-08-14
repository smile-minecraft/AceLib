/**
 * 資料儲存（Supported + SPI）。
 *
 * <p>提供統一的資料儲存入口：階層式鍵值視圖
 * {@link com.smile.acelib.data.Record}、schema 版本
 * {@link com.smile.acelib.data.SchemaVersion}、同步 + 非同步讀寫介面
 * {@link com.smile.acelib.data.DataStore}，以及版本遷移鏈
 * {@link com.smile.acelib.data.MigrationChain}。</p>
 *
 * <h2>取得方式</h2>
 * <p>建立 {@link com.smile.acelib.data.DataStore} 實作
 * （{@code JsonFileDataStore} / {@code JdbcDataStore}，皆為 Internal 非消費者
 * 契約）並呼叫 {@link com.smile.acelib.data.DataStore#init()} 後，由
 * {@link com.smile.acelib.AceLibApi} 或組裝鏈提供給下游；下游僅依賴
 * {@code DataStore} / {@code Record} 介面。</p>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.data.DataStore}（Supported）— 儲存服務介面</li>
 *   <li>{@link com.smile.acelib.data.Record}（SPI）— 消費者實作的記錄介面；
 *       定義 path / getter 契約，並以不可變語意回傳子視圖與快照</li>
 *   <li>{@link com.smile.acelib.data.SchemaVersion} /
 *       {@link com.smile.acelib.data.MigrationResult}（Supported）— 值型別</li>
 *   <li>{@link com.smile.acelib.data.DataMigration}（SPI）— 消費者實作的遷移介面
 *       （冪等與 rollback 責任見介面文件）</li>
 *   <li>{@link com.smile.acelib.data.MigrationChain} /
 *       {@link com.smile.acelib.data.DataMigrationContext}（Supported）—
 *       遷移鏈與上下文</li>
 *   <li>{@link com.smile.acelib.data.DataStoreException}（Supported）—
 *       資料例外，攜帶 {@code ACELIB-DATA-*} 錯誤代碼</li>
 * </ul>
 *
 * <h2>資料格式</h2>
 * <p>所有資料以「點分隔 path」存取（例如 {@code "user.balance"}）；值支援
 * 基本型別、巢狀 {@code Record}、{@code Map<String, Object>} 與
 * {@code List<Object>}。不支援型別在寫入時拋
 * {@code ACELIB-DATA-006}。</p>
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>初始化：{@link com.smile.acelib.data.DataStore#init()} 建立底層、執行
 *       schema 遷移；遷移失敗觸發 rollback，<strong>既有資料不變</strong></li>
 *   <li>寫入：{@code root()} 修改 → {@code save()} / {@code flush()} 寫回</li>
 *   <li>關閉：{@code close()} flush + 釋放資源；冪等。關閉後操作拋
 *       {@code ACELIB-DATA-005}</li>
 * </ul>
 *
 * <h2>執行緒模型</h2>
 * <ul>
 *   <li>同步 {@code root()} / {@code save()}：呼叫端須自行確保執行緒安全</li>
 *   <li>非同步 submit：透過 {@link java.util.concurrent.Executor} 排程，
 *       結果以 {@link java.util.concurrent.CompletableFuture} 回傳</li>
 *   <li>{@code flush()} / {@code close()} 為 block-and-wait，可從任意執行緒呼叫</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <p>{@code ACELIB-DATA-001} ~ {@code ACELIB-DATA-011}，詳見
 * {@link com.smile.acelib.data.DataStoreException} 文件。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.data;
