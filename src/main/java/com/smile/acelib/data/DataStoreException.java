package com.smile.acelib.data;

import java.util.Objects;

/**
 * 資料儲存例外（extends {@link RuntimeException}）。
 *
 * <p>所有對外拋出的儲存例外必須攜帶 {@code ACELIB-DATA-<CODE>} 格式分類代碼，
 * 讓 {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 能正確歸類。</p>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-DATA-001}：IO 失敗（檔案無法讀寫、目錄無法建立、磁碟空間不足）</li>
 *   <li>{@code ACELIB-DATA-002}：資料損壞（檔案格式錯誤、反序列化失敗、解碼失敗）</li>
 *   <li>{@code ACELIB-DATA-003}：索引錯誤（key/path 為 null、空白、不合法）</li>
 *   <li>{@code ACELIB-DATA-004}：遷移失敗（migration chain 中任一版本轉換失敗；攜帶 from/to 版本）</li>
 *   <li>{@code ACELIB-DATA-005}：儲存已關閉（store 已被 {@link DataStore#close() close} 後仍嘗試操作）</li>
 *   <li>{@code ACELIB-DATA-006}：序列化失敗（型別不支援、循環參考）</li>
 *   <li>{@code ACELIB-DATA-007}：非同步逾時（async 等待超過 deadline）</li>
 *   <li>{@code ACELIB-DATA-008}：資料源不可用（JDBC 連線拒絕、SQL 語法錯誤）</li>
 *   <li>{@code ACELIB-DATA-009}：無可用 migration（偵測到舊版本但 chain 中無對應 from）</li>
 *   <li>{@code ACELIB-DATA-010}：on-disk schema 版本比 current 新（拒絕降版覆寫既有資料）</li>
 *   <li>{@code ACELIB-DATA-011}：非法 SQL identifier（{@link JdbcDataStore} 建構子的 table 名稱驗證失敗）</li>
 * </ul>
 *
 * <h2>設計約定</h2>
 * <ul>
 *   <li>{@link #getCode()} 永遠不為 null</li>
 *   <li>{@link #getCause()} 保留底層 I/O / SQL / 反序列化例外（不安靜吞錯）</li>
 *   <li>錯誤訊息須含「失敗位置 + 失敗原因」，避免只回傳 {@code ex.getMessage()}</li>
 * </ul>
 *
 * @see DataStore
 * @see com.smile.acelib.diagnostics.ErrorCodeRegistry
 * @since 1.0.0
 */
public class DataStoreException extends RuntimeException {

    private final String code;

    /**
     * 主要建構子（無 cause）。
     *
     * @param code    錯誤代碼（{@code ACELIB-DATA-<CODE>}）；不可為 null
     * @param message 詳細訊息；不可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public DataStoreException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 完整建構子（含 cause）。
     *
     * @param code    錯誤代碼；不可為 null
     * @param message 詳細訊息；不可為 null
     * @param cause   底層例外；可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public DataStoreException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 取得錯誤分類代碼。
     *
     * @return {@code ACELIB-DATA-<CODE>} 格式字串；永遠不為 null
     */
    public String getCode() {
        return code;
    }
}