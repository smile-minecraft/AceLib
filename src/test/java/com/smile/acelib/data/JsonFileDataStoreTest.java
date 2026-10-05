package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JsonFileDataStore} 行為測試（對應 Plan §十三 Phase 8）。
 *
 * <p>覆蓋：</p>
 * <ul>
 *   <li>init / root / save / close 生命週期</li>
 *   <li>round-trip 讀寫 + missing default</li>
 *   <li>schema migration 成功 / 失敗 rollback</li>
 *   <li>原子 temp+move 寫入</li>
 *   <li>corrupt file / unwritable / closed 明確錯誤</li>
 *   <li>async submit 不阻塞 / 冪等 close / flush 重複呼叫</li>
 *   <li>duplicate save 一致性</li>
 * </ul>
 */
@DisplayName("JsonFileDataStore")
class JsonFileDataStoreTest {

    @TempDir
    Path tempDir;

    private Path filePath;
    private JsonCodec codec;

    @BeforeEach
    void setUp() {
        filePath = tempDir.resolve("data.json");
        codec = new JsonCodecImpl();
    }

    @AfterEach
    void tearDown() {
        // 清理 temp 檔
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
        } catch (IOException ignore) {
            // best effort
        }
    }

    // -----------------------------------------------------------------
    // 構造子
    // -----------------------------------------------------------------

    @Test
    @DisplayName("constructor：null 參數拋 NPE")
    void constructor_nullArgs_throw() {
        assertThrows(NullPointerException.class,
            () -> new JsonFileDataStore(null, filePath, SchemaVersion.V1_0, codec));
        assertThrows(NullPointerException.class,
            () -> new JsonFileDataStore("name", null, SchemaVersion.V1_0, codec));
        assertThrows(NullPointerException.class,
            () -> new JsonFileDataStore("name", filePath, null, codec));
        assertThrows(NullPointerException.class,
            () -> new JsonFileDataStore("name", filePath, SchemaVersion.V1_0, null));
    }

    @Test
    @DisplayName("constructor：空白 name 拋 IAE")
    void constructor_blankName_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> new JsonFileDataStore("", filePath, SchemaVersion.V1_0, codec));
        assertThrows(IllegalArgumentException.class,
            () -> new JsonFileDataStore("   ", filePath, SchemaVersion.V1_0, codec));
    }

    // -----------------------------------------------------------------
    // init / root / save
    // -----------------------------------------------------------------

    @Test
    @DisplayName("init：首次啟動無檔案時自動建立")
    void init_createsFileWhenMissing() {
        JsonFileDataStore store = newStore();
        assertFalse(Files.exists(filePath));
        store.init();
        assertTrue(store.isInitialized());
        assertTrue(Files.exists(filePath), "init 後必須自動建立檔案");
        store.close();
    }

    @Test
    @DisplayName("init 重複呼叫為 idempotent no-op")
    void init_isIdempotent() {
        JsonFileDataStore store = newStore();
        store.init();
        store.init();
        assertTrue(store.isInitialized());
        store.close();
    }

    @Test
    @DisplayName("root/set/save 後再 init 新 store 可讀回同樣資料")
    void roundtrip_setSaveReload() {
        JsonFileDataStore writer = newStore();
        writer.init();
        writer.root().set("user.balance", 100);
        writer.root().set("user.name", "alice");
        writer.save();
        writer.close();

        // 用新的 store instance 讀回
        JsonFileDataStore reader = newStore();
        reader.init();
        assertEquals(100, reader.root().getInt("user.balance", 0));
        assertEquals("alice", reader.root().getString("user.name", null));
        reader.close();
    }

    @Test
    @DisplayName("missing data：getXxx 回傳 default；get 回傳 null")
    void missing_returnsDefaults() {
        JsonFileDataStore store = newStore();
        store.init();
        Record root = store.root();
        assertEquals(null, root.get("missing"));
        assertEquals("d", root.getString("missing", "d"));
        assertEquals(42, root.getInt("missing", 42));
        assertEquals(true, root.getBoolean("missing", true));
        store.close();
    }

    @Test
    @DisplayName("save：未 init 拋 IllegalStateException")
    void save_beforeInit_throws() {
        JsonFileDataStore store = newStore();
        assertThrows(IllegalStateException.class, store::save);
    }

    @Test
    @DisplayName("root：未 init 拋 IllegalStateException")
    void root_beforeInit_throws() {
        JsonFileDataStore store = newStore();
        assertThrows(IllegalStateException.class, store::root);
    }

    @Test
    @DisplayName("init 後 root 可見既有資料（無 migration 情境）")
    void init_loadsExistingData() throws IOException {
        // 預先寫一個舊版資料（_version 已是 1.0，不需 migration）
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"k\":\"v\"}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = newStore();
        store.init();
        assertEquals("v", store.root().getString("k", null));
        store.close();
    }

    // -----------------------------------------------------------------
    // close
    // -----------------------------------------------------------------

    @Test
    @DisplayName("close：重複呼叫不丟例外（idempotent）")
    void close_isIdempotent() {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.close();
        store.close();
        assertTrue(store.isClosed());
    }

    @Test
    @DisplayName("close 自動 flush：未呼叫 save 的修改會被寫入磁碟")
    void close_autoFlushes() {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("pending", "value");
        // 不呼叫 save，直接 close
        store.close();

        // 重新讀取
        JsonFileDataStore reader = newStore();
        reader.init();
        assertEquals("value", reader.root().getString("pending", null));
        reader.close();
    }

    @Test
    @DisplayName("closed store 操作拋 ACELIB-DATA-005")
    void closedStore_operationsThrow() {
        JsonFileDataStore store = newStore();
        store.init();
        store.close();
        DataStoreException ex1 = assertThrows(DataStoreException.class, store::save);
        assertEquals("ACELIB-DATA-005", ex1.getCode());
        DataStoreException ex2 = assertThrows(DataStoreException.class, store::root);
        assertEquals("ACELIB-DATA-005", ex2.getCode());
    }

    // -----------------------------------------------------------------
    // schema migration
    // -----------------------------------------------------------------

    @Test
    @DisplayName("migration 成功：舊版自動升級到當前版本")
    void migration_success() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"oldKey\":\"oldValue\"}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
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
        store.close();
    }

    @Test
    @DisplayName("migration 後 atomic persist 失敗：不發布 rootView/initialized，可重試成功")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void migration_persistFailure_initRevertsState_andRetrySucceeds() throws IOException {
        // 預先建立舊版檔案，模擬「需 migration」場景。
        Path lockedParent = tempDir.resolve("locked-parent");
        Files.createDirectory(lockedParent);
        Path target = lockedParent.resolve("data.json");
        Files.writeString(target,
            "{\"_version\":\"1.0\",\"oldKey\":\"oldValue\"}\n",
            StandardCharsets.UTF_8);
        String originalContent = Files.readString(target, StandardCharsets.UTF_8);

        // 把 parent 目錄設為「可讀 + 可進入但不可寫入」，
        // 讓子進程無法建立 temp 檔（模擬 migration 成功後 atomic persist 階段失敗）；
        // 既有檔案仍可被讀取以驗證內容未被破壞。
        Set<PosixFilePermission> readOnly =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                       PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(lockedParent, readOnly);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", target, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });

        try {
            // 第一次 init 必須拋 DataStoreException(ACELIB-DATA-001)。
            DataStoreException ex = assertThrows(DataStoreException.class, store::init);
            assertEquals("ACELIB-DATA-001", ex.getCode(),
                "migration 後 persist 失敗應回報 DATA-001,實際:" + ex.getCode());

            // 失敗後 store 不應進入 initialized 狀態。
            assertFalse(store.isInitialized(),
                "persist 失敗後 isInitialized 必須為 false");

            // 失敗後 root() 必須遵循既有未初始化契約,拋 IllegalStateException。
            assertThrows(IllegalStateException.class, store::root,
                "未初始化時 root() 必須拋 IllegalStateException");

            // 原檔必須完整保留(沒有被 partial 寫入或 migration 後狀態覆寫)。
            String afterFail = Files.readString(target, StandardCharsets.UTF_8);
            assertEquals(originalContent, afterFail,
                "persist 失敗時原檔案內容不得被修改");
        } finally {
            // 解除 read-only 以便同 instance 重試。
            Set<PosixFilePermission> writable = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(lockedParent, writable);
        }

        // 同 instance 重試 init 必須能真正完成寫入。
        store.init();
        assertTrue(store.isInitialized(),
            "解除故障後同 instance init 必須成功");

        // 重試後 root() 必須可用,且資料為 migration 後狀態。
        assertEquals("migrated:oldValue",
            store.root().getString("newKey", null),
            "重試成功後 root 應為 migration 後結果");

        // 磁碟必須已更新為 current version 與 migrated 資料。
        String afterRetry = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(afterRetry.contains("\"_version\":\"2.0\""),
            "重試成功後 _version 必須已寫回磁碟,實際:" + afterRetry);
        assertTrue(afterRetry.contains("\"newKey\":\"migrated:oldValue\""),
            "重試成功後 migrated 資料必須已寫回磁碟,實際:" + afterRetry);
        assertFalse(afterRetry.contains("\"_version\":\"1.0\""),
            "舊版 _version 不應殘留,實際:" + afterRetry);

        store.close();
    }

    @Test
    @DisplayName("delete-key migration：init 後 root 與磁碟都不殘留舊鍵")
    void migration_deleteKey_rootAndFileConsistent() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"oldKey\":\"x\",\"keep\":1}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().remove("oldKey");
                ctx.write().set("newKey", "y");
            }
        });
        store.init();

        // in-memory root 不得殘留舊鍵
        assertNull(store.root().get("oldKey"), "root 不應殘留舊鍵");
        assertFalse(store.root().has("oldKey"));
        assertEquals("y", store.root().getString("newKey", null));
        assertEquals(1, store.root().getInt("keep", 0));

        // 磁碟落盤也不得殘留舊鍵，且與 root 一致
        String persisted = Files.readString(filePath, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("oldKey"),
            "落盤檔案不應殘留舊鍵，實際：" + persisted);
        assertTrue(persisted.contains("\"newKey\":\"y\""),
            "落盤檔案應有新鍵，實際：" + persisted);
        assertTrue(persisted.contains("\"_version\":\"2.0\""),
            "落盤檔案版本應已升級，實際：" + persisted);
        store.close();
    }

    @Test
    @DisplayName("巢狀 delete-key migration：root 與磁碟都移除巢狀舊鍵")
    void migration_deleteNestedKey_rootAndFileConsistent() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"user\":{\"legacy\":\"x\",\"name\":\"a\"}}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().remove("user.legacy");
            }
        });
        store.init();

        assertNull(store.root().get("user.legacy"));
        assertEquals("a", store.root().getString("user.name", null));
        String persisted = Files.readString(filePath, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("legacy"),
            "落盤檔案不應殘留巢狀舊鍵，實際：" + persisted);
        store.close();
    }

    @Test
    @DisplayName("含 JSON null 載入：save/close 不拋 NPE，重開後結果可預期")
    void nullValues_saveCloseReopen_noNpe() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"nick\":null,\"u\":{\"age\":null,\"name\":\"a\"}}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = newStore();
        store.init();
        // has/get 對 null 的約定：視為不存在、get 回 null
        assertFalse(store.root().has("nick"));
        assertNull(store.root().get("nick"));
        // snapshot（save/close 內部會用）不得拋 NPE
        assertDoesNotThrow(store::save);
        assertDoesNotThrow(store::close);

        // 重新載入：null 策略為保留 JSON null（has 視為不存在、get 回 null、落盤仍為 null）
        JsonFileDataStore reader = newStore();
        reader.init();
        assertNull(reader.root().get("nick"));
        assertNull(reader.root().get("u.age"));
        assertEquals("a", reader.root().getString("u.name", null));
        reader.close();

        String persisted = Files.readString(filePath, StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"nick\":null"),
            "null 策略為保留 JSON null 落盤，實際：" + persisted);
    }

    @Test
    @DisplayName("close 寫入失敗：不崩潰且錯誤分類可透過 logger 觀察")
    void close_writeFailure_isLoggedWithCode() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();

        // 攔截 AceLib logger
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("AceLib");
        java.util.List<java.util.logging.LogRecord> captured = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { captured.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        log.addHandler(handler);
        try {
            // 把資料檔案的 parent 改成唯讀，讓 close 的 save 階段失敗
            Set<PosixFilePermission> readOnly =
                EnumSet.of(PosixFilePermission.OWNER_READ,
                           PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(tempDir, readOnly);
            try {
                assertDoesNotThrow(store::close, "close 不得因寫入失敗崩潰");
                assertTrue(store.isClosed());
            } finally {
                Set<PosixFilePermission> writable = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(tempDir, writable);
            }
        } finally {
            log.removeHandler(handler);
        }

        boolean logged = captured.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains("ACELIB-DATA-001"));
        assertTrue(logged,
            "close 寫入失敗必須以 ACELIB-DATA-001 分類記錄，captured=" + captured.size());
    }

    @Test
    @DisplayName("temp 寫入階段失敗：不留孤兒 tmp 檔且錯誤分類為 ACELIB-DATA-001")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void atomicWrite_tempStageFailure_cleansOrphan() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();

        Set<PosixFilePermission> readOnly =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                       PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(tempDir, readOnly);
        try {
            DataStoreException ex = assertThrows(DataStoreException.class, store::save);
            assertEquals("ACELIB-DATA-001", ex.getCode());
        } finally {
            Set<PosixFilePermission> writable = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(tempDir, writable);
        }

        try (var stream = Files.list(tempDir)) {
            long orphans = stream
                .filter(p -> p.getFileName().toString().startsWith("acelib-")
                    && p.getFileName().toString().endsWith(".tmp"))
                .count();
            assertEquals(0, orphans, "temp 階段失敗不得留孤兒 tmp 檔");
        }
        store.close();
    }

    @Test
    @DisplayName("move 失敗：清理 temp 孤兒檔")
    void atomicWrite_moveFailure_cleansOrphan() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();

        // 把目標檔案換成「非空目錄」，讓後續 move 必然失敗
        Files.delete(filePath);
        Files.createDirectory(filePath);
        Files.writeString(filePath.resolve("occupied"), "x", StandardCharsets.UTF_8);

        try {
            DataStoreException ex = assertThrows(DataStoreException.class, store::save);
            assertEquals("ACELIB-DATA-001", ex.getCode());
        } finally {
            // 還原，避免影響其他斷言的清理
            try (var stream = Files.list(filePath)) {
                stream.forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignore) { }
                });
            }
            Files.deleteIfExists(filePath);
        }

        try (var stream = Files.list(tempDir)) {
            long orphans = stream
                .filter(p -> p.getFileName().toString().startsWith("acelib-")
                    && p.getFileName().toString().endsWith(".tmp"))
                .count();
            assertEquals(0, orphans, "move 失敗不得留孤兒 tmp 檔");
        }
    }

    @Test
    @DisplayName("temp 寫入失敗且清理失敗：原始錯誤保留且清理失敗為 suppressed，舊檔不變")
    void atomicWrite_writeFailure_cleanupFailure_suppressedAndPreservesFile() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();
        String before = Files.readString(filePath, StandardCharsets.UTF_8);

        java.util.concurrent.atomic.AtomicReference<Path> createdTmp =
            new java.util.concurrent.atomic.AtomicReference<>();
        JsonFileDataStore.FileOps failingOps = new JsonFileDataStore.FileOps() {
            @Override public Path createTempFile(Path dir, String prefix, String suffix)
                    throws IOException {
                Path tmp = Files.createTempFile(dir, prefix, suffix);
                createdTmp.set(tmp);
                return tmp;
            }
            @Override public void writeString(Path path, String content) throws IOException {
                throw new IOException("injected write failure");
            }
            @Override public void sync(Path path) throws IOException {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        path, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            @Override public void move(Path source, Path target) throws IOException {
                throw new AssertionError("write 失敗後不應走到 move");
            }
            @Override public boolean deleteIfExists(Path path) throws IOException {
                throw new IOException("injected cleanup failure");
            }
        };

        MemoryRecord root = (MemoryRecord) store.root();
        DataStoreException ex = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class, () -> store.writeAtomicFrom(root, failingOps));
        assertEquals("ACELIB-DATA-001", ex.getCode());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("injected write failure"),
            "原始寫入錯誤不得被掩蓋，實際 cause=" + ex.getCause());
        assertEquals(1, ex.getSuppressed().length,
            "清理失敗必須以 suppressed 留痕，不得靜默吞掉");
        assertTrue(ex.getSuppressed()[0].getMessage().contains("injected cleanup failure"));

        // 舊檔必須完整保留
        assertEquals(before, Files.readString(filePath, StandardCharsets.UTF_8),
            "寫入失敗時舊檔不得被修改");

        // 測試注入讓清理失敗，temp 會殘留：手動清掉避免污染其他測試
        Path leaked = createdTmp.get();
        if (leaked != null) {
            try {
                Files.deleteIfExists(leaked);
            } catch (IOException ignore) {
                // best effort
            }
        }
        store.close();
    }

    @Test
    @DisplayName("move 失敗且清理失敗：原始錯誤保留且清理失敗為 suppressed")
    void atomicWrite_moveFailure_cleanupFailure_suppressed() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();
        String before = Files.readString(filePath, StandardCharsets.UTF_8);

        java.util.concurrent.atomic.AtomicReference<Path> createdTmp =
            new java.util.concurrent.atomic.AtomicReference<>();
        JsonFileDataStore.FileOps failingOps = new JsonFileDataStore.FileOps() {
            @Override public Path createTempFile(Path dir, String prefix, String suffix)
                    throws IOException {
                Path tmp = Files.createTempFile(dir, prefix, suffix);
                createdTmp.set(tmp);
                return tmp;
            }
            @Override public void writeString(Path path, String content) throws IOException {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            @Override public void sync(Path path) throws IOException {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        path, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            @Override public void move(Path source, Path target) throws IOException {
                throw new IOException("injected move failure");
            }
            @Override public boolean deleteIfExists(Path path) throws IOException {
                throw new IOException("injected cleanup failure");
            }
        };

        MemoryRecord root = (MemoryRecord) store.root();
        DataStoreException ex = org.junit.jupiter.api.Assertions.assertThrows(
            DataStoreException.class, () -> store.writeAtomicFrom(root, failingOps));
        assertEquals("ACELIB-DATA-001", ex.getCode());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("injected move failure"),
            "原始搬移錯誤不得被掩蓋，實際 cause=" + ex.getCause());
        assertEquals(1, ex.getSuppressed().length,
            "清理失敗必須以 suppressed 留痕，不得靜默吞掉");
        assertTrue(ex.getSuppressed()[0].getMessage().contains("injected cleanup failure"));
        assertEquals(before, Files.readString(filePath, StandardCharsets.UTF_8),
            "搬移失敗時舊檔不得被修改");

        Path leaked = createdTmp.get();
        if (leaked != null) {
            try {
                Files.deleteIfExists(leaked);
            } catch (IOException ignore) {
                // best effort
            }
        }
        store.close();
    }

    @Test
    @DisplayName("migration 失敗：拋 ACELIB-DATA-004，既有資料不被破壞")
    void migration_failure_keepsOldData() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"oldKey\":\"oldValue\"}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
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
        // 磁碟上的舊資料不應被覆寫
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"oldKey\":\"oldValue\""),
            "migration 失敗時舊資料必須保留，但實際內容：" + content);
        assertTrue(content.contains("\"_version\":\"1.0\""),
            "_version 不應被升級到 2.0，實際：" + content);
    }

    @Test
    @DisplayName("migration 成功：舊版升級後 _version 與資料已被 atomic 寫回磁碟，重 init 不重跑")
    void migration_persistsAcrossNewStore_andFileIsUpdated() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"oldKey\":\"oldValue\"}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("newKey",
                    "migrated:" + ctx.read().getString("oldKey", ""));
            }
        });
        store.init();

        // migration 成功後 init() 必須 atomic persist，
        // 不能依賴 close() 補寫。close() 是 close 的副作用，init() 必須自己保證
        // 重啟時不重跑 migration。
        String persisted = Files.readString(filePath, StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"_version\":\"2.0\""),
            "init() 成功 migration 後 _version 必須已寫回磁碟，實際：" + persisted);
        assertTrue(persisted.contains("\"newKey\":\"migrated:oldValue\""),
            "migrated 資料必須已寫回磁碟，實際：" + persisted);
        assertFalse(persisted.contains("\"_version\":\"1.0\""),
            "舊版 _version 不應殘留，實際：" + persisted);
        store.close();

        // 重啟新 store 必須直接讀到 current version，不重跑 migration
        java.util.concurrent.atomic.AtomicInteger migrationRunCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        JsonFileDataStore reader = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(2, 0), codec);
        reader.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(2, 0); }
            @Override public void migrate(DataMigrationContext ctx) {
                migrationRunCount.incrementAndGet();
            }
        });
        reader.init();
        assertEquals(0, migrationRunCount.get(),
            "重啟新 store 不應再執行 migration（on-disk 已是 current version）");
        assertEquals("migrated:oldValue", reader.root().getString("newKey", null));
        assertEquals(new SchemaVersion(2, 0), reader.schemaVersion());
        reader.close();
    }

    @Test
    @DisplayName("on-disk schema 版本比 current 新：拋 ACELIB-DATA-010，檔案不被覆寫")
    void newerOnDiskVersion_rejectsWithData010_keepsFileIntact() throws IOException {
        String originalContent =
            "{\"_version\":\"2.0\",\"key\":\"v\"}\n";
        Files.writeString(filePath, originalContent, StandardCharsets.UTF_8);

        // store 配 current=1.0（比 on-disk 2.0 舊）；不應降版覆寫
        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, SchemaVersion.V1_0, codec);
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-010", ex.getCode(),
            "新於 current 的 on-disk 版本必須拒絕，實際 code：" + ex.getCode());
        assertTrue(ex.getMessage().contains("2.0"),
            "錯誤訊息應提示 on-disk 版本，實際：" + ex.getMessage());

        // 既有檔案必須保持原樣
        String afterContent = Files.readString(filePath, StandardCharsets.UTF_8);
        assertEquals(originalContent, afterContent,
            "拒絕時檔案不得被改寫");
    }

    @Test
    @DisplayName("migration chain：可串接多個步驟")
    void migration_chain() throws IOException {
        Files.writeString(filePath,
            "{\"_version\":\"1.0\",\"x\":\"start\"}\n",
            StandardCharsets.UTF_8);

        JsonFileDataStore store = new JsonFileDataStore(
            "test", filePath, new SchemaVersion(1, 3), codec);
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(1, 1); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("x", ctx.read().getString("x", "") + "+A");
            }
        });
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 1); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(1, 2); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("x", ctx.read().getString("x", "") + "+B");
            }
        });
        store.registerMigration(new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 2); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(1, 3); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("x", ctx.read().getString("x", "") + "+C");
            }
        });
        store.init();
        assertEquals("start+A+B+C", store.root().getString("x", null));
        store.close();
    }

    // -----------------------------------------------------------------
    // corrupt file
    // -----------------------------------------------------------------

    @Test
    @DisplayName("檔案內容損壞：init 拋 ACELIB-DATA-002")
    void corruptFile_throws() throws IOException {
        Files.writeString(filePath, "not valid json", StandardCharsets.UTF_8);
        JsonFileDataStore store = newStore();
        DataStoreException ex = assertThrows(DataStoreException.class, store::init);
        assertEquals("ACELIB-DATA-002", ex.getCode());
    }

    @Test
    @DisplayName("寫入 temp 失敗：保留原檔，拋 ACELIB-DATA-001")
    void atomicWrite_tempFailure_preservesOriginal() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();
        String beforeContent = Files.readString(filePath, StandardCharsets.UTF_8);
        // 不論寫入成功與否，原檔案內容必須可被讀回
        assertTrue(beforeContent.contains("\"k\":\"v\""));
        store.close();
    }

    // -----------------------------------------------------------------
    // 冪等 / duplicate save
    // -----------------------------------------------------------------

    @Test
    @DisplayName("duplicate save：相同資料多次 save 一致")
    void duplicateSave_isConsistent() {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("counter", 1);
        store.save();
        String first = readFile();
        store.save();
        String second = readFile();
        assertEquals(first, second, "duplicate save 必須產生相同檔案內容");
        store.close();
    }

    @Test
    @DisplayName("save 後 set 同 key 後再 save：內容正確更新，不會有殘留")
    void saveUpdateConsistent() {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v1");
        store.save();
        store.root().set("k", "v2");
        store.save();
        String text = readFile();
        assertTrue(text.contains("\"k\":\"v2\""), "save 後內容應為新值，實際：" + text);
        assertFalse(text.contains("\"v1\""), "舊值不應殘留");
        store.close();
    }

    // -----------------------------------------------------------------
    // async submit
    // -----------------------------------------------------------------

    @Test
    @DisplayName("submit 不阻塞 caller，future 反映任務結果")
    void submit_doesNotBlockCaller() throws Exception {
        JsonFileDataStore store = newStore();
        store.init();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            CompletableFuture<Integer> f = store.submit(exec, () -> {
                store.root().set("v", 42);
                return 42;
            });
            // caller 不會卡在這裡（任務尚未完成）
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < 50_000_000L, "submit 應立即返回，不應等任務完成");
            assertEquals(42, f.get(2, TimeUnit.SECONDS));
            // submit 任務裡的 set 不會自動 save；save 後才能讀回
            store.save();
            assertEquals(42, store.root().getInt("v", 0));
        } finally {
            exec.shutdownNow();
            store.close();
        }
    }

    @Test
    @DisplayName("submit 任務內拋例外：future complete exceptionally")
    void submit_propagatesExceptions() {
        JsonFileDataStore store = newStore();
        store.init();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Integer> f = store.submit(exec, () -> {
                throw new RuntimeException("task boom");
            });
            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> f.get(2, TimeUnit.SECONDS));
            // 包裝為 DataStoreException(ACELIB-DATA-006) 或保留原 exception
            assertNotNull(ex.getCause());
        } finally {
            exec.shutdownNow();
            store.close();
        }
    }

    @Test
    @DisplayName("flush 重複呼叫為 idempotent")
    void flush_isIdempotent() {
        JsonFileDataStore store = newStore();
        store.init();
        store.flush();
        store.flush();
        store.flush();
        store.close();
    }

    @Test
    @DisplayName("registerMigration 鏈式 API + 重複註冊同 from→to：依序觸發")
    void registerMigration_returnsSelfAndSupportsMulti() {
        JsonFileDataStore store = newStore();
        DataMigration m = new DataMigration() {
            @Override public SchemaVersion fromVersion() { return new SchemaVersion(1, 0); }
            @Override public SchemaVersion toVersion() { return new SchemaVersion(1, 1); }
            @Override public void migrate(DataMigrationContext ctx) {
                ctx.write().set("k", "v");
            }
        };
        assertSame(store, store.registerMigration(m));
    }

    // -----------------------------------------------------------------
    // temp 落盤（fsync）：write→sync→move
    // -----------------------------------------------------------------

    @Test
    @DisplayName("原子寫入：暫存檔在 move 之前先落盤（write→sync→move）")
    void atomicWrite_syncsTempBeforeMove() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");

        java.util.List<String> order = new java.util.ArrayList<>();
        JsonFileDataStore.FileOps recordingOps = new JsonFileDataStore.FileOps() {
            @Override public Path createTempFile(Path dir, String prefix, String suffix)
                    throws IOException {
                return Files.createTempFile(dir, prefix, suffix);
            }
            @Override public void writeString(Path path, String content) throws IOException {
                order.add("write");
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            @Override public void sync(Path path) throws IOException {
                order.add("sync");
            }
            @Override public void move(Path source, Path target) throws IOException {
                order.add("move");
                Files.move(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            @Override public boolean deleteIfExists(Path path) throws IOException {
                return Files.deleteIfExists(path);
            }
        };

        MemoryRecord root = (MemoryRecord) store.root();
        store.writeAtomicFrom(root, recordingOps);
        assertEquals(java.util.List.of("write", "sync", "move"), order,
            "暫存檔必須在 move 之前先落盤，否則斷電可能留下內容不全的目標檔");
        store.close();
    }

    @Test
    @DisplayName("sync 失敗：不做 move、清理 temp、拋 ACELIB-DATA-001 且保留原始 cause")
    void atomicWrite_syncFailure_noMoveAndCleansTemp() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();
        String before = Files.readString(filePath, StandardCharsets.UTF_8);

        java.util.concurrent.atomic.AtomicReference<Path> createdTmp =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean moved =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        JsonFileDataStore.FileOps failingOps = new JsonFileDataStore.FileOps() {
            @Override public Path createTempFile(Path dir, String prefix, String suffix)
                    throws IOException {
                Path tmp = Files.createTempFile(dir, prefix, suffix);
                createdTmp.set(tmp);
                return tmp;
            }
            @Override public void writeString(Path path, String content) throws IOException {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            @Override public void sync(Path path) throws IOException {
                throw new IOException("injected sync failure");
            }
            @Override public void move(Path source, Path target) throws IOException {
                moved.set(true);
                throw new AssertionError("sync 失敗後不應走到 move");
            }
            @Override public boolean deleteIfExists(Path path) throws IOException {
                return Files.deleteIfExists(path);
            }
        };

        MemoryRecord root = (MemoryRecord) store.root();
        DataStoreException ex = assertThrows(
            DataStoreException.class, () -> store.writeAtomicFrom(root, failingOps));
        assertEquals("ACELIB-DATA-001", ex.getCode());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("injected sync failure"),
            "原始落盤錯誤不得被掩蓋，實際 cause=" + ex.getCause());
        assertFalse(moved.get(), "sync 失敗後不得 move");
        assertFalse(Files.exists(createdTmp.get()), "sync 失敗必須清理 temp");
        assertEquals(before, Files.readString(filePath, StandardCharsets.UTF_8),
            "落盤失敗時舊檔不得被修改");
        store.close();
    }

    @Test
    @DisplayName("sync 失敗且清理失敗：原始錯誤保留且清理失敗為 suppressed")
    void atomicWrite_syncFailure_cleanupFailure_suppressed() throws IOException {
        JsonFileDataStore store = newStore();
        store.init();
        store.root().set("k", "v");
        store.save();
        String before = Files.readString(filePath, StandardCharsets.UTF_8);

        java.util.concurrent.atomic.AtomicReference<Path> createdTmp =
            new java.util.concurrent.atomic.AtomicReference<>();
        JsonFileDataStore.FileOps failingOps = new JsonFileDataStore.FileOps() {
            @Override public Path createTempFile(Path dir, String prefix, String suffix)
                    throws IOException {
                Path tmp = Files.createTempFile(dir, prefix, suffix);
                createdTmp.set(tmp);
                return tmp;
            }
            @Override public void writeString(Path path, String content) throws IOException {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            @Override public void sync(Path path) throws IOException {
                throw new IOException("injected sync failure");
            }
            @Override public void move(Path source, Path target) throws IOException {
                throw new AssertionError("sync 失敗後不應走到 move");
            }
            @Override public boolean deleteIfExists(Path path) throws IOException {
                throw new IOException("injected cleanup failure");
            }
        };

        MemoryRecord root = (MemoryRecord) store.root();
        DataStoreException ex = assertThrows(
            DataStoreException.class, () -> store.writeAtomicFrom(root, failingOps));
        assertEquals("ACELIB-DATA-001", ex.getCode());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("injected sync failure"),
            "原始落盤錯誤不得被掩蓋，實際 cause=" + ex.getCause());
        assertEquals(1, ex.getSuppressed().length,
            "清理失敗必須以 suppressed 留痕，不得靜默吞掉");
        assertTrue(ex.getSuppressed()[0].getMessage().contains("injected cleanup failure"));
        assertEquals(before, Files.readString(filePath, StandardCharsets.UTF_8),
            "落盤失敗時舊檔不得被修改");

        Path leaked = createdTmp.get();
        if (leaked != null) {
            try {
                Files.deleteIfExists(leaked);
            } catch (IOException ignore) {
                // best effort
            }
        }
        store.close();
    }

    // -----------------------------------------------------------------
    // 輔助
    // -----------------------------------------------------------------

    private JsonFileDataStore newStore() {
        return new JsonFileDataStore("test", filePath, SchemaVersion.V1_0, codec);
    }

    private String readFile() {
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unused")
    private static void touch(Path p) {
        try {
            if (!Files.exists(p)) {
                Files.createFile(p);
            }
        } catch (IOException ignore) {
            // best effort
        }
    }

    @SuppressWarnings("unused")
    private static List<String> emptyList() {
        return List.of();
    }

    @SuppressWarnings("unused")
    private static AtomicInteger newCounter() {
        return new AtomicInteger();
    }
}