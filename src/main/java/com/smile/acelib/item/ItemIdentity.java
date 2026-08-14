package com.smile.acelib.item;

/**
 * 自訂物品的唯一識別資料（immutable record）。
 *
 * <p>辨識物品必須依本結構，
 * <strong>不依賴 display name / lore 等顯示層資料</strong>。</p>
 *
 * <h2>三欄位語意</h2>
 * <ul>
 *   <li>{@code namespace} — 所屬 plugin 或模組（避免與其他插件的命名空間衝突）</li>
 *   <li>{@code key} — 該 namespace 內的物品識別字串</li>
 *   <li>{@code formatVersion} — 物品資料的當前格式版本（{@code major.minor}）；
 *       升級失敗保護依賴此欄位</li>
 * </ul>
 *
 * <h2>使用約定</h2>
 * <ul>
 *   <li>三個欄位共同決定 identity；三者皆相同才算相同</li>
 *   <li>紀錄 equality 與 hashCode 採三欄位組合</li>
 *   <li>{@link #toString()} 採 {@code "namespace:key@major.minor"} 形式，方便人工檢查</li>
 * </ul>
 *
 * @param namespace 所屬 plugin 或模組命名空間；不可為 null 或空白
 * @param key       該 namespace 內的物品識別字串；不可為 null 或空白
 * @param major     format version major（>= 0）
 * @param minor     format version minor（>= 0）
 */
public record ItemIdentity(String namespace, String key, int major, int minor) {

    public ItemIdentity {
        if (namespace == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemIdentity.namespace 不可為 null");
        }
        if (key == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemIdentity.key 不可為 null");
        }
        if (namespace.isBlank()) {
            throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                "ItemIdentity.namespace 不可為空字串或全空白");
        }
        if (key.isBlank()) {
            throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                "ItemIdentity.key 不可為空字串或全空白");
        }
        if (major < 0 || minor < 0) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemIdentity.formatVersion 不可為負（major=" + major + ", minor=" + minor + "）");
        }
    }

    /**
     * 序列化為 {@code "namespace:key@major.minor"} 形式（例如 {@code "acelib:sword@1.0"}）。
     *
     * @return 識別字串
     */
    @Override
    public String toString() {
        return namespace + ":" + key + "@" + major + "." + minor;
    }
}
