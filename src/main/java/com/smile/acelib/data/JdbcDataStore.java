package com.smile.acelib.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 關聯式資料庫型 {@link DataStore}（標準 JDBC）。
 *
 * <p>對應 Plan §十三 Phase 8「關聯式資料庫基本需求」與「資料表 / 資料格式初始化」。
 * 內部以標準 {@link DataSource} 取得 {@link Connection}，不使用任何 ORM 框架；
 * 表格由 store 自動建立。</p>
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE acelib_data_kv (
 *     store_name VARCHAR(255) NOT NULL,
 *     k          VARCHAR(1024) NOT NULL,
 *     v          TEXT,
 *     PRIMARY KEY (store_name, k)
 * )
 * </pre>
 *
 * <p>值以 {@link JsonCodec} 序列化的 JSON 字串儲存；多個 store 共享同一張表，
 * 以 {@code store_name} 區隔。</p>
 *
 * <h2>交易語意</h2>
 * <ul>
 *   <li>init 階段：建立表格、讀既有 schema 版本、執行 migration → 寫入新版本</li>
 *   <li>save 階段：刪除既有所有 key → 重新插入當前視圖；包裝在 transaction 內</li>
 *   <li>migration 失敗 → {@code ROLLBACK}，既有資料不變</li>
 *   <li>save 失敗 → {@code ROLLBACK}，既有資料不變</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-DATA-002}：JSON 解析失敗（讀回的 value 損壞）</li>
 *   <li>{@code ACELIB-DATA-004}：migration 失敗</li>
 *   <li>{@code ACELIB-DATA-005}：store 已關閉</li>
 *   <li>{@code ACELIB-DATA-008}：SQL 錯誤（連線失敗、語法錯誤、約束衝突）</li>
 *   <li>{@code ACELIB-DATA-009}：舊版本但無對應 migration</li>
 *   <li>{@code ACELIB-DATA-010}：on-disk schema 版本比 current 新（拒絕降版覆寫）</li>
 *   <li>{@code ACELIB-DATA-011}：table 名稱不是合法 SQL identifier</li>
 * </ul>
 *
 * @since Phase 8 (Plan §十三)
 */
public final class JdbcDataStore implements DataStore {

    /** 預設資料表名稱。 */
    public static final String DEFAULT_TABLE = "acelib_data_kv";

    /**
     * 安全 SQL identifier 規則：{@code [A-Za-z_][A-Za-z0-9_]*}。
     *
     * <p>用於驗證建構子傳入的 {@code tableName}，避免任意字串拼接進
     * {@code CREATE TABLE} / {@code SELECT} / {@code DELETE} / {@code INSERT}
     * 等 SQL 造成 identifier injection。</p>
     */
    static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String name;
    private final DataSource dataSource;
    private final SchemaVersion currentVersion;
    private final JsonCodec codec;
    private final String tableName;
    private final MigrationChain migrationChain = new MigrationChain();

    private volatile boolean initialized = false;
    private volatile boolean closed = false;
    private MemoryRecord rootView;

    /**
     * 主要建構子（使用預設 table 名）。
     *
     * @param name           store 識別名稱；不可為 null/空白
     * @param dataSource     JDBC {@link DataSource}；不可為 null
     * @param currentVersion 當前 schema 版本；不可為 null
     * @param codec          JSON codec；不可為 null
     * @throws NullPointerException     當任一參數為 null
     * @throws IllegalArgumentException 當 {@code name} 為空白
     */
    public JdbcDataStore(String name,
                         DataSource dataSource,
                         SchemaVersion currentVersion,
                         JsonCodec codec) {
        this(name, dataSource, currentVersion, codec, DEFAULT_TABLE);
    }

