package com.smile.acelib.data;

import java.util.Objects;

/**
 * 資料儲存 schema 版本（immutable record）。
 *
 * <p>{@link DataStore} 內部以 {@code major.minor} 形式記錄「當前 schema 版本」；
 * 載入既有資料時若版本較舊，自動觸發 {@link DataMigration} 升級。</p>
 *
 * <h2>排序規則</h2>
 * <ul>
 *   <li>先比 major：major 越大版本越新</li>
 *   <li>major 相同時比 minor：minor 越大版本越新</li>
 * </ul>
 *
 * <h2>字串格式</h2>
 * <p>{@link #toString()} 採 {@code "major.minor"} 形式（例如 {@code "1.0"}），
 * 方便 {@link JsonCodec#encodeVersion(SchemaVersion) JSON 編碼}與讀回。</p>
 *
 * <h2>與 {@code com.smile.acelib.config.ConfigVersion} 的差異</h2>
 * <ul>
 *   <li>{@code ConfigVersion} 專屬於設定檔；本類別供一般資料儲存使用</li>
 *   <li>兩者皆有 {@code major.minor} 結構，但刻意分開避免資料與設定耦合</li>
 * </ul>
 *
 * @param major 主版本號（先比較）
 * @param minor 次版本號（major 相同時比較）
 * @since 1.0.0
 */
public record SchemaVersion(int major, int minor) implements Comparable<SchemaVersion> {

    /** 初始版本 {@code 1.0}。 */
    public static final SchemaVersion V1_0 = new SchemaVersion(1, 0);

    /** Compact constructor：保留 record 自動產生的 accessor 與等價性。 */
    public SchemaVersion {
        // 不限制正負值；保持 record 自動規範一致。
        // 呼叫端若需驗證，使用 Objects.requireNonNull 或自訂 check。
    }

    /**
     * 與另一版本比較：先比 major，後比 minor。
     *
     * @return 負數表示此版本較舊；0 表示相同；正數表示此版本較新
     */
    @Override
    public int compareTo(SchemaVersion other) {
        Objects.requireNonNull(other, "other");
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.minor, other.minor);
    }

    /**
     * 序列化為 {@code "major.minor"} 形式（例如 {@code "1.0"}）。
     *
     * @return {@code major.minor} 字串
     */
    @Override
    public String toString() {
        return major + "." + minor;
    }
}