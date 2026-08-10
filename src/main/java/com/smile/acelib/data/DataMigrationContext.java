package com.smile.acelib.data;

import java.util.Objects;

/**
 * 遷移上下文：提供版本間資料讀寫的標準介面。
 *
 * <p>由 {@link MigrationChain} 在呼叫 {@link DataMigration#migrate(DataMigrationContext)}
 * 之前建立；實作內以「讀舊版／寫新版（in-memory）／全部成功才提交／失敗丟棄」三段式流程運作。</p>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * public void migrate(DataMigrationContext ctx) {
 *     // 從舊版讀所有 key
 *     Set&lt;String&gt; keys = ctx.read().keys();
 *     // 搬移到新版（in-memory）
 *     for (String k : keys) {
 *         if (k.startsWith("legacy_")) {
 *             ctx.write().set(k.substring("legacy_".length()), ctx.read().get(k));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>提交 / rollback 語意</h2>
 * <ul>
 *   <li>{@link DataMigration#migrate(DataMigrationContext) migrate} 成功返回
 *       → {@link MigrationChain} 將 {@code writeView} 的內容提交到 store</li>
 *   <li>{@code migrate} 拋例外 → {@code writeView} 整批丟棄，舊資料保留</li>
 * </ul>
 *
 * @since Phase 8 (Plan §十三)
 */
public final class DataMigrationContext {

    /** 對舊版的唯讀視圖（migration 期間讀取舊資料用）。 */
    private final Record readView;
    /** 對新版的寫入視圖（migration 期間寫入新資料用；失敗時丟棄）。 */
    private final Record writeView;

    /**
     * 建構子（package-private；僅 {@link MigrationChain} 可建立）。
     *
     * @param readView  舊版唯讀視圖；不可為 null
     * @param writeView 新版可寫視圖；不可為 null
     */
    DataMigrationContext(Record readView, Record writeView) {
        this.readView = Objects.requireNonNull(readView, "readView");
        this.writeView = Objects.requireNonNull(writeView, "writeView");
    }

    /**
     * 取得「舊版」唯讀視圖。呼叫端僅讀不寫。
     *
     * @return 不可為 null 的 {@link Record}
     */
    public Record read() {
        return readView;
    }

    /**
     * 取得「新版」可寫視圖。呼叫端寫入的資料將在
     * {@link DataMigration#migrate(DataMigrationContext) migrate} 成功返回後提交，
     * 失敗時自動丟棄（rollback）。
     *
     * @return 不可為 null 的 {@link Record}
     */
    public Record write() {
        return writeView;
    }
}