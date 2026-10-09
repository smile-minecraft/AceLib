package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 快照數值嚴格取得、內容相等與世代的測試。
 */
@DisplayName("ConfigSnapshot 數值嚴格取得、相等與世代")
class ConfigSnapshotNumericsTest {

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
    @DisplayName("getInt 拒絕小數，訊息含完整路徑")
    void getInt_rejectsFractional_withPath() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("rate", 2.9));
        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> snapshot.getInt("rate", 0));
        assertTrue(ex.getMessage().contains("rate"), "訊息須含路徑，實際：" + ex.getMessage());
        assertEquals("ACELIB-CFG-007", ex.getCode());
    }

    @Test
    @DisplayName("getInt 拒絕溢位")
    void getInt_rejectsOverflow() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("big", 99999999999L));
        assertThrows(ConfigBindingException.class, () -> snapshot.getInt("big", 0));
    }

    @Test
    @DisplayName("getLong 合法值通過；小數與溢位拋錯；缺值與錯型別回預設")
    void getLong_semantics() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of(
            "ok", 42,
            "fraction", 2.5,
            "huge", 1e19,
            "text", "nope"));
        assertEquals(42L, snapshot.getLong("ok", -1L));
        assertThrows(ConfigBindingException.class, () -> snapshot.getLong("fraction", -1L));
        assertThrows(ConfigBindingException.class, () -> snapshot.getLong("huge", -1L));
        assertEquals(-1L, snapshot.getLong("missing", -1L));
        assertEquals(-1L, snapshot.getLong("text", -1L));
    }

    @Test
    @DisplayName("getDouble 合法值通過；非有限值拋錯；缺值與錯型別回預設")
    void getDouble_semantics() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ok", 2.5);
        values.put("nan", Double.NaN);
        values.put("inf", Double.POSITIVE_INFINITY);
        // YAML 真實路徑的等價值：SnakeYAML 解析整數 3 為 Integer
        values.put("whole", 3);
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        assertEquals(2.5, snapshot.getDouble("ok", -1.0));
        assertEquals(3.0, snapshot.getDouble("whole", -1.0));
        assertThrows(ConfigBindingException.class, () -> snapshot.getDouble("nan", -1.0));
        assertThrows(ConfigBindingException.class, () -> snapshot.getDouble("inf", -1.0));
        assertEquals(-1.0, snapshot.getDouble("missing", -1.0));
    }

    @Test
    @DisplayName("getDouble 錯型別回預設")
    void getDouble_wrongType_returnsDefault() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("text", "nope"));
        assertEquals(-1.0, snapshot.getDouble("text", -1.0));
    }

    @Test
    @DisplayName("getInt 缺值與非 Number 回預設")
    void getInt_missingAndWrongType_returnsDefault() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("text", "nope"));
        assertEquals(7, snapshot.getInt("missing", 7));
        assertEquals(7, snapshot.getInt("text", 7));
    }

    @Test
    @DisplayName("同內容兩實例相等且 hashCode 相同；世代不影響相等")
    void equals_sameContent_equal_despiteGeneration() {
        ConfigSnapshot first = new ConfigSnapshot(Map.of("a", 1), 1L);
        ConfigSnapshot second = new ConfigSnapshot(Map.of("a", 1), 2L);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("不同內容不相等；含 null 清單語意保留")
    void equals_differentContent_notEqual_nullListKept() {
        ConfigSnapshot first = new ConfigSnapshot(Map.of("a", 1));
        ConfigSnapshot second = new ConfigSnapshot(Map.of("a", 2));
        assertNotEquals(first, second);

        List<Object> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lst", withNull);
        ConfigSnapshot nullList = new ConfigSnapshot(values);
        ConfigSnapshot sameNullList = new ConfigSnapshot(values);
        assertEquals(nullList, sameNullList);
        assertEquals(nullList.hashCode(), sameNullList.hashCode());
        List<String> strings = nullList.getStringList("lst");
        assertEquals(2, strings.size());
        assertEquals(null, strings.get(1));
    }

    @Test
    @DisplayName("長整數端點合法；超出範圍拋錯；±2^63 相鄰 double 符合預期")
    void getLong_boundaries() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("min", Long.MIN_VALUE);
        values.put("max", Long.MAX_VALUE);
        values.put("negPow", -9223372036854775808.0);
        values.put("belowMin", Math.nextAfter(-9223372036854775808.0, Double.NEGATIVE_INFINITY));
        values.put("topBelow", Math.nextAfter(9223372036854775808.0, 0.0));
        values.put("atPow", 9223372036854775808.0);
        values.put("overMax", new java.math.BigDecimal("9223372036854775808"));
        values.put("wayBelowMin", new java.math.BigDecimal("-18446744073709551616"));
        ConfigSnapshot snapshot = new ConfigSnapshot(values);

        assertEquals(Long.MIN_VALUE, snapshot.getLong("min", 0L));
        assertEquals(Long.MAX_VALUE, snapshot.getLong("max", 0L));
        assertEquals(Long.MIN_VALUE, snapshot.getLong("negPow", 0L),
            "-2^63 可精確表示，即 Long.MIN_VALUE，應成功");
        assertEquals(9223372036854774784L, snapshot.getLong("topBelow", 0L),
            "2^63 下一個可表示值（2^63 - 1024）在範圍內，應成功");
        for (String bad : new String[]{"belowMin", "atPow", "overMax", "wayBelowMin"}) {
            ConfigBindingException ex = assertThrows(ConfigBindingException.class,
                () -> snapshot.getLong(bad, 0L), "應拋錯：" + bad);
            assertEquals("ACELIB-CFG-007", ex.getCode());
            assertTrue(ex.getMessage().contains(bad), "實際：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("連續成功發布世代 +1（含同內容）；失敗 reload 世代不變")
    void generation_incrementsOnSuccess_notOnFailure() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-gen.yml"),
            "version: '1.0'\ngreeting: 'first'\n", StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-gen.yml", schema, currentVersion);
        mgr.load();
        long first = mgr.snapshot().generation();

        Files.writeString(dataFolder.toPath().resolve("snap-gen.yml"),
            "version: '1.0'\ngreeting: 'first'\n", StandardCharsets.UTF_8);
        assertTrue(mgr.reload());
        assertEquals(first + 1, mgr.snapshot().generation(), "同內容成功 reload 世代也要 +1");

        Files.writeString(dataFolder.toPath().resolve("snap-gen.yml"),
            "greeting: [unclosed\n", StandardCharsets.UTF_8);
        assertTrue(!mgr.reload());
        assertEquals(first + 1, mgr.snapshot().generation(), "失敗 reload 不得發布新世代");
    }

    @Test
    @DisplayName("YAML .nan 經真實載入後 getDouble 拋錯")
    void yamlNan_getDouble_throws() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("snap-nan.yml"),
            "version: '1.0'\ngreeting: 'hi'\nrate: .nan\n", StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "snap-nan.yml", schema, currentVersion);
        mgr.load();
        assertThrows(ConfigBindingException.class,
            () -> mgr.snapshot().getDouble("rate", -1.0));
    }
}
