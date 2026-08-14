package com.smile.acelib.data;

import java.util.Objects;

/**
 * 資料遷移介面（從 {@link #fromVersion()} 升級到 {@link #toVersion()}）。
 *
 * <p>多個 {@code DataMigration} 透過 {@link MigrationChain} 串接成鏈，依序執行；
 * 任一版本轉換失敗時觸發 rollback，<strong>不破壞既有資料</strong>。</p>
 *
 * <h2>使用情境</h2>
 * <pre>{@code
 * chain.add(new DataMigration() {
 *     public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
 *     public SchemaVersion toVersion() { return new SchemaVersion(1, 1); }
 *     public void migrate(DataMigrationContext ctx) {
 *         // 從 ctx.read() 讀舊資料、ctx.write() 寫新資料
 *     }
 * });
 * }</pre>
 *
 * <h2>設計約束</h2>
 * <ul>
 *   <li>單一 {@link DataMigration} 只能處理「一段版本」；
 *       跨版本（例如 1.0 → 2.0）應由多個 migration 串接</li>
 *   <li>{@link #migrate(DataMigrationContext)} 內若拋例外，{@link MigrationChain}
 *       將其包裝為 {@link MigrationResult#failure}，觸發 rollback</li>
 *   <li>migration 應為冪等：重複執行結果應相同</li>
 * </ul>
 *
 * @see MigrationChain
 * @see MigrationResult
 * @since 1.0.0
 */
public interface DataMigration {

    /**
     * 起始版本（migration 處理的「舊版本」）。
     *
     * @return 不可為 null
     */
    SchemaVersion fromVersion();

    /**
     * 目標版本（migration 處理後的「新版本」）。
     *
     * @return 不可為 null
     */
    SchemaVersion toVersion();

    /**
     * 執行遷移。
     *
     * <p>實作內部透過 {@link DataMigrationContext} 讀寫資料：
     * 從 {@code ctx.read()} 取得舊版資料、透過 {@code ctx.write(...)} 寫入新資料。
     * 若拋例外，{@link MigrationChain} 會視為失敗並觸發 rollback。</p>
     *
     * @param ctx 遷移上下文；不可為 null
     * @throws DataStoreException 當遷移過程中遇到不可恢復錯誤（攜帶 ACELIB-DATA-004）
     */
    void migrate(DataMigrationContext ctx);
}