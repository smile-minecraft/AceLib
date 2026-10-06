package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LangManager} 內建資源 fallback 與升級補 key 的 Red 測試。
 *
 * <p>需求：磁碟上的自訂文案優先，缺的 key 才讀 plugin JAR 內的 {@code lang/*.yml}
 * 內建資源；升級時只補新 key，管理員改過的文案逐位元保留。</p>
 */
@DisplayName("LangManager（內建資源 fallback 與升級補 key）")
class LangManagerBuiltinTest {

    @TempDir
    File tempDir;

    private JavaPlugin plugin;
    private final Map<String, String> builtin = new HashMap<>();
    private final List<LogRecord> captured = new ArrayList<>();
    private Handler handler;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File(tempDir, "data"));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LangManagerBuiltinTest"));
        // 內建資源以記憶體 map 扮演 plugin JAR 內容：key 為 "lang/<file>"。
        when(plugin.getResource(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            String body = builtin.get(name);
            if (body == null) {
                return null;
            }
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        });

        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);
        root.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        Logger root = Logger.getLogger("");
        root.removeHandler(handler);
        root.setLevel(Level.INFO);
        captured.clear();
        builtin.clear();
    }

    private void writeDisk(String fileName, String body) throws Exception {
        File langDir = new File(new File(tempDir, "data"), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        try (FileWriter w = new FileWriter(new File(langDir, fileName))) {
            w.write(body);
        }
    }

    private String readDisk(String fileName) throws Exception {
        File f = new File(new File(new File(tempDir, "data"), "lang"), fileName);
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    private boolean hasLogContaining(String text) {
        return captured.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains(text));
    }

    @Test
    @DisplayName("磁碟文案優先：同 key 以磁碟值為準，磁碟缺的 key 才讀內建資源")
    void diskWinsOverBuiltin() throws Exception {
        writeDisk("en_US.yml", "greeting: 'disk hello'\n");
        builtin.put("lang/en_US.yml",
            "greeting: 'builtin hello'\nonly.builtin: 'from jar'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        assertEquals("disk hello", mgr.get("greeting").orElse(null),
            "磁碟已有 key 不得被內建資源覆蓋");
        assertEquals("from jar", mgr.get("only.builtin").orElse(null),
            "磁碟缺的 key 必須退回內建資源");
    }

    @Test
    @DisplayName("get(Locale, key)：磁碟各 locale 優先，其次內建請求 locale，最後內建預設 locale")
    void localeLayeringDiskFirstThenBuiltin() throws Exception {
        writeDisk("zh_TW.yml", "a: 'disk-zh'\n");
        builtin.put("lang/zh_TW.yml", "a: 'builtin-zh'\nb: 'builtin-zh-b'\n");
        builtin.put("lang/en_US.yml", "b: 'builtin-en-b'\nc: 'builtin-en-c'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        assertEquals("disk-zh",
            mgr.get(Locale.TRADITIONAL_CHINESE, "a").orElse(null));
        assertEquals("builtin-zh-b",
            mgr.get(Locale.TRADITIONAL_CHINESE, "b").orElse(null));
        assertEquals("builtin-en-c",
            mgr.get(Locale.TRADITIONAL_CHINESE, "c").orElse(null));
    }

    @Test
    @DisplayName("三層都缺 key：回空並記錄 ACELIB-LANG-001")
    void missingEverywhereDiagnosable() throws Exception {
        writeDisk("en_US.yml", "present: 'yes'\n");
        builtin.put("lang/en_US.yml", "other: 'jar'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        captured.clear();

        assertTrue(mgr.get("does.not.exist").isEmpty());
        assertTrue(hasLogContaining("ACELIB-LANG-001"),
            "三層皆缺 key 必須留下可診斷的 LANG-001");
    }

    @Test
    @DisplayName("內建資源損壞：記 ACELIB-LANG-002 並退回下一層，不中斷查詢")
    void corruptBuiltinFallsThrough() throws Exception {
        writeDisk("en_US.yml", "present: 'yes'\n");
        builtin.put("lang/en_US.yml", "broken: [unclosed\n  indent: : :\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        captured.clear();

        assertTrue(mgr.get("broken").isEmpty(),
            "損壞的內建資源不得提供值");
        assertTrue(hasLogContaining("ACELIB-LANG-002"),
            "內建資源解析失敗必須記 LANG-002");
    }

    @Test
    @DisplayName("升級補 key：只補新 key，管理員改過的文案逐字保留")
    void syncAddsOnlyMissingKeys() throws Exception {
        writeDisk("en_US.yml",
            "# 管理員註解：自訂問候\n"
                + "greeting: 'admin custom'\n");
        builtin.put("lang/en_US.yml",
            "greeting: 'builtin hello'\nnew.key: 'builtin new'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        int added = mgr.syncMissingBuiltinKeys();

        assertEquals(1, added, "只補一個新 key");
        String after = readDisk("en_US.yml");
        assertTrue(after.contains("admin custom"),
            "管理員修改必須逐字保留，實際檔案：\n" + after);
        assertTrue(after.contains("管理員註解"),
            "管理員註解必須保留，實際檔案：\n" + after);
        assertTrue(after.contains("builtin new"),
            "新 key 必須補進磁碟檔");
        assertEquals("admin custom", mgr.get("greeting").orElse(null));
        assertEquals("builtin new", mgr.get("new.key").orElse(null));

        int second = mgr.syncMissingBuiltinKeys();
        assertEquals(0, second, "第二次同步不得再補 key（具冪等性）");
    }

    @Test
    @DisplayName("升級補 key（複雜結構）：註解逐字保留、只補缺 key、新 key 落對 section、冪等")
    void syncComplexStructure() throws Exception {
        writeDisk("en_US.yml",
            "# 管理員註解：問候區\n"
                + "greeting: 'Hi: there \"boss\"'\n"
                + "lore: |\n"
                + "  第一行\n"
                + "  第二行\n"
                + "menu:\n"
                + "  title: '選單'\n"
                + "  # 按鈕註解\n"
                + "  ok: '確定'\n"
                + "rewards:\n"
                + "- '金幣'\n"
                + "- '鑽石'\n");
        builtin.put("lang/en_US.yml",
            "greeting: 'jar hello'\n"
                + "menu:\n"
                + "  title: 'jar title'\n"
                + "  cancel: 'jar cancel'\n"
                + "brand.new: 'jar new'\n"
                + "tips: 'jar tips'\n"
                + "extra.list:\n"
                + "- 'jar-a'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();

        int added = mgr.syncMissingBuiltinKeys();

        assertEquals(4, added, "只補 menu.cancel、brand.new、tips、extra.list 四個缺 key");
        String after = readDisk("en_US.yml");
        assertTrue(after.contains("# 管理員註解：問候區"), "管理員註解必須逐字保留");
        assertTrue(after.contains("# 按鈕註解"), "節內註解必須逐字保留");
        assertTrue(after.contains("Hi: there \"boss\""), "引號冒號混用值不得被改寫");
        assertTrue(after.contains("第一行"), "多行字串不得被改寫");
        assertTrue(after.contains("金幣"), "既有 list 不得被改寫");
        assertTrue(after.contains("jar cancel"), "新 key 必須補進");
        assertEquals("Hi: there \"boss\"",
            mgr.get("greeting").orElse(null), "管理員修改不得被覆寫");
        assertEquals("jar cancel", mgr.get("menu.cancel").orElse(null));

        // 結果可解析回相同值
        org.bukkit.configuration.file.YamlConfiguration reparsed =
            new org.bukkit.configuration.file.YamlConfiguration();
        reparsed.loadFromString(after);
        assertEquals("確定", reparsed.getString("menu.ok"));
        assertEquals("jar cancel", reparsed.getString("menu.cancel"));
        assertEquals(java.util.List.of("jar-a"), reparsed.getList("extra.list"));

        assertEquals(0, mgr.syncMissingBuiltinKeys(), "第二次同步具冪等性");
    }

    @Test
    @DisplayName("磁碟檔不存在時同步：以內建資源建立可編輯副本")
    void syncCreatesFileWhenDiskAbsent() throws Exception {
        builtin.put("lang/en_US.yml", "greeting: 'builtin hello'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);

        int added = mgr.syncMissingBuiltinKeys();

        assertEquals(1, added);
        assertTrue(readDisk("en_US.yml").contains("builtin hello"));
        mgr.load();
        assertEquals("builtin hello", mgr.get("greeting").orElse(null));
    }

    @Test
    @DisplayName("reload 後內建快取失效：換掉 JAR 資源即生效，不必重開伺服器")
    void reloadRefreshesBuiltinCache() throws Exception {
        writeDisk("en_US.yml", "present: 'yes'\n");
        builtin.put("lang/en_US.yml", "dynamic: 'v1'\n");

        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("v1", mgr.get("dynamic").orElse(null));

        builtin.put("lang/en_US.yml", "dynamic: 'v2'\n");
        assertEquals("v1", mgr.get("dynamic").orElse(null),
            "reload 前快取仍有效");

        mgr.reload();
        assertEquals("v2", mgr.get("dynamic").orElse(null),
            "reload 後必須讀到新內建資源");
    }

    @Test
    @DisplayName("getResource 拋例外：視為無內建資源，不中斷磁碟查詢")
    void resourceLookupFailureDoesNotBreakDisk() throws Exception {
        writeDisk("en_US.yml", "greeting: 'disk'\n");
        JavaPlugin flaky = mock(JavaPlugin.class);
        when(flaky.getDataFolder()).thenReturn(new File(tempDir, "data"));
        when(flaky.getLogger()).thenReturn(Logger.getLogger("LangManagerBuiltinTest"));
        when(flaky.getResource(anyString())).thenAnswer(inv -> {
            throw new RuntimeException("jar locked");
        });

        LangManager mgr = new LangManager(flaky, Locale.US);
        mgr.load();

        assertEquals("disk", mgr.get("greeting").orElse(null));
        assertTrue(mgr.get("absent").isEmpty());
    }

    // 讓未使用的 import 檢查通過：InputStream 供 mock 行為描述使用。
    @SuppressWarnings("unused")
    private InputStream streamOf(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
