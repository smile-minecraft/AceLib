package com.smile.acelib.data;

import java.nio.file.Path;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * {@link PlayerDataStore} 的建立工廠（public API）。
 *
 * <p>下游透過本工廠取得 store，不直接依賴 {@code SqlitePlayerDataStore}／
 * {@code JdbcPlayerDataStore} 等 Internal 實作類別。</p>
 *
 * <h2>預設與替代</h2>
 * <ul>
 *   <li>{@link #sqlite(Path, SchemaVersion)}：預設後端，一個檔案承載全部玩家資料</li>
 *   <li>{@link #jdbc(DataSource, SchemaVersion)}：改用 MySQL／MariaDB 等關聯式資料庫，
 *       由呼叫端提供 {@link DataSource}（含連線池與帳密）</li>
 *   <li>{@link #fromDataStore(DataStore)}：把既有 {@link DataStore}（例如
 *       {@code JsonFileDataStore}）包成逐玩家存取語意，供遷移期間與既有組裝沿用</li>
 * </ul>
 *
 * <h2>SQLite 驅動程式</h2>
 * <p>SQLite 後端需要 {@code org.xerial:sqlite-jdbc}。AceLib 插件在
 * {@code plugin.yml} 以 {@code libraries:} 宣告版本，由 Paper 於啟動時下載；
 * 自行把 AceLib 當函式庫使用時，須自行確保該驅動在 classpath 上。驅動缺席時
 * {@code init()} 以 {@code ACELIB-DATA-012} 失敗，不影響其他模組。</p>
 *
 * @see PlayerDataStore
 * @since 1.4.0
 */
public final class PlayerDataStores {

    /**
     * 逐玩家資料的預設資料表名稱。
     *
     * <p>符合 {@code [A-Za-z_][A-Za-z0-9_]*}，因此可直接拼進 SQL。</p>
     */
    public static final String DEFAULT_TABLE = "acelib_player_data";

    /**
     * 內建玩家資料服務使用的 owner（命名空間）值。
     *
     * <p>AceLib 自身的玩家資料固定用此值；下游自建的 store 應提供自己的
     * owner，避免與內建資料互相覆寫。</p>
     */
    public static final String DEFAULT_OWNER = "acelib";

    private PlayerDataStores() {
    }

    /**
     * 建立 SQLite 後端的逐玩家 store。
     *
     * <p>檔案不存在時由 {@code init()} 建立；父目錄也會一併建立。</p>
     *
     * @param databaseFile  SQLite 資料庫檔案；不可為 null
     * @param schemaVersion schema 版本；不可為 null
     * @return 已建立但尚未 {@code init()} 的 store
     * @throws NullPointerException 當任一參數為 null
     */
    public static PlayerDataStore sqlite(Path databaseFile, SchemaVersion schemaVersion) {
        return sqlite(databaseFile, schemaVersion, DEFAULT_OWNER, DEFAULT_TABLE);
    }

    /**
     * 建立 SQLite 後端的逐玩家 store（自訂 owner 與資料表名）。
     *
     * @param databaseFile  SQLite 資料庫檔案；不可為 null
     * @param schemaVersion schema 版本；不可為 null
     * @param owner         owner（命名空間）；不可為 null 或空白
     * @param tableName     資料表名；須符合 {@code [A-Za-z_][A-Za-z0-9_]*}
     * @return 已建立但尚未 {@code init()} 的 store
     * @throws NullPointerException     當任一參數為 null
     * @throws IllegalArgumentException 當 {@code owner} 為空白或 {@code tableName} 非法
     */
    public static PlayerDataStore sqlite(Path databaseFile, SchemaVersion schemaVersion,
            String owner, String tableName) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        requireOwner(owner);
        return new SqlitePlayerDataStore("acelib-player-sqlite", databaseFile,
            schemaVersion, owner, tableName);
    }

    /**
     * 建立 JDBC 後端的逐玩家 store（預設 owner 與資料表名）。
     *
     * <p>{@code DataSource} 的生命週期由呼叫端管理；store 不會關閉它。</p>
     *
     * @param dataSource    JDBC 資料來源；不可為 null
     * @param schemaVersion schema 版本；不可為 null
     * @return 已建立但尚未 {@code init()} 的 store
     * @throws NullPointerException 當任一參數為 null
     */
    public static PlayerDataStore jdbc(DataSource dataSource, SchemaVersion schemaVersion) {
        return jdbc(dataSource, schemaVersion, DEFAULT_OWNER, DEFAULT_TABLE);
    }

    /**
     * 建立 JDBC 後端的逐玩家 store（自訂 owner 與資料表名）。
     *
     * @param dataSource    JDBC 資料來源；不可為 null
     * @param schemaVersion schema 版本；不可為 null
     * @param owner         owner（命名空間）；不可為 null 或空白
     * @param tableName     資料表名；須符合 {@code [A-Za-z_][A-Za-z0-9_]*}
     * @return 已建立但尚未 {@code init()} 的 store
     * @throws NullPointerException     當任一參數為 null
     * @throws IllegalArgumentException 當 {@code owner} 為空白或 {@code tableName} 非法
     */
    public static PlayerDataStore jdbc(DataSource dataSource, SchemaVersion schemaVersion,
            String owner, String tableName) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        requireOwner(owner);
        return new JdbcPlayerDataStore("acelib-player-jdbc", dataSource,
            schemaVersion, owner, tableName);
    }

    /**
     * 把既有 {@link DataStore} 包成逐玩家存取語意。
     *
     * <p>資料仍落在原 store 裡的 {@code players.<uuid>} 路徑，因此既有資料不需要
     * 搬移即可讀寫。適合遷移期間沿用現有 {@code JsonFileDataStore}，
     * 或下游想自備非 SQLite 的 key-value 儲存。</p>
     *
     * @param delegate 已初始化的 {@link DataStore}；不可為 null
     * @return 包裝後的 store；其生命週期與 {@code delegate} 分離
     * @throws NullPointerException     當 {@code delegate} 為 null
     * @throws DataStoreException       當 {@code delegate} 尚未 {@code init()}
     */
    public static PlayerDataStore fromDataStore(DataStore delegate) {
        Objects.requireNonNull(delegate, "delegate");
        if (!delegate.isInitialized()) {
            throw new DataStoreException("ACELIB-DATA-005",
                "delegate store '" + delegate.name() + "' must be initialized before wrapping");
        }
        return new DataStorePlayerDataStore(delegate);
    }

    private static void requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner 不可為空白");
        }
    }
}