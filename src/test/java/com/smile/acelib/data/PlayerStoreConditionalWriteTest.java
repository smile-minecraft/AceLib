package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 欄位 {@code revision} 條件寫入與原子讀檢查套用的行為測試。
 *
 * <p>三個後端各自驗證：SQLite 檔案（與 {@code JdbcPlayerDataStore} 共用同一份
 * SQL 骨架）、JDBC 通用路徑（SQLite 引擎上的 {@code DataSource}，走
 * {@code PlayerDataStores.jdbc} 建立的實例）、DataStore 轉接（記憶體
 * {@code revision}）。MySQL／MariaDB 的相容案例沿用既有閘門測試，不在本檔重複。</p>
 */
@DisplayName("PlayerDataStore 條件寫入與讀檢查套用")
class PlayerStoreConditionalWriteTest {

    @TempDir
    Path tempDir;

    private final List<PlayerDataStore> toClose = new ArrayList<>();
    private final Map<PlayerDataStore, Path> jdbcFiles = new java.util.IdentityHashMap<>();

    @AfterEach
    void tearDown() {
        for (PlayerDataStore store : toClose) {
            try {
                store.close();
            } catch (RuntimeException ignore) {
                // 關閉冪等；測試中途已關閉的不影響
            }
        }
        toClose.clear();
    }

    // -----------------------------------------------------------------
    // SQLite：applyIfRevision
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SQLite：revision 相符時寫入且 revision 遞增")
    void sqlite_conditionalWrite_matchApplies() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

        PlayerDataStore.ConditionalWriteResult result =
            store.applyIfRevision(uuid, "k", "new", 1L);

