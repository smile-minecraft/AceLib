package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link LangManager} 測試（12 個）。
 *
 * <p>對應 Plan §九 Phase 4「語言檔與訊息 key」「缺失訊息 key 回報」
 * 與 §5/§5 Phase 5 訊息系統對語言檔的依賴。
 * 錯誤代碼：
 * <ul>
 *   <li>{@code ACELIB-LANG-001}：訊息 key 缺失</li>
 *   <li>{@code ACELIB-LANG-002}：語言檔格式錯誤</li>
 * </ul>
 */
@DisplayName("LangManager")
class LangManagerTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private File langDir;

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
        langDir = new File(dataFolder, "lang");
        if (!langDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            langDir.mkdirs();
        }
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
            () -> new LangManager(null, Locale.US));
    }

    @Test
    @DisplayName("構造子：null locale 拋 NPE")
    void constructor_nullLocale_throws() {
        assertThrows(NullPointerException.class,
            () -> new LangManager(plugin, null));
    }

    // -----------------------------------------------------------------
    // load / reload / 語言檔存在性
    // -----------------------------------------------------------------

    @Test
    @DisplayName("load：lang/<locale>.yml 不存在時自動生成空檔")
    void load_generatesEmptyLangWhenMissing() {
        LangManager mgr = new LangManager(plugin, Locale.US);
        assertDoesNotThrow(() -> mgr.load());
        File enFile = new File(langDir, "en_US.yml");
        assertTrue(enFile.exists(), "首次啟動必須自動建立 lang/en_US.yml");
        assertTrue(mgr.isReady());
    }

    @Test
    @DisplayName("load：指定 locale 時切換到對應檔案")
    void load_resolvesLocaleFile() throws IOException {
        File twFile = new File(langDir, "zh_TW.yml");
        try (FileWriter w = new FileWriter(twFile)) {
            w.write("greeting: '你好 {player}'\n");
            w.write("farewell: '再見'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load(Locale.TRADITIONAL_CHINESE);
        assertEquals(Locale.TRADITIONAL_CHINESE, mgr.getCurrentLocale());
        Optional<String> greeting = mgr.get("greeting");
        assertTrue(greeting.isPresent());
        assertEquals("你好 {player}", greeting.get());
    }

    @Test
    @DisplayName("load：請求不存在 locale 時 fallback 到 default locale")
    void load_fallsBackToDefaultWhenMissing() throws IOException {
        // 只有 en_US.yml，沒有 zh_TW.yml
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'hello'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load(Locale.TRADITIONAL_CHINESE); // 請求 zh_TW → fallback 到 en_US
        assertEquals(Locale.US, mgr.getCurrentLocale(), "找不到請求 locale 時必須 fallback");
        assertEquals("hello", mgr.get("greeting").orElse(null));
    }

    @Test
    @DisplayName("get：取得已存在的 key")
    void get_returnsPresentKey() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("hello: 'world'\n");
            w.write("nested:\n  deep: 'value'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("world", mgr.get("hello").orElse(null));
        assertEquals("value", mgr.get("nested.deep").orElse(null));
    }

    @Test
    @DisplayName("get：key 缺失回傳 Optional.empty()，不中斷運行")
    void get_missingKey_returnsEmpty() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("present: 'yes'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        Optional<String> missing = mgr.get("does.not.exist");
        assertNotNull(missing, "get() 永遠不應回傳 null");
        assertTrue(missing.isEmpty(), "缺失 key 必須回傳 Optional.empty()");
    }

    @Test
    @DisplayName("get(key, vars)：替換 {var} 佔位符")
    void get_withVars_replacesPlaceholders() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("welcome: 'Hello {player}, welcome to {server}!'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        String rendered = mgr.get("welcome",
            Map.of("player", "smile", "server", "AceLib")).orElse(null);
        assertEquals("Hello smile, welcome to AceLib!", rendered);
    }

    @Test
    @DisplayName("get(key, vars)：缺失 vars 保留原佔位符字串，不丟例外")
    void get_withVars_missingVar_keepsPlaceholder() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'Hi {player}'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        // vars 沒有 player → 保留原 {player} 字串
        String rendered = mgr.get("greeting", Map.of()).orElse(null);
        assertEquals("Hi {player}", rendered);
    }

    @Test
    @DisplayName("get(key, null vars)：當 vars 為 null 時當作空 map，不丟 NPE")
    void get_nullVars_treatedAsEmpty() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'Hello {player}'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        String rendered = mgr.get("greeting", null).orElse(null);
        assertEquals("Hello {player}", rendered);
    }

    // -----------------------------------------------------------------
    // reload / 狀態
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：重新讀取檔案，新值生效")
    void reload_picksUpNewValue() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("msg: 'first'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("first", mgr.get("msg").orElse(null));
        // 模擬管理員修改檔案
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("msg: 'second'\n");
        }
        assertDoesNotThrow(() -> mgr.reload());
        assertEquals("second", mgr.get("msg").orElse(null));
    }

    @Test
    @DisplayName("reload(locale)：切換到指定 locale")
    void reload_withLocale_switchesLocale() throws IOException {
        File enFile = new File(langDir, "en_US.yml");
        File twFile = new File(langDir, "zh_TW.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'hi'\n");
        }
        try (FileWriter w = new FileWriter(twFile)) {
            w.write("greeting: '你好'\n");
        }
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("hi", mgr.get("greeting").orElse(null));
        mgr.reload(Locale.TRADITIONAL_CHINESE);
        assertEquals(Locale.TRADITIONAL_CHINESE, mgr.getCurrentLocale());
        assertEquals("你好", mgr.get("greeting").orElse(null));
    }

    @Test
    @DisplayName("isReady：load 前 false，load 後 true")
    void isReady_reflectsLoadState() {
        LangManager mgr = new LangManager(plugin, Locale.US);
        assertFalse(mgr.isReady());
        mgr.load();
        assertTrue(mgr.isReady());
    }

    @Test
    @DisplayName("getCurrentLocale / getDefaultLocale：分別回傳當前與建構時的 locale")
    void localeAccessors() {
        LangManager mgr = new LangManager(plugin, Locale.US);
        assertEquals(Locale.US, mgr.getDefaultLocale());
        assertEquals(Locale.US, mgr.getCurrentLocale(), "load 前 currentLocale == default");
    }
}