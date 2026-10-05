package com.smile.acelib.data;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * 真實 MySQL / MariaDB 相容驗證案例集。
 *
 * <p>同一個案例集有兩種執行方式（邏輯零重複）：</p>
 * <ul>
 *   <li>{@link JdbcCompatMain}：獨立 {@code main()}，由
 *       {@code scripts/jdbc-mysql-compat.sh} 在臨時容器上執行，
 *       產出完整驗證輸出（含版本與錯誤碼對照）。</li>
 *   <li>{@code JdbcCompatGatedTest}：JUnit 包裝，僅在
 *       {@code ACELIB_JDBC_URL} 等環境變數齊備時執行，
 *       否則直接略過（CI 不依賴 Docker）。</li>
 * </ul>
 *
 * <p>案例只用公開 API 與標準 {@link DataSource}，不依賴任何測試 fixture。</p>
 */
final class JdbcCompatSuite {

    private JdbcCompatSuite() {
    }

    /**
     * 執行全部案例。
     *
     * @param fresh   新形狀驗證用的 DataSource（utf8mb4 資料庫）
     * @param legacy  舊資料驗證用的 DataSource（latin1 資料庫，僅供建舊表；
     *                同一資料庫升級後表會變成 utf8mb4 新形狀）
     * @param label   日誌標籤（例如 {@code mysql:8.4}）
     * @param log     驗證輸出
     * @return 失敗案例數（0 表全過）
     */
    static int runAll(DataSource fresh, DataSource legacy, String label, PrintStream log) {
        int failures = 0;
        failures += run(label, "fresh-init-new-shape", log,
            () -> freshInitNewShape(fresh, log));
        failures += run(label, "long-key-emoji-large-value", log,
            () -> longKeyEmojiLargeValue(fresh, log));
        failures += run(label, "store-isolation-reload", log,
            () -> storeIsolationReload(fresh, log));
        failures += run(label, "legacy-latin1-migration", log,
            () -> legacyLatin1Migration(legacy, log));
        failures += run(label, "connection-failure-stage", log,
            () -> connectionFailureStage(log));
        return failures;
    }

    // -----------------------------------------------------------------
    // cases
    // -----------------------------------------------------------------

    /** 新庫 init：表含 k_hash、主鍵為 (store_name, k_hash)。 */
    static void freshInitNewShape(DataSource ds, PrintStream log) throws Exception {
        JsonCodec codec = new JsonCodecImpl();
        JdbcDataStore store = new JdbcDataStore("compat", ds, SchemaVersion.V1_0, codec);
        store.init();
        List<String> columns = columnsOf(ds, "acelib_data_kv");
        log.println("  columns=" + columns);
        check(columns.contains("k_hash"), "新表必須含 k_hash 欄");
        check(columns.contains("k"), "新表必須含 k 欄");
        List<String> pk = primaryKeyOf(ds, "acelib_data_kv");
        log.println("  primary-key=" + pk);
        check(pk.equals(List.of("STORE_NAME", "K_HASH")) || pk.equals(List.of("store_name", "k_hash")),
            "主鍵必須是 (store_name, k_hash)，實際：" + pk);
        store.close();
    }

    /** 長 key（1500 字元）+ emoji key/value + 60KB 大值：寫入與重讀一致。 */
    static void longKeyEmojiLargeValue(DataSource ds, PrintStream log) throws Exception {
        JsonCodec codec = new JsonCodecImpl();
        String longKey = "k".repeat(1500);
        String big = "v".repeat(60_000);
        JdbcDataStore writer =
            new JdbcDataStore("compat-long", ds, SchemaVersion.V1_0, codec);
        writer.init();
        writer.root().set(longKey, "long-value");
        writer.root().set("emoji-🔑", "😀🎉");
        writer.root().set("big", big);
        writer.save();
        writer.close();

        JdbcDataStore reader =
            new JdbcDataStore("compat-long", ds, SchemaVersion.V1_0, codec);
        reader.init();
        check("long-value".equals(reader.root().getString(longKey, null)), "長 key 讀回一致");
        check("😀🎉".equals(reader.root().getString("emoji-🔑", null)), "emoji 讀回一致");
        check(big.equals(reader.root().getString("big", null)), "60KB 大值讀回一致");
        log.println("  longKey=1500chars ok, emoji ok, big=60000chars ok");
        reader.close();
    }

    /** store 隔離 + 跨 store 實例重讀。 */
    static void storeIsolationReload(DataSource ds, PrintStream log) throws Exception {
        JsonCodec codec = new JsonCodecImpl();
        JdbcDataStore a = new JdbcDataStore("iso-a", ds, SchemaVersion.V1_0, codec);
        JdbcDataStore b = new JdbcDataStore("iso-b", ds, SchemaVersion.V1_0, codec);
        a.init();
        b.init();
        a.root().set("shared", "from-a");
        a.save();
        check(b.root().get("shared") == null, "store 隔離");
        b.root().set("shared", "from-b");
        b.save();
        a.close();
        b.close();

        JdbcDataStore a2 = new JdbcDataStore("iso-a", ds, SchemaVersion.V1_0, codec);
        a2.init();
        check("from-a".equals(a2.root().getString("shared", null)), "重讀一致");
        a2.close();
        log.println("  isolation ok, reload ok");
    }

