package com.smile.acelib.item;

import java.util.Objects;

/**
 * Item 相關錯誤代碼常數。
 *
 * <p>所有對外拋出或記錄的 item 錯誤都必須攜帶
 * {@code ACELIB-ITEM-*} 分類代碼，方便
 * {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 正確歸類。</p>
 *
 * <h2>錯誤代碼索引</h2>
 * <ul>
 *   <li>{@link #INVALID_SPEC} — 規格不合法（null、必填欄位缺失、型別錯誤）</li>
 *   <li>{@link #UNKNOWN_NAMESPACE} — 無法解析的 namespace 或 key</li>
 *   <li>{@link #UNSUPPORTED_DATA} — 不支援的資料型別／值</li>
 *   <li>{@link #MIGRATION_FAILED} — migration chain 中任一版本轉換失敗（攜帶 from→to）</li>
 *   <li>{@link #DESERIALIZE_FAILED} — 反序列化失敗（位元組格式錯誤、無對應 schema）</li>
 * </ul>
 */
public final class ItemErrorCode {

    private ItemErrorCode() {
        // utility class
    }

    /** 001 — 規格不合法（null、必填欄位缺失、型別錯誤）。 */
    public static final String INVALID_SPEC = "ACELIB-ITEM-001";

    /** 002 — 無法解析的 namespace 或 key（null、空白、不合法字元）。 */
    public static final String UNKNOWN_NAMESPACE = "ACELIB-ITEM-002";

    /** 003 — 不支援的資料型別／值（型別錯誤、大小超出限制）。 */
    public static final String UNSUPPORTED_DATA = "ACELIB-ITEM-003";

    /** 004 — migration 失敗（chain 中任一版本轉換失敗；攜帶 from→to）。 */
    public static final String MIGRATION_FAILED = "ACELIB-ITEM-004";

    /** 005 — 反序列化失敗（位元組格式錯誤、無對應 schema）。 */
    public static final String DESERIALIZE_FAILED = "ACELIB-ITEM-005";
}
