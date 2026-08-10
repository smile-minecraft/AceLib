package com.smile.acelib.data;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only 極簡 JDBC mock：使用 {@link Proxy} 動態實作
 * {@link Connection}/{@link Statement}/{@link PreparedStatement}/{@link ResultSet}，
 * 僅支援 {@link JdbcDataStore} 真正會用到的 SQL 模式。
 *
 * <h2>支援的 SQL</h2>
 * <ul>
 *   <li>{@code CREATE TABLE IF NOT EXISTS <table> (...)}</li>
 *   <li>{@code SELECT k, v FROM <table> WHERE store_name = ?}</li>
 *   <li>{@code SELECT v FROM <table> WHERE store_name = ? AND k = ?}</li>
 *   <li>{@code DELETE FROM <table> WHERE store_name = ?}</li>
 *   <li>{@code INSERT INTO <table> (store_name, k, v) VALUES (?, ?, ?)}</li>
 * </ul>
 *
 * <p>不實作 vendor 專屬語法（{@code ON DUPLICATE KEY UPDATE}、{@code ON CONFLICT}），
 * 強制 {@link JdbcDataStore} 使用 vendor-portable 寫法。</p>
 *
 * <p>transaction 語意：{@code setAutoCommit(false)} 後寫入先到 staging，
 * {@code commit()} 才合併進資料庫；{@code rollback()} 丟棄 staging。</p>
 *
 * @since Phase 8 (Plan §十三)
 */
final class InMemoryConnection {

