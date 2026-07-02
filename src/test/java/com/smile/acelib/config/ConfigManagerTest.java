package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
 * {@link ConfigManager} 測試（18 個）。
 *
 * <p>對應 Plan §九 Phase 4 驗收標準 1~6：
 * <ul>
 *   <li>預設檔生成</li>
 *   <li>版本過舊觸發遷移</li>
 *   <li>缺失欄位補齊</li>
 *   <li>reload 失敗保留舊值</li>
 *   <li>必填驗證</li>
 *   <li>錯誤代碼 ACELIB-CFG-001 ~ 005</li>
 * </ul>
 */
@DisplayName("ConfigManager")
class ConfigManagerTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private ConfigVersion currentVersion;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        dataFolder = plugin.getDataFolder();
        // 確保資料夾存在
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
        currentVersion = new ConfigVersion(1, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 構造子驗證
    // -----------------------------------------------------------------

    @Test
    @DisplayName("構造子：null plugin 拋 NPE")
    void constructor_nullPlugin_throws() {
        assertThrows(NullPointerException.class,
            () -> new ConfigManager(null, "config.yml", schema, currentVersion));
    }

    @Test
    @DisplayName("構造子：null schema 拋 NPE")
    void constructor_nullSchema_throws() {
        assertThrows(NullPointerException.class,
            () -> new ConfigManager(plugin, "config.yml", null, currentVersion));
    }

    @Test
    @DisplayName("構造子：null version 拋 NPE")
    void constructor_nullVersion_throws() {
        assertThrows(NullPointerException.class,
            () -> new ConfigManager(plugin, "config.yml", schema, null));
    }

    @Test
    @DisplayName("構造子：null fileName 拋 NPE；空字串拋 IAE")
    void constructor_invalidFileName_throws() {
        assertThrows(NullPointerException.class,
            () -> new ConfigManager(plugin, null, schema, currentVersion));
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigManager(plugin, "", schema, currentVersion));
        assertThrows(IllegalArgumentException.class,
            () -> new ConfigManager(plugin, "   ", schema, currentVersion));
    }

    // -----------------------------------------------------------------
    // load 行為
    // -----------------------------------------------------------------

    @Test
    @DisplayName("load：首次啟動無檔案時自動生成預設檔")
    void load_generatesDefaultWhenMissing() {
        File configFile = new File(dataFolder, "config.yml");
        assertFalse(configFile.exists(), "前置：檔案應不存在");
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        assertDoesNotThrow(() -> mgr.load());
        assertTrue(configFile.exists(), "load 後必須自動建立 config.yml");
        // 預設值應寫入
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(configFile);
        assertEquals("hello", onDisk.getString("greeting"));
        assertEquals(10, onDisk.getInt("maxPlayers"));
        assertTrue(mgr.isReady());
    }

    @Test
    @DisplayName("load：載入既有檔案，schema 內必填欄位缺失時補齊")
    void load_fillsMissingRequiredFields() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        // 既有檔案只有 greeting，但 maxPlayers 缺
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '1.0'\ngreeting: 'custom-hi'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        assertEquals("custom-hi", mgr.get("greeting"), "既有值必須保留");
        assertEquals(10, mgr.get("maxPlayers"), "缺失欄位必須被補齊為 default");
        // 補齊後應回寫磁碟
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(configFile);
        assertEquals(10, onDisk.getInt("maxPlayers"));
    }

    @Test
    @DisplayName("load：版本過舊時自動觸發 migration chain")
    void load_triggersMigrationWhenOutdated() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '0.9'\noldKey: 'old-value'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, new ConfigVersion(1, 0));
        mgr.registerMigration(new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return new ConfigVersion(0, 9); }
            @Override public ConfigVersion toVersion() { return new ConfigVersion(1, 0); }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                next.set("greeting", "migrated");
                next.set("version", "1.0");
            }
        });
        mgr.load();
        assertEquals("migrated", mgr.get("greeting"));
        // 遷移後版本應為 1.0
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(configFile);
        assertEquals("1.0", onDisk.getString("version"));
    }

    @Test
    @DisplayName("load：版本已是當前版本時不觸發 migration")
    void load_doesNotMigrateWhenCurrent() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '1.0'\ngreeting: 'as-is'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        boolean[] called = {false};
        mgr.registerMigration(new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return new ConfigVersion(1, 0); }
            @Override public ConfigVersion toVersion() { return new ConfigVersion(1, 0); }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                called[0] = true;
            }
        });
        mgr.load();
        assertFalse(called[0], "已是當前版本不應觸發 migration");
        assertEquals("as-is", mgr.get("greeting"));
    }

    @Test
    @DisplayName("load：版本過舊且無可用 migration 拋 ACELIB-CFG-004")
    void load_missingMigrationThrowsCfg004() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '0.5'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        ConfigException ex = assertThrows(ConfigException.class, mgr::load);
        assertEquals("ACELIB-CFG-004", ex.getCode());
    }

    @Test
    @DisplayName("load：dataFolder 不存在時自動建立子目錄")
    void load_createsDataFolderWhenMissing() {
        // 把既有 dataFolder 移走模擬「全新 plugin」情境
        File nestedDataFolder = new File(dataFolder, "sub/deeper");
        // 重設 plugin 取得新 dataFolder 不切實際；改驗證：在 dataFolder 內
        // load 寫入時會自動建立 parent 目錄
        File configFile = new File(dataFolder, "nested/config.yml");
        //noinspection ResultOfMethodCallIgnored
        configFile.getParentFile().delete();
        ConfigManager mgr = new ConfigManager(plugin, "nested/config.yml", schema, currentVersion);
        assertDoesNotThrow(() -> mgr.load());
        assertTrue(configFile.exists(), "nested/config.yml 必須被建立");
    }

    // -----------------------------------------------------------------
    // get / set / save
    // -----------------------------------------------------------------

    @Test
    @DisplayName("get：取得已設定的值；不存在路徑回傳 null")
    void get_returnsValueOrNull() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        assertEquals("hello", mgr.get("greeting"));
        assertNull(mgr.get("nonExistentKey"));
        assertNull(mgr.get("deep.path.that.does.not.exist"));
    }

    @Test
    @DisplayName("get(null) 拋 NPE")
    void get_nullPath_throws() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        assertThrows(NullPointerException.class, () -> mgr.get(null));
    }

    @Test
    @DisplayName("set：寫入值且可由 get 取得；save 後落盤")
    void set_thenGet_thenSave() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        mgr.set("greeting", "world");
        assertEquals("world", mgr.get("greeting"));
        mgr.save();
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(configFile);
        assertEquals("world", onDisk.getString("greeting"));
    }

    @Test
    @DisplayName("set：load 前呼叫拋 IllegalStateException")
    void set_beforeLoad_throws() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        assertThrows(IllegalStateException.class, () -> mgr.set("greeting", "x"));
    }

    @Test
    @DisplayName("save：load 前呼叫拋 IllegalStateException")
    void save_beforeLoad_throws() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        assertThrows(IllegalStateException.class, mgr::save);
    }

    // -----------------------------------------------------------------
    // reload
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：成功時新值生效")
    void reload_success_picksUpNewValue() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '1.0'\ngreeting: 'first'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        assertEquals("first", mgr.get("greeting"));
        // 模擬管理員手動修改檔案
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '1.0'\ngreeting: 'second'\n");
        }
        assertTrue(mgr.reload());
        assertEquals("second", mgr.get("greeting"));
    }

    @Test
    @DisplayName("reload：失敗時保留舊設定（檔案不存在或損壞）")
    void reload_failure_keepsOldValue() throws IOException {
        File configFile = new File(dataFolder, "config.yml");
        try (FileWriter w = new FileWriter(configFile)) {
            w.write("version: '1.0'\ngreeting: 'original'\n");
        }
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        mgr.load();
        assertEquals("original", mgr.get("greeting"));
        // 模擬檔案損壞（刪除檔案 → reload 找不到 → 失敗）
        //noinspection ResultOfMethodCallIgnored
        configFile.delete();
        boolean reloadResult = mgr.reload();
        // reload 失敗時應回傳 false，且 current 仍是原始值
        assertFalse(reloadResult, "reload 失敗必須回傳 false");
        assertEquals("original", mgr.get("greeting"), "舊值必須保留");
    }

    // -----------------------------------------------------------------
    // 必填驗證
    // -----------------------------------------------------------------

    @Test
    @DisplayName("validate：必填欄位缺失時拋 ACELIB-CFG-005")
    void validate_requiredMissingThrowsCfg005() {
        // schema 必填 greeting，但檔案只有 maxPlayers
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("maxPlayers", 5);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> mgr.validate(cfg));
        assertEquals("ACELIB-CFG-005", ex.getCode());
    }

    @Test
    @DisplayName("validate：必填欄位齊全時靜默通過")
    void validate_allPresent_passes() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("greeting", "ok");
        assertDoesNotThrow(() -> mgr.validate(cfg));
    }

    // -----------------------------------------------------------------
    // 註冊與狀態
    // -----------------------------------------------------------------

    @Test
    @DisplayName("registerMigration：鏈式呼叫回傳自身，可連續註冊多個")
    void registerMigration_returnsSelf() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        ConfigMigration m = new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return new ConfigVersion(1, 0); }
            @Override public ConfigVersion toVersion() { return new ConfigVersion(1, 1); }
            @Override public void migrate(YamlConfiguration o, YamlConfiguration n) {}
        };
        assertSame(mgr, mgr.registerMigration(m), "鏈式 API 應回傳自身");
    }

    @Test
    @DisplayName("isReady：load 前 false，load 後 true")
    void isReady_reflectsLoadState() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        assertFalse(mgr.isReady(), "load 前 isReady=false");
        mgr.load();
        assertTrue(mgr.isReady(), "load 後 isReady=true");
    }

    @Test
    @DisplayName("getCurrentVersion / getSchema：回傳建構時傳入的物件")
    void accessors_returnConstructorArgs() {
        ConfigManager mgr = new ConfigManager(plugin, "config.yml", schema, currentVersion);
        assertSame(currentVersion, mgr.getCurrentVersion());
        assertSame(schema, mgr.getSchema());
    }

    // -----------------------------------------------------------------
    // 工具：建立空檔案（測試輔助）
    // -----------------------------------------------------------------

    @SuppressWarnings("unused")
    private static void touch(Path p) throws IOException {
        if (!Files.exists(p)) {
            Files.createFile(p);
        }
    }
}