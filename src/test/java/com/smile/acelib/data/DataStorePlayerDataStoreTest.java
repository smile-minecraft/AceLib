package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataStores#fromDataStore(DataStore)} 轉接的失敗回復測試。
 *
 * <p>轉接把 delegate 整棵 tree 當逐玩家語意使用：{@code applyChanges}／
 * {@code deletePlayer} 先改記憶體樹再呼叫 {@code save()}。{@code save()}
 * 失敗時，記憶體樹、欄位存在性與 revision 都必須回到操作前，呼叫端失敗後
 * 讀到的是操作前的值；復原本身失敗時必須留下原因，不能靜默。</p>
 *
 * <p>只保證 AceLib 自己的記憶體視圖回到操作前；delegate 的 {@code save()}
 * 若已部分落盤，不可逆。revision 只放在轉接的記憶體裡，只對同一轉接實例
 * 的生命週期有效，不跨實例、不持久化。</p>
 */
@DisplayName("DataStorePlayerDataStore 失敗回復")
class DataStorePlayerDataStoreTest {

    @TempDir
    Path tempDir;

    private FakeDataStore delegate;
    private PlayerDataStore store;

    @BeforeEach
    void setUp() {
        delegate = new FakeDataStore();
        delegate.init();
        store = PlayerDataStores.fromDataStore(delegate);
        store.init();
    }

    // -----------------------------------------------------------------
    // save 失敗：值、存在性、revision 還原
    // -----------------------------------------------------------------

