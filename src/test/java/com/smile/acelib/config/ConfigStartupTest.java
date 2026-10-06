package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 啟動四分類 Red 測試。
 *
 * <p>鎖住路線圖缺口：「檔案不存在就建立預設檔，分不出首次安裝和用過之後被刪」。
 * 識別依據是 sidecar 安裝狀態（只在驗證成功後寫入），不是設定檔是否存在。</p>
 */
@DisplayName("ConfigManager 啟動分類（首次安裝／有效／損壞／使用後缺檔）")
class ConfigStartupTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private final ConfigVersion currentVersion = new ConfigVersion(1, 0);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
        schema = new ConfigSchema(
            new ConfigVersion(1, 0),
            List.of(
                new FieldSpec("greeting", "hello", true),
                new FieldSpec("maxPlayers", 10, false)
            )
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("全新目錄啟動 → FRESH_INSTALL，快照為預設值且可用")
    void startup_freshDirectory_reportsFreshInstall() {
        ConfigManager mgr = new ConfigManager(plugin, "fresh-start.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.FRESH_INSTALL, result.status());
        assertNotNull(result.snapshot(), "首次安裝也必須有可用快照（預設值）");
        assertEquals("hello", result.snapshot().getString("greeting", null));
        assertTrue(mgr.isReady());
    }

    @Test
    @DisplayName("有效設定啟動 → LOADED，快照與磁碟一致")
    void startup_validFile_reportsLoaded() throws Exception {
        Path file = dataFolder.toPath().resolve("valid-start.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'hi-there'\nmaxPlayers: 7\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "valid-start.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.LOADED, result.status());
        assertEquals("hi-there", result.snapshot().getString("greeting", null));
        assertEquals(7, result.snapshot().getInt("maxPlayers", 0));
    }

    @Test
    @DisplayName("損壞設定啟動 → CORRUPT，原檔逐位元不變且診斷帶 CFG-002")
    void startup_corruptFile_reportsCorrupt_andKeepsFileBitIdentical() throws Exception {
        Path file = dataFolder.toPath().resolve("corrupt-start.yml");
        byte[] before = "greeting: [unclosed\nversion: '1.0'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);

        ConfigManager mgr = new ConfigManager(plugin, "corrupt-start.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertTrue(result.detail().contains("ACELIB-CFG-002"),
            "損壞診斷必須帶錯誤代碼，實際：" + result.detail());
        assertArrayEquals(before, Files.readAllBytes(file), "損壞原檔必須逐位元不變");
        try (var entries = Files.list(dataFolder.toPath())) {
            assertEquals(0, entries
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".tmp"))
                .count(), "損壞啟動不得留孤兒 temp 檔");
        }
    }

    @Test
    @DisplayName("用過之後被刪 → MISSING_AFTER_USE（不是 FRESH_INSTALL），且會重建檔案")
    void startup_deletedAfterUse_reportsMissingAfterUse_notFreshInstall() throws Exception {
        // 第一輪：正常啟動，留下安裝狀態
        ConfigManager first = new ConfigManager(plugin, "gone-after-use.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.FRESH_INSTALL, first.startup().status());

        // 使用者用過之後把檔案刪掉
        assertTrue(new File(dataFolder, "gone-after-use.yml").delete());

        // 第二輪：同一個檔名再次啟動，必須認出「用過之後缺檔」
        ConfigManager second = new ConfigManager(plugin, "gone-after-use.yml", schema, currentVersion);
        StartupResult result = second.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertNotNull(result.snapshot(), "使用後缺檔仍須有可用快照（最後成功副本）");
        assertEquals("hello", result.snapshot().getString("greeting", null));
        assertTrue(new File(dataFolder, "gone-after-use.yml").exists(), "缺檔後應重建預設檔讓伺服器繼續跑");
    }

    @Test
    @DisplayName("損壞時保留最後驗證成功副本：先好後壞，快照仍是舊的好值")
    void startup_corruptAfterGood_keepsLastGoodSnapshot() throws Exception {
        Path file = dataFolder.toPath().resolve("good-then-bad.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'last-good-value'\n", StandardCharsets.UTF_8);

        ConfigManager first = new ConfigManager(plugin, "good-then-bad.yml", schema, currentVersion);
        assertEquals(StartupResult.Status.LOADED, first.startup().status());

        // 管理員把檔寫壞了
        byte[] broken = "greeting: [unclosed\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, broken);

        ConfigManager second = new ConfigManager(plugin, "good-then-bad.yml", schema, currentVersion);
        StartupResult result = second.startup();

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertNotNull(result.snapshot(), "有最後成功副本時損壞啟動仍須有快照");
        assertEquals("last-good-value", result.snapshot().getString("greeting", null));
        assertArrayEquals(broken, Files.readAllBytes(file), "損壞原檔必須逐位元不變");
    }

    @Test
    @DisplayName("損壞且無任何成功副本時，可用呼叫端指定的保守後備設定")
    void startup_corruptWithoutHistory_usesCallerFallback() throws Exception {
        Path file = dataFolder.toPath().resolve("corrupt-fallback.yml");
        Files.writeString(file, "greeting: [unclosed\n", StandardCharsets.UTF_8);

        YamlConfiguration fallback = new YamlConfiguration();
        fallback.set("greeting", "safe-mode");
        fallback.set("maxPlayers", 1);

        ConfigManager mgr = new ConfigManager(plugin, "corrupt-fallback.yml", schema, currentVersion);
        StartupResult result = mgr.startup(fallback);

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertNotNull(result.snapshot(), "有後備設定時必須有可用快照");
        assertEquals("safe-mode", result.snapshot().getString("greeting", null));
        assertEquals("greeting: [unclosed\n", Files.readString(file, StandardCharsets.UTF_8),
            "後備設定不得覆寫損壞原檔");
    }

    @Test
    @DisplayName("損壞、無副本、無後備 → 快照為 null 且診斷帶 CFG-003")
    void startup_corruptWithoutAnything_snapshotNull_withCfg003() throws Exception {
        Path file = dataFolder.toPath().resolve("corrupt-nothing.yml");
        Files.writeString(file, "greeting: [unclosed\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "corrupt-nothing.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertNull(result.snapshot(), "無副本又無後備時快照必須為 null（下游依此決定禁用操作）");
        assertTrue(result.detail().contains("ACELIB-CFG-003"),
            "無值可回退必須帶 CFG-003，實際：" + result.detail());
    }

    @Test
    @DisplayName("最後成功副本較新 → 拒絕還原（CFG-006）、副本不被覆寫、改用預設重建")
    void lastGoodNewerThanCurrent_refusesRestore_andKeepsLastGood() throws Exception {
        Path base = dataFolder.toPath().resolve("downgrade-restore.yml");
        // sidecar：曾經成功載入，且副本是較新版本
        Files.writeString(base.resolveSibling("downgrade-restore.yml.acelib-state"),
            "installed=true\n", StandardCharsets.UTF_8);
        String newerCopy = "version: '2.0'\ngreeting: 'from-future'\n";
        Files.writeString(base.resolveSibling("downgrade-restore.yml.last-good"),
            newerCopy, StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "downgrade-restore.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.MISSING_AFTER_USE, result.status());
        assertTrue(result.detail().contains("ACELIB-CFG-006"),
            "拒絕還原必須帶 CFG-006，實際：" + result.detail());
        assertEquals(newerCopy,
            Files.readString(base.resolveSibling("downgrade-restore.yml.last-good"),
                StandardCharsets.UTF_8),
            "較新副本不得被降版覆寫");
        assertEquals("1.0",
            YamlConfiguration.loadConfiguration(base.toFile()).getString("version"),
            "設定檔應以預設值重建");
    }
    @Test
    @DisplayName("較新版本啟動 → CORRUPT 且診斷帶 CFG-006，原檔不變")
    void startup_newerVersion_reportsCorrupt_withCfg006() throws Exception {
        Path file = dataFolder.toPath().resolve("newer-start.yml");
        String original = "version: '2.0'\ngreeting: 'from-future'\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "newer-start.yml", schema, currentVersion);
        StartupResult result = mgr.startup();

        assertEquals(StartupResult.Status.CORRUPT, result.status());
        assertTrue(result.detail().contains("ACELIB-CFG-006"), "實際：" + result.detail());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }
}
