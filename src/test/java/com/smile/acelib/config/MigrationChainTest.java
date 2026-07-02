package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MigrationChain} 測試。
 *
 * <p>對應 Plan §九 Phase 4「設定檔版本欄位存在；版本過舊自動觸發遷移」。
 * MigrationChain 負責依版本順序串接多個 {@link ConfigMigration}，從「當前版本」
 * 一路 migrate 到「目標版本」。</p>
 */
@DisplayName("MigrationChain")
class MigrationChainTest {

    @Test
    @DisplayName("空 chain：ordered() 回傳空 list")
    void emptyChain_returnsEmpty() {
        MigrationChain chain = new MigrationChain();
        List<ConfigMigration> ordered = chain.ordered();
        assertNotNull(ordered);
        assertTrue(ordered.isEmpty());
    }

    @Test
    @DisplayName("add 與 ordered 按 fromVersion 排序（非插入順序）")
    void ordered_sortsByFromVersion() {
        MigrationChain chain = new MigrationChain();
        // 故意反向新增
        ConfigMigration v2to3 = stub(new ConfigVersion(2, 0), new ConfigVersion(3, 0));
        ConfigMigration v1to2 = stub(new ConfigVersion(1, 0), new ConfigVersion(2, 0));
        chain.add(v2to3);
        chain.add(v1to2);
        List<ConfigMigration> ordered = chain.ordered();
        assertEquals(2, ordered.size());
        assertEquals(new ConfigVersion(1, 0), ordered.get(0).fromVersion());
        assertEquals(new ConfigVersion(2, 0), ordered.get(1).fromVersion());
    }

    @Test
    @DisplayName("add(null) 拋 NullPointerException")
    void add_null_throws() {
        MigrationChain chain = new MigrationChain();
        assertThrows(NullPointerException.class, () -> chain.add(null));
    }

    @Test
    @DisplayName("migrateAll 從 1.0 升到 2.0 執行單個 migration")
    void migrateAll_singleMigration() {
        MigrationChain chain = new MigrationChain();
        boolean[] executed = {false};
        chain.add(stubWithExec(
            new ConfigVersion(1, 0), new ConfigVersion(2, 0),
            (oldCfg, nextCfg) -> {
                executed[0] = true;
                nextCfg.set("version", "2.0");
                nextCfg.set("addedKey", "added-by-migration");
            }));
        YamlConfiguration start = new YamlConfiguration();
        start.set("version", "1.0");
        MigrationResult result = chain.migrateAll(start, new ConfigVersion(2, 0));
        assertTrue(executed[0], "migration 必須被執行");
        assertTrue(result.success(), "成功結果");
        assertEquals(new ConfigVersion(1, 0), result.from());
        assertEquals(new ConfigVersion(2, 0), result.to());
        assertEquals("2.0", start.getString("version"));
        assertEquals("added-by-migration", start.getString("addedKey"));
    }

    @Test
    @DisplayName("migrateAll 從 1.0 透過 1.5、2.0 到 3.0 依序執行多個 migration")
    void migrateAll_multipleMigrations() {
        MigrationChain realChain = new MigrationChain();
        List<String> executed = new ArrayList<>();
        realChain.add(stubWithExec(
            new ConfigVersion(1, 0), new ConfigVersion(2, 0),
            (o, n) -> { executed.add("1->2"); n.set("v", 2); }));
        realChain.add(stubWithExec(
            new ConfigVersion(2, 0), new ConfigVersion(3, 0),
            (o, n) -> { executed.add("2->3"); n.set("v", 3); }));
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("version", "1.0");
        MigrationResult result = realChain.migrateAll(cfg, new ConfigVersion(3, 0));
        assertTrue(result.success());
        assertEquals(List.of("1->2", "2->3"), executed, "依序執行");
        assertEquals(3, cfg.getInt("v"));
    }

    @Test
    @DisplayName("migrateAll：當前版本已等於目標版本時不執行任何 migration")
    void migrateAll_alreadyAtTarget() {
        MigrationChain chain = new MigrationChain();
        boolean[] executed = {false};
        chain.add(stubWithExec(
            new ConfigVersion(2, 0), new ConfigVersion(2, 0),
            (o, n) -> executed[0] = true));
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("version", "2.0");
        MigrationResult result = chain.migrateAll(cfg, new ConfigVersion(2, 0));
        assertFalse(executed[0], "已在目標版本不應執行");
        assertTrue(result.success());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("migrateAll：找不到對應 migration 時回傳 failure + 警示訊息")
    void migrateAll_missingMigration() {
        MigrationChain chain = new MigrationChain();
        chain.add(stub(new ConfigVersion(2, 0), new ConfigVersion(3, 0)));
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("version", "1.0");
        MigrationResult result = chain.migrateAll(cfg, new ConfigVersion(3, 0));
        assertFalse(result.success(), "缺 migration 必須 failure");
        assertFalse(result.warnings().isEmpty(), "必須有警示訊息");
    }

    @Test
    @DisplayName("migrateAll：migration 內拋例外包裝為 failure（不回傳未捕例外）")
    void migrateAll_exceptionWrapped() {
        // 第一個場景：target == from，migration 不會被執行（保持沉默）
        MigrationChain chain = new MigrationChain();
        chain.add(stubWithExec(
            new ConfigVersion(1, 0), new ConfigVersion(1, 0),
            (o, n) -> { throw new IllegalStateException("migration boom"); }));
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("version", "1.0");
        // 已在目標版本不執行 → 直接 success
        MigrationResult result = chain.migrateAll(cfg, new ConfigVersion(1, 0));
        assertTrue(result.success(),
            "target == from 時不執行 migration；不會發生 exception path");

        // 第二個場景：target > from，migration 內拋例外 → 包裝為 failure
        MigrationChain chain2 = new MigrationChain();
        chain2.add(stubWithExec(
            new ConfigVersion(1, 0), new ConfigVersion(2, 0),
            (o, n) -> { throw new RuntimeException("boom"); }));
        YamlConfiguration cfg2 = new YamlConfiguration();
        cfg2.set("version", "1.0");
        MigrationResult result2 = chain2.migrateAll(cfg2, new ConfigVersion(2, 0));
        assertFalse(result2.success());
        assertTrue(result2.warnings().stream()
            .anyMatch(w -> w.contains("boom") || w.contains("exception")),
            "警示訊息必須含錯誤訊息");
    }

    @Test
    @DisplayName("ordered() 回傳不可變 list，多次呼叫結果一致")
    void ordered_isImmutable() {
        MigrationChain chain = new MigrationChain();
        chain.add(stub(new ConfigVersion(1, 0), new ConfigVersion(2, 0)));
        List<ConfigMigration> first = chain.ordered();
        List<ConfigMigration> second = chain.ordered();
        assertEquals(first.size(), second.size());
        // 不可變檢查
        assertThrows(UnsupportedOperationException.class,
            () -> first.add(stub(new ConfigVersion(3, 0), new ConfigVersion(4, 0))));
    }

    // -----------------------------------------------------------------
    // Helper：建立具名 migration
    // -----------------------------------------------------------------

    private static ConfigMigration stub(ConfigVersion from, ConfigVersion to) {
        return new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return from; }
            @Override public ConfigVersion toVersion() { return to; }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                // no-op stub
            }
        };
    }

    private static ConfigMigration stubWithExec(ConfigVersion from,
                                                ConfigVersion to,
                                                java.util.function.BiConsumer<YamlConfiguration, YamlConfiguration> body) {
        return new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return from; }
            @Override public ConfigVersion toVersion() { return to; }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                body.accept(old, next);
            }
        };
    }
}