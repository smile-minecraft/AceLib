package com.smile.acelib.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 關聯式資料庫型 {@link DataStore}（標準 JDBC）。
 *
 * <p>內部以標準 {@link DataSource} 取得 {@link Connection}，不使用任何 ORM 框架；
 * 表格由 store 自動建立。</p>
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE acelib_data_kv (
 *     store_name VARCHAR(255) NOT NULL,
 *     k_hash     CHAR(64) NOT NULL,
 *     k          TEXT NOT NULL,
 *     v          TEXT,
 *     PRIMARY KEY (store_name, k_hash)
 * )
 * </pre>
 *
 * <p>{@code k_hash} 是 key 的 SHA-256 hex（UTF-8，固定 64 字元）；主鍵最壞
 * {@code (255 + 64) * 4 = 1276 bytes}，在 utf8mb4 的 InnoDB 索引上限
 * （3072 bytes）之內。完整 key 另存於 {@code k} 欄（TEXT），無 key 長度上限。
 * 舊版形狀 {@code (store_name, k, v) + PRIMARY KEY (store_name, k)} 的表
 * 在 init 時會自動升級（見「舊表升級」）。MySQL / MariaDB 建表時另加
 * {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}；其他資料庫維持可攜寫法。</p>
 *
 * <h2>舊表升級</h2>
 * <p>偵測到舊形狀表時：讀出整張舊表全部 store 的舊資料 → 版本檢查與資料遷移（只跑本 store，
 * 失敗分別拋 {@code ACELIB-DATA-010} / {@code ACELIB-DATA-004} /
 * {@code ACELIB-DATA-009}，舊表不動）→ 本 store 遷移結果與其他 store 原樣列
 * （{@code store_name}/{@code k}/{@code v} 不變，{@code k_hash} 由 {@code k} 重算）
 * 一起寫入暫存表並讀回驗證
 * （本 store 筆數與版本一致，且暫存表總筆數涵蓋整張舊表）→ 刪除舊表 → 暫存表改名為正式表。
 * 舊表保留，拋 {@code ACELIB-DATA-008 [jdbc:migrate-table]}。其他 store 的版本列
 * 也原樣保留，等它們各自 init 時再走新形狀路徑做自己的遷移。
 * 中斷後殘留的暫存表會在下次 init 開頭、建表之前先復原：
 * 若舊表已刪而改名未完成（只剩暫存表）則接續改名，
 * 若舊表還在則丟棄暫存表後重新升級（舊表仍持有整表完整資料，
 * DROP 只發生在暫存表驗證涵蓋整表之後，所以雙表並存時丟暫存表是安全的；
 * 復原必須先於建表，否則憑空造出的空正式表會讓復原誤判而刪掉唯一副本）。</p>
 *
 * <p>併發限制：同一 JVM 內同表的併發 init 已用表級互斥序列化；
 * 跨行程 / 跨主機共享同一張表時，呼叫端必須在外部序列化 init
 * （同一時間只有一個升級在跑），否則 DROP + RENAME 會互相踩踏丟資料。</p>
 *
 * <p>值以 {@link JsonCodec} 序列化的 JSON 字串儲存；多個 store 共享同一張表，
 * 以 {@code store_name} 區隔。</p>
 *
 * <h2>交易語意</h2>
 * <ul>
 *   <li>init 階段：單一連線內建立表格、讀既有 schema 版本、執行 migration →
 *       寫入新版本 → commit；root 視圖由剛寫入的同一份記憶體資料建立，
 *       commit 後不再開第二連線（已提交狀態不再面臨第二個失敗點）</li>
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
 *   <li>{@code ACELIB-DATA-008}：SQL 錯誤（連線失敗、語法錯誤、約束衝突）；
 *       訊息帶階段前綴以區分來源：{@code [jdbc:create-table]} 建表、
 *       {@code [jdbc:read]} 查詢、{@code [jdbc:migrate-table]} 舊表升級、
 *       {@code [jdbc:save]} 保存、{@code [jdbc:init]} 連線層</li>
 *   <li>{@code ACELIB-DATA-009}：舊版本但無對應 migration</li>
 *   <li>{@code ACELIB-DATA-010}：on-disk schema 版本比 current 新（拒絕降版覆寫）</li>
 *   <li>{@code ACELIB-DATA-011}：table 名稱不是合法 SQL identifier</li>
 * </ul>
 *
 * @since 1.0.0
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

    /**
     * 表級 init 互斥（同一 JVM 內同表同時只跑一個 init）。
     *
     * <p>舊表升級會做 DROP + RENAME，併發跑兩個升級會互相踩踏；
     * 跨行程 / 跨主機的併發仍須呼叫端在外部序列化（見類別 Javadoc「併發限制」）。</p>
     */
    private static final ConcurrentHashMap<String, Object> TABLE_LOCKS =
        new ConcurrentHashMap<>();

    private static Object tableLock(String tableName) {
        return TABLE_LOCKS.computeIfAbsent(tableName, k -> new Object());
    }

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
        Map<String, Object> committed;
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 同一 JVM 內同表只跑一個 init（舊表升級的 DROP + RENAME 不可併發）。
                synchronized (tableLock(tableName)) {
                    // 中斷復原必須先於建表：若上次升級停在「舊表已刪、改名未完成」，
                    // 暫存表是唯一副本；先建表會憑空造出空正式表，讓復原誤判為
                    // 雙表並存而刪掉唯一副本。
                    recoverInterruptedSwap(conn);
                    createTableIfMissing(conn, tableName, "create-table");
                    if (isLegacyShape(conn, tableName)) {
                        committed = initLegacyTable(conn);
                    } else {
                        committed = initCurrentTable(conn);
                    }
                    conn.commit();
                }
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
                "[jdbc:init] failed to init store '" + name + "': " + ex.getMessage(), ex);
        }

        // root 視圖由剛提交的同一份資料建立，不再開第二連線重讀：
        // 已 commit 的狀態不再面臨第二個失敗點。
        Map<String, Object> view = new LinkedHashMap<>(committed);
        view.put("_version", codec.encodeVersion(currentVersion));
        this.rootView = new MemoryRecord("", view);
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
                "[jdbc:save] failed to save store '" + name + "': " + ex.getMessage(), ex);
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

    /**
     * 升級暫存表名（正式表名 + 固定後綴；後綴符合 table identifier 規則，
     * 因此拼接結果仍是合法 identifier）。
     */
    String migrationTempTable() {
        return tableName + "__acelib_migrate";
    }

    private DataStoreException sqlError(String stage, String action, SQLException cause) {
        return new DataStoreException("ACELIB-DATA-008",
            "[jdbc:" + stage + "] " + action + " store '" + name + "': "
                + cause.getMessage(), cause);
    }

    /**
     * 新形狀表的 init 流程（呼叫前表已存在且為新形狀）。
     *
     * @return 已提交的資料（含 {@code _version} 純文字）
     */
    private Map<String, Object> initCurrentTable(Connection conn) throws SQLException {
        Map<String, Object> loaded;
        SchemaVersion onDiskVersion;
        try {
            loaded = readAllFrom(conn, tableName);
            onDiskVersion = readVersionFrom(conn, tableName);
        } catch (SQLException ex) {
            throw sqlError("read", "failed to read data during init of", ex);
        }

        // 版本檢查與資料遷移；失敗拋 ACELIB-DATA-004/009/010，
        // 由 init 的 catch 統一 rollback（此時正式表尚未被改動）。
        applyDataMigration(loaded, onDiskVersion);

        // 重新寫入全部（schema 變更後可能資料結構變化）
        try {
            deleteAll(conn, tableName);
            loaded.put("_version", codec.encodeVersion(currentVersion));
            writeAll(conn, tableName, loaded);
        } catch (SQLException ex) {
            throw sqlError("write", "failed to persist data during init of", ex);
        }
        return loaded;
    }

    /**
     * 與版本無關的資料遷移（新舊形狀共用）。
     *
     * @param loaded       已讀出的資料（不含 {@code _version}）；遷移成功時就地更新
     * @param onDiskVersion 磁碟上的 schema 版本
     */
    private void applyDataMigration(Map<String, Object> loaded, SchemaVersion onDiskVersion) {
        // on-disk 版本比 current 新時必須拒絕：
        // 不可降版覆寫既有資料（會造成資料遺失）。
        if (onDiskVersion.compareTo(currentVersion) > 0) {
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
                throw new DataStoreException("ACELIB-DATA-004",
                    "migration failed: " + result.errorMessage(),
                    result.cause());
            }
            if (result.appliedSteps().isEmpty()) {
                throw new DataStoreException("ACELIB-DATA-009",
                    "no migration found from " + onDiskVersion
                        + " to " + currentVersion);
            }
        }
    }

    /**
     * 舊形狀表的 init 流程：讀整張舊表 → 本 store 版本檢查與資料遷移
     * （舊表不動，其他 store 不跑遷移）→ 換表升級為新形狀。
     *
     * @return 已提交的資料（含 {@code _version} 純文字）
     */
    private Map<String, Object> initLegacyTable(Connection conn) throws SQLException {
        Map<String, Object> loaded;
        SchemaVersion onDiskVersion;
        List<LegacyRow> allRows;
        try {
            loaded = readAllLegacy(conn);
            onDiskVersion = readVersionLegacy(conn);
            allRows = readAllRows(conn, tableName);
        } catch (SQLException ex) {
            throw sqlError("read", "failed to read legacy data during init of", ex);
        }

        applyDataMigration(loaded, onDiskVersion);
        loaded.put("_version", codec.encodeVersion(currentVersion));
        swapToNewShape(conn, loaded, allRows);
        return loaded;
    }

    /**
     * 舊表（或新表）整表原始列：搬遷其他 store 資料與驗證覆蓋率用。
     *
     * @param storeName 列所屬 store（不可為 null，否則該列無法歸屬）
     * @param k         key（null 列會在搬遷時跳過，與讀路徑一致）
     * @param v         未解碼的原始 stored value（含 {@code _version} 在內原樣保留）
     */
    private record LegacyRow(String storeName, String k, String v) {
    }

    /**
     * 讀出指定表全部 store 的原始列（不分 store，不過濾 {@code _version}）。
     */
    private List<LegacyRow> readAllRows(Connection conn, String table)
            throws SQLException {
        List<LegacyRow> rows = new ArrayList<>();
        String sql = "SELECT store_name, k, v FROM " + table;
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new LegacyRow(
                    rs.getString("store_name"), rs.getString("k"), rs.getString("v")));
            }
        }
        return rows;
    }

    /**
     * 把已遷移的資料與其他 store 原樣列一起寫入暫存表、讀回驗證後與正式表交換。
     *
     * <p>暫存表寫入會先 {@code commit}（DDL 在 MySQL / MariaDB 本就自動提交，
     * 暫存表與舊表並存，可安全驗證）；驗證失敗時刪暫存表、舊表保留。
     * 注意：此方法內已自行 commit，呼叫端的 transaction 會接續使用同一個連線。</p>
     *
     * @param data    本 store 遷移結果（含 {@code _version})
     * @param allRows 舊表整表原始列；本 store 以外全部原樣寫入暫存表
     */
    private void swapToNewShape(Connection conn,
                                Map<String, Object> data,
                                List<LegacyRow> allRows)
            throws SQLException {
        String tmp = migrationTempTable();
        long legacyThisCount = allRows.stream()
            .filter(r -> name.equals(r.storeName()))
            .count();
        // 暫存表應有：舊表非本 store 列全數 + 本 store 遷移結果（含 _version）。
        long expectedTmpTotal = (allRows.size() - legacyThisCount) + data.size();
        try {
            createTableIfMissing(conn, tmp, "migrate-table");
            writeAll(conn, tmp, data);
            writePreservedRows(conn, tmp, allRows);
            conn.commit();
        } catch (SQLException ex) {
            rollbackQuietly(conn);
            dropQuietly(conn, tmp);
            throw sqlError("migrate-table",
                "failed to stage upgraded table '" + tmp + "' during init of", ex);
        }

        Map<String, Object> check;
        SchemaVersion checkVersion;
        long tmpTotal;
        try {
            check = readAllFrom(conn, tmp);
            checkVersion = readVersionFrom(conn, tmp);
            tmpTotal = readAllRows(conn, tmp).size();
        } catch (SQLException ex) {
            dropQuietly(conn, tmp);
            throw sqlError("migrate-table",
                "failed to verify upgraded table '" + tmp + "' during init of", ex);
        }
        if (check.size() != data.size() - 1
                || checkVersion.compareTo(currentVersion) != 0
                || tmpTotal != expectedTmpTotal) {
            dropQuietly(conn, tmp);
            throw new DataStoreException("ACELIB-DATA-008",
                "[jdbc:migrate-table] upgraded row verification failed for store '"
                    + name + "': expected " + (data.size() - 1) + " rows at version "
                    + currentVersion + " plus " + (expectedTmpTotal - data.size() + 1)
                    + " preserved rows (total " + expectedTmpTotal + "), got "
                    + check.size() + " rows at version "
                    + checkVersion + " (total " + tmpTotal + ")");
        }

        try {
            dropTableIfExists(conn, tableName);
            renameTable(conn, tmp, tableName);
        } catch (SQLException ex) {
            throw sqlError("migrate-table",
                "failed to swap upgraded table '" + tmp + "' during init of", ex);
        }
    }

    /**
     * 把舊表非本 store 的列原樣寫入暫存表新形狀。
     *
     * <p>{@code store_name}/{@code k}/{@code v} 逐字保留
     * （含各 store 的 {@code _version} 列，等它們各自 init 時再遷移），
     * {@code k_hash} 由 {@code k} 重算；本 store 的列已由 {@code writeAll}
     * 寫入遷移結果，此處跳過。</p>
     */
    private void writePreservedRows(Connection conn, String table, List<LegacyRow> allRows)
            throws SQLException {
        String sql = "INSERT INTO " + table
            + " (store_name, k_hash, k, v) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (LegacyRow r : allRows) {
                if (name.equals(r.storeName())) {
                    continue;
                }
                if (r.k() == null) {
                    continue;
                }
                ps.setString(1, r.storeName());
                ps.setString(2, hashKey(r.k()));
                ps.setString(3, r.k());
                ps.setString(4, r.v());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 復原中斷的換表：舊表已刪而改名未完成時接續改名；否則清掉殘留暫存表。
     */
    private void recoverInterruptedSwap(Connection conn) throws SQLException {
        String tmp = migrationTempTable();
        boolean tmpExists;
        boolean realExists;
        try {
            tmpExists = tableExists(conn, tmp);
            realExists = tableExists(conn, tableName);
        } catch (SQLException ex) {
            throw sqlError("read", "failed to inspect tables during init of", ex);
        }
        if (!tmpExists) {
            return;
        }
        try {
            if (!realExists) {
                renameTable(conn, tmp, tableName);
            } else {
                dropTableIfExists(conn, tmp);
            }
        } catch (SQLException ex) {
            throw sqlError("migrate-table",
                "failed to recover interrupted table upgrade during init of", ex);
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // best effort
        }
    }

    private void dropQuietly(Connection conn, String table) {
        try {
            dropTableIfExists(conn, table);
        } catch (SQLException ignore) {
            // best effort
        }
    }

    private void createTableIfMissing(Connection conn) throws SQLException {
        createTableIfMissing(conn, tableName, "create-table");
    }

    /**
     * 計算 key 的 SHA-256 hex（UTF-8）。
     *
     * <p>新 schema 以 {@code k_hash}（固定 64 字元）為主鍵的一部分，
     * 任意長度的 key 都不會超過 utf8mb4 InnoDB 索引上限；完整 key 另存於
     * {@code k} 欄。碰撞機率可忽略；寫入時主鍵衝突仍會以
     * {@code ACELIB-DATA-008} 回報，不會靜默覆蓋。</p>
     *
     * @param key store key；不可為 null
     * @return 64 字元小寫 hex
     */
    static String hashKey(String key) {
        try {
            java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private void createTableIfMissing(Connection conn, String table, String stage)
            throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
            + "store_name VARCHAR(255) NOT NULL, "
            + "k_hash CHAR(64) NOT NULL, "
            + "k TEXT NOT NULL, "
            + "v TEXT, "
            + "PRIMARY KEY (store_name, k_hash))"
            + tableOptions(conn);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException ex) {
            throw sqlError(stage,
                "failed to create table '" + table + "' during init of", ex);
        }
    }

    /**
     * MySQL / MariaDB 專用的建表後綴（InnoDB + utf8mb4）；其他資料庫回傳空字串
     * 維持可攜寫法。product 名取不到時保守回傳空字串，錯誤交給後續語句回報。
     */
    private String tableOptions(Connection conn) {
        String product = "";
        try {
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            if (meta != null && meta.getDatabaseProductName() != null) {
                product = meta.getDatabaseProductName();
            }
        } catch (SQLException ex) {
            return "";
        }
        String lower = product.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        }
        return "";
    }

    private void dropTableIfExists(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + table);
        }
    }

    private void renameTable(Connection conn, String from, String to) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE " + from + " RENAME TO " + to);
        }
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        java.sql.DatabaseMetaData meta = conn.getMetaData();
        if (meta == null) {
            return false;
        }
        // catalog 必須限定為當前庫：傳 null 時 MySQL / MariaDB 的 metadata
        // 會跨資料庫洩漏同名表的資訊（實證：同伺服器另一庫的同名表會被掃到）。
        String catalog = conn.getCatalog();
        try (ResultSet rs = meta.getTables(catalog, null, table, null)) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        try (ResultSet rs = meta.getTables(catalog, null, "%", null)) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 形狀偵測：表含 {@code k_hash} 欄即新形狀，否則視為舊形狀。
     */
    private boolean isLegacyShape(Connection conn, String table) throws SQLException {
        java.sql.DatabaseMetaData meta = conn.getMetaData();
        if (meta == null) {
            return false;
        }
        // 同上：catalog 限定當前庫，避免同伺服器他庫同名表污染形狀判斷。
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, "k_hash")) {
            while (rs.next()) {
                if ("k_hash".equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, Object> readAll(Connection conn) throws SQLException {
        return readAllFrom(conn, tableName);
    }

    private Map<String, Object> readAllFrom(Connection conn, String table)
            throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        String sql = "SELECT k, v FROM " + table + " WHERE store_name = ?";
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

    /** 舊形狀表的讀取（舊 SQL；新形狀表永不走此路徑）。 */
    private Map<String, Object> readAllLegacy(Connection conn) throws SQLException {
        return readAllFrom(conn, tableName);
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
        return readVersionFrom(conn, tableName);
    }

    private SchemaVersion readVersionFrom(Connection conn, String table) throws SQLException {
        String sql = "SELECT v FROM " + table + " WHERE store_name = ? AND k_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, hashKey("_version"));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString("v");
                    if (raw != null && !raw.isBlank()) {
                        return decodeStoredVersion(raw);
                    }
                }
            }
        }
        return currentVersion;
    }

    /**
     * 相容兩種 {@code _version} 歷史儲存格式：
     * <ul>
     *   <li>新版（生產）：純文字 {@code "1.0"}（不包 JSON quotes）</li>
     *   <li>測試 pre-insert 與舊資料：JSON 編碼 {@code "\"1.0\""}</li>
     * </ul>
     * <p>用首字元區分：JSON 形式一定以 {@code "} 開頭；純文字一定以數字開頭。
     * 新舊形狀表的版本讀取共用此方法——整表搬遷會把其他 store 的版本列原樣
     * 帶進新表，它們可能是舊的 JSON 殼格式。</p>
     */
    private SchemaVersion decodeStoredVersion(String raw) {
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

    /** 舊形狀表的版本讀取（兩種格式共用 decodeStoredVersion）。 */
    private SchemaVersion readVersionLegacy(Connection conn) throws SQLException {
        String sql = "SELECT v FROM " + tableName + " WHERE store_name = ? AND k = '_version'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString("v");
                    if (raw != null && !raw.isBlank()) {
                        return decodeStoredVersion(raw);
                    }
                }
            }
        }
        return currentVersion;
    }

    private void deleteAll(Connection conn) throws SQLException {
        deleteAll(conn, tableName);
    }

    private void deleteAll(Connection conn, String table) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE store_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    private void writeAll(Connection conn, Map<String, Object> data) throws SQLException {
        writeAll(conn, tableName, data);
    }

    private void writeAll(Connection conn, String table, Map<String, Object> data)
            throws SQLException {
        if (data.isEmpty()) {
            return;
        }
        // vendor-portable: plain INSERT. 呼叫端已先清空（deleteAll）或寫入全新暫存表，
        // 不需要 MERGE/ON DUPLICATE KEY UPDATE。
        String sql = "INSERT INTO " + table
            + " (store_name, k_hash, k, v) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                ps.setString(1, name);
                ps.setString(2, hashKey(e.getKey()));
                ps.setString(3, e.getKey());
                ps.setString(4, storedValueFor(e.getKey(), e.getValue()));
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