    @Test
    @DisplayName("save 失敗後同欄位讀回操作前的值且 revision 不動")
    void saveFailure_upsertRestoresPreviousValueAndRevision() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 1)));
        assertEquals(1L, store.revisionOf(uuid, "level"));

        delegate.failOnSave = true;
        DataStoreException failure = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 2))));
        assertEquals("ACELIB-DATA-008", failure.getCode());

        assertEquals(1, store.load(uuid).orElseThrow().getInt("level", -1));
        assertEquals(1, store.fieldCount(uuid));
        assertEquals(1L, store.revisionOf(uuid, "level"));
    }

    @Test
    @DisplayName("save 失敗後新增欄位不存在且 revision 不動")
    void saveFailure_newFieldRestoresAbsence() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "kept", "a")));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "extra", "b"))));

        assertNull(store.load(uuid).orElseThrow().get("extra"));
        assertEquals("a", store.load(uuid).orElseThrow().getString("kept", null));
        assertEquals(1, store.fieldCount(uuid));
        assertEquals(1L, store.revisionOf(uuid, "kept"));
        assertEquals(1L, store.revisionOf(uuid, "extra"));
    }

    @Test
    @DisplayName("save 失敗後全新玩家不留痕跡")
    void saveFailure_brandNewPlayerLeavesNoTrace() {
        UUID uuid = UUID.randomUUID();

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 9))));

        assertTrue(store.load(uuid).isEmpty());
        assertEquals(0, store.fieldCount(uuid));
        assertEquals(0L, store.revisionOf(uuid, "level"));
    }

    @Test
    @DisplayName("save 失敗後刪除操作還原欄位")
    void saveFailure_deletionRestoresField() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "gone", "v"),
            PlayerDataStore.FieldChange.upsert(uuid, "kept", "k")));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "gone"))));

        assertEquals("v", store.load(uuid).orElseThrow().getString("gone", null));
        assertEquals(2, store.fieldCount(uuid));
        assertEquals(2L, store.revisionOf(uuid, "gone"));
    }

    @Test
    @DisplayName("save 失敗後巢狀值深層還原")
    void saveFailure_nestedValueRestoredDeeply() {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("city", "Taipei");
        before.put("tags", new ArrayList<>(List.of("a", "b")));
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "profile", before)));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("city", "Osaka");
        after.put("tags", new ArrayList<>(List.of("z")));
        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "profile", after))));

        assertEquals(before, store.load(uuid).orElseThrow().get("profile"));
        assertEquals(1L, store.revisionOf(uuid, "profile"));
    }

    @Test
    @DisplayName("save 失敗後多欄位批次整批還原")
    void saveFailure_multiFieldBatchRestoresAll() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "f1", "a")));
        long revisionBefore = store.revisionOf(uuid, "f1");

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "f1", "x"),
                PlayerDataStore.FieldChange.upsert(uuid, "f2", "y"),
                PlayerDataStore.FieldChange.upsert(uuid, "f3", "z"))));

        Optional<Record> loaded = store.load(uuid);
        assertTrue(loaded.isPresent());
        assertEquals("a", loaded.get().getString("f1", null));
        assertNull(loaded.get().get("f2"));
        assertNull(loaded.get().get("f3"));
        assertEquals(1, store.fieldCount(uuid));
        assertEquals(revisionBefore, store.revisionOf(uuid, "f1"));
    }

    @Test
    @DisplayName("失敗後重試成功且 revision 只推進一次")
    void retryAfterFailure_succeeds() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 1)));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 2))));

        delegate.allowSave();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 2)));

        assertEquals(2, store.load(uuid).orElseThrow().getInt("level", -1));
        assertEquals(2L, store.revisionOf(uuid, "level"));
    }

    // -----------------------------------------------------------------
    // deletePlayer
    // -----------------------------------------------------------------

    @Test
    @DisplayName("deletePlayer 失敗後資料與 revision 還原")
    void deletePlayer_failureRestores() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 3)));

        delegate.failOnSave = true;
        DataStoreException failure = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class, () -> store.deletePlayer(uuid));
        assertEquals("ACELIB-DATA-008", failure.getCode());

        assertEquals(3, store.load(uuid).orElseThrow().getInt("level", -1));
        assertEquals(1L, store.revisionOf(uuid, "level"));
    }

    @Test
    @DisplayName("deletePlayer 對從未寫入的玩家是 no-op 且不呼叫 save")
    void deletePlayer_unknownPlayerIsNoOpWithoutSave() {
        UUID uuid = UUID.randomUUID();
        int savesBefore = delegate.saveCalls;

        delegate.failOnSave = true;
        store.deletePlayer(uuid);

        assertEquals(savesBefore, delegate.saveCalls);
        assertTrue(store.load(uuid).isEmpty());
    }

    @Test
    @DisplayName("deletePlayer 成功後資料消失且 revision 清除")
    void deletePlayer_successRemovesDataAndRevision() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 3)));

        store.deletePlayer(uuid);

        assertTrue(store.load(uuid).isEmpty());
        assertEquals(0, store.fieldCount(uuid));
        assertEquals(0L, store.revisionOf(uuid, "level"));
    }

    // -----------------------------------------------------------------
    // 復原本身失敗：不吞錯
    // -----------------------------------------------------------------

    @Test
    @DisplayName("復原本身失敗時以 suppressed 留下原因且 revision 照樣還原")
    void restoreFailure_attachesSuppressed() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 1)));

        delegate.failOnSave = true;
        delegate.armMutationFailureOnSave();
        DataStoreException failure = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 2))));

        assertEquals("ACELIB-DATA-008", failure.getCode());
        assertNotNull(failure.getSuppressed());
        assertTrue(failure.getSuppressed().length > 0,
            "還原失敗必須掛在 suppressed，不可靜默");
        assertEquals(1L, store.revisionOf(uuid, "level"));
    }

    // -----------------------------------------------------------------
    // 特殊資料的失敗還原
    // -----------------------------------------------------------------

    @Test
    @DisplayName("save 失敗後頂層 null 欄位仍存在")
    void saveFailure_topLevelNullFieldPreserved() {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("nullField", null);
        seed.put("normal", "keep");
        delegate.root().set("players." + uuid, seed);
        assertEquals(2, store.fieldCount(uuid));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "nullField", "changed"),
                PlayerDataStore.FieldChange.upsert(uuid, "normal", "changed"))));

        Optional<Record> loaded = store.load(uuid);
        assertTrue(loaded.isPresent());
        assertTrue(loaded.get().keys().contains("nullField"));
        assertNull(loaded.get().get("nullField"));
        assertEquals("keep", loaded.get().getString("normal", null));
        assertEquals(2, store.fieldCount(uuid));
        assertEquals(0L, store.revisionOf(uuid, "nullField"));
    }

    @Test
    @DisplayName("save 失敗後空玩家節點仍存在且為空")
    void saveFailure_emptyPlayerNodePreserved() {
        UUID uuid = UUID.randomUUID();
        delegate.root().set("players." + uuid, new LinkedHashMap<>());
        assertTrue(store.load(uuid).isEmpty());

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "late", 1))));

        Record node = delegate.root().getRecord("players." + uuid, null);
        assertNotNull(node);
        assertTrue(node.keys().isEmpty());
        assertTrue(store.load(uuid).isEmpty());
        assertEquals(0L, store.revisionOf(uuid, "late"));
    }

    @Test
    @DisplayName("save 失敗後字面點號鍵保持原字形")
    void saveFailure_dottedLiteralKeyPreserved() {
        UUID uuid = UUID.randomUUID();
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("a.b", "dotted");
        seed.put("plain", "v");
        delegate.root().set("players." + uuid, seed);

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "plain", "v2"))));

        Record node = delegate.root().getRecord("players." + uuid, null);
        assertNotNull(node);
        assertTrue(node.keys().contains("a.b"));
        assertEquals("v", node.get("plain"));
        assertEquals("dotted", ((MemoryRecord) node).snapshot().get("a.b"));
        assertEquals(2, store.fieldCount(uuid));
    }

    // -----------------------------------------------------------------
    // 多玩家混合批次
    // -----------------------------------------------------------------

    @Test
    @DisplayName("多玩家混合批次失敗後全部還原")
    void saveFailure_multiPlayerMixedBatchRestoresAll() {
        UUID modify = UUID.randomUUID();
        UUID remove = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(modify, "f", "a-old")));
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(remove, "g", "b-old")));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(modify, "f", "a-new"),
                PlayerDataStore.FieldChange.deletion(remove, "g"),
                PlayerDataStore.FieldChange.upsert(fresh, "h", "c-new"))));

        assertEquals("a-old", store.load(modify).orElseThrow().getString("f", null));
        assertEquals("b-old", store.load(remove).orElseThrow().getString("g", null));
        assertTrue(store.load(fresh).isEmpty());
        assertEquals(1L, store.revisionOf(modify, "f"));
        assertEquals(1L, store.revisionOf(remove, "g"));
        assertEquals(0L, store.revisionOf(fresh, "h"));
    }

    @Test
    @DisplayName("其中一位玩家還原失敗時其他玩家仍完整還原")
    void restoreFailure_onePlayerFailsOthersStillRestored() {
        UUID doomed = UUID.randomUUID();
        UUID spared = UUID.randomUUID();
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(doomed, "f", "doomed-old")));
        store.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(spared, "f", "spared-old")));

        delegate.failOnSave = true;
        delegate.armSelectiveRestoreFailure(doomed.toString());
        DataStoreException failure = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class,
            () -> store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(doomed, "f", "doomed-new"),
                PlayerDataStore.FieldChange.upsert(spared, "f", "spared-new"))));

        assertEquals("ACELIB-DATA-008", failure.getCode());
        assertTrue(failure.getSuppressed().length > 0);
        assertEquals("spared-old", store.load(spared).orElseThrow().getString("f", null));
        assertEquals(1L, store.revisionOf(spared, "f"));
        assertEquals(1L, store.revisionOf(doomed, "f"));
    }

    @Test
    @DisplayName("全新玩家批次失敗後原本不存在的 players 根節點消失")
    void saveFailure_freshBatchRemovesCreatedPlayersRoot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertNull(delegate.root().getRecord("players", null));

        delegate.failOnSave = true;
        org.junit.jupiter.api.Assertions.assertThrows(DataStoreException.class,
            () -> store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(first, "f", 1),
                PlayerDataStore.FieldChange.upsert(second, "g", 2))));

        assertTrue(store.load(first).isEmpty());
        assertTrue(store.load(second).isEmpty());
        assertNull(delegate.root().getRecord("players", null));
    }

    // -----------------------------------------------------------------
    // 成功路徑不受影響
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功路徑：寫入、刪除與空批次維持既有語意")
    void successPath_keepsExistingSemantics() {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "a", 1),
            PlayerDataStore.FieldChange.upsert(uuid, "b", 2)));
        assertEquals(1, store.load(uuid).orElseThrow().getInt("a", -1));
        assertEquals(2, store.fieldCount(uuid));

        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "a")));
        assertNull(store.load(uuid).orElseThrow().get("a"));
        assertEquals(1, store.fieldCount(uuid));

        store.applyChanges(List.of(PlayerDataStore.FieldChange.deletion(uuid, "b")));
        assertTrue(store.load(uuid).isEmpty());

        int savesBefore = delegate.saveCalls;
        store.applyChanges(List.of());
        assertEquals(savesBefore, delegate.saveCalls);
    }

    @Test
    @DisplayName("未初始化的 delegate 不可包裝")
    void fromDataStore_rejectsUninitializedDelegate() {
        FakeDataStore fresh = new FakeDataStore();
        DataStoreException failure = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class, () -> PlayerDataStores.fromDataStore(fresh));
        assertEquals("ACELIB-DATA-005", failure.getCode());
    }

    @Test
    @DisplayName("真實 JsonFileDataStore 後端可寫讀刪且重開仍在")
    void realJsonDelegate_roundtripAndReload(@TempDir Path dir) {
        Path file = dir.resolve("players.json");
        JsonFileDataStore first = new JsonFileDataStore("real", file, SchemaVersion.V1_0, new JsonCodecImpl());
        first.init();
        PlayerDataStore wrapped = PlayerDataStores.fromDataStore(first);
        wrapped.init();

        UUID uuid = UUID.randomUUID();
        wrapped.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 7)));
        assertEquals(7, wrapped.load(uuid).orElseThrow().getInt("level", -1));
        assertEquals(1, wrapped.fieldCount(uuid));

        wrapped.deletePlayer(uuid);
        assertTrue(wrapped.load(uuid).isEmpty());

        wrapped.applyChanges(List.of(PlayerDataStore.FieldChange.upsert(uuid, "level", 8)));
        first.close();

        JsonFileDataStore second = new JsonFileDataStore("real", file, SchemaVersion.V1_0, new JsonCodecImpl());
        second.init();
        PlayerDataStore reopened = PlayerDataStores.fromDataStore(second);
        reopened.init();
        assertEquals(8, reopened.load(uuid).orElseThrow().getInt("level", -1));
        second.close();
    }

    // -----------------------------------------------------------------
    // 可注入 save 失敗的假 delegate（記憶體樹沿用 MemoryRecord 語意）
    // -----------------------------------------------------------------

    static final class FakeDataStore implements DataStore {

        final GuardedRoot rootView = new GuardedRoot();
        boolean initialized;
        boolean closed;
        boolean failOnSave;
        int saveCalls;

        @Override
        public String name() {
            return "fake";
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
                throw new DataStoreException("ACELIB-DATA-005", "store 'fake' is closed");
            }
            initialized = true;
        }

        @Override
        public Record root() {
            if (!initialized) {
                throw new IllegalStateException("init() must be called before root()");
            }
            if (closed) {
                throw new DataStoreException("ACELIB-DATA-005", "store 'fake' is closed");
            }
            return rootView;
        }

        @Override
        public void save() {
            saveCalls++;
            if (failOnSave) {
                if (rootView.armOnNextSave) {
                    rootView.armOnNextSave = false;
                    rootView.failMutations = true;
                }
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
        public <T> CompletableFuture<T> submit(Executor executor, java.util.concurrent.Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        void allowSave() {
            failOnSave = false;
            rootView.failMutations = false;
            rootView.failRestorePathContains = null;
        }

        void armMutationFailureOnSave() {
            rootView.armOnNextSave = true;
        }

        void armSelectiveRestoreFailure(String pathToken) {
            rootView.armOnNextSave = true;
            rootView.failRestorePathContains = pathToken;
        }
    }

    /**
     * 可在 {@code save()} 被呼叫的同時讓後續 {@code set}／{@code remove}
     * 失敗的根視圖委派，用來模擬「還原本身失敗」。讀取一律委派給內部的
     * {@link MemoryRecord}，不受開關影響。
     */
    static final class GuardedRoot implements Record {

        final MemoryRecord inner = new MemoryRecord();
        boolean failMutations;
        boolean armOnNextSave;
        String failRestorePathContains;

        @Override
        public String key() {
            return inner.key();
        }

        @Override
        public boolean has(String path) {
            return inner.has(path);
        }

        @Override
        public Object get(String path) {
            return inner.get(path);
        }

        @Override
        public Object set(String path, Object value) {
            requireUsable(path);
            return inner.set(path, value);
        }

        @Override
        public boolean remove(String path) {
            requireUsable(path);
            return inner.remove(path);
        }

        @Override
        public java.util.Set<String> keys() {
            return inner.keys();
        }

        @Override
        public Record copy() {
            return inner.copy();
        }

        @Override
        public String getString(String path, String defaultValue) {
            return inner.getString(path, defaultValue);
        }

        @Override
        public int getInt(String path, int defaultValue) {
            return inner.getInt(path, defaultValue);
        }

        @Override
        public long getLong(String path, long defaultValue) {
            return inner.getLong(path, defaultValue);
        }

        @Override
        public double getDouble(String path, double defaultValue) {
            return inner.getDouble(path, defaultValue);
        }

        @Override
        public boolean getBoolean(String path, boolean defaultValue) {
            return inner.getBoolean(path, defaultValue);
        }

        @Override
        public Record getRecord(String path, Record defaultValue) {
            return inner.getRecord(path, defaultValue);
        }

        @Override
        public <T> T getObject(String path, Class<T> type, T defaultValue) {
            return inner.getObject(path, type, defaultValue);
        }

        private void requireUsable(String path) {
            if (failMutations
                    && (failRestorePathContains == null || path.contains(failRestorePathContains))) {
                throw new DataStoreException("ACELIB-DATA-008", "[fake:mutate] injected mutation failure");
            }
        }
    }
}
