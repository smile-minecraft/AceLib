package com.smile.acelib.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 物品 migration 鏈（多個 {@link ItemMigration} 依序執行）。
 *
 * <p>從「目前版本」依序套用 migration 到「目標版本」；任一失敗觸發 rollback，
 * <strong>輸入 ItemStack 不被破壞</strong>。</p>
 *
 * <h2>失敗 rollback 語意</h2>
 * <ul>
 *   <li>當 migration N 失敗時，N 之前已套用 N-1、N-2... 的修改（在工作複本上）整批丟棄，
 *       <strong>不 commit 到 ItemStack</strong></li>
 *   <li>commit 只有「整個 chain 全部成功」才會發生；保證既有資料不會被半套用</li>
 * </ul>
 *
 * <h2>兩種 migrate 入口</h2>
 * <ul>
 *   <li>{@link #migrateTracked(ItemSchemaVersion, ItemSchemaVersion, Consumer)} —
 *       舊簽章，僅追蹤版本推進；不允許 migration 修改 metadata（適合 chain 自身單元測試）</li>
 *   <li>{@link #migrateTracked(ItemMigrationContext, ItemSchemaVersion, Consumer)} —
 *       新簽章，由 caller 提供 {@link ItemMigrationContext}，migration 可讀寫
 *       identity / display / gameplay / display metadata；commit 時由
 *       {@code onCommit} 把工作複本套回 {@code ItemStack}</li>
 * </ul>
 */
public final class ItemMigrationChain {

    private final List<ItemMigration> migrations;

    public ItemMigrationChain() {
        this.migrations = new ArrayList<>();
    }

    /**
     * 加入一個 migration。
     *
     * @param migration 不可為 null
     * @return this（支援鏈式呼叫）
     * @throws NullPointerException 當 {@code migration} 為 null 時拋出
     */
    public ItemMigrationChain add(ItemMigration migration) {
        Objects.requireNonNull(migration, "migration");
        migrations.add(migration);
        return this;
    }

    /**
     * 回傳目前已加入的 migration 不可變清單。
     *
     * @return 不可變的 {@link List}
     */
    public List<ItemMigration> migrations() {
        return Collections.unmodifiableList(new ArrayList<>(migrations));
    }

    /**
     * 舊簽章：執行 migration；依序套用從 {@code currentVersion} 到 {@code targetVersion} 的 step。
     *
     * <p>此簽章使用 {@link ItemMigrationContext#stub(ItemSchemaVersion)} —
     * 所有 metadata 讀寫為 no-op；僅追蹤版本推進。</p>
     *
     * <p>commit hook 只在整個 chain 全部成功時觸發；任一失敗則 commit 不觸發。</p>
     *
     * @param currentVersion 當前 schema 版本
     * @param targetVersion  目標 schema 版本
     * @param onCommit       commit hook（成功時觸發，攜帶最終版本）
     * @return {@link ItemMigrationResult}
     */
    public ItemMigrationResult migrateTracked(ItemSchemaVersion currentVersion,
                                              ItemSchemaVersion targetVersion,
                                              Consumer<ItemSchemaVersion> onCommit) {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(targetVersion, "targetVersion");
        Objects.requireNonNull(onCommit, "onCommit");
        return migrateTracked(ItemMigrationContext.stub(currentVersion), targetVersion, onCommit);
    }

    /**
     * 新簽章：執行 migration；migration 可透過 {@code context} 讀寫 identity / display /
     * gameplay / display metadata。
     *
     * <p>commit hook 只在整個 chain 全部成功時觸發；任一失敗則 commit 不觸發，
     * 由 caller 自行處理 rollback（例如 {@link AceItemFactory#migrate} 把備份的
     * {@code ItemMeta} 設回 {@code ItemStack}）。</p>
     *
     * @param context      起始 {@link ItemMigrationContext}；不可為 null，且
     *                     {@link ItemMigrationContext#currentVersion()} 即為 chain 起點版本
     * @param targetVersion 目標 schema 版本
     * @param onCommit       commit hook（成功時�發，攜帶最終版本）
     * @return {@link ItemMigrationResult}
     */
    public ItemMigrationResult migrateTracked(ItemMigrationContext context,
                                              ItemSchemaVersion targetVersion,
                                              Consumer<ItemSchemaVersion> onCommit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(targetVersion, "targetVersion");
        Objects.requireNonNull(onCommit, "onCommit");

        ItemSchemaVersion currentVersion = context.currentVersion();
        if (currentVersion.compareTo(targetVersion) >= 0) {
            // 已是當前版本或目標版本較舊：no-op
            return ItemMigrationResult.success(null, List.of());
        }

        List<ItemSchemaVersion> applied = new ArrayList<>();

        for (ItemMigration m : migrations) {
            // 找 from 與目前 context 版本對得上的 migration
            if (m.fromVersion().compareTo(context.currentVersion()) != 0) {
                continue;
            }
            try {
                m.migrate(context);
            } catch (RuntimeException ex) {
                return ItemMigrationResult.failure(
                    "Item migration failed at " + m.fromVersion() + "->" + m.toVersion() + ": "
                        + ex.getMessage(),
                    ex);
            }
            applied.add(context.currentVersion());
            if (context.currentVersion().compareTo(targetVersion) >= 0) {
                break;
            }
        }

        if (context.currentVersion().compareTo(targetVersion) != 0) {
            return ItemMigrationResult.failure(
                "No continuous migration path from " + currentVersion + " to " + targetVersion
                    + "; final write version reached: " + context.currentVersion(),
                null);
        }

        onCommit.accept(context.currentVersion());
        return ItemMigrationResult.success(context.currentVersion(), applied);
    }
}
