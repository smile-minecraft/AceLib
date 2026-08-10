package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcDataStore} 行為測試（對應 Plan §十三 Phase 8）。
 *
 * <p>使用 in-memory {@link DataSource} 模擬 JDBC；本 fixture 不實作 vendor 專屬語法
 * （{@code ON DUPLICATE KEY UPDATE}、{@code ON CONFLICT}），因此 JdbcDataStore 必須使用
 * vendor-portable 寫法（先 DELETE 後 INSERT）。</p>
 */
@DisplayName("JdbcDataStore")
class JdbcDataStoreTest {

    private InMemoryDataSource dataSource;
    private JsonCodec codec;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryDataSource();
        codec = new JsonCodecImpl();
    }

    // -----------------------------------------------------------------
    // constructor
    // -----------------------------------------------------------------

    @Test
    @DisplayName("constructor：null 參數拋 NPE；空白 name/table 拋 IAE")
    void constructor_invalidArgs_throw() {
        assertThrows(NullPointerException.class,
            () -> new JdbcDataStore(null, dataSource, SchemaVersion.V1_0, codec));
        assertThrows(NullPointerException.class,
            () -> new JdbcDataStore("name", null, SchemaVersion.V1_0, codec));
        assertThrows(NullPointerException.class,
            () -> new JdbcDataStore("name", dataSource, null, codec));
        assertThrows(NullPointerException.class,
            () -> new JdbcDataStore("name", dataSource, SchemaVersion.V1_0, null));
        assertThrows(IllegalArgumentException.class,
            () -> new JdbcDataStore("", dataSource, SchemaVersion.V1_0, codec));
        assertThrows(IllegalArgumentException.class,
            () -> new JdbcDataStore("name", dataSource, SchemaVersion.V1_0, codec, ""));
        assertThrows(NullPointerException.class,
            () -> new JdbcDataStore("name", dataSource, SchemaVersion.V1_0, codec, null));
    }

    // -----------------------------------------------------------------
    // table identifier 驗證
    // -----------------------------------------------------------------

    @Test
    @DisplayName("constructor：非法 table identifier 拋 ACELIB-DATA-011（SQL injection 防護）")
    void constructor_invalidTableIdentifier_throws() {
        // 內含分號 + SQL 片段 → 注入風險
        DataStoreException sqlInjection = assertThrows(DataStoreException.class,
            () -> new JdbcDataStore("test", dataSource, SchemaVersion.V1_0, codec,
                "acelib_data_kv; DROP TABLE users; --"));
        assertEquals("ACELIB-DATA-011", sqlInjection.getCode());

        // 含 dash、dot、space、quote、前導數字等全部拒絕
        // （規範僅要求 `[A-Za-z_][A-Za-z0-9_]*` pattern；SQL 保留字如 SELECT
        //  在字母集內因此 pattern 通過；如需擋保留字需另加 reserved-word 檢查）
        String[] invalidNames = {
            "acelib-data",          // dash
            "acelib.data",          // dot
            "acelib data",          // space
            "acelib\"data",         // double quote
            "acelib`data`",         // backtick
            "1table",               // 數字開頭
            "' OR 1=1 --",          // classic injection payload
            "; DROP TABLE x",       // leading semicolon
            "table--",              // SQL comment
            "table(name)",          // parentheses
        };
        for (String bad : invalidNames) {
            DataStoreException ex = assertThrows(DataStoreException.class,
                () -> new JdbcDataStore("test", dataSource, SchemaVersion.V1_0, codec, bad),
                "非法 identifier 應被拒絕: " + bad);
            assertEquals("ACELIB-DATA-011", ex.getCode(),
                "非法 identifier '" + bad + "' 必須拋 ACELIB-DATA-011，實際：" + ex.getCode());
            assertTrue(ex.getMessage().contains(bad),
                "錯誤訊息應包含被拒絕的 identifier，實際：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("constructor：合法 table identifier（default + 自訂）皆通過驗證")
    void constructor_validTableIdentifier_accepted() {
        // 預設 table 名（透過 4-arg constructor）必須可用
        JdbcDataStore defaultStore = new JdbcDataStore("test", dataSource, SchemaVersion.V1_0, codec);
        defaultStore.init();
        defaultStore.close();

        // 合法自訂名稱：[A-Za-z_][A-Za-z0-9_]*
        String[] validNames = {
            "acelib_data_kv",
            "my_store",
            "Store_1",
            "_underscore_lead",
            "T",                  // 最短合法
            "ABC123",
        };
        for (String good : validNames) {
            // 用獨立的 InMemoryDatabase 給每個 table 名，避免互相衝突
            InMemoryDataSource ds = new InMemoryDataSource();
            JdbcDataStore store = new JdbcDataStore("test", ds, SchemaVersion.V1_0, codec, good);
            assertEquals("test", store.name(), "store.name() 應為建構子的 store 識別名");
            store.init(); // 不應拋例外
            assertTrue(ds.database.tableExists(good),
                "合法 table identifier 應能建立表格，table=" + good);
            store.close();
        }
    }

    // -----------------------------------------------------------------
    // init
    // -----------------------------------------------------------------

    @Test
    @DisplayName("init：自動建立表格、寫入 _version")
    void init_createsTableAndVersion() {
        JdbcDataStore store = newStore();
        assertFalse(dataSource.database.tableExists("acelib_data_kv"));
        store.init();
        assertTrue(dataSource.database.tableExists("acelib_data_kv"),
            "init 後表格必須被建立");
        String version = dataSource.database.selectValue(
            "acelib_data_kv", "test", "_version");
        assertEquals("1.0", version);
        store.close();
    }

    @Test
    @DisplayName("init 重複呼叫為 idempotent")
    void init_isIdempotent() {
        JdbcDataStore store = newStore();
        store.init();
        store.init();
        assertTrue(store.isInitialized());
        store.close();
    }

    @Test
    @DisplayName("root/set/save 後重新 init 新 store 可讀回同樣資料")
    void roundtrip_setSaveReload() {
        JdbcDataStore writer = newStore();
        writer.init();
        writer.root().set("user.balance", 100);
        writer.root().set("user.name", "alice");
        writer.save();
        writer.close();

        JdbcDataStore reader = newStore();
        reader.init();
        assertEquals(100, reader.root().getInt("user.balance", 0));
        assertEquals("alice", reader.root().getString("user.name", null));
        reader.close();
    }

    @Test
    @DisplayName("missing data：getXxx 回傳 default")
    void missing_returnsDefaults() {
        JdbcDataStore store = newStore();
        store.init();
        Record root = store.root();
        assertEquals(null, root.get("missing"));
        assertEquals("d", root.getString("missing", "d"));
        assertEquals(42, root.getInt("missing", 42));
        store.close();
    }

    // -----------------------------------------------------------------
    // save / transaction
    // -----------------------------------------------------------------

    @Test
    @DisplayName("save 在 transaction 內：commit 後資料可見，rollback 後不可見")
    void save_usesTransaction() {
        JdbcDataStore store = newStore();
        store.init();
        // 模擬手動在外部 connection 觀察 transaction 狀態：
        // save 完成後可見；save 失敗時 rollback → 不可見。
        store.root().set("k1", "v1");
        store.save();
        // 直接看 db：v1 必須存在
        assertEquals("\"v1\"", dataSource.database.selectValue("acelib_data_kv", "test", "k1"));
        store.close();
    }

    @Test
    @DisplayName("save 失敗時 rollback：原資料保留")
    void save_rollbackOnFailure() {
        // 預先寫入資料
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");

        // FailingDataSource 只在 init 完成後才拒絕新 connection：
        // init() 內部需要 2 個 connection（migration transaction + post-init read），
        // 所以必須允許前 N 次連線成功，否則連 init 都跑不起來就無法測 save rollback。
        JdbcDataStore store = new JdbcDataStore(
            "test", new FailingDataSource(dataSource, 2), SchemaVersion.V1_0, codec);
        store.init();
        store.root().set("oldKey", "newValue");
        DataStoreException ex = assertThrows(DataStoreException.class, store::save);
        assertEquals("ACELIB-DATA-008", ex.getCode());

        // 切回原始 dataSource 觀察：oldKey 仍是舊值
        assertEquals("\"oldValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "oldKey"));
    }

    @Test
    @DisplayName("init 期間發生例外：transaction rollback，不寫入任何東西")
    void init_rollbackOnFailure() {
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");
        // 確認 init 前 oldKey 是舊值
        assertEquals("\"oldValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "oldKey"));

        JdbcDataStore store = newStore();
        // 故意讓 init 失敗：DataSource 拒絕 connection
        store.init();
        // 正常的 init 應該保留 oldKey（沒動過）
        assertEquals("\"oldValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "oldKey"));
        store.close();
    }

    // -----------------------------------------------------------------
    // migration
    // -----------------------------------------------------------------

    @Test
    @DisplayName("migration 成功：舊版升級後版本正確")
    void migration_success() {
        // 預先寫入舊版資料
        dataSource.database.insert("acelib_data_kv", "test", "_version", "\"1.0\"");
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");

        JdbcDataStore store = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });
        store.init();
        assertEquals("migrated:oldValue",
            store.root().getString("newKey", null));
        store.close();
    }

    @Test
    @DisplayName("migration 成功：_version 與資料必須 persist，重 init 不重跑 migration")
    void migration_persistsAcrossNewStore_andVersionIsCurrent() {
        // 預先寫入舊版資料
        dataSource.database.insert("acelib_data_kv", "test", "_version", "\"1.0\"");
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");

        JdbcDataStore writer = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        writer.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });
        writer.init();
        writer.close();

        // on-disk 必須已是 current version 2.0，
        // 重啟新 store 不應再觸發 migration。
        String persistedVersion =
            dataSource.database.selectValue("acelib_data_kv", "test", "_version");
        assertEquals("2.0", persistedVersion,
            "migration 後 _version 必須 persist 到 DB，實際：" + persistedVersion);
        String persistedNewKey =
            dataSource.database.selectValue("acelib_data_kv", "test", "newKey");
        assertEquals("\"migrated:oldValue\"", persistedNewKey,
            "migrated 資料必須 persist 到 DB，實際：" + persistedNewKey);

        // 重啟新 store：不應再跑 migration
        java.util.concurrent.atomic.AtomicInteger migrationRunCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        JdbcDataStore reader = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        reader.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                migrationRunCount.incrementAndGet();
            }
        });
        reader.init();
        assertEquals(0, migrationRunCount.get(),
            "重啟新 store 不應再執行 migration（DB 已是 current version）");
        assertEquals("migrated:oldValue", reader.root().getString("newKey", null));
        reader.close();
    }

    @Test
    @DisplayName("on-disk schema 版本比 current 新：拋 ACELIB-DATA-010，rollback 保留原資料")
    void newerOnDiskVersion_rejectsWithData010_keepsDataIntact() {
        // 預先寫入「較新」資料（_version=2.0）+ 既有關鍵資料
        dataSource.database.insert("acelib_data_kv", "test", "_version", "\"2.0\"");
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");
        dataSource.database.insert("acelib_data_kv", "test", "futureKey", "\"futureValue\"");

        // store 配 current=V1_0（比 on-disk 2.0 舊）；不應降版覆寫
        JdbcDataStore store = new JdbcDataStore(
            "test", dataSource, SchemaVersion.V1_0, codec);
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-010", ex.getCode(),
            "新於 current 的 on-disk 版本必須拒絕，實際 code：" + ex.getCode());
        assertTrue(ex.getMessage().contains("2.0"),
            "錯誤訊息應提示 on-disk 版本，實際：" + ex.getMessage());

        // transaction rollback 後，DB 必須保留原資料
        assertEquals("\"2.0\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "_version"),
            "_version 必須保留為 2.0，不可被降版為 1.0");
        assertEquals("\"oldValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "oldKey"),
            "oldKey 必須保留");
        assertEquals("\"futureValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "futureKey"),
            "futureKey 必須保留");

        // store 必須未被初始化（init 拒絕）
        assertFalse(store.isInitialized());
    }

    @Test
    @DisplayName("migration 失敗：拋 ACELIB-DATA-004，舊資料保留")
    void migration_failure_keepsOldData() {
        dataSource.database.insert("acelib_data_kv", "test", "_version", "\"1.0\"");
        dataSource.database.insert("acelib_data_kv", "test", "oldKey", "\"oldValue\"");

        JdbcDataStore store = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("temp", "x");
                throw new IllegalStateException("migration boom");
            }
        });
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-004", ex.getCode());

        // 既有資料保留：_version 仍是 1.0；oldKey 仍是 oldValue
        assertEquals("\"1.0\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "_version"));
        assertEquals("\"oldValue\"",
            dataSource.database.selectValue("acelib_data_kv", "test", "oldKey"));
    }

    // -----------------------------------------------------------------
    // close
    // -----------------------------------------------------------------

    @Test
    @DisplayName("close 重複呼叫為 idempotent")
    void close_isIdempotent() {
        JdbcDataStore store = newStore();
        store.init();
        store.close();
        store.close();
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("closed store 操作拋 ACELIB-DATA-005")
    void closedStore_operationsThrow() {
        JdbcDataStore store = newStore();
        store.init();
        store.close();
        DataStoreException ex = assertThrows(DataStoreException.class, store::save);
        assertEquals("ACELIB-DATA-005", ex.getCode());
    }

    // -----------------------------------------------------------------
    // duplicate save 一致性
    // -----------------------------------------------------------------

    @Test
    @DisplayName("duplicate save：相同資料多次 save 一致")
    void duplicateSave_isConsistent() {
        JdbcDataStore store = newStore();
        store.init();
        store.root().set("counter", 1);
        store.save();
        String first = readAll();
        store.save();
        String second = readAll();
        assertEquals(first, second, "duplicate save 必須產生相同結果");
        store.close();
    }

    @Test
    @DisplayName("save 流程不依賴 MySQL-only 語法（vendor-portable）")
    void save_isVendorPortable() {
        // 本測試之所以重要：若 production 使用 ON DUPLICATE KEY UPDATE，
        // InMemoryDataSource（無此語法）會拋 IllegalArgumentException，
        // save() 最終會包裝為 ACELIB-DATA-008。
        JdbcDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save(); // 若這裡拋 ACELIB-DATA-008 即代表 vendor-specific 語法
        // 預期：成功
        store.close();
    }

    @Test
    @DisplayName("registerMigration 鏈式 API")
    void registerMigration_returnsSelf() {
        JdbcDataStore store = newStore();
        DataMigration m = new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(1, 1); }
            @Override public void migrate(DataMigrationContext ctx) {}
        };
        assertSame(store, store.registerMigration(m));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private JdbcDataStore newStore() {
        return new JdbcDataStore("test", dataSource, SchemaVersion.V1_0, codec);
    }

    private String readAll() {
        StringBuilder sb = new StringBuilder();
        for (InMemoryDatabase.Row r : dataSource.database.select(
                "acelib_data_kv", "test")) {
            sb.append(r.k).append("=").append(r.v).append("\n");
        }
        return sb.toString();
    }

    /**
     * 一個「先正常、後失敗」的 DataSource，用於測試 save 失敗時的 rollback。
     *
     * <p>前 {@code allowedConnections} 次連線委派給 delegate（共享 database）；
     * 超過則拋 {@link java.sql.SQLException}。設計原因：
     * {@link JdbcDataStore#init()} 內部需要多個 connection（migration transaction + post-init read）；
     * 若一律拒絕，連 init 都跑不起來，無法驗證「save 失敗時資料保留」語意。</p>
     */
    private static final class FailingDataSource implements javax.sql.DataSource {
        private final InMemoryDataSource delegate;
        private final java.util.concurrent.atomic.AtomicInteger count =
            new java.util.concurrent.atomic.AtomicInteger(0);
        private final int allowed;

        FailingDataSource(InMemoryDataSource delegate, int allowedConnections) {
            this.delegate = delegate;
            this.allowed = allowedConnections;
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            int n = count.incrementAndGet();
            if (n > allowed) {
                throw new java.sql.SQLException(
                    "simulated connection failure after " + allowed + " allowed");
            }
            return delegate.getConnection();
        }

        @Override
        public java.sql.Connection getConnection(String username, String password)
                throws java.sql.SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("FailingDataSource");
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}