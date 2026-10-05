package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcDataStore} MySQL / MariaDB 相容測試。
 *
 * <p>背景：舊 DDL {@code PRIMARY KEY (store_name VARCHAR(255), k VARCHAR(1024))}
 * 在 utf8mb4 下最壞需要 {@code (255 + 1024) * 4 = 5116 bytes}，超過 InnoDB
 * 索引上限 3072 bytes，真實 MySQL 8.4 / MariaDB 11 建表即報
 * {@code ERROR 1071}（見 Phase 1 容器證據）。本測試鎖定修復後行為：</p>
 * <ul>
 *   <li>新表使用 {@code (store_name, k_hash)} 主鍵（SHA-256 hex），無 key 長度上限</li>
 *   <li>舊形狀表保留可讀，並在 init 時升級遷移到新形狀</li>
 *   <li>init 只用單一連線（已 commit 的狀態不再面臨第二個失敗點）</li>
 *   <li>失敗訊息帶階段前綴，可區分建表 / 查詢 / 遷移表</li>
 * </ul>
 *
 * <p>in-memory fixture 會模擬 {@code ERROR 1071}（legacy 形狀超長 key）
 * 與 {@code Unknown column 'k_hash'}（新舊 SQL 交叉使用），charset 與
 * 定序行為則由真實容器驗證（見 {@code scripts/jdbc-mysql-compat.sh}）。</p>
 */
@DisplayName("JdbcDataStore MySQL/MariaDB 相容")
class JdbcMySqlCompatTest {

    private static final String TABLE = "acelib_data_kv";

