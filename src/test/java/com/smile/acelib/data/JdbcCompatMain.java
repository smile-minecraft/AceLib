package com.smile.acelib.data;

import java.sql.Connection;

/**
 * 真實資料庫相容驗證入口（測試源碼，僅供 {@code scripts/jdbc-mysql-compat.sh}
 * 呼叫；Gradle test 不執行此類別）。
 *
 * <p>用法：{@code java ... JdbcCompatMain <freshUrl> <legacyUrl> <user> <password>
 * <label>}；印出版本、案例結果與錯誤碼對照，失敗案例數 {@code > 0} 時以
 * 非零結束碼離開。</p>
 */
final class JdbcCompatMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println(
                "usage: JdbcCompatMain <freshUrl> <legacyUrl> <user> <password> <label>");
            System.exit(2);
        }
        String freshUrl = args[0];
        String legacyUrl = args[1];
        String user = args[2];
        String password = args[3];
        String label = args[4];

        JdbcCompatSuite.DriverManagerDataSource fresh =
            new JdbcCompatSuite.DriverManagerDataSource(freshUrl, user, password);
        JdbcCompatSuite.DriverManagerDataSource legacy =
            new JdbcCompatSuite.DriverManagerDataSource(legacyUrl, user, password);

        try (Connection conn = fresh.getConnection()) {
            System.out.println("server=" + label
                + " product=" + conn.getMetaData().getDatabaseProductName()
                + " version=" + conn.getMetaData().getDatabaseProductVersion());
        }
        try (Connection conn = legacy.getConnection()) {
            System.out.println("legacy-db charset check:");
            try (java.sql.Statement st = conn.createStatement();
                    java.sql.ResultSet rs = st.executeQuery(
                        "SELECT DEFAULT_CHARACTER_SET_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                            + "WHERE SCHEMA_NAME = DATABASE()")) {
                while (rs.next()) {
                    System.out.println("  legacy schema charset=" + rs.getString(1));
                }
            }
        }

        int failures = JdbcCompatSuite.runAll(fresh, legacy, label, System.out);
        System.out.println("compat-result label=" + label + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
