package com.smile.acelib.data;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 本地檔案型 {@link DataStore}（JSON 原子寫入）。
 *
 * <p>內部以 {@link JsonCodec} 序列化整棵 record tree 為 JSON；
 * 寫入採用 Java NIO 原子 temp+move 流程，避免半寫入損壞既有資料。</p>
 *
 * <h2>檔案格式</h2>
 * <pre>
 * {
 *   "_version": "1.0",
 *   "key1": value1,
 *   "key2": value2
 * }
 * </pre>
 *
 * <h2>原子寫入流程</h2>
 * <ol>
 *   <li>將新內容寫入 {@code <file>.tmp}（與目標檔案同目錄）</li>
 *   <li>對 temp 做 fsync（強制落盤），確保斷電後目標檔內容完整</li>
 *   <li>呼叫 {@code Files.move(tmp, target, ATOMIC_MOVE)}；
 *       若底層檔案系統不支援，自動降級為 {@code REPLACE_EXISTING}</li>
 *   <li>若寫入 temp、落盤或 move 失敗，刪除 temp + 保留原檔；
 *       清理本身失敗時以 {@code suppressed} 留痕，不掩蓋原始寫入錯誤</li>
 * </ol>
 *
 * <h2>執行緒模型</h2>
 * <ul>
 *   <li>{@link #save()} 為同步阻塞寫入；呼叫端應於主執行緒或一次性背景任務內呼叫</li>
 *   <li>{@link #flush()} 為同步阻塞等待（目前無 async task 概念，no-op 等待）</li>
 *   <li>{@link #submit(Executor, java.util.concurrent.Callable)} 透過 {@link Executor}
 *       派送任意任務；不阻塞 caller</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-DATA-001}：檔案 IO 失敗（無法建立、寫入、移動）</li>
 *   <li>{@code ACELIB-DATA-002}：檔案內容損壞（無法解析為 JSON）</li>
 *   <li>{@code ACELIB-DATA-004}：migration 失敗（攜帶 from/to 版本）</li>
 *   <li>{@code ACELIB-DATA-005}：store 已關閉</li>
 *   <li>{@code ACELIB-DATA-009}：舊版本但無對應 migration</li>
 *   <li>{@code ACELIB-DATA-010}：on-disk schema 版本比 current 新（拒絕降版覆寫）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class JsonFileDataStore implements DataStore {

    private final String name;
    private final Path targetPath;
    private final SchemaVersion currentVersion;
    private final JsonCodec codec;
    private final MigrationChain migrationChain = new MigrationChain();

    private volatile boolean initialized = false;
    private volatile boolean closed = false;
    private MemoryRecord rootView;

    /**
     * 主要建構子。
     *
     * @param name           store 識別名稱；不可為 null/空白
     * @param targetPath     目標檔案路徑；不可為 null
     * @param currentVersion 當前 schema 版本；不可為 null
     * @param codec          JSON codec；不可為 null
     * @throws NullPointerException     當任一參數為 null
     * @throws IllegalArgumentException 當 {@code name} 為空白
     */
    public JsonFileDataStore(String name,
                             Path targetPath,
                             SchemaVersion currentVersion,
                             JsonCodec codec) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public SchemaVersion schemaVersion() {
        return currentVersion;
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
    public DataStore registerMigration(DataMigration migration) {
        Objects.requireNonNull(migration, "migration");
        migrationChain.add(migration);
        return this;
    }

    @Override
    public synchronized void init() {
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "store '" + name + "' is closed");
        }
        if (initialized) {
            return;
        }
        Map<String, Object> loaded;
        SchemaVersion onDiskVersion = currentVersion; // 預設 = 已是當前版本
        boolean fileExisted = Files.exists(targetPath);

        if (fileExisted) {
            String text = readFile();
            loaded = codec.decode(text);
            Object versionObj = loaded.get("_version");
            if (versionObj instanceof String vs) {
                onDiskVersion = codec.decodeVersion(vs);
            }
        } else {
            // 新建檔案
            loaded = new LinkedHashMap<>();
        }

        // on-disk 版本比 current 新時必須拒絕：
        // 不可靜默以 current 覆寫既有資料（會造成資料降版／遺失）。
        if (onDiskVersion.compareTo(currentVersion) > 0) {
            throw new DataStoreException("ACELIB-DATA-010",
                "on-disk schema version " + onDiskVersion
                    + " is newer than current " + currentVersion
                    + "; refusing to downgrade. Upgrade AceLib or restore a "
                    + "compatible data file before initializing this store.");
        }

        // 套用 schema migration（從 onDiskVersion → currentVersion）
        boolean migrationApplied = false;
        if (onDiskVersion.compareTo(currentVersion) < 0) {
            MemoryRecord snapshot = new MemoryRecord("", new LinkedHashMap<>(loaded));
            MigrationResult result = migrationChain.migrateTracked(
                onDiskVersion, currentVersion, snapshot,
                finalState -> {
                    // 以 migration 後的最終狀態整體取代既有資料：
                    // 只 putAll 會讓 migration 刪除的鍵殘留（復活）。
                    loaded.clear();
                    loaded.putAll(((MemoryRecord) finalState).snapshot());
                });
            if (!result.success()) {
                throw new DataStoreException("ACELIB-DATA-004",
                    "migration failed: " + result.errorMessage(),
                    result.cause());
            }
            // 確保沒有任何 migration 從 > currentVersion 開始
            if (result.appliedSteps().isEmpty() && onDiskVersion.compareTo(currentVersion) < 0) {
                throw new DataStoreException("ACELIB-DATA-009",
                    "no migration found from " + onDiskVersion + " to " + currentVersion);
            }
            migrationApplied = !result.appliedSteps().isEmpty();
        }

        loaded.put("_version", codec.encodeVersion(currentVersion));
        MemoryRecord pendingRoot = new MemoryRecord("", loaded);

        // migration 後或新檔案都必須立即 atomic persist，避免依賴 close() 補寫：
        // plugin crash 後重啟才能不重跑 migration，並保證 on-disk 與 in-memory 一致。
        // 寫入必須在發布 rootView/initialized 之前完成，否則 persist 失敗時
        // 會留下「看似可用但未實際落地」的狀態，且同 instance 後續 init() 為 no-op
        // 無法重試。
        if (!fileExisted || migrationApplied) {
            writeAtomicFrom(pendingRoot);
        }
        this.rootView = pendingRoot;
        this.initialized = true;
    }

    @Override
    public Record root() {
        if (!initialized) {
            throw new IllegalStateException("init() must be called before root()");
        }
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "store '" + name + "' is closed");
        }
        return rootView;
    }

    @Override
    public synchronized void save() {
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "store '" + name + "' is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("init() must be called before save()");
        }
        writeAtomicFrom(rootView);
    }

    @Override
    public void flush() {
        save();
    }

    @Override
    public <T> CompletableFuture<T> submit(Executor executor,
                                           java.util.concurrent.Callable<T> task) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DataStoreException("ACELIB-DATA-006",
                    "async task failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (initialized) {
            try {
                writeAtomicFrom(rootView);
            } catch (RuntimeException ex) {
                // close 內部不丟例外：disable 流程不應崩潰；但錯誤分類必須可觀察，
                // 否則資料遺失毫無紀錄。保留 ACELIB-DATA-xxx 代碼於訊息中。
                java.util.logging.Logger.getLogger("AceLib").log(
                    java.util.logging.Level.WARNING,
                    "JsonFileDataStore '" + name + "' close 時持久化失敗："
                        + (ex instanceof DataStoreException dse ? dse.getCode() : "ACELIB-DATA-001")
                        + " - " + ex.getMessage(), ex);
            }
        }
        closed = true;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private String readFile() {
        try {
            return Files.readString(targetPath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to read file: " + targetPath, ex);
        }
    }

    private void writeAtomicFrom(MemoryRecord source) {
        writeAtomicFrom(source, FileOps.defaultOps());
    }

    /**
     * 檔案操作接縫（測試注入用，不對外暴露）。
     *
     * <p>正式環境一律走 {@link FileOps#defaultOps()}（直接委派 {@code Files}）。
     * 測試需要讓「temp 建出來之後的寫入／搬移」失敗時，才經由多載傳入假實作；
     * 不可用「建不出 temp」來冒充「寫 temp 失敗」，兩者的清理路徑不同。</p>
     */
    interface FileOps {

        Path createTempFile(Path dir, String prefix, String suffix) throws IOException;

        void writeString(Path path, String content) throws IOException;

        /**
         * 把暫存檔內容強制落盤（fsync），必須在 {@link #move} 之前呼叫；
         * 否則斷電時目標檔可能只有部分內容。
         */
        void sync(Path path) throws IOException;

        void move(Path source, Path target) throws IOException;

        boolean deleteIfExists(Path path) throws IOException;

        static FileOps defaultOps() {
            return DefaultFileOps.INSTANCE;
        }
    }

    private static final class DefaultFileOps implements FileOps {

        private static final DefaultFileOps INSTANCE = new DefaultFileOps();

        @Override
        public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
            return Files.createTempFile(dir, prefix, suffix);
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }

        @Override
        public void sync(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    }

    void writeAtomicFrom(MemoryRecord source, FileOps ops) {
        // 1. 序列化來源視圖為 JSON
        Map<String, Object> snapshot = new LinkedHashMap<>(source.snapshot());
        snapshot.put("_version", codec.encodeVersion(currentVersion));
        String text = codec.encode(snapshot);

        // 2. 確保 parent 目錄存在
        Path parent = targetPath.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new DataStoreException("ACELIB-DATA-001",
                    "failed to create directory: " + parent, ex);
            }
        }

        // temp 建出來之後的每一步都可能留下孤兒檔：寫入與搬移各自負責清理，
        // 不共用同一個 catch，否則會把「建不出 temp」和「寫壞 temp」混成同一條路。
        final Path tmp;
        try {
            tmp = ops.createTempFile(parent, "acelib-", ".tmp");
        } catch (IOException ex) {
            throw new DataStoreException("ACELIB-DATA-001",
                "failed to write temp file for " + targetPath, ex);
        }

        try {
            ops.writeString(tmp, text);
        } catch (IOException ex) {
            DataStoreException failure = new DataStoreException("ACELIB-DATA-001",
                "failed to write temp file for " + targetPath, ex);
            deleteQuietly(tmp, failure, ops);
            throw failure;
        }

        // 3. 暫存檔落盤後才搬移：不斷電保證 temp 內容已進穩定儲存，
        // move 之後目標檔即為完整內容。落盤失敗比照寫入失敗處理。
        try {
            ops.sync(tmp);
        } catch (IOException ex) {
            DataStoreException failure = new DataStoreException("ACELIB-DATA-001",
                "failed to sync temp file for " + targetPath, ex);
            deleteQuietly(tmp, failure, ops);
            throw failure;
        }

        // 4. Atomic move（若不支援，降級為 replace 由 DefaultFileOps 處理）
        try {
            ops.move(tmp, targetPath);
        } catch (IOException ex) {
            // 5. 失敗時清理 temp
            DataStoreException failure = new DataStoreException("ACELIB-DATA-001",
                "failed to move temp file to " + targetPath, ex);
            deleteQuietly(tmp, failure, ops);
            throw failure;
        }
    }

    /**
     * 盡力清掉 temp 孤兒檔；清不掉時把清理失敗掛到原始錯誤的 {@code suppressed}，
     * 讓維運仍能從例外鏈查到兩邊的原因，而不是靜靜吞掉其中一邊。
     */
    private static void deleteQuietly(Path tmp, DataStoreException failure, FileOps ops) {
        try {
            ops.deleteIfExists(tmp);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}