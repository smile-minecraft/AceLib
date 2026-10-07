package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真實資料庫逐玩家儲存相容測試（環境閘門）。
 *
 * <p>僅在 {@code ACELIB_PLAYER_STORE_URL}、{@code ACELIB_PLAYER_STORE_LEGACY_URL}、
 * {@code ACELIB_PLAYER_STORE_USER}、{@code ACELIB_PLAYER_STORE_PASSWORD}、
 * {@code ACELIB_PLAYER_STORE_DRIVER} 齊備時執行，否則略過（CI 不依賴 Docker）。
 * 密碼只在需要時提供（空值視為不檢查）。</p>
 *
 * <p>本機完整驗證（含容器起停與輸出存檔）走
 * {@code scripts/jdbc-mysql-compat.sh}，與此測試共用
 * {@link PlayerStoreCompatSuite} 同一組案例。</p>
 */
@DisplayName("PlayerStoreCompat（真 DB，需環境變數，否則略過）")
class PlayerStoreCompatGatedTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("真實資料庫逐玩家儲存案例集")
    void realDatabasePlayerStoreCompat() throws Exception {
        String url = System.getenv("ACELIB_PLAYER_STORE_URL");
        String legacyUrl = System.getenv("ACELIB_PLAYER_STORE_LEGACY_URL");
        String user = System.getenv("ACELIB_PLAYER_STORE_USER");
        String password = System.getenv("ACELIB_PLAYER_STORE_PASSWORD");
        String driver = System.getenv("ACELIB_PLAYER_STORE_DRIVER");
        String label = System.getenv("ACELIB_PLAYER_STORE_LABEL");
        assumeTrue(url != null && !url.isBlank()
                && legacyUrl != null && !legacyUrl.isBlank()
                && user != null && !user.isBlank() && driver != null && !driver.isBlank(),
            "未設定 ACELIB_PLAYER_STORE_URL/_LEGACY_URL/_USER/_DRIVER，略過真 DB 逐玩家相容測試");
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ex) {
            abort("classpath 無 " + driver + "，略過真 DB 逐玩家相容測試");
        }
        if (label == null || label.isBlank()) {
            label = url;
        }
        Files.createDirectories(tempDir);
        PrintStream log = System.out;
        int failures = PlayerStoreCompatSuite.runAll(
            new JdbcCompatSuite.DriverManagerDataSource(url, user, password),
            new JdbcCompatSuite.DriverManagerDataSource(legacyUrl, user, password),
            tempDir, label, log);
        assertEquals(0, failures, "真 DB 逐玩家案例有 " + failures + " 個失敗");
    }
}