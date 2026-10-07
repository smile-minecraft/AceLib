package com.smile.acelib.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * {@link PlayerDataStore} 的 JDBC 通用實作（Internal）。
 *
 * <p>{@link SqlitePlayerDataStore} 與其他關聯式資料庫共用這份 SQL 骨架，只在建表選項
 * 與連線後設定上不同（見 {@link #tableOptions}、{@link #configureConnection}）。</p>
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE acelib_player_data (
 *     player_uuid CHAR(36)  NOT NULL,
 *     owner       VARCHAR(255) NOT NULL,
 *     field_hash  CHAR(64)  NOT NULL,
 *     field       VARCHAR(255) NOT NULL,
  *     payload     &lt;database-specific text type&gt; NOT NULL,
 *     schema_ver  VARCHAR(16) NOT NULL,
 *     revision    BIGINT     NOT NULL,
 *     updated_at  BIGINT     NOT NULL,
 *     PRIMARY KEY (player_uuid, owner, field_hash)
 * )
 * </pre>
 *
 * <p>{@code field_hash} 是欄位名的 SHA-256 hex（UTF-8，固定 64 字元），與
 * {@link JdbcDataStore} 的 key 雜湊同一演算法。主鍵最壞位元組數在 utf8mb4 下為
 * {@code 36 + 255*4 + 64*4 = 1352} bytes，遠低於 InnoDB 索引上限 3072；
 * 完整欄位名另存於 {@code field}。</p>
 *
 * <p>主鍵最左前綴即 {@code (player_uuid, owner)}，已覆蓋「讀某玩家全部欄位」與
 * 「列出某 owner 的全部玩家」，不需額外索引。</p>
 *
 * <h2>增量 upsert</h2>
  * <p>不使用 {@code ON DUPLICATE KEY UPDATE}／{@code ON CONFLICT} 等 vendor 專屬語法，
  * 改以先 {@code INSERT}、遇到主鍵衝突再 {@code UPDATE} 達成 upsert，保持 SQL 可攜，
  * 並避免 InnoDB 對「先刪後插」的不存在主鍵取得 gap lock。值與現有內容相同時提前跳過，
  * 既不產生寫入也不推進 {@code revision}。</p>
 *
 * <p>{@link JdbcDataStore} 的 {@code TABLE_LOCKS} 只在 JVM 內序列化；跨行程同時寫同一張表
 * 仍需呼叫端自行序列化，本類不宣稱跨行程安全。</p>
 *
 * @see PlayerDataStore
 * @since 1.4.0
 */
class JdbcPlayerDataStore implements PlayerDataStore {

    /** 安全 SQL identifier 規則；用於驗證建構子傳入的 table 名。 */
    static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 欄位名長度上限：與既有 key 儲存的長度假設一致，超長在建構時拒絕。 */
    static final int MAX_FIELD_LENGTH = 255;

    private final String name;
    private final DataSource dataSource;
    private final SchemaVersion currentVersion;
    private final JsonCodec codec;
    private final String owner;
    private final String tableName;

    private volatile boolean initialized = false;
    private volatile boolean closed = false;

    JdbcPlayerDataStore(String name, DataSource dataSource,
            SchemaVersion currentVersion, String owner, String tableName) {
        this.name = requireName(name);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.codec = new JsonCodecImpl();
        this.owner = requireOwner(owner);
        this.tableName = requireTableName(tableName);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String owner() {
        return owner;
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
    public void init() {
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "store '" + name + "' is closed");
        }
        if (initialized) {
            return;
        }
        try (Connection conn = openConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                createTableIfMissing(conn);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(conn);
                throw ex;
            } finally {
                restoreAutoCommit(conn, prevAutoCommit);
            }
        } catch (SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "[player-jdbc:init] failed to init store '" + name + "': " + ex.getMessage(), ex);
        }
        this.initialized = true;
    }

    @Override
    public Optional<Record> load(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        String sql = "SELECT field, payload FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ? ORDER BY field";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> values = new LinkedHashMap<>();
                while (rs.next()) {
                    String field = rs.getString(1);
                    String payload = rs.getString(2);
                    if (field == null) {
                        continue;
                    }
                    values.put(field, payload == null ? null : decodePayload(payload, field));
                }
                if (values.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new MemoryRecord("", values));
            }
        } catch (SQLException ex) {
            throw sqlError("read", uuid, ex);
        }
    }

    @Override
    public void applyChanges(List<FieldChange> changes) {
        Objects.requireNonNull(changes, "changes");
        requireReady();
        // 同批次重複指定同一欄位時以最後一次為準；先收斂再開交易，避免
        // 同一主鍵被刪除兩次、插入兩次的無意義往返。
        Map<FieldKey, FieldChange> deduped = new LinkedHashMap<>();
        for (FieldChange change : changes) {
            deduped.put(new FieldKey(change.uuid(), change.field()), change);
        }
        if (deduped.isEmpty()) {
            return;
        }
        try (Connection conn = openConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (FieldChange change : deduped.values()) {
                    if (change.deletion()) {
                        deleteOne(conn, change.uuid(), change.field());
                    } else {
                        upsertOne(conn, change);
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(conn);
                throw ex;
            } finally {
                restoreAutoCommit(conn, prevAutoCommit);
            }
        } catch (SQLException ex) {
            throw new DataStoreException("ACELIB-DATA-008",
                "[player-jdbc:save] failed to apply " + deduped.size()
                    + " change(s) on store '" + name + "': " + ex.getMessage(), ex);
        }
    }

    @Override
    public void deletePlayer(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        String sql = "DELETE FROM " + tableName + " WHERE player_uuid = ? AND owner = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw sqlError("delete", uuid, ex);
        }
    }

    @Override
    public long revisionOf(UUID uuid, String field) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(field, "field");
        requireReady();
        String sql = "SELECT revision FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ? AND field_hash = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            ps.setString(3, JdbcDataStore.hashKey(field));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw sqlError("read", uuid, ex);
        }
    }

    @Override
    public int fieldCount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        String sql = "SELECT COUNT(*) FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            throw sqlError("read", uuid, ex);
        }
    }

    @Override
    public void close() {
        this.closed = true;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * 取得一筆現有 payload；不存在回 {@code null}。
     *
     * <p>供 upsert 比較用；{@code null} 表示該欄位不存在，必須寫入。</p>
     */
    private String existingPayload(Connection conn, UUID uuid, String field)
            throws SQLException {
        String sql = "SELECT payload FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ? AND field_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            ps.setString(3, JdbcDataStore.hashKey(field));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void upsertOne(Connection conn, FieldChange change) throws SQLException {
        String encoded = change.value() == null
            ? "null"
            : codec.encode(change.value());
        String current = existingPayload(conn, change.uuid(), change.field());
        if (encoded.equals(current)) {
            // 值未變：不寫入、不推進 revision。呼叫端即使誤以為有變更也不產生多餘寫入。
            return;
        }
        // 先 INSERT、撞主鍵再 UPDATE：
        // 「先 DELETE 再 INSERT」在 InnoDB REPEATABLE READ 下會對不存在的主鍵取得
        // gap lock，接著 INSERT 需要 insert-intent lock；並行交易會因此形成死結。
        // INSERT 優先只取 insert-intent lock，重複時才 UPDATE 已存在的列。
        String insertSql = "INSERT INTO " + tableName
            + " (player_uuid, owner, field_hash, field, payload, schema_ver,"
            + " revision, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, change.uuid().toString());
            ps.setString(2, owner);
            ps.setString(3, JdbcDataStore.hashKey(change.field()));
            ps.setString(4, change.field());
            ps.setString(5, encoded);
            ps.setString(6, codec.encodeVersion(currentVersion));
            ps.setLong(7, 1L);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            return;
        } catch (SQLException duplicate) {
            if (!isDuplicateKey(duplicate)) {
                throw duplicate;
            }
        }
        String updateSql = "UPDATE " + tableName
            + " SET field = ?, payload = ?, schema_ver = ?, revision = revision + 1,"
            + " updated_at = ? WHERE player_uuid = ? AND owner = ? AND field_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, change.field());
            ps.setString(2, encoded);
            ps.setString(3, codec.encodeVersion(currentVersion));
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, change.uuid().toString());
            ps.setString(6, owner);
            ps.setString(7, JdbcDataStore.hashKey(change.field()));
            ps.executeUpdate();
        }
    }

    /**
     * 判斷 SQLException 是否為主鍵／唯一性衝突（而非其他 SQL 失敗）。
     *
     * <p>MySQL／MariaDB 使用 vendor code {@code 1062}，通用 JDBC 可使用 SQLState
     * {@code 23505}。SQLite driver 的主要結果碼 {@code 19} 代表所有 constraint
     * 錯誤（包括 trigger abort），不能單獨據此當成重複；需確認 extended result code
     * 或錯誤訊息明確指出 PRIMARYKEY／UNIQUE。其他 SQL 失敗照原樣往上拋。</p>
     *
     * @param ex 失敗的 SQLException
     * @return true 表示主鍵已存在、應改走 UPDATE
     */
    private static boolean isDuplicateKey(SQLException ex) {
        String sqlState = ex.getSQLState();
        if ("23505".equals(sqlState) || ex.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
            return true;
        }
        int code = ex.getErrorCode();
        if (code == SQLITE_CONSTRAINT_PRIMARYKEY || code == SQLITE_CONSTRAINT_UNIQUE) {
            return true;
        }
        String message = ex.getMessage();
        return code == SQLITE_CONSTRAINT_PRIMARY
            && message != null
            && (message.contains("[SQLITE_CONSTRAINT_PRIMARYKEY]")
                || message.contains("[SQLITE_CONSTRAINT_UNIQUE]"));
    }

    /** SQLite {@code SQLITE_CONSTRAINT} 主要結果碼（未引用 sqlite driver 型別）。 */
    private static final int SQLITE_CONSTRAINT_PRIMARY = 19;
    /** SQLite extended result code：主鍵違反。 */
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    /** SQLite extended result code：唯一性違反。 */
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;
    /** MySQL／MariaDB duplicate entry vendor code。 */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private void deleteOne(Connection conn, UUID uuid, String field) throws SQLException {
        String sql = "DELETE FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ? AND field_hash = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            ps.setString(3, JdbcDataStore.hashKey(field));
            ps.executeUpdate();
        }
    }

    private void createTableIfMissing(Connection conn) throws SQLException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
            .append(tableName).append(" (")
            .append("player_uuid CHAR(36) NOT NULL, ")
            .append("owner VARCHAR(255) NOT NULL, ")
            .append("field_hash CHAR(64) NOT NULL, ")
            .append("field VARCHAR(255) NOT NULL, ")
            .append("payload ").append(isMySqlFamily(conn) ? "MEDIUMTEXT" : "TEXT")
            .append(" NOT NULL, ")
            .append("schema_ver VARCHAR(16) NOT NULL, ")
            .append("revision BIGINT NOT NULL, ")
            .append("updated_at BIGINT NOT NULL, ")
            .append("PRIMARY KEY (player_uuid, owner, field_hash))")
            .append(tableOptions());
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl.toString());
        }
    }

    /**
     * 建表選項。
     *
     * <p>預設（可攜寫法）不加任何 vendor 選項；MySQL／MariaDB 子類覆寫為
     * {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}，不依賴伺服器預設。</p>
     */
    protected String tableOptions() {
        return "";
    }

    /**
     * 取得連線並套用引擎專屬設定。
     *
     * <p>設定必須在 <strong>交易開始之前</strong>完成：SQLite 的
     * {@code PRAGMA journal_mode=WAL} 不能在交易內執行。預設為直接
     * {@code dataSource.getConnection()}；子類在此疊加連線層設定。</p>
     */
    protected Connection openConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        try {
            configureConnection(conn);
        } catch (SQLException | RuntimeException ex) {
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
        return conn;
    }

    /**
     * 連線建立後的引擎專屬設定（於交易開始前呼叫）。
     *
     * <p>預設為 no-op；SQLite 子類在此設定 WAL、fsync 與 busy timeout。</p>
     */
    protected void configureConnection(Connection conn) throws SQLException {
        // no-op by default
    }

    /**
     * 驗證後端是否為 MySQL 家族；供子類決定 table options。
     *
     * @param conn 已取得的連線
     * @return true 表示 MySQL／MariaDB
     */
    protected static boolean isMySqlFamily(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null) {
                return false;
            }
            String lower = product.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("mysql") || lower.contains("mariadb");
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * 解碼單一欄位的 payload。
     *
     * <p>{@link JsonCodec#decode} 只接受 JSON object 為根，但欄位值可能是字串、
     * 數值或陣列，因此包一層 {@code {"v": …}} 後再取出，與
     * {@link JdbcDataStore} 讀回單值時的處理一致。</p>
     */
    private Object decodePayload(String payload, String field) {
        try {
            return codec.decode("{\"v\":" + payload + "}").get("v");
        } catch (RuntimeException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "failed to decode stored payload for field='" + field + "' on store '"
                    + name + "': " + ex.getMessage(), ex);
        }
    }

    private void requireReady() {
        if (closed) {
            throw new DataStoreException("ACELIB-DATA-005",
                "store '" + name + "' is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("init() must be called before using store '"
                + name + "'");
        }
    }

    private DataStoreException sqlError(String stage, UUID uuid, SQLException cause) {
        return new DataStoreException("ACELIB-DATA-008",
            "[player-jdbc:" + stage + "] failed on store '" + name + "' for uuid=" + uuid
                + ": " + cause.getMessage(), cause);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // best effort：原始失敗點才是呼叫端要處理的
        }
    }

    private static void restoreAutoCommit(Connection conn, boolean prev) {
        try {
            conn.setAutoCommit(prev);
        } catch (SQLException ignore) {
            // best effort
        }
    }

    static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不可為空白");
        }
        return name;
    }

    static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner 不可為空白");
        }
        return owner;
    }

    static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                "tableName 必須符合 [A-Za-z_][A-Za-z0-9_]*：" + tableName);
        }
        return tableName;
    }

    /**
     * 批次去重用的複合鍵。
     *
     * @param uuid  玩家 UUID
     * @param field 欄位名
     */
    private record FieldKey(UUID uuid, String field) {
    }

    /**
     * 給測試與診斷用的欄位名清單（依欄位名排序）。
     *
     * @param uuid 玩家 UUID
     * @return 欄位名清單
     */
    List<String> fieldsOf(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireReady();
        String sql = "SELECT field FROM " + tableName
            + " WHERE player_uuid = ? AND owner = ? ORDER BY field";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            List<String> fields = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fields.add(rs.getString(1));
                }
            }
            return fields;
        } catch (SQLException ex) {
            throw sqlError("read", uuid, ex);
        }
    }
}