    /**
     * latin1 舊庫的舊形狀表 + 舊資料：init 升級為 utf8mb4 新形狀並跑資料遷移。
     *
     * <p>舊 DDL 在 utf8mb4 下根本建不出表（ERROR 1071），因此舊資料情境只能
     * 來自當年以 latin1（或 utf8）建出的庫；此案例重現該升級路徑。</p>
     */
    static void legacyLatin1Migration(DataSource ds, PrintStream log) throws Exception {
        try (Connection conn = ds.getConnection();
                java.sql.Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS acelib_data_kv");
            st.executeUpdate("CREATE TABLE acelib_data_kv ("
                + "store_name VARCHAR(255) NOT NULL, "
                + "k VARCHAR(1024) NOT NULL, "
                + "v TEXT, "
                + "PRIMARY KEY (store_name, k))");
            // [原因] 同一張舊表兩個 store：compat-legacy 需資料遷移 1.0→2.0，
            // [原因] compat-legacy-b 維持 1.0（升級時必須原樣保留，不得被 DROP）。
            st.executeUpdate("INSERT INTO acelib_data_kv (store_name, k, v) VALUES "
                + "('compat-legacy', '_version', '\"1.0\"'), "
                + "('compat-legacy', 'oldKey', '\"oldValue\"'), "
                + "('compat-legacy-b', '_version', '\"1.0\"'), "
                + "('compat-legacy-b', 'bKey', '\"bValue\"')");
        }
        log.println("  legacy latin1 table created with old DDL (pre-existing data)");

        JsonCodec codec = new JsonCodecImpl();
        JdbcDataStore store =
            new JdbcDataStore("compat-legacy", ds, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });
        store.init();
        check("migrated:oldValue".equals(store.root().getString("newKey", null)),
            "舊資料經 migration 可讀");
        store.close();

        List<String> columns = columnsOf(ds, "acelib_data_kv");
        check(columns.contains("k_hash"), "升級後為新形狀，實際欄位：" + columns);
        log.println("  migrated columns=" + columns);

        JdbcDataStore reader =
            new JdbcDataStore("compat-legacy", ds, new SchemaVersion(2, 0), codec);
        reader.init();
        check("migrated:oldValue".equals(reader.root().getString("newKey", null)),
            "升級後重讀一致");
        reader.close();

        // 同表另一個 store 的列必須原樣保留（v 不變，k_hash 由 k 重算），
        // 且該 store 之後能以自身版本正常 init 讀取。
        check("\"bValue\"".equals(rawValueOf(ds, "compat-legacy-b", "bKey")),
            "同表其他 store 的列必須原樣保留");
        JdbcDataStore other =
            new JdbcDataStore("compat-legacy-b", ds, SchemaVersion.V1_0, codec);
        other.init();
        check("bValue".equals(other.root().getString("bKey", null)),
            "其他 store 之後可正常 init 讀取");
        other.close();
        check(!tableExists(ds, "acelib_data_kv__acelib_migrate"),
            "升級暫存表必須清理，不可殘留");
        log.println("  multi-store preserved ok, other store reload ok");
    }

    /** 連線失敗：ACELIB-DATA-008 且標示 [jdbc:init] 階段。 */
    static void connectionFailureStage(PrintStream log) {
        DataSource bad = new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:1/nonexistent?connectTimeout=1000", "no", "no");
        JdbcDataStore store =
            new JdbcDataStore("compat-bad", bad, SchemaVersion.V1_0, new JsonCodecImpl());
        try {
            store.init();
            throw new AssertionError("預期連線失敗拋錯");
        } catch (DataStoreException ex) {
            log.println("  code=" + ex.getCode() + " message=" + ex.getMessage());
            check("ACELIB-DATA-008".equals(ex.getCode()), "連線失敗為 DATA-008");
            check(ex.getMessage().contains("[jdbc:init]"), "標示 [jdbc:init] 階段");
        }
    }

    // -----------------------------------------------------------------
    // infra
    // -----------------------------------------------------------------

    /** 指定 store/key 的原始 stored value（新形狀表，驗證原樣保留用）。 */
    static String rawValueOf(DataSource ds, String storeName, String key) throws Exception {
        try (Connection conn = ds.getConnection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT v FROM acelib_data_kv WHERE store_name = ? AND k_hash = ?")) {
            ps.setString(1, storeName);
            ps.setString(2, JdbcDataStore.hashKey(key));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    /** 指定表是否存在（catalog 限定當前庫，避免跨庫同名表干擾）。 */
    static boolean tableExists(DataSource ds, String table) throws Exception {
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table, null)) {
                while (rs.next()) {
                    if (table.equals(rs.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Case {
        void run() throws Exception;
    }

    private static int run(String label, String name, PrintStream log, Case c) {
        try {
            c.run();
            log.println("[PASS] " + label + " / " + name);
            return 0;
        } catch (AssertionError | Exception ex) {
            log.println("[FAIL] " + label + " / " + name + " :: " + ex);
            return 1;
        }
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("check failed: " + what);
        }
    }

    static List<String> columnsOf(DataSource ds, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // catalog 限定當前庫：傳 null 時 MySQL / MariaDB 會跨庫洩漏同名表。
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, null)) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return cols;
    }

    static List<String> primaryKeyOf(DataSource ds, String table) throws Exception {
        Map<Short, String> seq = new TreeMap<>();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getPrimaryKeys(conn.getCatalog(), null, table)) {
                while (rs.next()) {
                    seq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
            }
        }
        return new ArrayList<>(seq.values());
    }

    /** 最小的 DriverManager DataSource（驗證 harness 用，不進 production）。 */
    static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return java.sql.DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password)
                throws java.sql.SQLException {
            return java.sql.DriverManager.getConnection(url, username, password);
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("DriverManagerDataSource");
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
