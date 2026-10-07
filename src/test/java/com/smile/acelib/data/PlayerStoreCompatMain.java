package com.smile.acelib.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * 真實資料庫逐玩家儲存相容驗證入口（測試源碼，僅供
 * {@code scripts/jdbc-mysql-compat.sh} 呼叫；Gradle test 不執行此類別）。
 *
 * <p>用法：{@code java ... PlayerStoreCompatMain <freshUrl> <legacyUrl> <user>
 * <password> <label> <workDir>}；印出版本、案例結果與 {@code SHOW CREATE TABLE}，
 * 失敗案例數 {@code > 0} 時以非零結束碼離開。</p>
 *
 * <p>與 {@link JdbcCompatMain} 相同簽章慣例，故同一支腳本可用相同連線參數
 * 依次呼叫兩套案例集（通用 store 與逐玩家 store）。</p>
 */
final class PlayerStoreCompatMain {

    private PlayerStoreCompatMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: PlayerStoreCompatMain <freshUrl> <legacyUrl> <user>"
                + " <password> <label> <workDir>");
            System.exit(2);
        }
        String freshUrl = args[0];
        String legacyUrl = args[1];
        String user = args[2];
        String password = args[3];
        String label = args[4];
        Path workDir = Path.of(args[5]);
        Files.createDirectories(workDir);

        JdbcCompatSuite.DriverManagerDataSource fresh =
            new JdbcCompatSuite.DriverManagerDataSource(freshUrl, user, password);
        JdbcCompatSuite.DriverManagerDataSource legacy =
            new JdbcCompatSuite.DriverManagerDataSource(legacyUrl, user, password);

        try (Connection conn = fresh.getConnection()) {
            System.out.println("player-store engine=" + label
                + " product=" + conn.getMetaData().getDatabaseProductName()
                + " version=" + conn.getMetaData().getDatabaseProductVersion());
        }

        int failures = PlayerStoreCompatSuite.runAll(
            fresh, legacy, workDir, label, System.out);

        // 逐玩家表最終形狀（人工檢視：欄位、主鍵、字元集）
        System.out.println("--- SHOW CREATE TABLE " + PlayerStoreCompatSuite.TABLE
            + " (fresh db) ---");
        showCreateTable(fresh);

        System.out.println("player-store-compat-result label=" + label
            + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void showCreateTable(JdbcCompatSuite.DriverManagerDataSource ds)
            throws Exception {
        try (Connection conn = ds.getConnection();
                java.sql.Statement st = conn.createStatement();
                java.sql.ResultSet rs = st.executeQuery(
                    "SHOW CREATE TABLE " + PlayerStoreCompatSuite.TABLE)) {
            if (rs.next()) {
                System.out.println(rs.getString(2));
            }
        } catch (java.sql.SQLException ex) {
            // 表不存在（案例全數在建表前就失敗）時不中斷摘要輸出。
            System.out.println("(table not created: " + ex.getMessage() + ")");
        }
    }
}