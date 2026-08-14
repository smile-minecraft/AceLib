package com.smile.acelib.data;

import java.util.Set;

/**
 * 資料儲存的「記錄」抽象：階層式鍵值視圖。
 *
 * <p>{@link DataStore} 提供單一根 {@link DataStore#root() root} 視圖，所有資料以
 * 「點分隔 path」存取（例如 {@code "user.balance"}、{@code "players.uuid-1234.lastLogin"}）。</p>
 *
 * <h2>型別支援</h2>
 * <ul>
 *   <li>基本型別：{@link String}、{@link Integer}、{@link Long}、{@link Double}、{@link Boolean}</li>
 *   <li>巢狀 {@link Record}（子節點）</li>
 *   <li>任意 {@code Map<String, Object>} 與 {@code List<Object>}（JSON 相容集合）</li>
 * </ul>
 *
 * <h2>Round-trip 設計</h2>
 * <p>{@link #set(String, Object)} + {@link #getObject(String, Class, Object)}
 * 對基本型別、{@link Record} 子節點、{@code Map}/{@code List} 結構皆提供可逆序列化，
 * 不丟失結構性資料。複雜型別（非上述白名單）會在 {@code setObject} 拋
 * {@link DataStoreException}（{@code ACELIB-DATA-006}）。</p>
 *
 * <h2>Null 與缺失處理</h2>
 * <ul>
 *   <li>key 為 null 或空白 → {@link DataStoreException}（{@code ACELIB-DATA-003}）</li>
 *   <li>path 不存在但有預設值 → 回傳預設值（不丟例外）</li>
 *   <li>path 不存在且無預設值 → 回傳 null（基本型別 getter 會回傳 type-default）</li>
 * </ul>
 *
 * @see DataStore
 * @see DataStoreException
 * @since 1.0.0
 */
public interface Record {

    /**
     * 取得本視圖的「根 key」（相對於父 {@link Record} 的識別）。
     *
     * <p>根 {@link Record}（由 {@link DataStore#root()} 取得）回傳空字串。
     * 子視圖回傳對應的單層 key。</p>
     *
     * @return 不可為 null；可能是空字串（根視圖）
     */
    String key();

    /**
     * 判斷指定 path 是否存在。
     *
     * @param path 點分隔路徑（例如 {@code "user.balance"}）；不可為 null/空白
     * @return true 表示存在
     * @throws DataStoreException 當 {@code path} 為 null/空白（{@code ACELIB-DATA-003}）
     */
    boolean has(String path);

    /**
     * 取得指定 path 的原始物件；不存在回傳 null。
     *
     * @param path 點分隔路徑；不可為 null/空白
     * @return 對應值；不存在回傳 null
     * @throws DataStoreException 當 {@code path} 為 null/空白（{@code ACELIB-DATA-003}）
     */
    Object get(String path);

    /**
     * 寫入指定 path 的值（{@code null} 表示清除）。
     *
     * <p>值必須為支援型別（基本型別、{@link Record} 子節點、{@code Map<String, Object>}、
     * {@code List<Object>} 或 null）。不支援型別拋 {@link DataStoreException}
     * （{@code ACELIB-DATA-006}）。</p>
     *
     * @param path  點分隔路徑；不可為 null/空白
     * @param value 欲寫入的值；可為 null（表示清除）
     * @return 先前對應的值；若不存在回傳 null
     * @throws DataStoreException 當 {@code path} 為 null/空白（{@code ACELIB-DATA-003}）
     *                            或型別不支援（{@code ACELIB-DATA-006}）
     */
    Object set(String path, Object value);

    /**
     * 移除指定 path；不存在為 no-op。
     *
     * @param path 點分隔路徑；不可為 null/空白
     * @return true 表示實際移除了某個值
     * @throws DataStoreException 當 {@code path} 為 null/空白（{@code ACELIB-DATA-003}）
     */
    boolean remove(String path);

    /**
     * 取得所有頂層 key 集合（不遞遞）。
     *
     * @return 不可變的 key 集合；空集合表示無資料
     */
    Set<String> keys();

    /**
     * 複製整個視圖（淺拷貝：子節點為共享 reference）。
     *
     * <p>主要用於 {@link DataMigration#migrate(DataMigrationContext) migration} 流程：
     * 「在寫入視圖上修改 → 成功就成為新 currentView / 失敗就整批丟棄」。</p>
     *
     * <p>預設實作為 in-memory 淺拷貝；對檔案或 JDBC 後端的 store 而言，
     * 內部以 {@code Map<String, Object>} 表達時直接 {@code new LinkedHashMap<>(map)}。</p>
     *
     * @return 新的、與本視圖資料相同的 {@link Record}
     */
    Record copy();

    // -----------------------------------------------------------------
    // 型別化 getter（缺失時回傳 default）
    // -----------------------------------------------------------------

    /**
     * 取得字串值；缺失時回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值；可為 null
     * @return 對應字串或預設值
     */
    String getString(String path, String defaultValue);

    /**
     * 取得整數值；缺失或無法轉型時回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值
     * @return 對應整數或預設值
     */
    int getInt(String path, int defaultValue);

    /**
     * 取得長整數值；缺失或無法轉型時回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值
     * @return 對應長整數或預設值
     */
    long getLong(String path, long defaultValue);

    /**
     * 取得雙精度浮點數值；缺失或無法轉型時回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值
     * @return 對應雙精度浮點數或預設值
     */
    double getDouble(String path, double defaultValue);

    /**
     * 取得布林值；缺失或無法轉型時回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值
     * @return 對應布林或預設值
     */
    boolean getBoolean(String path, boolean defaultValue);

    /**
     * 取得子 {@link Record}；不存在或型別不符回傳 {@code defaultValue}。
     *
     * @param path         點分隔路徑
     * @param defaultValue 預設值；可為 null
     * @return 對應子視圖或預設值
     */
    Record getRecord(String path, Record defaultValue);

    /**
     * 取得指定型別物件；缺失或型別不符回傳 {@code defaultValue}。
     *
     * <p>支援 {@link String}/{@link Integer}/{@link Long}/{@link Double}/{@link Boolean}、
     * {@link Record}、{@code Map<String, Object>}、{@code List<Object>} 與對應基本型別
     * 包裝類別。不支援型別回傳 {@code defaultValue}（不丟例外）。</p>
     *
     * @param path         點分隔路徑
     * @param type         目標型別；不可為 null
     * @param defaultValue 預設值
     * @param <T>          型別參數
     * @return 對應物件或預設值
     * @throws DataStoreException 當 {@code path} 為 null/空白（{@code ACELIB-DATA-003}）
     */
    <T> T getObject(String path, Class<T> type, T defaultValue);
}