    private InMemoryDataSource dataSource;
    private JsonCodec codec;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryDataSource();
        codec = new JsonCodecImpl();
    }

    // -----------------------------------------------------------------
    // 新 schema 形狀
    // -----------------------------------------------------------------

    @Test
    @DisplayName("fresh init：建出含 k_hash 的新形狀表（utf8mb4 索引安全）")
    void freshInit_createsHashKeyedSchema() {
        JdbcDataStore store = newStore(dataSource);
        store.init();
        assertTrue(dataSource.database.tableHasKeyHash(TABLE),
            "新表必須是 (store_name, k_hash) 主鍵形狀，否則 utf8mb4 下建表即 ERROR 1071");
        store.close();
    }

    @Test
    @DisplayName("長 key（1500 字元，超舊索引上限）：寫入與重讀一致")
    void longKey_beyondOldIndexLimit_roundtrips() {
        String longKey = "k".repeat(1500);
        JdbcDataStore writer = newStore(dataSource);
        writer.init();
        writer.root().set(longKey, "long-value");
        writer.root().set("emoji-🔑", "😀🎉");
        writer.save();
        writer.close();

        JdbcDataStore reader = newStore(dataSource);
        reader.init();
        assertEquals("long-value", reader.root().getString(longKey, null));
        assertEquals("😀🎉", reader.root().getString("emoji-🔑", null));
        reader.close();
    }

    @Test
    @DisplayName("大值（60KB，TEXT 範圍內）：寫入與重讀一致")
    void largeValue_withinTextLimit_roundtrips() {
        String big = "v".repeat(60_000);
        JdbcDataStore writer = newStore(dataSource);
        writer.init();
        writer.root().set("big", big);
        writer.save();
        writer.close();

        JdbcDataStore reader = newStore(dataSource);
        reader.init();
        assertEquals(big, reader.root().getString("big", null));
        reader.close();
    }

    @Test
    @DisplayName("store_name 隔離：同表多 store 互不可見")
    void storeIsolation_sameTable() {
        JdbcDataStore a = new JdbcDataStore("a", dataSource, SchemaVersion.V1_0, codec);
        JdbcDataStore b = new JdbcDataStore("b", dataSource, SchemaVersion.V1_0, codec);
        a.init();
        b.init();
        a.root().set("shared", "from-a");
        a.save();
        assertEquals(null, b.root().get("shared"),
            "store b 不應看到 store a 的 key（同名 key 以 store_name 隔離）");
        // b 寫入同名 key 後各自獨立
        b.root().set("shared", "from-b");
        b.save();

        JdbcDataStore a2 = new JdbcDataStore("a", dataSource, SchemaVersion.V1_0, codec);
        a2.init();
        assertEquals("from-a", a2.root().getString("shared", null));
        a.close();
        b.close();
        a2.close();
    }

    // -----------------------------------------------------------------
    // 舊表升級遷移
    // -----------------------------------------------------------------

    @Test
    @DisplayName("legacy 表：init 讀取舊資料並升級為新形狀（含資料遷移）")
    void legacyTable_migratesToNewShape() {
        // 模擬舊版建出的表與資料
        dataSource.database.createLegacyTable(TABLE);
        dataSource.database.insert(TABLE, "test", "_version", "\"1.0\"");
        dataSource.database.insert(TABLE, "test", "oldKey", "\"oldValue\"");
        assertFalse(dataSource.database.tableHasKeyHash(TABLE));

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

        assertTrue(dataSource.database.tableHasKeyHash(TABLE),
            "升級後表必須是新形狀");
        assertFalse(dataSource.database.tableExists(TABLE + "__acelib_migrate"),
            "升級暫存表必須清理，不可殘留");
        assertEquals("migrated:oldValue", store.root().getString("newKey", null));
        assertEquals("\"migrated:oldValue\"",
            dataSource.database.selectValue(TABLE, "test", "newKey"));
        assertEquals("2.0",
            dataSource.database.selectValue(TABLE, "test", "_version"));
        store.close();

        // 重開新 store：不重跑 migration，資料一致
        AtomicInteger runs = new AtomicInteger(0);
        JdbcDataStore reader = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        reader.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                runs.incrementAndGet();
            }
        });
        reader.init();
        assertEquals(0, runs.get(), "升級過的表不應再跑 migration");
        assertEquals("migrated:oldValue", reader.root().getString("newKey", null));
        reader.close();
    }

    @Test
    @DisplayName("legacy 表無需資料遷移（同版本）：直接升級形狀，資料保留")
    void legacyTable_sameVersion_upgradesShapeKeepsData() {
        dataSource.database.createLegacyTable(TABLE);
        dataSource.database.insert(TABLE, "test", "_version", "\"1.0\"");
        dataSource.database.insert(TABLE, "test", "k1", "\"v1\"");

        JdbcDataStore store = newStore(dataSource);
        store.init();

        assertTrue(dataSource.database.tableHasKeyHash(TABLE));
        assertEquals("v1", store.root().getString("k1", null));
        store.close();
    }

    @Test
    @DisplayName("legacy 表版本比 current 新：拋 ACELIB-DATA-010，原資料保留")
    void legacyTable_downgradeRefused() {
        dataSource.database.createLegacyTable(TABLE);
        dataSource.database.insert(TABLE, "test", "_version", "\"2.0\"");
        dataSource.database.insert(TABLE, "test", "futureKey", "\"futureValue\"");

        JdbcDataStore store = new JdbcDataStore(
            "test", dataSource, SchemaVersion.V1_0, codec);
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-010", ex.getCode());
        assertEquals("\"futureValue\"",
            dataSource.database.selectValue(TABLE, "test", "futureKey"));
        assertFalse(store.isInitialized());
    }

    // -----------------------------------------------------------------
    // 單一連線 init（消除第二失敗點）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("init 只用一個 connection：commit 後不再開第二連線讀取")
    void init_usesSingleConnection() {
        CountingDataSource counting = new CountingDataSource();
        JdbcDataStore store = new JdbcDataStore(
            "test", counting, SchemaVersion.V1_0, codec);
        store.init();
        assertEquals(1, counting.connections.get(),
            "init 必須只開一個 connection；commit 後再開連線重讀會形成第二個失敗點");
        assertTrue(store.isInitialized());
        store.close();
    }

    // -----------------------------------------------------------------
    // 失敗階段可區分
    // -----------------------------------------------------------------

    @Test
    @DisplayName("建表失敗：ACELIB-DATA-008 且訊息標示 create-table 階段")
    void init_createTableFailure_reportsCreateStage() {
        JdbcDataStore store = new JdbcDataStore(
            "test", new FailOnCreateDataSource(dataSource), SchemaVersion.V1_0, codec);
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-008", ex.getCode());
        assertTrue(ex.getMessage().contains("[jdbc:create-table]"),
            "建表失敗必須標示階段，實際訊息：" + ex.getMessage());
        assertFalse(store.isInitialized());
    }

    @Test
    @DisplayName("查詢失敗：ACELIB-DATA-008 且訊息標示 read 階段（非 create-table）")
    void init_readFailure_reportsReadStage() {
        JdbcDataStore store = new JdbcDataStore(
            "test", new FailOnSelectDataSource(dataSource), SchemaVersion.V1_0, codec);
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-008", ex.getCode());
        assertTrue(ex.getMessage().contains("[jdbc:read]"),
            "查詢失敗必須標示 read 階段，實際訊息：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("create-table"),
            "查詢失敗不可誤標為 create-table，實際訊息：" + ex.getMessage());
        assertFalse(store.isInitialized());
    }

    // -----------------------------------------------------------------
    // 舊表升級：多 store 整表搬遷（M1 回歸）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("legacy 表含多 store：init A 後 B 的列完整保留，B 仍可正常 init 讀取")
    void legacyTable_multiStore_upgradePreservesOtherStores() {
        // 同一張舊表含 store a（需遷移 1.0→2.0）與 store b（維持 1.0）
        dataSource.database.createLegacyTable(TABLE);
        dataSource.database.insert(TABLE, "a", "_version", "\"1.0\"");
        dataSource.database.insert(TABLE, "a", "oldKey", "\"oldValue\"");
        dataSource.database.insert(TABLE, "b", "_version", "\"1.0\"");
        dataSource.database.insert(TABLE, "b", "bKey", "\"bValue\"");

        JdbcDataStore storeA = new JdbcDataStore(
            "a", dataSource, new SchemaVersion(2, 0), codec);
        storeA.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });
        storeA.init();
        assertEquals("migrated:oldValue", storeA.root().getString("newKey", null));
        storeA.close();

        // B 的列必須原樣留在新表（v 不變，k_hash 由 k 計算）
        assertEquals("\"bValue\"",
            dataSource.database.selectValue(TABLE, "b", "bKey"),
            "升級不得 DROP 同表其他 store 的資料");
        assertEquals(JdbcDataStore.hashKey("bKey"),
            dataSource.database.selectHash(TABLE, "b", "bKey"));

        // B 之後以自身版本正常 init 並讀回
        JdbcDataStore storeB = new JdbcDataStore(
            "b", dataSource, SchemaVersion.V1_0, codec);
        storeB.init();
        assertEquals("bValue", storeB.root().getString("bKey", null));
        storeB.close();
    }

    // -----------------------------------------------------------------
    // 中斷換表復原（M2 回歸）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("中斷復原：只剩暫存表（正式表不存在）→ init 接續改名，資料無遺失")
    void interruptedSwap_tmpOnly_recoversData() {
        String tmp = TABLE + "__acelib_migrate";
        // 模擬「舊表已刪、改名未完成」：暫存表是唯一副本（新形狀、已升級資料）
        dataSource.database.createTableIfMissing(tmp,
            "CREATE TABLE IF NOT EXISTS " + tmp
                + " (store_name VARCHAR(255), k_hash CHAR(64), k TEXT, v TEXT)");
        dataSource.database.insert(tmp, "test", "_version", "2.0");
        dataSource.database.insert(tmp, "test", "newKey", "\"migrated:oldValue\"");
        assertFalse(dataSource.database.tableExists(TABLE),
            "前置狀態：正式表不存在，只剩暫存表");

        java.util.concurrent.atomic.AtomicInteger runs =
            new java.util.concurrent.atomic.AtomicInteger(0);
        JdbcDataStore store = new JdbcDataStore(
            "test", dataSource, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                runs.incrementAndGet();
            }
        });
        store.init();

        assertEquals("migrated:oldValue", store.root().getString("newKey", null),
            "唯一副本（暫存表）不可被刪除，必須接續改名保留資料");
        assertEquals(0, runs.get(), "暫存表已是 current 版本，不應重跑 migration");
        assertFalse(dataSource.database.tableExists(tmp), "復原後暫存表不應殘留");
        store.close();
    }

    @Test
    @DisplayName("中斷復原：雙表並存（舊正式表＋殘留暫存表）→ 丟暫存表後由舊表升級")
    void interruptedSwap_bothTablesExist_dropsStaleTmpThenUpgrades() {
        String tmp = TABLE + "__acelib_migrate";
        // 正式表（舊形狀、完整資料）仍在：暫存表是未完成的升級殘留，可安全丟棄
        dataSource.database.createLegacyTable(TABLE);
        dataSource.database.insert(TABLE, "test", "_version", "\"1.0\"");
        dataSource.database.insert(TABLE, "test", "oldKey", "\"oldValue\"");
        dataSource.database.createTableIfMissing(tmp,
            "CREATE TABLE IF NOT EXISTS " + tmp
                + " (store_name VARCHAR(255), k_hash CHAR(64), k TEXT, v TEXT)");
        dataSource.database.insert(tmp, "test", "_version", "2.0");
        dataSource.database.insert(tmp, "test", "stale", "\"stale\"");

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

        assertEquals("migrated:oldValue", store.root().getString("newKey", null));
        assertEquals(null, dataSource.database.selectValue(TABLE, "test", "stale"),
            "殘留暫存表的 stale 資料不可混入正式表");
        assertFalse(dataSource.database.tableExists(tmp), "復原後暫存表不應殘留");
        store.close();
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private JdbcDataStore newStore(DataSource ds) {
        return new JdbcDataStore("test", ds, SchemaVersion.V1_0, codec);
    }

    /** 計算 getConnection 次數的 DataSource。 */
    private static final class CountingDataSource implements DataSource {
        final InMemoryDataSource delegate = new InMemoryDataSource();
        final AtomicInteger connections = new AtomicInteger();

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("CountingDataSource");
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** 建表語句即失敗的 DataSource（模擬 DDL 被拒）。 */
    private static final class FailOnCreateDataSource implements DataSource {
        private final InMemoryDataSource delegate;

        FailOnCreateDataSource(InMemoryDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        throw new SQLException("simulated DDL failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("FailOnCreateDataSource");
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** SELECT 即失敗的 DataSource（模擬查詢被拒；建表仍可成功）。 */
    private static final class FailOnSelectDataSource implements DataSource {
        private final InMemoryDataSource delegate;

        FailOnSelectDataSource(InMemoryDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args.length > 0
                            && args[0] instanceof String sql
                            && sql.trim().toUpperCase().startsWith("SELECT")) {
                        throw new SQLException("simulated SELECT failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("FailOnSelectDataSource");
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
