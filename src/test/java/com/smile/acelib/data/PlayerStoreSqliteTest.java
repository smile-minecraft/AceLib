package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataStore} 以真 SQLite 檔案為後端的行為測試。
 *
 * <p>驗證重點：每位玩家獨立儲存、欄位級增量寫入（未變欄位不重寫 revision）、
 * 欄位刪除、交易失敗整批不落地、以及關閉後的拒絕語意。
 * 全部走真 driver（{@code org.xerial:sqlite-jdbc}），不使用 in-memory 假資料庫。</p>
 */
@DisplayName("PlayerDataStore（真 SQLite）")
class PlayerStoreSqliteTest {

    private static final String TABLE = PlayerDataStores.DEFAULT_TABLE;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private PlayerDataStore store;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("players.db");
        store = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        store.init();
    }

    @AfterEach
    void tearDown() {
        if (store != null && !store.isClosed()) {
            store.close();
        }
    }

    // -----------------------------------------------------------------
    // 生命週期
    // -----------------------------------------------------------------

    @Test
    @DisplayName("init：建立 SQLite 檔案與資料表，owner 預設 acelib")
    void init_createsFileAndTable() throws Exception {
        assertTrue(java.nio.file.Files.exists(dbFile), "init 必須建立 SQLite 檔案");
        assertEquals(PlayerDataStores.DEFAULT_OWNER, store.owner());
        assertTrue(store.isInitialized());
        try (Connection conn = rawConnection();
             Statement st = conn.createStatement()) {
            assertTrue(tableExists(st), "init 必須建立 " + TABLE);
        }
    }

    @Test
    @DisplayName("init 重複呼叫為 no-op；close 冪等")
    void init_idempotentAndCloseIdempotent() {
        store.init();
        store.close();
        store.close();
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("close 後 load / applyChanges / deletePlayer 皆拒絕")
    void closedStore_rejectsOperations() {
        store.close();
        UUID uuid = UUID.randomUUID();
        assertThrows(DataStoreException.class, () -> store.load(uuid));
        assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(
                uuid, "k", "v"))));
        assertThrows(DataStoreException.class, () -> store.deletePlayer(uuid));
    }

    @Test
    @DisplayName("init 前 load 拋 IllegalStateException")
    void load_beforeInit_throws() throws Exception {
        PlayerDataStore fresh = PlayerDataStores.sqlite(
            tempDir.resolve("uninit.db"), SchemaVersion.V1_0);
        assertThrows(IllegalStateException.class, () -> fresh.load(UUID.randomUUID()));
        fresh.close();
    }

    // -----------------------------------------------------------------
    // 逐玩家隔離
    // -----------------------------------------------------------------

    @Test
    @DisplayName("每位玩家資料互相隔離：寫 A 不影響 B，未註冊玩家回 empty")
    void playersAreIsolated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(a, "balance", 100),
            PlayerDataStore.FieldChange.upsert(a, "name", "alpha"),
            PlayerDataStore.FieldChange.upsert(b, "balance", 7)));

        assertEquals(100, store.load(a).orElseThrow().getInt("balance", -1));
        assertEquals("alpha", store.load(a).orElseThrow().getString("name", null));
        assertEquals(7, store.load(b).orElseThrow().getInt("balance", -1));
        assertFalse(store.load(b).orElseThrow().has("name"),
            "B 不得看到 A 的欄位");
        assertEquals(Optional.empty(), store.load(UUID.randomUUID()),
            "從未寫入的玩家必須回 empty");
    }

    @Test
    @DisplayName("round-trip：巢狀 Map/List、unicode、emoji、null 欄位語意")
    void roundTripNestedValues() {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("deep", "值-😀");
        nested.put("n", 42L);
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "nested", nested),
            PlayerDataStore.FieldChange.upsert(uuid, "list", List.of("a", "b")),
            PlayerDataStore.FieldChange.upsert(uuid, "flag", true),
            PlayerDataStore.FieldChange.upsert(uuid, "ratio", 1.5d)));

        Record loaded = store.load(uuid).orElseThrow();
        assertEquals("值-😀", loaded.get("nested.deep"));
        assertEquals(42L, ((Number) loaded.get("nested.n")).longValue());
        assertEquals(List.of("a", "b"), loaded.get("list"));
        assertEquals(Boolean.TRUE, loaded.get("flag"));
        assertEquals(1.5d, ((Number) loaded.get("ratio")).doubleValue(), 1e-9);
    }

    // -----------------------------------------------------------------
    // 增量寫入
    // -----------------------------------------------------------------

    @Test
    @DisplayName("同值 upsert 不改 revision；值變了才 +1")
    void sameValueUpsert_doesNotBumpRevision() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "b", 2)));
        long revA = revisionOf(uuid, "a");
        long revB = revisionOf(uuid, "b");
        assertEquals(1L, revA);
        assertEquals(1L, revB);

        // a 送相同值、b 送新值：a 不該被重寫
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "b", 99)));
        assertEquals(revA, revisionOf(uuid, "a"), "值未變的欄位不得被重寫");
        assertEquals(revB + 1, revisionOf(uuid, "b"), "值變了必須 revision +1");
    }

    @Test
    @DisplayName("刪除欄位：刪除後該欄位不存在，其餘欄位不動")
    void delete_removesOnlyThatField() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "keep", "yes"),
            PlayerDataStore.FieldChange.upsert(uuid, "drop", "no")));
        long keepRev = revisionOf(uuid, "keep");

        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "drop")));

        Record loaded = store.load(uuid).orElseThrow();
        assertTrue(loaded.has("keep"));
        assertFalse(loaded.has("drop"), "刪除後欄位必須不存在");
        assertEquals(keepRev, revisionOf(uuid, "keep"), "未受影響欄位的 revision 不變");
        assertEquals(1, countRows(uuid), "刪除後該玩家的列數必須只剩 1（keep）");
    }

    @Test
    @DisplayName("刪除不存在的欄位是 no-op，不影響其他資料")
    void deleteMissingField_isNoOp() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 1)));
        long rev = revisionOf(uuid, "a");

        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "absent")));

        assertEquals(rev, revisionOf(uuid, "a"));
        assertEquals(1, countRows(uuid));
    }

    @Test
    @DisplayName("刪除最後一個欄位後該玩家回 empty")
    void deleteLastField_playerBecomesEmpty() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "only", 1)));
        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "only")));
        assertEquals(Optional.empty(), store.load(uuid));
    }

    @Test
    @DisplayName("deletePlayer 移除該玩家全部欄位，其他玩家不受影響")
    void deletePlayer_removesOnlyThatPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(a, "k", 1),
            PlayerDataStore.FieldChange.upsert(a, "j", 2),
            PlayerDataStore.FieldChange.upsert(b, "k", 3)));

        store.deletePlayer(a);

        assertEquals(Optional.empty(), store.load(a));
        assertEquals(3, store.load(b).orElseThrow().getInt("k", -1));
    }

    // -----------------------------------------------------------------
    // 交易
    // -----------------------------------------------------------------

    @Test
    @DisplayName("交易失敗：整批不落地（rollback），既有資料保持舊值")
    void transactionFailure_rollsBackWholeBatch() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

        // 在真 DB 上掛一個 trigger，讓 field='boom' 的 INSERT 失敗：
        // 這是批次中途的真實 SQL 失敗，可驗證前面已執行的語句被 rollback。
        try (Connection conn = rawConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TRIGGER poison_boom BEFORE INSERT ON " + TABLE
                + " WHEN NEW.field = 'boom' BEGIN"
                + " SELECT RAISE(ABORT, 'injected failure'); END");
        }

        List<PlayerDataStore.FieldChange> poison = List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "written_before_failure", "new"),
            PlayerDataStore.FieldChange.upsert(uuid, "boom", "x"));
        DataStoreException failure = assertThrows(DataStoreException.class,
            () -> store.applyChanges(poison));
        assertEquals("ACELIB-DATA-008", failure.getCode());

        Record loaded = store.load(uuid).orElseThrow();
        assertEquals("old", loaded.getString("k", null), "失敗批次不得影響既有資料");
        assertFalse(loaded.has("written_before_failure"),
            "失敗批次中先執行的語句必須一併 rollback");
        assertEquals(1, countRows(uuid));
    }

    @Test
    @DisplayName("同一批次內重複指定同一欄位：最後一次為準且 revision 只推進一次")
    void duplicateFieldInBatch_lastWinsAndBumpsRevisionOnce() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "initial")));
        assertEquals(1L, revisionOf(uuid, "k"));

        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "k", "first"),
            PlayerDataStore.FieldChange.upsert(uuid, "k", "second")));
        assertEquals("second", store.load(uuid).orElseThrow().getString("k", null));
        assertEquals(2L, revisionOf(uuid, "k"),
            "同一交易前收斂的重複欄位變更只算一次實際寫入");
    }

    // -----------------------------------------------------------------
    // 關閉後重開：資料必須真的落盤
    // -----------------------------------------------------------------

    @Test
    @DisplayName("close 後重開同一檔案，資料完整保留")
    void reopen_preservesData() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "k", "v"),
            PlayerDataStore.FieldChange.upsert(uuid, "n", 5)));
        store.close();

        PlayerDataStore reopened = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        reopened.init();
        try {
            Record loaded = reopened.load(uuid).orElseThrow();
            assertEquals("v", loaded.getString("k", null));
            assertEquals(5, loaded.getInt("n", -1));
        } finally {
            reopened.close();
        }
    }

    // -----------------------------------------------------------------
    // 非法輸入
    // -----------------------------------------------------------------

    @Test
    @DisplayName("null uuid / 空欄位名 / null 欄位名一律拒絕")
    void invalidArguments_rejected() {
        UUID uuid = UUID.randomUUID();
        assertThrows(NullPointerException.class,
            () -> PlayerDataStore.FieldChange.upsert(null, "k", "v"));
        assertThrows(IllegalArgumentException.class,
            () -> PlayerDataStore.FieldChange.upsert(uuid, "", "v"));
        assertThrows(NullPointerException.class,
            () -> PlayerDataStore.FieldChange.upsert(uuid, null, "v"));
        assertThrows(NullPointerException.class, () -> store.load(null));
        assertThrows(NullPointerException.class, () -> store.applyChanges(null));
    }

    @Test
    @DisplayName("空白 owner / 非法 table 名在建構時拒絕")
    void constructorRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class,
            () -> PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0, "  ", "valid_table"));
        assertThrows(IllegalArgumentException.class,
            () -> PlayerDataStores.jdbc(rawDataSource(), SchemaVersion.V1_0,
                PlayerDataStores.DEFAULT_OWNER, "bad-name"));
    }

    @Test
    @DisplayName("從不存在的目錄建 store 時，init 應建立父目錄")
    void init_createsParentDirectory() {
        Path nested = tempDir.resolve("nested/deeper/players.db");
        PlayerDataStore nestedStore = PlayerDataStores.sqlite(nested, SchemaVersion.V1_0);
        try {
            nestedStore.init();
            assertTrue(java.nio.file.Files.exists(nested));
            assertTrue(nestedStore.isInitialized());
        } finally {
            nestedStore.close();
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private Connection rawConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
            "jdbc:sqlite:" + dbFile.toAbsolutePath(), new java.util.Properties());
    }

    private DataSource rawDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                try {
                    return rawConnection();
                } catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private boolean tableExists(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='"
                    + TABLE + "'")) {
            return rs.next();
        }
    }

    private long revisionOf(UUID uuid, String field) throws SQLException {
        try (Connection conn = rawConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT revision FROM " + TABLE
                     + " WHERE player_uuid = ? AND owner = ? AND field = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, store.owner());
            ps.setString(3, field);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "找不到欄位 " + field);
                return rs.getLong(1);
            }
        }
    }

    private int countRows(UUID uuid) throws SQLException, IOException {
        try (Connection conn = rawConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM " + TABLE + " WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("smoke：sqlite driver 可用")
    void sqliteDriverAvailable() throws Exception {
        assertNotNull(rawConnection());
    }
}
