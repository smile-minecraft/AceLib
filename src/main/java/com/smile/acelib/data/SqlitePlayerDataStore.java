package com.smile.acelib.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * 以 SQLite 檔案為後端的 {@link PlayerDataStore}（Internal）。
 *
 * <p>AceLib 內建玩家資料服務的預設後端：不需要外部服務，單一檔案即可承載全部
 * 玩家資料，與 {@link JdbcPlayerDataStore} 共用同一份 SQL 骨架。</p>
 *
 * <h2>連線與耐久性</h2>
 * <ul>
 *   <li>每次操作開一條連線並於結束時關閉；SQLite 檔案層由 WAL 模式提供併發讀寫</li>
 *   <li>{@code journal_mode=WAL}：讀寫不互相阻塞</li>
 *   <li>{@code synchronous=FULL}：每次交易提交都 fsync，確保「週期保存」宣稱的
 *       落盤語意在斷電情境也成立</li>
 *   <li>{@code busy_timeout=5000}：短暫鎖等待，避免立即回
 *       {@code SQLITE_BUSY}</li>
 * </ul>
 *
 * <p>WAL 模式會在檔案旁產生 {@code -wal}／{@code -shm} 附檔。要取一致快照做備份時
 * 必須用 {@code VACUUM INTO} 或 SQLite 的備份 API，直接複製 {@code .db} 會遺失
 * 尚未 checkpoint 的頁（見 {@link PlayerDataConverter}）。</p>
 *
 * <h2>驅動程式</h2>
 * <p>需要 {@code org.xerial:sqlite-jdbc} 在 classpath 上。{@link #init()} 會先嘗試
 * 載入 {@code org.sqlite.JDBC}；缺席時以 {@code ACELIB-DATA-012} 失敗並說明
 * 安裝方式，不讓呼叫端看到 {@link ClassNotFoundException}。</p>
 *
 * @see PlayerDataStore
 * @since 1.4.0
 */
final class SqlitePlayerDataStore extends JdbcPlayerDataStore {

    /** SQLite JDBC driver 類別名；以反射載入避免編譯期依賴。 */
    static final String DRIVER_CLASS = "org.sqlite.JDBC";

    /** 取得連線前的鎖等待毫秒數。 */
    static final String BUSY_TIMEOUT_MS = "5000";

    private final Path databaseFile;

    SqlitePlayerDataStore(String name, Path databaseFile, SchemaVersion currentVersion,
            String owner, String tableName) {
        super(name, new FileDataSource(databaseFile), currentVersion, owner, tableName);
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile");
    }

    /**
     * 取得此 store 使用的 SQLite 檔案路徑（診斷與備份流程使用）。
     *
     * @return 不可為 null 的檔案路徑
     */
    Path databaseFile() {
        return databaseFile;
    }

    @Override
    public void init() {
        ensureDriverAvailable();
        super.init();
    }

    @Override
    protected String tableOptions() {
        // SQLite 不接受 ENGINE／CHARSET；保持可攜寫法即可。
        return "";
    }

    @Override
    protected void configureConnection(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
        }
    }

    private static void ensureDriverAvailable() {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException ex) {
            throw new DataStoreException("ACELIB-DATA-012",
                "SQLite JDBC driver (" + DRIVER_CLASS + ") is not on the classpath; "
                    + "install org.xerial:sqlite-jdbc or switch the player data store to "
                    + "a JDBC backend via PlayerDataStores.jdbc(...)");
        }
    }

    /**
     * 以 {@link DriverManager} 開啟檔案連線的最小 {@link DataSource}。
     *
     * <p>{@link DriverManager} 需要能自行載入 driver；SQLite JDBC 以
     * {@code java.sql.Driver} 靜態註冊，故顯式 {@code Class.forName} 載入即可，
     * 不需要自訂 classloader。</p>
     */
    private static final class FileDataSource implements DataSource {

        private final Path databaseFile;

        FileDataSource(Path databaseFile) {
            this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile");
        }

        @Override
        public Connection getConnection() throws SQLException {
            Path parent = databaseFile.toAbsolutePath().getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException ex) {
                    throw new DataStoreException("ACELIB-DATA-001",
                        "failed to create SQLite directory: " + parent, ex);
                }
            }
            ensureDriverAvailable();
            return DriverManager.getConnection(jdbcUrl(), new Properties());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private String jdbcUrl() {
            return "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            // no-op
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // no-op
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
    }
}