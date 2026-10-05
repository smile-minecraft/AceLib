package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link ConfigManager} 可靠性測試。
 *
 * <p>守住三條規則：</p>
 * <ul>
 *   <li>磁碟上的設定版本比當前版本新時，必須拒絕載入而不是把它降版覆寫；</li>
 *   <li>{@code reload()} 的失敗原因必須可由 logger 與錯誤代碼查到，
 *       且舊設定要留著，不是靜靜回傳 {@code false}；</li>
 *   <li>{@code reload()} 的版本與遷移行為要和 {@code load()} 一致；
 *       寫入走 temp + atomic move，失敗時保留舊檔。</li>
 * </ul>
 *
 * <p>每個測試用各自的設定檔名，避免共用 data folder 時互相干擾。</p>
 */
@DisplayName("ConfigManager 可靠性（拒絕降版、reload 可追蹤、原子寫入）")
class ConfigManagerReliabilityTest {

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

    // -----------------------------------------------------------------
    // 較新版本：拒絕而非降版覆寫
    // -----------------------------------------------------------------

    @Test
    @DisplayName("load：磁碟版本比當前版本新 → 拋 CFG-006 且檔案逐位元不變")
    void load_onDiskVersionNewer_rejectsWithCfg006_andKeepsFileIntact() throws IOException {
        File configFile = new File(dataFolder, "newer.yml");
        String original = "version: '2.0'\ngreeting: 'from-future'\nmaxPlayers: 42\n";
        Files.writeString(configFile.toPath(), original, StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "newer.yml", schema, currentVersion);
        ConfigException ex = assertThrows(ConfigException.class, mgr::load);

        assertEquals("ACELIB-CFG-006", ex.getCode(),
            "磁碟版本較新必須有專屬錯誤代碼，實際：" + ex.getCode());
        assertTrue(ex.getMessage().contains("2.0"),
            "訊息需指出磁碟上的版本，實際：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("1.0"),
            "訊息需指出當前版本，實際：" + ex.getMessage());
        assertFalse(mgr.isReady(), "拒絕載入後不得標記為 ready");
        assertEquals(original, Files.readString(configFile.toPath(), StandardCharsets.UTF_8),
            "較新版本的設定檔必須逐位元保持原樣，不可被降版覆寫");
    }

    @Test
    @DisplayName("load：拒絕較新版本後 save() 不會寫回磁碟")
    void load_rejectedThenSave_isRefused_andFileUntouched() throws IOException {
        File configFile = new File(dataFolder, "newer-save.yml");
        String original = "version: '2.0'\ngreeting: 'from-future'\n";
        Files.writeString(configFile.toPath(), original, StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "newer-save.yml", schema, currentVersion);
        assertThrows(ConfigException.class, mgr::load);

        // current 從未被賦值，save() 必須拒絕，而不是寫出一份降版檔案
        assertThrows(IllegalStateException.class, mgr::save,
            "未成功 load 前 save() 必須擋下，否則會覆蓋較新版本");
        assertEquals(original, Files.readString(configFile.toPath(), StandardCharsets.UTF_8),
            "save 被擋下後磁碟內容不得改變");
    }

