package com.smile.acelib.item;

import java.util.Objects;

/**
 * 物品資料的 schema 版本（immutable record）。
 *
 * <p>對應 Plan Phase 12「舊版資料升級」需求：
 * 採用 {@code major.minor} 結構；major 用於破壞性變更，minor 用於相容變更。
 * 升級路徑透過 {@link ItemMigration} 來表達。</p>
 *
 * @since Phase 12
 */
public record ItemSchemaVersion(int major, int minor) implements Comparable<ItemSchemaVersion> {

    /** Phase 12 起始版本 {@code 1.0}。 */
    public static final ItemSchemaVersion V1_0 = new ItemSchemaVersion(1, 0);

    public ItemSchemaVersion {
        // 允許 0.0 / 1.0 等；呼叫端若需驗證，使用 Objects.requireNonNull 或自訂 check
    }

    @Override
    public int compareTo(ItemSchemaVersion other) {
        Objects.requireNonNull(other, "other");
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
