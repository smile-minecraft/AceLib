package com.smile.acelib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemMigrationChain} 行為測試。
 *
 * <p>對應 Plan Phase 12 自訂物品核心「舊版資料升級」需求：
 * 多個 {@link ItemMigration} 依序執行；任一失敗觸發 rollback，
 * <strong>輸入 ItemStack 不可被部分修改</strong>。</p>
 *
 * <p>此處的 chain 測試使用 {@link ItemMigrationContext#stub} —
 * 只追蹤版本推進，不允許 metadata 寫入（metadata 寫入由 {@link AceItemFactory#migrate}
 * 透過 {@link ItemMigrationContext#workingCopy} 處理）。</p>
 */
@DisplayName("ItemMigrationChain")
class ItemMigrationChainTest {

    @Test
    @DisplayName("add + migrations() 回傳不可變清單")
    void add_appendsAndExposes() {
        ItemMigrationChain chain = new ItemMigrationChain();
        ItemMigration m1 = passthrough(new ItemSchemaVersion(1, 0), new ItemSchemaVersion(1, 1));
        ItemMigration m2 = passthrough(new ItemSchemaVersion(1, 1), new ItemSchemaVersion(1, 2));
        assertSame(chain, chain.add(m1).add(m2));
        assertEquals(2, chain.migrations().size());
        assertThrows(UnsupportedOperationException.class,
            () -> chain.migrations().add(passthrough(new ItemSchemaVersion(1, 2), new ItemSchemaVersion(1, 3))));
    }

    @Test
    @DisplayName("add(null) 拋 NullPointerException，訊息提及 'migration'")
    void add_nullMigration_throws() {
        ItemMigrationChain chain = new ItemMigrationChain();
        NullPointerException ex = assertThrows(NullPointerException.class,
            () -> chain.add(null));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("migration"),
            "NPE 訊息必須指出哪個參數不可為 null (got: " + ex.getMessage() + ")");
    }

    @Test
    @DisplayName("從當前版本升級：逐步套用並回傳 ItemMigrationResult.success")
    void migrate_callsEachStepInOrder() {
        ItemMigrationChain chain = new ItemMigrationChain()
            .add(passthrough(new ItemSchemaVersion(1, 0), new ItemSchemaVersion(1, 1)))
            .add(passthrough(new ItemSchemaVersion(1, 1), new ItemSchemaVersion(1, 2)));

        AtomicBoolean committed = new AtomicBoolean(false);
        List<ItemSchemaVersion> applied = new ArrayList<>();
        ItemMigrationResult r = chain.migrateTracked(new ItemSchemaVersion(1, 0),
            new ItemSchemaVersion(1, 2),
            finalVersion -> {
                committed.set(true);
                applied.add(finalVersion);
            });

        assertTrue(r.success());
        assertEquals(new ItemSchemaVersion(1, 2), r.finalVersion());
        assertTrue(committed.get(), "commit hook 必須在全部成功後觸發");
        assertEquals(1, applied.size());
        assertEquals(new ItemSchemaVersion(1, 2), applied.get(0));
    }

    @Test
    @DisplayName("已是當前版本：no-op，直接 success 但不�發 commit")
    void migrate_alreadyAtTarget_isNoop() {
        ItemMigrationChain chain = new ItemMigrationChain()
            .add(passthrough(new ItemSchemaVersion(1, 0), new ItemSchemaVersion(1, 1)));
        AtomicBoolean committed = new AtomicBoolean(false);
        ItemMigrationResult r = chain.migrateTracked(new ItemSchemaVersion(1, 1),
            new ItemSchemaVersion(1, 1),
            finalVersion -> committed.set(true));
        assertTrue(r.success());
        assertFalse(committed.get(), "no-op 時不應觸發 commit");
    }

    @Test
    @DisplayName("migration 拋例外：回傳 failure，commit 不觸發，errorCode 為 ACELIB-ITEM-004")
    void migrate_migrationThrows_returnsFailure() {
        ItemMigration bad = new ItemMigration() {
            @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(1, 0); }
            @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 1); }
            @Override public void migrate(ItemMigrationContext ctx) {
                throw new RuntimeException("boom");
            }
        };
        ItemMigrationChain chain = new ItemMigrationChain().add(bad);
        AtomicBoolean committed = new AtomicBoolean(false);
        ItemMigrationResult r = chain.migrateTracked(new ItemSchemaVersion(1, 0),
            new ItemSchemaVersion(1, 1),
            finalVersion -> committed.set(true));
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
        assertTrue(r.errorMessage().contains("boom"));
        assertEquals(ItemErrorCode.MIGRATION_FAILED, r.errorCode());
        assertFalse(committed.get(), "失敗時 commit 不應觸發");
    }

    @Test
    @DisplayName("rollback：第二個 migration 失敗，第一個的修改不生效 (commit 未觸發)")
    void migrate_failurePreservesOldState() {
        ItemMigration ok = passthrough(new ItemSchemaVersion(1, 0), new ItemSchemaVersion(1, 1));
        ItemMigration bad = new ItemMigration() {
            @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(1, 1); }
            @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 2); }
            @Override public void migrate(ItemMigrationContext ctx) {
                throw new IllegalStateException("simulated failure");
            }
        };
        ItemMigrationChain chain = new ItemMigrationChain().add(ok).add(bad);
        AtomicBoolean committed = new AtomicBoolean(false);
        ItemMigrationResult r = chain.migrateTracked(new ItemSchemaVersion(1, 0),
            new ItemSchemaVersion(1, 2),
            finalVersion -> committed.set(true));
        assertFalse(r.success());
        assertFalse(committed.get(), "失敗 chain commit 不應觸發");
        assertNull(r.finalVersion());
    }

    @Test
    @DisplayName("failure 靜態工廠：errorCode 與 message 都需保留")
    void failureStaticFactory() {
        ItemMigrationResult r = ItemMigrationResult.failure("oops", new RuntimeException("root"));
        assertFalse(r.success());
        assertEquals(ItemErrorCode.MIGRATION_FAILED, r.errorCode());
        assertNotNull(r.errorMessage());
        assertNotNull(r.cause());
    }

    /**
     * 真實通過的 migration helper：在 {@link ItemMigrationContext#migrate} 內呼叫
     * {@code ctx.writeVersion(to)} 推進版本；用於測試 chain 的逐步套用與 commit 觸發。
     */
    private static ItemMigration passthrough(ItemSchemaVersion from, ItemSchemaVersion to) {
        return new ItemMigration() {
            @Override public ItemSchemaVersion fromVersion() { return from; }
            @Override public ItemSchemaVersion toVersion() { return to; }
            @Override public void migrate(ItemMigrationContext ctx) {
                ctx.writeVersion(to);
            }
        };
    }
}