    @Test
    @DisplayName("reload：磁碟版本比當前版本新 → 回傳 false、保留舊值、檔案不變")
    void reload_onDiskVersionNewer_returnsFalse_keepsOldValueAndFile() throws IOException {
        File configFile = new File(dataFolder, "reload-newer.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "reload-newer.yml", schema, currentVersion);
        mgr.load();
        assertEquals("running", mgr.get("greeting"));

        String newer = "version: '2.0'\ngreeting: 'from-future'\n";
        Files.writeString(configFile.toPath(), newer, StandardCharsets.UTF_8);

        assertFalse(mgr.reload(), "磁碟版本較新時 reload 必須失敗");
        assertEquals("running", mgr.get("greeting"), "reload 失敗必須保留舊設定");
        assertTrue(mgr.isReady(), "reload 失敗不得把已就緒狀態清掉");
        assertEquals(newer, Files.readString(configFile.toPath(), StandardCharsets.UTF_8),
            "reload 不得把較新版本改寫回舊版本");
    }

    // -----------------------------------------------------------------
    // reload 失敗可追蹤
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：檔案損壞 → 回傳 false、記錄 CFG-002、保留舊值")
    void reload_corruptFile_returnsFalse_logsCfg002_keepsOldValue() throws IOException {
        File configFile = new File(dataFolder, "reload-corrupt.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "reload-corrupt.yml", schema, currentVersion);
        mgr.load();

        Files.writeString(configFile.toPath(), "greeting: [unclosed\n", StandardCharsets.UTF_8);

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            assertFalse(mgr.reload(), "YAML 損壞時 reload 必須回傳 false");
            assertTrue(logs.hasMessageContaining("ACELIB-CFG-002"),
                "reload 失敗必須記錄錯誤代碼，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("reload-corrupt.yml"),
                "記錄需指出失敗的檔名，實際記錄：" + logs.messages());
            assertEquals(1, logs.countAt(Level.WARNING, "ACELIB-CFG-002"),
                "reload 失敗應以 WARNING 記錄一次（plugin 仍以舊設定繼續運行），實際記錄："
                    + logs.messages());
        }
        assertEquals("running", mgr.get("greeting"), "reload 失敗必須保留舊設定");
    }

    @Test
    @DisplayName("reload：檔案不存在 → 回傳 false、記錄 CFG-001、保留舊值")
    void reload_missingFile_returnsFalse_logsCfg001_keepsOldValue() throws IOException {
        File configFile = new File(dataFolder, "reload-missing.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "reload-missing.yml", schema, currentVersion);
        mgr.load();
        assertTrue(configFile.delete());

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            assertFalse(mgr.reload(), "檔案不存在時 reload 必須回傳 false");
            assertTrue(logs.hasMessageContaining("ACELIB-CFG-001"),
                "檔案不存在也要有錯誤代碼可查，實際記錄：" + logs.messages());
            assertTrue(logs.hasMessageContaining("reload-missing.yml"),
                "記錄需指出找不到的檔名，實際記錄：" + logs.messages());
        }
        assertEquals("running", mgr.get("greeting"), "reload 失敗必須保留舊設定");
    }

    // -----------------------------------------------------------------
    // load / reload 行為一致
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：與 load 一樣補齊欄位並執行 migration，版本寫回磁碟")
    void reload_appliesDefaultsAndMigration_likeLoad() throws IOException {
        File configFile = new File(dataFolder, "reload-migrate.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'first'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "reload-migrate.yml", schema, currentVersion);
        mgr.load();
        assertEquals("first", mgr.get("greeting"));

        mgr.registerMigration(new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return new ConfigVersion(0, 9); }
            @Override public ConfigVersion toVersion() { return new ConfigVersion(1, 0); }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                next.set("greeting", "migrated");
            }
        });

        // 管理員換成較舊版本的檔案，且少了 schema 宣告的欄位
        Files.writeString(configFile.toPath(),
            "version: '0.9'\nlegacyKey: 'old'\n", StandardCharsets.UTF_8);

