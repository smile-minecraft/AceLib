package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.diagnostics.ErrorCategory;
import com.smile.acelib.diagnostics.ErrorCodeRegistry;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 型別綁定 Red 測試。
 *
 * <p>鎖住路線圖缺口：「schema 只檢查必填欄位在不在」。
 * record／一般類別綁定在載入時驗證型別、範圍、列舉，錯誤帶完整欄位路徑。</p>
 */
@DisplayName("ConfigBinder record／類別綁定（型別／範圍／列舉＋完整路徑）")
class ConfigBinderTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private final ConfigVersion currentVersion = new ConfigVersion(1, 0);

    /** 綁定目標 record：含重新命名、範圍、列舉與巢狀。 */
    public record ServerSettings(
        @ConfigBinder.ConfigKey("server.host") String host,
        @ConfigBinder.ConfigKey("server.port") @ConfigBinder.ConfigRange(min = 1, max = 65535) int port,
        @ConfigBinder.ConfigKey("server.mode") Mode mode,
        Limits limits) {
    }

    public record Limits(
        @ConfigBinder.ConfigKey("limits.maxPlayers") @ConfigBinder.ConfigRange(min = 1, max = 1000) int maxPlayers) {
    }

    public enum Mode {
        SURVIVAL, CREATIVE
    }

    /** 綁定目標一般類別：無參建構＋欄位注入。 */
    public static class PlainSettings {
        @ConfigBinder.ConfigKey("greeting")
        public String greeting;
        @ConfigBinder.ConfigKey("maxPlayers")
        @ConfigBinder.ConfigRange(min = 1, max = 100)
        public int maxPlayers;
    }

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
            List.of(new FieldSpec("greeting", "hello", true))
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ConfigSnapshot snapshotOf(String fileName, String yaml) throws Exception {
        Files.writeString(dataFolder.toPath().resolve(fileName), yaml, StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, fileName, schema, currentVersion);
        mgr.load();
        return mgr.snapshot();
    }

    @Test
    @DisplayName("正常綁定：record 含巢狀與列舉一次到位")
    void bind_validRecord_bindsAllFields() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-ok.yml",
            "version: '1.0'\ngreeting: 'hi'\n"
                + "server:\n  host: 'play.example'\n  port: 25565\n  mode: 'SURVIVAL'\n"
                + "limits:\n  maxPlayers: 20\n");

        ServerSettings settings = ConfigBinder.bind(snapshot, ServerSettings.class);

        assertEquals("play.example", settings.host());
        assertEquals(25565, settings.port());
        assertEquals(Mode.SURVIVAL, settings.mode());
        assertEquals(20, settings.limits().maxPlayers());
    }

    @Test
    @DisplayName("正常綁定：一般類別走無參建構＋欄位注入")
    void bind_validPlainClass_bindsFields() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-plain.yml",
            "version: '1.0'\ngreeting: 'yo'\nmaxPlayers: 5\n");

        PlainSettings settings = ConfigBinder.bind(snapshot, PlainSettings.class);

        assertEquals("yo", settings.greeting);
        assertEquals(5, settings.maxPlayers);
    }

    @Test
    @DisplayName("型別錯誤帶完整欄位路徑（CFG-007）")
    void bind_wrongType_reportsFullPath_withCfg007() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-type.yml",
            "version: '1.0'\ngreeting: 'hi'\n"
                + "server:\n  host: 'x'\n  port: 'not-a-number'\n  mode: 'SURVIVAL'\n"
                + "limits:\n  maxPlayers: 20\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, ServerSettings.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("server.port"),
            "錯誤必須帶完整欄位路徑，實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("範圍違規帶完整欄位路徑")
    void bind_outOfRange_reportsFullPath() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-range.yml",
            "version: '1.0'\ngreeting: 'hi'\n"
                + "server:\n  host: 'x'\n  port: 99999\n  mode: 'SURVIVAL'\n"
                + "limits:\n  maxPlayers: 20\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, ServerSettings.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("server.port"),
            "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("列舉值非法帶完整欄位路徑與合法選項")
    void bind_badEnum_reportsFullPathAndAllowedValues() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-enum.yml",
            "version: '1.0'\ngreeting: 'hi'\n"
                + "server:\n  host: 'x'\n  port: 25565\n  mode: 'ADVENTURE'\n"
                + "limits:\n  maxPlayers: 20\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, ServerSettings.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("server.mode"), "實際：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("SURVIVAL"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("巢狀路徑錯誤帶完整路徑（limits.maxPlayers 越界）")
    void bind_nestedRangeViolation_reportsNestedPath() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-nested.yml",
            "version: '1.0'\ngreeting: 'hi'\n"
                + "server:\n  host: 'x'\n  port: 25565\n  mode: 'SURVIVAL'\n"
                + "limits:\n  maxPlayers: 5000\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, ServerSettings.class));

        assertTrue(ex.getMessage().contains("limits.maxPlayers"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("ACELIB-CFG-007 已登記於 ErrorCodeRegistry（CONFIG 分類）")
    void cfg007_isRegistered() {
        assertNotNull(ErrorCodeRegistry.lookup("ACELIB-CFG-007"));
        assertEquals(ErrorCategory.CONFIG,
            ErrorCodeRegistry.categorize("ACELIB-CFG-007"));
    }

    @Test
    @DisplayName("ConfigManager.bind 直接綁定當前快照")
    void manager_bind_delegatesToCurrentSnapshot() throws Exception {
        Files.writeString(dataFolder.toPath().resolve("bind-manager.yml"),
            "version: '1.0'\ngreeting: 'hey'\nmaxPlayers: 9\n", StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, "bind-manager.yml", schema, currentVersion);
        mgr.load();

        PlainSettings settings = mgr.bind(PlainSettings.class);

        assertEquals("hey", settings.greeting);
        assertEquals(9, settings.maxPlayers);
    }

    /** 超出 int 範圍的綁定目標。 */
    public record IntHolder(
        @ConfigBinder.ConfigKey("maxPlayers") int maxPlayers) {
    }

    /** 小數 long 綁定目標。 */
    public record LongHolder(
        @ConfigBinder.ConfigKey("maxPlayers") long maxPlayers) {
    }

    /** 清單綁定目標（含 null 元素語意）。 */
    public record ListHolder(
        @ConfigBinder.ConfigKey("lst") List<String> lst) {
    }

    @Test
    @DisplayName("清單 null 元素綁定保留（不 NPE、不跳過）")
    void bind_listWithNullElement_keepsNull() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("lst", java.util.Arrays.asList("a", null, "b"));
        ConfigSnapshot snapshot = new ConfigSnapshot(values);

        ListHolder holder = ConfigBinder.bind(snapshot, ListHolder.class);

        assertEquals(java.util.Arrays.asList("a", null, "b"), holder.lst());
    }

    @Test
    @DisplayName("超出 int 範圍的數值綁定失敗（CFG-007），不靜默溢位")
    void bind_intOverflow_reportsCfg007() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-overflow.yml",
            "version: '1.0'\ngreeting: 'hi'\nmaxPlayers: 9999999999\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, IntHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("maxPlayers"), "實際：" + ex.getMessage());
    }

    /** double 無範圍綁定目標。 */
    public record DoubleHolder(
        @ConfigBinder.ConfigKey("ratio") double ratio) {
    }

    /** double 有範圍綁定目標（含端點）。 */
    public record RangedDoubleHolder(
        @ConfigBinder.ConfigKey("ratio") @ConfigBinder.ConfigRange(min = 0, max = 1) double ratio) {
    }

    @Test
    @DisplayName("有範圍的 double 拒絕 NaN（CFG-007），訊息帶路徑與範圍")
    void bind_rangedDoubleNaN_rejectedWithPathAndRange() throws Exception {
        ConfigSnapshot snapshot = snapshotOf("bind-double-nan.yml",
            "version: '1.0'\ngreeting: 'hi'\nratio: .nan\n");

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, RangedDoubleHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("ratio"), "實際：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("[0.0, 1.0]"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("無範圍的 double 拒絕 NaN 與正負無限大")
    void bind_unrangedDoubleNonFinite_rejected() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("ratio", bad));

            ConfigBindingException ex = assertThrows(ConfigBindingException.class,
                () -> ConfigBinder.bind(snapshot, DoubleHolder.class),
                "應拒絕非有限值：" + bad);

            assertEquals("ACELIB-CFG-007", ex.getCode());
            assertTrue(ex.getMessage().contains("ratio"), "實際：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("有範圍的 double 拒絕正負無限大，訊息帶範圍")
    void bind_rangedDoubleInfinite_rejectedWithRange() {
        for (double bad : new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("ratio", bad));

            ConfigBindingException ex = assertThrows(ConfigBindingException.class,
                () -> ConfigBinder.bind(snapshot, RangedDoubleHolder.class),
                "應拒絕非有限值：" + bad);

            assertEquals("ACELIB-CFG-007", ex.getCode());
            assertTrue(ex.getMessage().contains("ratio"), "實際：" + ex.getMessage());
            assertTrue(ex.getMessage().contains("[0.0, 1.0]"), "實際：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("經 YAML 真實載入的 .nan 與 .inf 也拒絕")
    void bind_yamlNonFinite_rejected() throws Exception {
        ConfigSnapshot nanSnapshot = snapshotOf("bind-yaml-nan.yml",
            "version: '1.0'\ngreeting: 'hi'\nratio: .nan\n");
        assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(nanSnapshot, RangedDoubleHolder.class));

        ConfigSnapshot infSnapshot = snapshotOf("bind-yaml-inf.yml",
            "version: '1.0'\ngreeting: 'hi'\nratio: .inf\n");
        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(infSnapshot, RangedDoubleHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("ratio"), "實際：" + ex.getMessage());
    }

    @Test
    @DisplayName("有限值端點與界限外維持既有範圍語意")
    void bind_rangedDoubleFinite_keepsRangeSemantics() {
        assertEquals(0.0, ConfigBinder.bind(
            new ConfigSnapshot(Map.of("ratio", 0.0)), RangedDoubleHolder.class).ratio());
        assertEquals(1.0, ConfigBinder.bind(
            new ConfigSnapshot(Map.of("ratio", 1.0)), RangedDoubleHolder.class).ratio());
        assertEquals(0.5, ConfigBinder.bind(
            new ConfigSnapshot(Map.of("ratio", 0.5)), RangedDoubleHolder.class).ratio());

        assertThrows(ConfigBindingException.class, () -> ConfigBinder.bind(
            new ConfigSnapshot(Map.of("ratio", -0.1)), RangedDoubleHolder.class));
        assertThrows(ConfigBindingException.class, () -> ConfigBinder.bind(
            new ConfigSnapshot(Map.of("ratio", 1.1)), RangedDoubleHolder.class));
    }

    @Test
    @DisplayName("小數綁定到 long 失敗（CFG-007），不靜默截斷")
    void bind_fractionalLong_reportsCfg007() throws Exception {
        ConfigManager mgr = new ConfigManager(plugin, "bind-overflow.yml", schema, currentVersion);
        // 直接以記憶體快照測，不經檔案（值型別語意與來源無關）
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("maxPlayers", 1.5));

        ConfigBindingException ex = assertThrows(ConfigBindingException.class,
            () -> ConfigBinder.bind(snapshot, LongHolder.class));

        assertEquals("ACELIB-CFG-007", ex.getCode());
        assertTrue(ex.getMessage().contains("maxPlayers"), "實際：" + ex.getMessage());
    }
}