    /**
     * 完整建構子（自訂 table 名）。
     *
     * @param name           store 識別名稱；不可為 null/空白
     * @param dataSource     JDBC {@link DataSource}；不可為 null
     * @param currentVersion 當前 schema 版本；不可為 null
     * @param codec          JSON codec；不可為 null
     * @param tableName      資料表名稱；不可為 null/空白
     */
    public JdbcDataStore(String name,
                         DataSource dataSource,
                         SchemaVersion currentVersion,
                         JsonCodec codec,
                         String tableName) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.codec = Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(tableName, "tableName");
        if (tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        // tableName 必須是合法 SQL identifier，
        // 拒絕分號、空格、引號、保留字首字母數字等注入載體。
        if (!TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new DataStoreException("ACELIB-DATA-011",
                "invalid table identifier '" + tableName
                    + "'; must match [A-Za-z_][A-Za-z0-9_]*");
        }
        this.tableName = tableName;
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
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                createTableIfMissing(conn);
                Map<String, Object> loaded = readAll(conn);
                SchemaVersion onDiskVersion = readVersionOrCurrent(conn);

                // on-disk 版本比 current 新時必須拒絕：
                // 不可降版覆寫既有資料（會造成資料遺失）。檢查在 deleteAll/writeAll
                // 之前，確保 transaction rollback 後原始資料完整保留。
                if (onDiskVersion.compareTo(currentVersion) > 0) {
                    conn.rollback();
                    throw new DataStoreException("ACELIB-DATA-010",
                        "on-disk schema version " + onDiskVersion
                            + " is newer than current " + currentVersion
                            + "; refusing to downgrade. Upgrade AceLib or restore a "
                            + "compatible data row before initializing this store.");
                }

                if (onDiskVersion.compareTo(currentVersion) < 0) {
                    MemoryRecord snapshot = new MemoryRecord("",
                        new LinkedHashMap<>(loaded));
                    MigrationResult result = migrationChain.migrateTracked(
                        onDiskVersion, currentVersion, snapshot,
                        finalState -> {
                            Map<String, Object> finalSnapshot =
                                ((MemoryRecord) finalState).snapshot();
                            loaded.clear();
                            loaded.putAll(finalSnapshot);
                        });
                    if (!result.success()) {
                        conn.rollback();
                        throw new DataStoreException("ACELIB-DATA-004",
                            "migration failed: " + result.errorMessage(),
                            result.cause());
                    }
                    if (result.appliedSteps().isEmpty()) {
                        conn.rollback();
                        throw new DataStoreException("ACELIB-DATA-009",
                            "no migration found from " + onDiskVersion
                                + " to " + currentVersion);
                    }
                }

                // 重新寫入全部（schema 變更後可能資料結構變化）
                deleteAll(conn);
                loaded.put("_version", codec.encodeVersion(currentVersion));
                writeAll(conn, loaded);

                conn.commit();
            } catch (RuntimeException | SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignore) {
                    // best effort
                }
            }
        } catch (SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "failed to init store '" + name + "': " + ex.getMessage(), ex);
        }

        // init 完成後建立 rootView；後續 root() 直接回傳
        try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> loaded = readAll(conn);
            loaded.put("_version", codec.encodeVersion(currentVersion));
            this.rootView = new MemoryRecord("", loaded);
            this.initialized = true;
        } catch (SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "failed to read post-init data: " + ex.getMessage(), ex);
        }
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
        Map<String, Object> snapshot = new LinkedHashMap<>(rootView.snapshot());
        snapshot.put("_version", codec.encodeVersion(currentVersion));

        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                deleteAll(conn);
                writeAll(conn, snapshot);
                conn.commit();
            } catch (RuntimeException | SQLException ex) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                    // best effort
                }
                throw ex;
            } finally {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignore) {
                    // best effort
                }
            }
        } catch (SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "failed to save store '" + name + "': " + ex.getMessage(), ex);
        }
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
        closed = true;
        // 不主動關閉 dataSource（其生命週期由 caller 管理）
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private void createTableIfMissing(Connection conn) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
            + "store_name VARCHAR(255) NOT NULL, "
            + "k VARCHAR(1024) NOT NULL, "
            + "v TEXT, "
            + "PRIMARY KEY (store_name, k))";
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        }
    }

    private Map<String, Object> readAll(Connection conn) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        String sql = "SELECT k, v FROM " + tableName + " WHERE store_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String k = rs.getString("k");
                    String v = rs.getString("v");
                    if (k == null) {
                        continue;
                    }
                    if (k.equals("_version")) {
                        continue;
                    }
                    if (v == null) {
                        result.put(k, null);
                    } else {
                        result.put(k, parseJsonValue(v, k));
                    }
                }
            }
        }
        return result;
    }

    private Object parseJsonValue(String json, String key) {
        try {
            return codec.decode("{\"v\":" + json + "}").get("v");
        } catch (RuntimeException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "failed to decode stored value for key='" + key + "': "
                    + ex.getMessage(), ex);
        }
    }

    private SchemaVersion readVersionOrCurrent(Connection conn) throws SQLException {
        String sql = "SELECT v FROM " + tableName + " WHERE store_name = ? AND k = '_version'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString("v");
                    if (raw != null && !raw.isBlank()) {
                        // 相容兩種 _version 歷史儲存格式：
                        // - 新版（生產）：純文字 "1.0"（不包 JSON quotes）
                        // - 測試 pre-insert 與舊資料：JSON 編碼 "\"1.0\""
                        // 用首字元區分：JSON 形式一定以 " 開頭；純文字一定以數字開頭。
                        String versionText = raw;
                        if (raw.startsWith("\"")) {
                            try {
                                Object decoded = codec.decode("{\"v\":" + raw + "}").get("v");
                                if (decoded instanceof String s) {
                                    versionText = s;
                                }
                            } catch (RuntimeException ex) {
                                throw new DataStoreException("ACELIB-DATA-002",
                                    "failed to decode stored _version string: " + raw, ex);
                            }
                        }
                        return codec.decodeVersion(versionText);
                    }
                }
            }
        }
        return currentVersion;
    }

    private void deleteAll(Connection conn) throws SQLException {
        String sql = "DELETE FROM " + tableName + " WHERE store_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    private void writeAll(Connection conn, Map<String, Object> data) throws SQLException {
        if (data.isEmpty()) {
            return;
        }
        // vendor-portable: plain INSERT. save() 流程已先 deleteAll()，
        // 不需要 MERGE/ON DUPLICATE KEY UPDATE。
        String sql = "INSERT INTO " + tableName
            + " (store_name, k, v) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                ps.setString(1, name);
                ps.setString(2, e.getKey());
                ps.setString(3, storedValueFor(e.getKey(), e.getValue()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 計算要寫入 DB 的 stored value 表示。
     *
     * <p>_version 是系統內部 metadata，使用 {@link JsonCodec#encodeVersion} 的純文字
     * 形式（{@code "1.0"}）寫入；其他 key 走 {@link JsonCodec#encode}（JSON 字串）。</p>
     *
     * <p>這樣的好處：</p>
     * <ul>
     *   <li>讀回 {@code _version} 時不必再多一層 JSON 解碼，可直接傳給 {@code decodeVersion}</li>
     *   <li>現有測試 fixtures 與 pre-insert helper 預期是 RAW "1.0"，與生產一致</li>
     *   <li>若讀到舊版/測試用過的 JSON 編碼殼（{@code "\"1.0\""}），{@link #readVersionOrCurrent}
     *       內部仍會相容處理</li>
     * </ul>
     */
    private String storedValueFor(String key, Object value) {
        if ("_version".equals(key)) {
            return value == null ? "null" : value.toString();
        }
        return encodeValue(value);
    }

    private String encodeValue(Object value) {
        if (value == null) {
            return "null";
        }
        return codec.encode(value);
    }
}