        assertTrue(result.applied());
        assertEquals(2L, result.currentRevision());
        assertEquals("new", store.load(uuid).orElseThrow().getString("k", null));
        assertEquals(2L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("SQLite：revision 不符時不寫並回報實際 revision")
    void sqlite_conditionalWrite_mismatchDoesNotWrite() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

        PlayerDataStore.ConditionalWriteResult result =
            store.applyIfRevision(uuid, "k", "stale", 0L);

        assertFalse(result.applied());
        assertEquals(1L, result.currentRevision());
        assertEquals("old", store.load(uuid).orElseThrow().getString("k", null));
        assertEquals(1L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("SQLite：不存在的欄位以 expected 0 建立，不符則拒絕")
    void sqlite_conditionalWrite_createIfAbsent() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();

        PlayerDataStore.ConditionalWriteResult created =
            store.applyIfRevision(uuid, "fresh", "v", 0L);
        assertTrue(created.applied());
        assertEquals(1L, created.currentRevision());

        PlayerDataStore.ConditionalWriteResult refused =
            store.applyIfRevision(uuid, "other", "v", 7L);
        assertFalse(refused.applied());
        assertEquals(0L, refused.currentRevision());
        assertTrue(store.load(uuid).orElseThrow().keys().stream()
            .noneMatch("other"::equals));
    }

    @Test
    @DisplayName("SQLite：條件相符且值相同時仍視為寫入並推進 revision")
    void sqlite_conditionalWrite_sameValueStillBumpsRevision() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "same")));

        PlayerDataStore.ConditionalWriteResult result =
            store.applyIfRevision(uuid, "k", "same", 1L);

        assertTrue(result.applied());
        assertEquals(2L, result.currentRevision());
        assertEquals(2L, store.revisionOf(uuid, "k"));
        assertEquals("same", store.load(uuid).orElseThrow().getString("k", null));
    }

    @Test
    @DisplayName("SQLite：非法輸入一律拒絕")
    void sqlite_conditionalWrite_invalidArguments() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        assertThrows(NullPointerException.class,
            () -> store.applyIfRevision(null, "k", "v", 0L));
        assertThrows(NullPointerException.class,
            () -> store.applyIfRevision(uuid, null, "v", 0L));
        assertThrows(IllegalArgumentException.class,
            () -> store.applyIfRevision(uuid, "  ", "v", 0L));
        assertThrows(NullPointerException.class,
            () -> store.applyIfRevision(uuid, "k", null, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> store.applyIfRevision(uuid, "k", "v", -1L));
        assertThrows(NullPointerException.class,
            () -> store.readCheckApply(null, present -> true, List.of()));
        assertThrows(NullPointerException.class,
            () -> store.readCheckApply(uuid, null, List.of()));
        assertThrows(NullPointerException.class,
            () -> store.readCheckApply(uuid, present -> true, null));
    }

    @Test
    @DisplayName("SQLite：未 init 與已關閉的拒絕語意")
    void sqlite_conditionalWrite_lifecycleGuards() {
        PlayerDataStore fresh = PlayerDataStores.sqlite(
            tempDir.resolve("uninit.db"), SchemaVersion.V1_0);
        toClose.add(fresh);
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalStateException.class,
            () -> fresh.applyIfRevision(uuid, "k", "v", 0L));
        assertThrows(IllegalStateException.class,
            () -> fresh.readCheckApply(uuid, present -> true, List.of()));

        PlayerDataStore store = newSqliteStore();
        store.close();
        assertThrows(DataStoreException.class,
            () -> store.applyIfRevision(uuid, "k", "v", 0L));
        assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid, present -> true, List.of()));
    }

    // -----------------------------------------------------------------
    // SQLite：readCheckApply
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SQLite：檢查通過才套用，套用後可讀到新值")
    void sqlite_readCheckApply_passApplies() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "balance", 100)));

        PlayerDataStore.ReadCheckApplyResult result = store.readCheckApply(uuid,
            present -> present.isPresent()
                && present.get().getInt("balance", -1) == 100,
            List.of(PlayerDataStore.FieldChange.upsert(uuid, "balance", 130)));

        assertEquals(PlayerDataStore.Outcome.APPLIED, result.outcome());
        assertEquals(130, store.load(uuid).orElseThrow().getInt("balance", -1));
    }

    @Test
    @DisplayName("SQLite：檢查拒絕時沒有任何變更")
    void sqlite_readCheckApply_rejectLeavesNothing() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "b", "keep")));
        Map<String, Object> before = snapshotOf(store.load(uuid).orElseThrow());
        long revA = store.revisionOf(uuid, "a");
        long revB = store.revisionOf(uuid, "b");

        PlayerDataStore.ReadCheckApplyResult result = store.readCheckApply(uuid,
            present -> false,
            List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "a", 999),
                PlayerDataStore.FieldChange.upsert(uuid, "c", "new")));

        assertEquals(PlayerDataStore.Outcome.CHECK_REJECTED, result.outcome());
        assertEquals(before, snapshotOf(store.load(uuid).orElseThrow()));
        assertEquals(revA, store.revisionOf(uuid, "a"));
        assertEquals(revB, store.revisionOf(uuid, "b"));
    }

    @Test
    @DisplayName("SQLite：交易內儲存失敗時無部分修改")
    void sqlite_readCheckApply_infraFailureNoPartial() throws Exception {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));
        poisonField(store, "boom");

        DataStoreException failure = assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid,
                present -> true,
                List.of(
                    PlayerDataStore.FieldChange.upsert(uuid, "written_before_failure", "new"),
                    PlayerDataStore.FieldChange.upsert(uuid, "boom", "x"))));
        assertEquals("ACELIB-DATA-008", failure.getCode());

        Record loaded = store.load(uuid).orElseThrow();
        assertEquals("old", loaded.getString("k", null));
        assertFalse(loaded.keys().contains("written_before_failure"));
    }

    @Test
    @DisplayName("SQLite：檢查本身拋錯時原樣傳遞且無部分修改")
    void sqlite_readCheckApply_checkThrowsPropagates() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

        IllegalStateException checkFailure = new IllegalStateException("boom-check");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> store.readCheckApply(uuid,
                present -> {
                    throw checkFailure;
                },
                List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "new"))));

        assertTrue(thrown == checkFailure);
        assertEquals("old", store.load(uuid).orElseThrow().getString("k", null));
        assertEquals(1L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("SQLite：跨玩家的變更批次拒絕")
    void sqlite_readCheckApply_crossPlayerRejected() {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> store.readCheckApply(uuid,
                present -> true,
                List.of(PlayerDataStore.FieldChange.upsert(other, "k", "v"))));
    }

    // -----------------------------------------------------------------
    // SQLite：併發與生命週期
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SQLite：同一 revision 併發寫入恰好一個成功")
    void sqlite_concurrentConditionalWrite_exactlyOneWins() throws Exception {
        PlayerDataStore store = newSqliteStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "seed")));

        List<Boolean> applied = runConcurrentConditionalWrites(store, uuid, 1L);
        assertEquals(1, applied.stream().filter(b -> b).count());
        assertEquals(2L, store.revisionOf(uuid, "k"));
        String value = store.load(uuid).orElseThrow().getString("k", null);
        assertTrue("from-a".equals(value) || "from-b".equals(value));
    }

    @Test
    @DisplayName("SQLite：關閉重開後 revision 保留，舊 revision 不可再寫")
    void sqlite_reopen_preservesRevision() {
        Path file = tempDir.resolve("reopen.db");
        PlayerDataStore store = PlayerDataStores.sqlite(file, SchemaVersion.V1_0);
        toClose.add(store);
        store.init();
        UUID uuid = UUID.randomUUID();
        assertTrue(store.applyIfRevision(uuid, "k", "v1", 0L).applied());
        store.close();

        PlayerDataStore reopened = PlayerDataStores.sqlite(file, SchemaVersion.V1_0);
        toClose.add(reopened);
        reopened.init();
        assertEquals(1L, reopened.revisionOf(uuid, "k"));
        assertFalse(reopened.applyIfRevision(uuid, "k", "stale", 0L).applied());
        assertTrue(reopened.applyIfRevision(uuid, "k", "v2", 1L).applied());
        assertEquals("v2", reopened.load(uuid).orElseThrow().getString("k", null));
    }

    // -----------------------------------------------------------------
    // JDBC 通用路徑（SQLite 引擎）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("JDBC：條件寫入相符／不符與交易 rollback")
    void jdbc_conditionalWrite_matchMismatchAndRollback() throws Exception {
        PlayerDataStore store = newJdbcStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "old")));

        assertTrue(store.applyIfRevision(uuid, "k", "new", 1L).applied());
        PlayerDataStore.ConditionalWriteResult stale =
            store.applyIfRevision(uuid, "k", "stale", 1L);
        assertFalse(stale.applied());
        assertEquals(2L, stale.currentRevision());
        assertEquals("new", store.load(uuid).orElseThrow().getString("k", null));

        poisonField(store, "boom");
        DataStoreException failure = assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid,
                present -> true,
                List.of(
                    PlayerDataStore.FieldChange.upsert(uuid, "written_before_failure", "new"),
                    PlayerDataStore.FieldChange.upsert(uuid, "boom", "x"))));
        assertEquals("ACELIB-DATA-008", failure.getCode());
        assertFalse(store.load(uuid).orElseThrow().keys()
            .contains("written_before_failure"));
    }

    @Test
    @DisplayName("JDBC：檢查拒絕時沒有任何變更")
    void jdbc_readCheckApply_rejectLeavesNothing() {
        PlayerDataStore store = newJdbcStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 1)));
        Map<String, Object> before = snapshotOf(store.load(uuid).orElseThrow());

        PlayerDataStore.ReadCheckApplyResult result = store.readCheckApply(uuid,
            present -> false,
            List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 999)));

        assertEquals(PlayerDataStore.Outcome.CHECK_REJECTED, result.outcome());
        assertEquals(before, snapshotOf(store.load(uuid).orElseThrow()));
        assertEquals(1L, store.revisionOf(uuid, "a"));
    }

    @Test
    @DisplayName("JDBC：條件相符且值相同時仍視為寫入並推進 revision")
    void jdbc_conditionalWrite_sameValueStillBumpsRevision() {
        PlayerDataStore store = newJdbcStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "same")));

        PlayerDataStore.ConditionalWriteResult result =
            store.applyIfRevision(uuid, "k", "same", 1L);

        assertTrue(result.applied());
        assertEquals(2L, result.currentRevision());
        assertEquals(2L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("JDBC：關閉後新方法以 ACELIB-DATA-005 拒絕")
    void jdbc_closedStore_rejectsNewMethods() {
        PlayerDataStore store = newJdbcStore();
        UUID uuid = UUID.randomUUID();
        store.close();
        DataStoreException first = assertThrows(DataStoreException.class,
            () -> store.applyIfRevision(uuid, "k", "v", 0L));
        assertEquals("ACELIB-DATA-005", first.getCode());
        DataStoreException second = assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid, present -> true, List.of()));
        assertEquals("ACELIB-DATA-005", second.getCode());
    }

    @Test
    @DisplayName("JDBC：同一 revision 併發寫入恰好一個成功")
    void jdbc_concurrentConditionalWrite_exactlyOneWins() throws Exception {
        PlayerDataStore store = newJdbcStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "seed")));

        List<Boolean> applied = runConcurrentConditionalWrites(store, uuid, 1L);
        assertEquals(1, applied.stream().filter(b -> b).count());
        assertEquals(2L, store.revisionOf(uuid, "k"));
    }

    // -----------------------------------------------------------------
    // DataStore 轉接：記憶體 revision
    // -----------------------------------------------------------------

    @Test
    @DisplayName("轉接：revision 相符寫入、不符回報實際值")
    void adapter_conditionalWrite_matchAndMismatch() {
        PlayerDataStore store = newAdapterStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 1)));
        assertEquals(1L, store.revisionOf(uuid, "a"));

        PlayerDataStore.ConditionalWriteResult ok =
            store.applyIfRevision(uuid, "a", 2, 1L);
        assertTrue(ok.applied());
        assertEquals(2L, ok.currentRevision());
        assertEquals(2, store.load(uuid).orElseThrow().getInt("a", -1));

        PlayerDataStore.ConditionalWriteResult stale =
            store.applyIfRevision(uuid, "a", 3, 1L);
        assertFalse(stale.applied());
        assertEquals(2L, stale.currentRevision());
        assertEquals(2, store.load(uuid).orElseThrow().getInt("a", -1));
    }

    @Test
    @DisplayName("轉接：檢查拒絕與儲存失敗都不留部分修改")
    void adapter_readCheckApply_rejectAndSaveFailure() {
        FakeDelegate delegate = new FakeDelegate();
        delegate.init();
        PlayerDataStore store = PlayerDataStores.fromDataStore(delegate);
        toClose.add(store);
        store.init();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "b", "keep")));

        PlayerDataStore.ReadCheckApplyResult rejected = store.readCheckApply(uuid,
            present -> false,
            List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 999)));
        assertEquals(PlayerDataStore.Outcome.CHECK_REJECTED, rejected.outcome());
        assertEquals(1, store.load(uuid).orElseThrow().getInt("a", -1));

        delegate.failOnSave = true;
        DataStoreException failure = assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid,
                present -> true,
                List.of(
                    PlayerDataStore.FieldChange.upsert(uuid, "a", 2),
                    PlayerDataStore.FieldChange.upsert(uuid, "c", "new"))));
        assertEquals("ACELIB-DATA-008", failure.getCode());
        assertEquals(1, store.load(uuid).orElseThrow().getInt("a", -1));
        assertFalse(store.load(uuid).orElseThrow().keys().contains("c"));
        assertEquals(2L, store.revisionOf(uuid, "a"));
    }

    @Test
    @DisplayName("轉接：重建後記憶體 revision 歸零（只對同一實例生命週期有效）")
    void adapter_revisionResetsOnRebuild() {
        FakeDelegate delegate = new FakeDelegate();
        delegate.init();
        PlayerDataStore first = PlayerDataStores.fromDataStore(delegate);
        toClose.add(first);
        first.init();
        UUID uuid = UUID.randomUUID();
        first.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 1)));
        assertEquals(1L, first.revisionOf(uuid, "a"));

        PlayerDataStore rebuilt = PlayerDataStores.fromDataStore(delegate);
        toClose.add(rebuilt);
        rebuilt.init();
        assertEquals(0L, rebuilt.revisionOf(uuid, "a"));
        assertEquals(1, rebuilt.load(uuid).orElseThrow().getInt("a", -1));
    }

    @Test
    @DisplayName("轉接：同一 revision 併發寫入恰好一個成功")
    void adapter_concurrentConditionalWrite_exactlyOneWins() throws Exception {
        PlayerDataStore store = newAdapterStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "seed")));

        List<Boolean> applied = runConcurrentConditionalWrites(store, uuid, 1L);
        assertEquals(1, applied.stream().filter(b -> b).count());
        assertEquals(2L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("轉接：條件相符且值相同時仍視為寫入並推進 revision")
    void adapter_conditionalWrite_sameValueStillBumpsRevision() {
        PlayerDataStore store = newAdapterStore();
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "k", "same")));

        PlayerDataStore.ConditionalWriteResult result =
            store.applyIfRevision(uuid, "k", "same", 1L);

        assertTrue(result.applied());
        assertEquals(2L, result.currentRevision());
        assertEquals(2L, store.revisionOf(uuid, "k"));
    }

    @Test
    @DisplayName("轉接：重建後計數歸零，既有欄位以期望 0 可寫入（已知限制）")
    void adapter_rebuiltConditionalWrite_usesResetCounter() {
        FakeDelegate delegate = new FakeDelegate();
        delegate.init();
        PlayerDataStore first = PlayerDataStores.fromDataStore(delegate);
        toClose.add(first);
        first.init();
        UUID uuid = UUID.randomUUID();
        first.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "a", 1)));
        assertEquals(1L, first.revisionOf(uuid, "a"));

        // 重建後記憶體計數歸零：delegate 裡的欄位仍在，但新實例看不到舊計數，
        // 因此既有欄位以期望 0 會判定相符並覆寫。這是轉接的已知限制
        // （JDBC／SQLite 的 revision 留在資料表裡，不會有此行為），
        // 呼叫端不得在轉接重建後沿用舊 revision。
        PlayerDataStore rebuilt = PlayerDataStores.fromDataStore(delegate);
        toClose.add(rebuilt);
        rebuilt.init();
        assertEquals(0L, rebuilt.revisionOf(uuid, "a"));

        PlayerDataStore.ConditionalWriteResult result =
            rebuilt.applyIfRevision(uuid, "a", 2, 0L);
        assertTrue(result.applied());
        assertEquals(1L, result.currentRevision());
        assertEquals(2, rebuilt.load(uuid).orElseThrow().getInt("a", -1));
    }

    @Test
    @DisplayName("轉接：關閉後新方法以 ACELIB-DATA-005 拒絕")
    void adapter_closedStore_rejectsNewMethods() {
        PlayerDataStore store = newAdapterStore();
        UUID uuid = UUID.randomUUID();
        store.close();
        assertThrows(DataStoreException.class,
            () -> store.applyIfRevision(uuid, "k", "v", 0L));
        assertThrows(DataStoreException.class,
            () -> store.readCheckApply(uuid, present -> true, List.of()));
    }

    // -----------------------------------------------------------------
    // 預設拒絕：外部 SPI 實作者可不實作
    // -----------------------------------------------------------------

    @Test
    @DisplayName("未覆寫的新方法預設拋 UnsupportedOperationException")
    void defaultMethods_rejectWithoutOverride() {
        PlayerDataStore stub = new MinimalStore();
        UUID uuid = UUID.randomUUID();
        assertThrows(UnsupportedOperationException.class,
            () -> stub.applyIfRevision(uuid, "k", "v", 0L));
        assertThrows(UnsupportedOperationException.class,
            () -> stub.readCheckApply(uuid, present -> true, List.of()));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private PlayerDataStore newSqliteStore() {
        Path file = tempDir.resolve("cond-" + UUID.randomUUID() + ".db");
        PlayerDataStore store = PlayerDataStores.sqlite(file, SchemaVersion.V1_0);
        store.init();
        toClose.add(store);
        return store;
    }

    private PlayerDataStore newJdbcStore() {
        Path file = tempDir.resolve("cond-jdbc-" + UUID.randomUUID() + ".db");
        PlayerDataStore store = PlayerDataStores.jdbc(
            sqliteDataSource(file), SchemaVersion.V1_0,
            "cond-jdbc-" + UUID.randomUUID().toString().replace("-", ""),
            PlayerDataStores.DEFAULT_TABLE);
        store.init();
        toClose.add(store);
        jdbcFiles.put(store, file);
        return store;
    }

    private PlayerDataStore newAdapterStore() {
        FakeDelegate delegate = new FakeDelegate();
        delegate.init();
        PlayerDataStore store = PlayerDataStores.fromDataStore(delegate);
        store.init();
        toClose.add(store);
        return store;
    }

    /**
     * 以 SQLite 引擎支撐的通用 JDBC {@code DataSource}，走
     * {@code JdbcPlayerDataStore} 的預設連線路徑（不經 SQLite 子類的
     * {@code PRAGMA} 設定；此處補上 {@code busy_timeout} 讓併發寫入排隊而非
     * 立即回 {@code SQLITE_BUSY}）。
     */
    private static DataSource sqliteDataSource(Path file) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = DriverManager.getConnection(
                    "jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA busy_timeout=5000");
                } catch (SQLException ex) {
                    try {
                        conn.close();
                    } catch (SQLException suppressed) {
                        ex.addSuppressed(suppressed);
                    }
                    throw ex;
                }
                return conn;
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
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
                throw new UnsupportedOperationException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    /**
     * 兩個執行緒以同一個 {@code expectedRevision} 併發條件寫入，回傳各自的
     * {@code applied}（呼叫端斷言恰好一個為 {@code true}）。
     */
    private static List<Boolean> runConcurrentConditionalWrites(
            PlayerDataStore store, UUID uuid, long expectedRevision) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Boolean> applied = java.util.Collections.synchronizedList(new ArrayList<>());
        String[] values = {"from-a", "from-b"};
        List<Thread> threads = new ArrayList<>();
        for (String value : values) {
            Thread thread = new Thread(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("起跑閘門逾時");
                    }
                    applied.add(store.applyIfRevision(uuid, "k", value, expectedRevision)
                        .applied());
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "併發寫入必須在時限內完成");
        for (Thread thread : threads) {
            thread.join(5000L);
        }
        if (failure.get() != null) {
            throw new AssertionError("併發寫入失敗", failure.get());
        }
        assertEquals(2, applied.size());
        return applied;
    }

    private static Map<String, Object> snapshotOf(Record record) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : record.keys()) {
            snapshot.put(key, record.get(key));
        }
        return snapshot;
    }

    private void poisonField(PlayerDataStore store, String field) throws Exception {
        Path file = sqliteFileOf(store);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TRIGGER poison_" + field + " BEFORE INSERT ON "
                + PlayerDataStores.DEFAULT_TABLE
                + " WHEN NEW.field = '" + field + "' BEGIN"
                + " SELECT RAISE(ABORT, 'injected failure'); END");
        }
    }

    private Path sqliteFileOf(PlayerDataStore store) throws Exception {
        Path tracked = jdbcFiles.get(store);
        if (tracked != null) {
            return tracked;
        }
        java.lang.reflect.Method method = store.getClass().getDeclaredMethod("databaseFile");
        method.setAccessible(true);
        return (Path) method.invoke(store);
    }

    /** 可注入 {@code save()} 失敗的最小 delegate（記憶體樹沿用 {@code MemoryRecord} 語意）。 */
    static final class FakeDelegate implements DataStore {

        final MemoryRecord rootView = new MemoryRecord();
        boolean initialized;
        boolean closed;
        boolean failOnSave;

        @Override
        public String name() {
            return "fake-cond";
        }

        @Override
        public SchemaVersion schemaVersion() {
            return SchemaVersion.V1_0;
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void init() {
            if (closed) {
                throw new DataStoreException("ACELIB-DATA-005", "store 'fake-cond' is closed");
            }
            initialized = true;
        }

        @Override
        public Record root() {
            if (!initialized) {
                throw new IllegalStateException("init() must be called before root()");
            }
            if (closed) {
                throw new DataStoreException("ACELIB-DATA-005", "store 'fake-cond' is closed");
            }
            return rootView;
        }

        @Override
        public void save() {
            if (failOnSave) {
                throw new DataStoreException("ACELIB-DATA-001", "[fake:save] injected save failure");
            }
        }

        @Override
        public void flush() {
            save();
        }

        @Override
        public DataStore registerMigration(DataMigration migration) {
            return this;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<T> submit(
                Executor executor, Callable<T> task) {
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(task.call());
            } catch (Exception ex) {
                return java.util.concurrent.CompletableFuture.failedFuture(ex);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 未覆寫新預設方法的最小 SPI 實作（驗證預設拒絕用）。 */
    static final class MinimalStore implements PlayerDataStore {

        @Override
        public String name() {
            return "minimal";
        }

        @Override
        public String owner() {
            return PlayerDataStores.DEFAULT_OWNER;
        }

        @Override
        public SchemaVersion schemaVersion() {
            return SchemaVersion.V1_0;
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void init() {
        }

        @Override
        public Optional<Record> load(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
            return Optional.empty();
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            Objects.requireNonNull(changes, "changes");
        }

        @Override
        public void deletePlayer(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
        }

        @Override
        public long revisionOf(UUID uuid, String field) {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(field, "field");
            return 0L;
        }

        @Override
        public int fieldCount(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
