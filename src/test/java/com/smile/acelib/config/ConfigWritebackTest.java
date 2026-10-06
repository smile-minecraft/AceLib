package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
 * 保註解寫回 Red 測試。
 *
 * <p>鎖住：寫回保留既有註解與排版、缺 key 補預設與說明、替換失敗保護、
 * POSIX 權限邊界實測。</p>
 */
@DisplayName("ConfigManager 寫回保留註解（補缺 key／權限邊界）")
class ConfigWritebackTest {

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
    @DisplayName("load 回寫保留管理員註解與既有排版")
    void writeback_keepsExistingComments() throws Exception {
        Path file = dataFolder.toPath().resolve("comments-keep.yml");
        String original = "# 管理員的說明：問候語\n"
            + "version: '1.0'  # 版本請勿手動改\n"
            + "greeting: 'custom-hi'  # 行尾註解\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "comments-keep.yml", schema, currentVersion);
        mgr.load();

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("# 管理員的說明：問候語"), "檔案頭註解必須保留，實際：\n" + after);
        assertTrue(after.contains("# 行尾註解"), "行尾註解必須保留，實際：\n" + after);
        assertTrue(after.contains("# 版本請勿手動改"), "version 行尾註解必須保留，實際：\n" + after);
        assertTrue(after.contains("greeting: 'custom-hi'"), "既有值不得被改寫，實際：\n" + after);
        // 語意仍正確
        assertEquals("custom-hi",
            YamlConfiguration.loadConfiguration(file.toFile()).getString("greeting"));
    }

    @Test
    @DisplayName("缺 key 補上預設值與欄位說明；已存在的 key 不重複追加")
    void writeback_appendsMissingKeysWithDescriptions() throws Exception {
        Path file = dataFolder.toPath().resolve("comments-fill.yml");
        Files.writeString(file,
            "# 只有問候語的舊檔\nversion: '1.0'\ngreeting: 'hi'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "comments-fill.yml", schema, currentVersion);
        mgr.setFieldDescription("maxPlayers", "同時在線人數上限");
        mgr.load();

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("同時在線人數上限"), "補上的 key 必須帶欄位說明，實際：\n" + after);
        assertEquals(10,
            YamlConfiguration.loadConfiguration(file.toFile()).getInt("maxPlayers"));

        // 第二次 load 不得重複追加同一段
        mgr.load();
        String twice = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(countOccurrences(twice, "maxPlayers:"), 1,
            "重複 load 不得重複追加 key，實際：\n" + twice);
    }

    @Test
    @DisplayName("巢狀缺 key 補在既有父節點下，不產生重複父節點")
    void writeback_appendsNestedKeyUnderExistingParent() throws Exception {
        ConfigSchema nestedSchema = new ConfigSchema(
            new ConfigVersion(1, 0),
            List.of(new FieldSpec("server.port", 25565, false)));
        Path file = dataFolder.toPath().resolve("comments-nested.yml");
        Files.writeString(file,
            "version: '1.0'\nserver:\n  # 主機位置\n  host: 'play.example'\n",
            StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "comments-nested.yml", nestedSchema, currentVersion);
        mgr.setFieldDescription("server.port", "伺服器埠號");
        mgr.load();

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("# 主機位置"), "父節點下的既有註解必須保留，實際：\n" + after);
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("play.example", onDisk.getString("server.host"));
        assertEquals(25565, onDisk.getInt("server.port"));
        assertEquals(1, countOccurrences(after, "server:"),
            "不得產生重複的父節點，實際：\n" + after);
    }

    @Test
    @DisplayName("save 改值保留同行註解")
    void writeback_saveChangedValue_keepsInlineComment() throws Exception {
        Path file = dataFolder.toPath().resolve("comments-save.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'before'  # 問候語說明\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "comments-save.yml", schema, currentVersion);
        mgr.load();
        mgr.set("greeting", "after");
        mgr.save();

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("# 問候語說明"), "改值不得吃掉同行註解，實際：\n" + after);
        assertEquals("after",
            YamlConfiguration.loadConfiguration(file.toFile()).getString("greeting"));
    }

    @Test
    @DisplayName("寫入中途失敗 → 原檔逐位元不變、不留孤兒 temp")
    void writeback_writeFailure_keepsFileBitIdentical() throws Exception {
        Path dir = dataFolder.toPath().resolve("wblock");
        Files.createDirectory(dir);
        Path target = dir.resolve("wblock.yml");
        ConfigManager mgr = new ConfigManager(plugin, "wblock/wblock.yml", schema, currentVersion);
        mgr.load();
        String before = Files.readString(target, StandardCharsets.UTF_8);

        // 把目標換成非空目錄：任何取代都會失敗
        Files.delete(target);
        Files.createDirectory(target);
        Files.writeString(target.resolve("occupied"), "x", StandardCharsets.UTF_8);

        mgr.set("greeting", "changed");
        ConfigException ex = assertThrows(ConfigException.class, mgr::save);
        assertEquals("ACELIB-CFG-001", ex.getCode());
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".tmp"))
                .count());
        }
    }

    @Test
    @DisplayName("POSIX 權限：原子替換後保留目標檔原有權限（實測）")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeback_preservesPosixPermissions() throws Exception {
        Path file = dataFolder.toPath().resolve("perms-keep.yml");
        Files.writeString(file, "version: '1.0'\ngreeting: 'hi'\n", StandardCharsets.UTF_8);
        // temp 檔預設是 600；原檔設 644，還原機制沒生效就會被洗成 600
        java.util.Set<PosixFilePermission> before = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(file, before);

        ConfigManager mgr = new ConfigManager(plugin, "perms-keep.yml", schema, currentVersion);
        mgr.load();
        mgr.set("greeting", "changed");
        mgr.save();

        assertEquals(before, Files.getPosixFilePermissions(file),
            "原子替換不得把 644 權限洗成 temp 檔預設的 600");
        assertEquals("changed",
            YamlConfiguration.loadConfiguration(file.toFile()).getString("greeting"));
    }

    @Test
    @DisplayName("set null 後 save 真的移除 key（檔案與記憶體一致）")
    void setNull_thenSave_removesKey() throws Exception {
        Path file = dataFolder.toPath().resolve("delete-key.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'hi'\nextra: 'keepme'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "delete-key.yml", schema, currentVersion);
        mgr.load();
        mgr.set("extra", null);
        mgr.save();

        assertNull(mgr.get("extra"), "set null 後記憶體內必須取不到");
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(!onDisk.contains("extra"), "save 後磁碟不得再有該 key");
        assertTrue(!Files.readString(file, StandardCharsets.UTF_8).contains("extra"),
            "合併寫回必須真的刪行，實際：\n" + Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("migration 移除的舊 key 不得留在磁碟")
    void migrationRemovedKey_doesNotStayOnDisk() throws Exception {
        Path file = dataFolder.toPath().resolve("delete-migrated.yml");
        Files.writeString(file,
            "version: '0.9'\ngreeting: 'hi'\noldKey: 'legacy'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "delete-migrated.yml", schema, currentVersion);
        mgr.registerMigration(new ConfigMigration() {
            @Override public ConfigVersion fromVersion() { return new ConfigVersion(0, 9); }
            @Override public ConfigVersion toVersion() { return new ConfigVersion(1, 0); }
            @Override public void migrate(YamlConfiguration old, YamlConfiguration next) {
                next.set("greeting", old.getString("greeting", "hello"));
                next.set("oldKey", null);
            }
        });
        mgr.load();

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(!onDisk.contains("oldKey"), "migration 刪除的 key 不得留在磁碟");
        assertEquals("hi", mgr.get("greeting"));
    }

    @Test
    @DisplayName("多行字串值可正確寫回讀回（語意不失）")
    void multilineValue_roundTrips() throws Exception {
        Path file = dataFolder.toPath().resolve("multiline.yml");
        Files.writeString(file,
            "version: '1.0'\ngreeting: 'hi'\n", StandardCharsets.UTF_8);

        ConfigManager mgr = new ConfigManager(plugin, "multiline.yml", schema, currentVersion);
        mgr.load();
        mgr.set("greeting", "line1\nline2\nline3");
        mgr.save();

        assertEquals("line1\nline2\nline3", mgr.get("greeting"));
        assertEquals("line1\nline2\nline3",
            YamlConfiguration.loadConfiguration(file.toFile()).getString("greeting"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
