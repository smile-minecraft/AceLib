package com.smile.acelib.data;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Test-only 純 in-memory {@link DataSource}：不依賴任何外部資料庫或大型依賴。
 *
 * <p>用於 {@link JdbcDataStore} 測試；以 {@link java.sql.DriverManager} 取得
 * {@code :memory:} SQLite-style connection 不可行（SQLite JDBC 不在 classpath），
 * 因此本 fixture 自行實作一個極簡 {@link DataSource}：</p>
 *
 * <ul>
 *   <li>底層用 {@link java.sql} 標準 API，無 vendor 專屬語法</li>
 *   <li>所有 {@link Connection} 都共享同一個 in-memory database</li>
 *   <li>對 {@link JdbcDataStore} 的合約測試而言已足夠（init / save / transaction）</li>
 * </ul>
 *
 * <p>此類別僅供測試使用；不暴露為 public API。</p>
 *
 * @since Phase 8 (Plan §十三)
 */
final class InMemoryDataSource implements DataSource {

    final InMemoryDatabase database = new InMemoryDatabase();

    @Override
    public Connection getConnection() throws SQLException {
        return new InMemoryConnection(database).asJdbc();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("InMemoryDataSource");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}