        assertTrue(mgr.reload(), "可遷移的舊檔必須 reload 成功");
        assertEquals("migrated", mgr.get("greeting"), "reload 必須和 load 一樣執行 migration");
        assertEquals(10, mgr.get("maxPlayers"), "reload 必須和 load 一樣補齊 schema 缺欄");

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(configFile);
        assertEquals("1.0", onDisk.getString("version"), "遷移後版本必須寫回磁碟");
        assertEquals("migrated", onDisk.getString("greeting"), "遷移結果必須寫回磁碟");
    }

    @Test
    @DisplayName("reload：版本過舊且無可用 migration → 回傳 false、記錄 CFG-004、保留舊值")
    void reload_missingMigration_returnsFalse_logsCfg004_keepsOldValue() throws IOException {
        File configFile = new File(dataFolder, "reload-nomigrate.yml");
        Files.writeString(configFile.toPath(),
            "version: '1.0'\ngreeting: 'running'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "reload-nomigrate.yml", schema, currentVersion);
        mgr.load();

        // 0.5 沒有對應 migration（chain 從 1.0 開始）
        Files.writeString(configFile.toPath(),
            "version: '0.5'\nlegacyKey: 'old'\n", StandardCharsets.UTF_8);

        try (LogCapture logs = LogCapture.attachTo(plugin)) {
            assertFalse(mgr.reload(), "無法遷移時 reload 必須回傳 false");
            assertTrue(logs.hasMessageContaining("ACELIB-CFG-004"),
                "遷移失敗必須記錄 CFG-004，實際記錄：" + logs.messages());
        }
        assertEquals("running", mgr.get("greeting"), "reload 失敗必須保留舊設定");
        assertEquals("0.5", YamlConfiguration.loadConfiguration(configFile).getString("version"),
            "遷移失敗不得改動磁碟上的版本");
    }

    // -----------------------------------------------------------------
    // 原子寫入
    // -----------------------------------------------------------------

    @Test
    @DisplayName("save：目標檔唯讀仍可原子替換（replace 只需父目錄可寫）")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void save_replacesReadOnlyTargetFile_atomically() throws IOException {
        ConfigManager mgr = new ConfigManager(plugin, "atomic.yml", schema, currentVersion);
        mgr.load();
        mgr.set("greeting", "updated");

        Path target = new File(dataFolder, "atomic.yml").toPath();
        Files.setPosixFilePermissions(target, EnumSet.of(PosixFilePermission.OWNER_READ));
        try {
            assertDoesNotThrow(mgr::save,
                "temp+move 的替換語意只要求父目錄可寫，不該被目標檔唯讀擋下");
        } finally {
            Files.setPosixFilePermissions(target, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        assertEquals("updated",
            YamlConfiguration.loadConfiguration(target.toFile()).getString("greeting"),
            "原子替換後磁碟必須是新內容");
    }

    @Test
    @DisplayName("save：父目錄不可寫 → 失敗可診斷、保留舊檔、不留孤兒 tmp，修好後可重試")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void save_tempStageFailure_keepsOldFile_andRecovers() throws IOException {
        // 原子替換需要「父目錄可寫」：temp 檔要建在同目錄，move 也要改目錄項目。
        // 舊的 config.save(file) 只打開既有檔案，POSIX 下不需要目錄寫入權限，
        // 因此會在不該成功的地方靜靜覆寫；這裡要守住的是失敗時的行為。
        Path dir = dataFolder.toPath().resolve("locked");
        Files.createDirectory(dir);
        Path target = dir.resolve("locked.yml");
        Files.writeString(target,
            "version: '1.0'\ngreeting: 'keepme'\nmaxPlayers: 3\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "locked/locked.yml", schema, currentVersion);
        mgr.load();
        String before = Files.readString(target, StandardCharsets.UTF_8);

        // 父目錄可讀可進入但不可寫入 → temp 檔建不出來
        Files.setPosixFilePermissions(dir, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            mgr.set("greeting", "changed");
            ConfigException ex = assertThrows(ConfigException.class, mgr::save);
            assertEquals("ACELIB-CFG-001", ex.getCode(),
                "寫入失敗的錯誤代碼必須可查，實際：" + ex.getCode());
            assertTrue(ex.getMessage().contains("locked.yml"),
                "訊息需指出寫不動的檔名，實際：" + ex.getMessage());
        } finally {
            Files.setPosixFilePermissions(dir, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        }

        assertEquals(before, Files.readString(target, StandardCharsets.UTF_8),
            "寫入失敗必須保留舊檔，不得留下截斷內容");
        assertEquals(0, countTempFiles(dir), "temp 階段失敗不得留孤兒 .tmp");

        // 可恢復：權限修好後同一個 manager 仍能寫入
        mgr.save();
        assertEquals("changed",
            YamlConfiguration.loadConfiguration(target.toFile()).getString("greeting"),
            "失敗原因排除後應能正常寫入");
    }

    @Test
    @DisplayName("save：move 階段失敗 → CFG-001、清掉 temp 孤兒檔")
    void save_moveStageFailure_cleansOrphanTemp() throws IOException {
        Path dir = dataFolder.toPath().resolve("movefail");
        Files.createDirectory(dir);
        Path target = dir.resolve("movefail.yml");

        ConfigManager mgr = new ConfigManager(plugin, "movefail/movefail.yml", schema, currentVersion);
        mgr.load();
        assertTrue(Files.exists(target), "前置：load 應已建立設定檔");

        // 把目標路徑換成非空目錄 → move 必然失敗
        Files.delete(target);
        Files.createDirectory(target);
        Files.writeString(target.resolve("occupied"), "x", StandardCharsets.UTF_8);

        mgr.set("greeting", "changed");
        ConfigException ex = assertThrows(ConfigException.class, mgr::save);
        assertEquals("ACELIB-CFG-001", ex.getCode(),
            "move 失敗的錯誤代碼必須可查，實際：" + ex.getCode());
        assertEquals(0, countTempFiles(dir),
            "move 失敗必須清掉 temp 孤兒檔，否則 lang/ 與 data 夾會越積越多");
    }

    // -----------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------

    /**
     * 計算目錄下由原子寫入留下的 {@code .tmp} 孤兒檔數量。
     */
    private static long countTempFiles(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".tmp"))
                .count();
        }
    }
}