package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真實資料庫相容測試（環境閘門）。
 *
 * <p>僅在下列環境變數齊備時執行，否則略過（CI 不依賴 Docker）：</p>
 * <ul>
 *   <li>{@code ACELIB_JDBC_URL}：新形狀驗證庫（utf8mb4）的 JDBC URL</li>
 *   <li>{@code ACELIB_JDBC_LEGACY_URL}：舊資料驗證庫（latin1）的 JDBC URL</li>
 *   <li>{@code ACELIB_JDBC_USER} / {@code ACELIB_JDBC_PASSWORD}：帳號</li>
 *   <li>{@code ACELIB_JDBC_DRIVER}：driver 類別名（例如
 *       {@code com.mysql.cj.jdbc.Driver}）</li>
 *   <li>{@code ACELIB_JDBC_LABEL}：可選，日誌標籤</li>
 * </ul>
 *
 * <p>本機完整驗證（含容器起停與輸出存檔）走
 * {@code scripts/jdbc-mysql-compat.sh}，與此測試共用
 * {@link JdbcCompatSuite} 同一組案例。</p>
 */
@DisplayName("JdbcCompat（真 DB，需環境變數，否則略過）")
class JdbcCompatGatedTest {

    @Test
    @DisplayName("真實資料庫相容案例集")
    void realDatabaseCompat() {
        String url = System.getenv("ACELIB_JDBC_URL");
        String legacyUrl = System.getenv("ACELIB_JDBC_LEGACY_URL");
        String user = System.getenv("ACELIB_JDBC_USER");
        String password = System.getenv("ACELIB_JDBC_PASSWORD");
        String driver = System.getenv("ACELIB_JDBC_DRIVER");
        String label = System.getenv("ACELIB_JDBC_LABEL");
        assumeTrue(url != null && !url.isBlank()
                && legacyUrl != null && !legacyUrl.isBlank()
                && user != null && driver != null && !driver.isBlank(),
            "未設定 ACELIB_JDBC_URL/_LEGACY_URL/_USER/_DRIVER，略過真 DB 相容測試");
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ex) {
            abort("classpath 無 " + driver + "，略過真 DB 相容測試");
        }
        if (label == null || label.isBlank()) {
            label = url;
        }
        PrintStream log = System.out;
        int failures = JdbcCompatSuite.runAll(
            new JdbcCompatSuite.DriverManagerDataSource(url, user, password),
            new JdbcCompatSuite.DriverManagerDataSource(legacyUrl, user, password),
            label, log);
        assertEquals(0, failures, "真 DB 相容案例有 " + failures + " 個失敗");
    }
}
