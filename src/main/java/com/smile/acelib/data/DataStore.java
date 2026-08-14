package com.smile.acelib.data;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 資料儲存抽象（public API 主介面）。
 *
 * <p>提供後續插件統一的儲存入口。內部實作不可暴露 JDBC / NIO / Map 細節；
 * 對外只暴露 {@link Record} / {@link SchemaVersion} / 同步 + 非同步讀寫介面。</p>
 *
 * <h2>生命週期</h2>
 * <ol>
 *   <li>{@link #init()} — 開啟／建立底層儲存、執行 schema 遷移；
 *       {@link #isInitialized()} 之後才回傳 true</li>
 *   <li>同步操作：{@link #root()}/{@link #save()}/{@link #flush()}；
 *       非同步操作：{@link #submit} 等</li>
 *   <li>{@link #close()} — flush + 釋放資源；冪等，重複呼叫不丟例外</li>
 * </ol>
 *
 * <h2>執行緒模型</h2>
 * <ul>
 *   <li>同步 {@code root()} / {@code save()}：呼叫端須自行確保執行緒安全；
 *       本介面預期由 plugin 主執行緒持有</li>
 *   <li>非同步 submit：透過 {@link Executor} 排程，<strong>不阻塞 caller</strong>；
 *       結果透過 {@link CompletableFuture} 傳回</li>
 *   <li>{@link #flush()} 與 {@link #close()} 為 block-and-wait，可從任意執行緒呼叫</li>
 * </ul>
 *
 * <h2>關服 / shutdown</h2>
 * <p>{@link #close()} 自動 {@link #flush()}，<strong>冪等</strong>。
 * 對 plugin lifecycle 而言，建議在 {@code onDisable()} 內呼叫一次以確保未寫入資料落地。</p>
 *
 * @see Record
 * @see SchemaVersion
 * @see DataStoreException
 * @since 1.0.0
 */
public interface DataStore extends AutoCloseable {

    /**
     * 取得此 store 的識別名稱（用於 log、診斷、metrics）。
     *
     * @return 不可為 null 的識別名稱
     */
    String name();

    /**
     * 取得當前 schema 版本。
     *
     * <p>未 {@link #init()} 前可回傳 {@code null} 或 schema 預設版本 —
     * 視實作而定；對 caller 而言，應在 {@link #isInitialized()} 為 true 後再讀。</p>
     *
     * @return 當前 schema 版本；不可為 null
     */
    SchemaVersion schemaVersion();

    /**
     * 是否已成功 {@link #init()}。
     *
     * @return true 表示已初始化
     */
    boolean isInitialized();

    /**
     * 是否已 {@link #close()}。
     *
     * @return true 表示已關閉
     */
    boolean isClosed();

    /**
     * 開啟／建立底層儲存，並依 {@link MigrationChain} 自動執行 schema 遷移。
     *
     * <p>典型流程：</p>
     * <ol>
     *   <li>建立底層檔案／表格／連線</li>
     *   <li>讀取既有資料的 schema 版本</li>
     *   <li>若既有版本較舊，依序執行 migration 直到當前版本</li>
     *   <li>任何 migration 失敗 → 拋 {@link DataStoreException}（{@code ACELIB-DATA-004}），
     *       <strong>既有資料不變</strong></li>
     * </ol>
     *
     * <p>冪等：重複呼叫不丟例外；第二次以後為 no-op。</p>
     *
     * @throws DataStoreException 當底層 IO 失敗、格式錯誤、遷移失敗
     */
    void init();

    /**
     * 取得「根」{@link Record} 視圖。對該視圖的修改直到 {@link #save()} 或
     * {@link #flush()} 才會寫回底層。
     *
     * @return 不可為 null 的根視圖
     * @throws IllegalStateException 當 {@link #isInitialized()} 為 false 或
     *                                {@link #isClosed()} 為 true
     */
    Record root();

    /**
     * 同步保存所有未寫入的變更；等同 {@code flush()}。
     *
     * <p>呼叫端應在 onDisable() 內呼叫一次以避免資料遺失。</p>
     *
     * @throws DataStoreException 當 IO 失敗、資料源不可用、序列化失敗
     */
    void save();

    /**
     * 同步等待所有非同步任務完成（{@link #submit} 派送後尚未完成的），
     * 並將資料寫回底層。
     *
     * <p>與 {@link #save()} 的差別在於：{@code save()} 僅 flush 同步視圖的差異，
     * {@code flush()} 同時等待所有 async submit 完成。</p>
     *
     * <p>冪等；重複呼叫不丟例外。</p>
     *
     * @throws DataStoreException 當 IO 失敗
     */
    void flush();

    /**
     * 註冊 migration 到內部 chain（鏈式 API）。
     *
     * @param migration 要加入的 migration；不可為 null
     * @return this
     */
    DataStore registerMigration(DataMigration migration);

    /**
     * 提交一個非同步寫入任務（不阻塞 caller）。
     *
     * <p>典型用法：</p>
     * <pre>{@code
     * CompletableFuture&lt;Void&gt; f = store.submit(executor, () -> {
     *     Record root = store.root();
     *     root.set("user.balance", 12345);
     *     store.save();
     * });
     * }</pre>
     *
     * @param executor 執行任務的 executor；不可為 null
     * @param task     欲執行的任務；不可為 null
     * @param <T>      任務回傳型別
     * @return {@link CompletableFuture}；任務完成時完成
     */
    <T> CompletableFuture<T> submit(Executor executor, java.util.concurrent.Callable<T> task);

    /**
     * 關閉 store：flush + 釋放底層資源。
     *
     * <p>冪等；重複呼叫不丟例外。關閉後所有 {@link #root()}/{@link #save()} 等方法
     * 拋 {@link DataStoreException}（{@code ACELIB-DATA-005}）。</p>
     */
    @Override
    void close();
}