package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 資料遷移鏈（多個 {@link DataMigration} 依序執行）。
 *
 * <p>從「檔案現有版本」依序套用 migration 到「目標版本」；
 * 任一失敗觸發 rollback（既有資料不變）。</p>
 *
 * <h2>失敗 rollback 語意</h2>
 * <ul>
 *   <li>當 migration N 失敗時，N 之前已套用的 migration N-1, N-2... 的修改
 *       （in-memory write view）整批丟棄，<strong>不寫回 store</strong></li>
 *   <li>寫回動作只有「整個 chain 全部成功」才會發生；保證既有資料不會被半套用</li>
 *   <li>對 {@link JsonFileDataStore} 而言：所有寫入都在「讀取後的 in-memory 視圖」上進行，
 *       失敗時不觸發磁碟 IO；對 {@link JdbcDataStore} 而言：使用 transaction + rollback</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class MigrationChain {

    private final List<DataMigration> migrations = new ArrayList<>();

    /**
     * 加入一個 migration。
     *
     * @param migration 不可為 null
     * @return this（鏈式 API）
     */
    public MigrationChain add(DataMigration migration) {
        Objects.requireNonNull(migration, "migration");
        migrations.add(migration);
        return this;
    }

    /**
     * 取得所有已註冊 migrations。
     *
     * @return 不可變清單
     */
    public List<DataMigration> migrations() {
        return List.copyOf(migrations);
    }

    /**
     * 套用所有必要的 migration：從 {@code from} 升級到 {@code target}。
     *
     * <p>典型流程：</p>
     * <ol>
     *   <li>依 from→to 順序排序 migrations</li>
     *   <li>從 {@code from} 開始，依序套用直到 {@code target}（含）</li>
     *   <li>每步套用前建立 {@link DataMigrationContext}，
     *       {@code readView} 為「到目前為止的最新狀態」（初次 = 既有資料快照），
     *       {@code writeView} 為 in-memory 寫入區</li>
     *   <li>任一失敗 → 立即回傳 {@link MigrationResult#failure(String, Throwable)}；
     *       寫入區整批丟棄，<strong>既有資料不變</strong></li>
     * </ol>
     *
     * @param from       起始版本；不可為 null
     * @param target     目標版本；不可為 null
     * @param readView   既有資料的 in-memory 視圖（首次呼叫 = 從底層讀取的快照）
     * @param commitHook 成功完成所有 steps 後被呼叫的 commit hook；
     *                   接收「最終的 readView + 所有 writeView 的合併結果」並寫回底層
     * @return 遷移結果；永遠不為 null
     */
    public MigrationResult migrate(SchemaVersion from,
                                   SchemaVersion target,
                                   Record readView,
                                   CommitHook commitHook) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(readView, "readView");
        Objects.requireNonNull(commitHook, "commitHook");

        if (from.compareTo(target) >= 0) {
            // 已是當前版本或更新：no-op（target 已達，不需 migration）
            return MigrationResult.success(target, List.of());
        }

        // 1. 依 from→to 排序後，依序套用
        List<DataMigration> sorted = sortByFromTo();
        List<SchemaVersion> appliedSteps = new ArrayList<>();
        SchemaVersion currentVersion = from;
        Record currentView = readView;
        for (DataMigration m : sorted) {
            SchemaVersion f = m.fromVersion();
            SchemaVersion t = m.toVersion();
            // 只套用「起點 = 當前版本」的 migration
            if (f.compareTo(currentVersion) != 0) {
                continue;
            }
            // 終點 > target 則停止（我們不會升級超過 target）
            if (t.compareTo(target) > 0) {
                continue;
            }
            try {
                Record writeView = currentView.copy();
                DataMigrationContext ctx = new DataMigrationContext(currentView, writeView);
                m.migrate(ctx);
                currentView = writeView;
                currentVersion = t;
                appliedSteps.add(t);
                if (t.compareTo(target) == 0) {
                    break;
                }
            } catch (RuntimeException ex) {
                String msg = "migration " + f + "→" + t + " failed: " + ex.getMessage();
                // rollback：寫入區整批丟棄（currentView 仍為舊版），
                // caller 持有的 readView 不應被動到；保留既有資料不變。
                return MigrationResult.failure(msg, ex);
            }
        }
        // 2. 沒有任何 migration 套用：判定為「無可用 migration」
        // 當 from < target 且 chain 內無適用的 migration，
        // 不觸發 commit hook（commit 是「有套用」的後置動作），
        // 回傳 success(null, [])；呼叫端可由 appliedSteps.isEmpty() 判斷此情境。
        if (appliedSteps.isEmpty()) {
            return MigrationResult.success(null, List.of());
        }
        // 3. 全部成功 → commit hook
        try {
            commitHook.commit(currentView);
        } catch (RuntimeException ex) {
            String msg = "commit after migration failed: " + ex.getMessage();
            return MigrationResult.failure(msg, ex);
        }
        // commit hook 成功後，將最終狀態合併回 caller 持有的 readView，
        // 讓 caller 透過同一個 Record 參考觀察到 migrated 結果（測試與 production 一致）。
        if (readView instanceof MemoryRecord mr && currentView instanceof MemoryRecord mc) {
            mr.replaceContents(new LinkedHashMap<>(mc.snapshot()));
        }
        return MigrationResult.success(currentVersion, appliedSteps);
    }

    /**
     * 套用所有必要的 migration（呼叫端提供完整 from/target/readView/commitHook）。
     *
     * <p>此方法等同於 {@link #migrate(SchemaVersion, SchemaVersion, Record, CommitHook)}；
     * 為了向後相容既有內部呼叫而保留別名。</p>
     *
     * @param from       起始版本；不可為 null
     * @param target     目標版本；不可為 null
     * @param readView   既有資料的 in-memory 視圖
     * @param commitHook commit hook
     * @return 遷移結果
     */
    public MigrationResult migrateTracked(SchemaVersion from,
                                          SchemaVersion target,
                                          Record readView,
                                          CommitHook commitHook) {
        return migrate(from, target, readView, commitHook);
    }

    /**
     * 用於在遷移鏈完成後將最終資料寫回底層的 hook。
     *
     * @since 1.0.0
     */
    @FunctionalInterface
    public interface CommitHook {
        void commit(Record finalState);
    }

    /**
     * 依 {@link DataMigration#fromVersion()} 排序後回傳。
     */
    private List<DataMigration> sortByFromTo() {
        List<DataMigration> copy = new ArrayList<>(migrations);
        copy.sort((a, b) -> a.fromVersion().compareTo(b.fromVersion()));
        return copy;
    }
}