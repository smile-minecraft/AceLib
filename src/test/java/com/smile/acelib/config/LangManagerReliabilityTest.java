package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link LangManager} 可靠性測試。
 *
 * <p>守住三條規則：</p>
 * <ul>
 *   <li>缺鍵 warning 要去重，同一次載入週期內同一個 key 只記一次，reload 後才會再記；</li>
 *   <li>語言檔不存在／解析損壞要有負向快取，且兩者可用不同錯誤代碼區分；</li>
 *   <li>reload 失敗時保留 current、currentLocale 與 per-locale 快取，
 *       不能因為清快取的時機太早而讓失敗的 reload 順手清掉可用狀態。</li>
 * </ul>
 */
@DisplayName("LangManager 可靠性（缺鍵節流、負向快取、reload 一致性）")
class LangManagerReliabilityTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File langDir;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Path write(String fileName, String content) throws IOException {
        Path path = langDir.toPath().resolve(fileName);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    // -----------------------------------------------------------------
    // 缺鍵 warning 去重
    // -----------------------------------------------------------------

    @Test
    @DisplayName("get：同一個缺鍵重複查詢只記一次 warning")
    void get_repeatedMissingKey_warnsOnce() throws IOException {
        write("en_US.yml", "present: 'yes'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            for (int i = 0; i < 5; i++) {
                assertTrue(mgr.get("absent.key").isEmpty());
            }
            assertEquals(1, logs.countAt(Level.WARNING, "absent.key"),
                "同一個缺鍵在一次載入週期內只應記一次，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("ACELIB-LANG-001"),
                "缺鍵仍需帶錯誤代碼，實際記錄：" + logs.messages());
        }
    }

    @Test
    @DisplayName("reload：缺鍵去重狀態失效，同一個 key 會再記一次")
    void reload_rearmsMissingKeyWarning() throws IOException {
        write("en_US.yml", "present: 'yes'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            mgr.get("absent.key");
            mgr.get("absent.key");
            assertEquals(1, logs.countAt(Level.WARNING, "absent.key"));

            mgr.reload();

            mgr.get("absent.key");
            assertEquals(2, logs.countAt(Level.WARNING, "absent.key"),
                "reload 後磁碟內容可能改變，去重狀態必須失效並重新記錄，實際記錄："
                    + logs.messages());
        }
    }

    @Test
    @DisplayName("load：切換 locale 後各 locale 的缺鍵分別記錄一次")
    void get_distinctLocales_eachWarnOnce() throws IOException {
        write("en_US.yml", "present: 'yes'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load(Locale.US);

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            mgr.get("absent.key");
            mgr.get("absent.key");
            mgr.load(Locale.TRADITIONAL_CHINESE);
            mgr.get("absent.key");
            mgr.get("absent.key");

            assertEquals(2, logs.countAt(Level.WARNING, "absent.key"),
                "切換 locale 等於新的載入週期，兩個 locale 各記一次，實際記錄："
                    + logs.messages());
        }
    }

    // -----------------------------------------------------------------
    // 負向快取：不存在
    // -----------------------------------------------------------------

    @Test
    @DisplayName("get(Locale,String)：locale 檔不存在 → 負向快取，reload 後才失效")
    void get_missingLocaleFile_negativeCachedUntilReload() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        assertTrue(mgr.get(Locale.FRENCH, "x").isEmpty(), "前置：fr.yml 不存在");

        // 沒有 reload 就新增檔案：負向快取期間不應讀到新檔
        write("fr.yml", "x: 'nouveau'\n");
        assertTrue(mgr.get(Locale.FRENCH, "x").isEmpty(),
            "負向快取生效期間不得回頭讀檔，否則每次查詢都要 stat 一次");

        mgr.reload();
        assertEquals("nouveau", mgr.get(Locale.FRENCH, "x").orElse(null),
            "reload 後負向快取必須失效並讀到新檔");
    }

    @Test
    @DisplayName("get(Locale,String)：locale 檔不存在只記一次 LANG-003")
    void get_missingLocaleFile_logsLang003Once() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            for (int i = 0; i < 5; i++) {
                mgr.get(Locale.FRENCH, "x");
            }
            assertEquals(1, logs.countAt(Level.WARNING, "ACELIB-LANG-003"),
                "不存在的語言檔只記一次，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("fr.yml"),
                "記錄需指出缺少的檔名，實際記錄：" + logs.messages());
        }
    }

    // -----------------------------------------------------------------
    // 負向快取：解析損壞（要能跟「不存在」區分）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("get(Locale,String)：檔案存在但損壞 → 記 LANG-002 而非 LANG-003")
    void get_corruptLocaleFile_logsLang002_notLang003() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        write("fr.yml", "x: [unclosed\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            Optional<String> result = mgr.get(Locale.FRENCH, "x");

            assertTrue(result.isEmpty(), "損壞的 locale 檔讀不到 key，應退回 default 後仍為空");
            assertTrue(logs.hasMessageContaining("ACELIB-LANG-002"),
                "解析損壞必須記錄 LANG-002，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("fr.yml"),
                "記錄需指出損壞的檔名，實際記錄：" + logs.messages());
            assertFalse(logs.hasMessageContaining("ACELIB-LANG-003"),
                "檔案存在但損壞不得被記成「不存在」，實際記錄：" + logs.messages());
        }
    }

    @Test
    @DisplayName("get(Locale,String)：損壞的 locale 檔負向快取，修好並 reload 後可讀取")
    void get_corruptLocaleFile_recoveredAfterReload() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        write("fr.yml", "x: [unclosed\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        assertTrue(mgr.get(Locale.FRENCH, "x").isEmpty(), "前置：檔案損壞讀不到");

        write("fr.yml", "x: 'repaire'\n");
        assertTrue(mgr.get(Locale.FRENCH, "x").isEmpty(),
            "損壞期間同樣負向快取，不反覆解析壞檔");

        mgr.reload();
        assertEquals("repaire", mgr.get(Locale.FRENCH, "x").orElse(null),
            "修好並 reload 後必須讀得到");
    }

    // -----------------------------------------------------------------
    // reload 失敗要保留狀態
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：當前 locale 檔損壞 → 保留 current 與 per-locale 快取")
    void reload_failure_preservesCurrentAndLocaleCache() throws IOException {
        write("en_US.yml", "msg: 'v1'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("v1", mgr.get(Locale.US, "msg").orElse(null), "前置：先讓快取有內容");

        write("en_US.yml", "msg: [unclosed\n");

        assertThrows(ConfigException.class, mgr::reload,
            "reload 對損壞檔案維持既有例外語意（不靜靜回傳）");

        assertEquals(Locale.US, mgr.getCurrentLocale(), "失敗的 reload 不得改動 currentLocale");
        assertEquals("v1", mgr.get("msg").orElse(null),
            "失敗的 reload 必須保留全域 current 的舊訊息");
        assertEquals("v1", mgr.get(Locale.US, "msg").orElse(null),
            "失敗的 reload 必須保留 per-locale 快取，否則同一個壞檔會讓訊息全部變空");
        assertTrue(mgr.isReady(), "失敗的 reload 不得把 ready 狀態清掉");
    }

    // -----------------------------------------------------------------
    // 寫入失敗可診斷
    // -----------------------------------------------------------------

    @Test
    @DisplayName("load：語言檔目錄不可寫 → 拋 LANG-002、訊息可診斷、不留孤兒 tmp")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void load_generationFailure_isDiagnosable_andLeavesNoOrphanTemp() throws IOException {
        // 刪掉預設語言檔，讓 load 走「生成空檔」分支
        Files.deleteIfExists(langDir.toPath().resolve("en_US.yml"));

        Files.setPosixFilePermissions(langDir.toPath(), EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            LangManager mgr = new LangManager(plugin, Locale.US);
            ConfigException ex = assertThrows(ConfigException.class, mgr::load);
            assertEquals("ACELIB-LANG-002", ex.getCode(),
                "生成語言檔失敗的錯誤代碼必須可查，實際：" + ex.getCode());
            assertTrue(ex.getMessage().contains("en_US.yml"),
                "訊息需指出寫不動的檔名，實際：" + ex.getMessage());
        } finally {
            Files.setPosixFilePermissions(langDir.toPath(), EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        }

        assertEquals(0, countTempFiles(langDir.toPath()),
            "寫入失敗不得留孤兒 .tmp 檔");
        assertFalse(Files.exists(langDir.toPath().resolve("en_US.yml")),
            "寫入失敗不得留下半個檔案");
    }

    private static long countTempFiles(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".tmp"))
                .count();
        }
    }
}