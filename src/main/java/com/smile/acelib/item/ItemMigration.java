package com.smile.acelib.item;

/**
 * 物品 migration 介面：從 {@link #fromVersion()} 升級到 {@link #toVersion()}。
 *
 * <p>多個 {@code ItemMigration}
 * 透過 {@link ItemMigrationChain} 串接，依序執行；任一失敗觸發 rollback，
 * <strong>輸入 ItemStack 不被部分修改</strong>。</p>
 *
 * <h2>使用情境</h2>
 * <pre>{@code
 * chain.add(new ItemMigration() {
 *     public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(1, 0); }
 *     public ItemSchemaVersion toVersion()   { return new ItemSchemaVersion(1, 1); }
 *     public void migrate(ItemMigrationContext ctx) {
 *         ctx.writeVersion(new ItemSchemaVersion(1, 1));
 *     }
 * });
 * }</pre>
 *
 * <h2>設計約束</h2>
 * <ul>
 *   <li>單一 {@link ItemMigration} 只能處理「一段版本」</li>
 *   <li>{@link #migrate(ItemMigrationContext)} 若拋例外，{@link ItemMigrationChain} 視為失敗並 rollback</li>
 * </ul>
 *
 * @see ItemMigrationChain
 * @see ItemMigrationResult
 */
public interface ItemMigration {

    /**
     * 起始版本（migration 處理的「舊版本」）。
     *
     * @return 不可為 null
     */
    ItemSchemaVersion fromVersion();

    /**
     * 目標版本（migration 處理後的「新版本」）。
     *
     * @return 不可為 null
     */
    ItemSchemaVersion toVersion();

    /**
     * 執行遷移。
     *
     * <p>透過 {@link ItemMigrationContext#writeVersion(ItemSchemaVersion)} 標記新版本；
     * 若拋例外，{@link ItemMigrationChain} 將其包裝為 {@link ItemMigrationResult#failure} 並 rollback。</p>
     *
     * @param ctx 遷移上下文；不可為 null
     * @throws ItemException 當遷移過程中遇到不可恢復錯誤（攜帶 {@code ACELIB-ITEM-004}）
     */
    void migrate(ItemMigrationContext ctx);
}