    // ---- SQL patterns ----
    static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
        "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+).*",
        Pattern.CASE_INSENSITIVE);
    static final Pattern SELECT_KV_PATTERN = Pattern.compile(
        "SELECT\\s+k\\s*,\\s*v\\s+FROM\\s+(\\w+)\\s+WHERE\\s+store_name\\s*=\\s*\\?",
        Pattern.CASE_INSENSITIVE);
    // 同時支援 `k = ?`（parameter）與 `k = '_version'`（literal，production 使用）。
    // 兩種形式查詢目的相同：取得當前 schema 版本。fixture 須覆蓋 production 合法 SQL。
    static final Pattern SELECT_VERSION_PATTERN = Pattern.compile(
        "SELECT\\s+v\\s+FROM\\s+(\\w+)\\s+WHERE\\s+store_name\\s*=\\s*\\?\\s+AND\\s+k\\s*=\\s*(?:\\?|'_version')",
        Pattern.CASE_INSENSITIVE);
    static final Pattern DELETE_ALL_PATTERN = Pattern.compile(
        "DELETE\\s+FROM\\s+(\\w+)\\s+WHERE\\s+store_name\\s*=\\s*\\?",
        Pattern.CASE_INSENSITIVE);
    static final Pattern INSERT_KV_PATTERN = Pattern.compile(
        "INSERT\\s+INTO\\s+(\\w+)\\s*\\(\\s*store_name\\s*,\\s*k\\s*,\\s*v\\s*\\)\\s+"
            + "VALUES\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*,\\s*\\?\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    private final InMemoryDatabase database;
    private final InMemoryTransaction tx;
    private final Connection proxy;
    private boolean closed = false;
    private boolean autoCommit = true;

    InMemoryConnection(InMemoryDatabase database) {
        this.database = database;
        this.tx = new InMemoryTransaction(database);
        this.proxy = (Connection) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { Connection.class },
            new ConnectionHandler());
    }

    Connection asJdbc() {
        return proxy;
    }

    void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            tx.rollback();
        } catch (Exception ignore) {
            // best effort
        }
    }

    boolean isClosed() {
        return closed;
    }

    void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("connection is closed");
        }
    }

    void doSetAutoCommit(boolean v) throws SQLException {
        checkOpen();
        if (autoCommit == v) {
            return;
        }
        if (!v && !tx.isActive()) {
            tx.begin();
        }
        autoCommit = v;
    }

    boolean doGetAutoCommit() {
        return autoCommit;
    }

    void doCommit() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("commit not allowed in auto-commit mode");
        }
        tx.commit();
    }

    void doRollback() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("rollback not allowed in auto-commit mode");
        }
        tx.rollback();
    }

    Statement doCreateStatement() {
        return StatementProxy.create(database, tx, this::checkOpen);
    }

    PreparedStatement doPrepareStatement(String sql) {
        String trimmed = sql.trim();
        Matcher m;
        if ((m = CREATE_TABLE_PATTERN.matcher(trimmed)).matches()) {
            return new CreateTableStatement(database, m.group(1), this::checkOpen).asProxy();
        }
        if ((m = SELECT_KV_PATTERN.matcher(trimmed)).matches()) {
            return new SelectKvStatement(database, m.group(1), this::checkOpen).asProxy();
        }
        if ((m = SELECT_VERSION_PATTERN.matcher(trimmed)).matches()) {
            return new SelectVersionStatement(database, m.group(1), this::checkOpen).asProxy();
        }
        if ((m = DELETE_ALL_PATTERN.matcher(trimmed)).matches()) {
            return new DeleteAllStatement(database, m.group(1), tx, this::checkOpen).asProxy();
        }
        if ((m = INSERT_KV_PATTERN.matcher(trimmed)).matches()) {
            return new InsertKvStatement(database, m.group(1), tx, this::checkOpen).asProxy();
        }
        throw new IllegalArgumentException("unsupported SQL: " + sql);
    }

    private final class ConnectionHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                doClose();
                return null;
            }
            if ("isClosed".equals(name)) {
                return isClosed();
            }
            if ("setAutoCommit".equals(name)) {
                doSetAutoCommit((Boolean) args[0]);
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return doGetAutoCommit();
            }
            if ("commit".equals(name)) {
                doCommit();
                return null;
            }
            if ("rollback".equals(name)) {
                doRollback();
                return null;
            }
            if ("createStatement".equals(name)) {
                return doCreateStatement();
            }
            if ("prepareStatement".equals(name)) {
                return doPrepareStatement((String) args[0]);
            }
            if ("isValid".equals(name)) {
                return !isClosed();
            }
            if ("getMetaData".equals(name)) {
                return null;
            }
            if ("toString".equals(name)) {
                return "InMemoryConnection@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // Common interface
    // =================================================================

    interface CheckOpen {
        void run() throws SQLException;
    }

    // =================================================================
    // Statement proxy
    // =================================================================

    static final class StatementProxy implements InvocationHandler {
        private final InMemoryDatabase database;
        private final InMemoryTransaction tx;
        private final CheckOpen checkOpen;
        private boolean closed = false;

        private StatementProxy(InMemoryDatabase database, InMemoryTransaction tx,
                              CheckOpen checkOpen) {
            this.database = database;
            this.tx = tx;
            this.checkOpen = checkOpen;
        }

        static Statement create(InMemoryDatabase database, InMemoryTransaction tx,
                                CheckOpen checkOpen) {
            StatementProxy h = new StatementProxy(database, tx, checkOpen);
            return (Statement) Proxy.newProxyInstance(
                StatementProxy.class.getClassLoader(),
                new Class<?>[] { Statement.class },
                h);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("executeUpdate".equals(name)) {
                checkOpen.run();
                String sql = (String) args[0];
                Matcher m = CREATE_TABLE_PATTERN.matcher(sql.trim());
                if (m.matches()) {
                    database.createTableIfMissing(m.group(1));
                    return 0;
                }
                throw new IllegalArgumentException("unsupported DDL via Statement: " + sql);
            }
            if ("executeQuery".equals(name)) {
                throw new IllegalArgumentException("use prepareStatement for SELECT");
            }
            if ("execute".equals(name)) {
                checkOpen.run();
                String sql = (String) args[0];
                if (sql.trim().toUpperCase().startsWith("SELECT")) {
                    return true;
                }
                Matcher m = CREATE_TABLE_PATTERN.matcher(sql.trim());
                if (m.matches()) {
                    database.createTableIfMissing(m.group(1));
                    return false;
                }
                throw new IllegalArgumentException("unsupported: " + sql);
            }
            if ("toString".equals(name)) {
                return "Statement@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // PreparedStatement：CREATE TABLE
    // =================================================================

    static final class CreateTableStatement implements InvocationHandler {
        private final InMemoryDatabase database;
        private final String table;
        private final CheckOpen checkOpen;
        private boolean closed = false;

        CreateTableStatement(InMemoryDatabase db, String table, CheckOpen checkOpen) {
            this.database = db;
            this.table = table;
            this.checkOpen = checkOpen;
        }

        PreparedStatement asProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                this);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("executeUpdate".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                database.createTableIfMissing(table);
                return 0;
            }
            if ("execute".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                database.createTableIfMissing(table);
                return false;
            }
            if ("setString".equals(name) || "setInt".equals(name) || "setLong".equals(name)
                || "setDouble".equals(name) || "setBoolean".equals(name)
                || "setObject".equals(name) || "setNull".equals(name)
                || "setBytes".equals(name)) {
                return null;
            }
            if ("addBatch".equals(name) || "clearBatch".equals(name)) {
                return null;
            }
            if ("executeBatch".equals(name)) {
                return new int[0];
            }
            if ("executeQuery".equals(name)) {
                throw new RuntimeException(new SQLException("CREATE TABLE cannot return ResultSet"));
            }
            if ("toString".equals(name)) {
                return "CreateTable(" + table + ")@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // PreparedStatement：SELECT k, v
    // =================================================================

    static final class SelectKvStatement implements InvocationHandler {
        private final InMemoryDatabase database;
        private final String table;
        private final CheckOpen checkOpen;
        private String storeNameParam;
        private boolean closed = false;

        SelectKvStatement(InMemoryDatabase db, String table, CheckOpen checkOpen) {
            this.database = db;
            this.table = table;
            this.checkOpen = checkOpen;
        }

        PreparedStatement asProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                this);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("setString".equals(name)) {
                int idx = (Integer) args[0];
                String val = (String) args[1];
                if (idx == 1) {
                    storeNameParam = val;
                }
                return null;
            }
            if ("executeQuery".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                if (storeNameParam == null) {
                    throw new RuntimeException(new SQLException("setString(1, ?) not called"));
                }
                List<InMemoryDatabase.Row> rows = database.select(table, storeNameParam);
                return ResultSetProxy.create(rows);
            }
            if ("execute".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                return true;
            }
            if ("setInt".equals(name) || "setLong".equals(name) || "setDouble".equals(name)
                || "setBoolean".equals(name) || "setObject".equals(name)
                || "setNull".equals(name) || "setBytes".equals(name)) {
                return null;
            }
            if ("addBatch".equals(name) || "clearBatch".equals(name)) {
                return null;
            }
            if ("executeBatch".equals(name)) {
                return new int[0];
            }
            if ("executeUpdate".equals(name)) {
                throw new RuntimeException(new SQLException("SELECT cannot executeUpdate"));
            }
            if ("toString".equals(name)) {
                return "SelectKv@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // PreparedStatement：SELECT version
    // =================================================================

    static final class SelectVersionStatement implements InvocationHandler {
        private final InMemoryDatabase database;
        private final String table;
        private final CheckOpen checkOpen;
        private String storeNameParam;
        private String keyParam;
        private boolean closed = false;

        SelectVersionStatement(InMemoryDatabase db, String table, CheckOpen checkOpen) {
            this.database = db;
            this.table = table;
            this.checkOpen = checkOpen;
        }

        PreparedStatement asProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                this);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("setString".equals(name)) {
                int idx = (Integer) args[0];
                String val = (String) args[1];
                if (idx == 1) {
                    storeNameParam = val;
                } else if (idx == 2) {
                    keyParam = val;
                }
                return null;
            }
            if ("executeQuery".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                // 同時支援兩種 SQL 形式：
                // production 使用 `k = '_version'` literal（不呼叫 setString(2,...)），
                // 早期測試可能使用 `k = ?` 並 setString(2, "_version")。
                // 若 keyParam 未設定（literal 形式），預設為 "_version"。
                String key = keyParam != null ? keyParam : "_version";
                String v = database.selectValue(table, storeNameParam, key);
                return ResultSetProxy.createSingle(v);
            }
            if ("execute".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                return true;
            }
            if ("setInt".equals(name) || "setLong".equals(name) || "setDouble".equals(name)
                || "setBoolean".equals(name) || "setObject".equals(name)
                || "setNull".equals(name) || "setBytes".equals(name)) {
                return null;
            }
            if ("addBatch".equals(name) || "clearBatch".equals(name)) {
                return null;
            }
            if ("executeBatch".equals(name)) {
                return new int[0];
            }
            if ("executeUpdate".equals(name)) {
                throw new RuntimeException(new SQLException("SELECT cannot executeUpdate"));
            }
            if ("toString".equals(name)) {
                return "SelectVersion@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // PreparedStatement：DELETE all
    // =================================================================

    static final class DeleteAllStatement implements InvocationHandler {
        private final InMemoryDatabase database;
        private final String table;
        private final InMemoryTransaction tx;
        private final CheckOpen checkOpen;
        private String storeNameParam;
        private boolean closed = false;

        DeleteAllStatement(InMemoryDatabase db, String table, InMemoryTransaction tx,
                          CheckOpen checkOpen) {
            this.database = db;
            this.table = table;
            this.tx = tx;
            this.checkOpen = checkOpen;
        }

        PreparedStatement asProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                this);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("setString".equals(name)) {
                if ((Integer) args[0] == 1) {
                    storeNameParam = (String) args[1];
                }
                return null;
            }
            if ("executeUpdate".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                tx.recordDelete(table, storeNameParam);
                return 0;
            }
            if ("execute".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                tx.recordDelete(table, storeNameParam);
                return false;
            }
            if ("setInt".equals(name) || "setLong".equals(name) || "setDouble".equals(name)
                || "setBoolean".equals(name) || "setObject".equals(name)
                || "setNull".equals(name) || "setBytes".equals(name)) {
                return null;
            }
            if ("addBatch".equals(name) || "clearBatch".equals(name)) {
                return null;
            }
            if ("executeBatch".equals(name)) {
                return new int[0];
            }
            if ("executeQuery".equals(name)) {
                throw new RuntimeException(new SQLException("DELETE cannot return ResultSet"));
            }
            if ("toString".equals(name)) {
                return "DeleteAll@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // PreparedStatement：INSERT
    // =================================================================

    static final class InsertKvStatement implements InvocationHandler {
        private final InMemoryDatabase database;
        private final String table;
        private final InMemoryTransaction tx;
        private final CheckOpen checkOpen;
        private String storeNameParam;
        private String kParam;
        private String vParam;
        private boolean closed = false;

        InsertKvStatement(InMemoryDatabase db, String table, InMemoryTransaction tx,
                          CheckOpen checkOpen) {
            this.database = db;
            this.table = table;
            this.tx = tx;
            this.checkOpen = checkOpen;
        }

        PreparedStatement asProxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                this);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("setString".equals(name)) {
                int idx = (Integer) args[0];
                String val = (String) args[1];
                if (idx == 1) {
                    storeNameParam = val;
                } else if (idx == 2) {
                    kParam = val;
                } else if (idx == 3) {
                    vParam = val;
                }
                return null;
            }
            if ("addBatch".equals(name)) {
                tx.recordInsert(table, storeNameParam, kParam, vParam);
                return null;
            }
            if ("executeBatch".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                int[] results = new int[] { 1 };
                return results;
            }
            if ("clearBatch".equals(name)) {
                return null;
            }
            if ("executeUpdate".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                tx.recordInsert(table, storeNameParam, kParam, vParam);
                return 1;
            }
            if ("execute".equals(name)) {
                try {
                    checkOpen.run();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                tx.recordInsert(table, storeNameParam, kParam, vParam);
                return false;
            }
            if ("setInt".equals(name) || "setLong".equals(name) || "setDouble".equals(name)
                || "setBoolean".equals(name) || "setObject".equals(name)
                || "setNull".equals(name) || "setBytes".equals(name)) {
                return null;
            }
            if ("executeQuery".equals(name)) {
                throw new RuntimeException(new SQLException("INSERT cannot return ResultSet"));
            }
            if ("toString".equals(name)) {
                return "InsertKv@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    // =================================================================
    // ResultSet proxy
    // =================================================================

    static final class ResultSetProxy implements InvocationHandler {
        private final List<InMemoryDatabase.Row> rows;
        private int cursor = -1;
        private boolean closed = false;

        private ResultSetProxy(List<InMemoryDatabase.Row> rows) {
            this.rows = rows;
        }

        static ResultSet create(List<InMemoryDatabase.Row> rows) {
            return (ResultSet) Proxy.newProxyInstance(
                ResultSetProxy.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                new ResultSetProxy(rows));
        }

        static ResultSet createSingle(String value) {
            List<InMemoryDatabase.Row> list = new ArrayList<>();
            if (value != null) {
                list.add(new InMemoryDatabase.Row(null, null, value));
            }
            return create(list);
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            if ("next".equals(name)) {
                cursor++;
                return cursor < rows.size();
            }
            if ("getString".equals(name)) {
                int col = args.length == 1 ? labelToIndex((String) args[0]) : (Integer) args[0];
                if (cursor < 0 || cursor >= rows.size()) {
                    throw new RuntimeException(new SQLException("cursor out of range"));
                }
                if (col == 1) {
                    return rows.get(cursor).k;
                }
                if (col == 2) {
                    return rows.get(cursor).v;
                }
                throw new RuntimeException(new SQLException("unknown column: " + col));
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("wasNull".equals(name)) {
                return false;
            }
            if ("toString".equals(name)) {
                return "ResultSet@" + System.identityHashCode(proxyObj);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxyObj);
            }
            if ("equals".equals(name)) {
                return proxyObj == args[0];
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }

        private static int labelToIndex(String label) {
            if (label == null) {
                return -1;
            }
            String s = label.toLowerCase();
            if (s.equals("k") || s.equals("1")) {
                return 1;
            }
            if (s.equals("v") || s.equals("2")) {
                return 2;
            }
            return -1;
        }
    }
}