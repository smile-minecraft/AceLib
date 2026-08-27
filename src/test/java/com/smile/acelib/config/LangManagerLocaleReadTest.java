package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link LangManager#get(Locale, String)} 的 locale 讀取與 default fallback 測試。
 *
 * <p>對應 Task3 核心交付之一：LangManager locale read。重點驗證：
 * <ul>
 *   <li>請求 locale 檔缺失時，退回 default locale 檔取 key；</li>
 *   <li>請求 locale 檔存在但 key 缺失時，退回 default locale 檔取 key；</li>
 *   <li>兩者皆缺失時回傳 {@link Optional#empty()}；</li>
 *   <li>{@code get(Locale, String)} 不寫入全域 current / currentLocale state；</li>
 *   <li>reload 後快取失效，新值即時生效。</li>
 * </ul>
 */
@DisplayName("LangManager locale read")
class LangManagerLocaleReadTest {

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

    private void write(String fileName, String content) throws IOException {
        try (FileWriter w = new FileWriter(new File(langDir, fileName))) {
            w.write(content);
        }
    }

    @Test
    @DisplayName("get(Locale,String)：請求 locale 檔缺失 → 退回 default locale 取 key")
    void missingLocaleFile_fallsBackToDefault() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        // 不寫 fr_FR.yml
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        Optional<String> result = mgr.get(Locale.FRENCH, "hello");
        assertTrue(result.isPresent(), "請求 locale 檔缺失應退回 default locale");
        assertEquals("world", result.get());
    }

    @Test
    @DisplayName("get(Locale,String)：請求 locale 檔存在但 key 缺失 → 退回 default locale 取 key")
    void localeFileExistsButKeyMissing_fallsBackToDefault() throws IOException {
        write("en_US.yml", "shared: 'en-value'\n");
        write("zh_TW.yml", "other: 'tw-value'\n"); // 不含 shared
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        Optional<String> result = mgr.get(Locale.TRADITIONAL_CHINESE, "shared");
        assertTrue(result.isPresent(), "請求 locale key 缺失應退回 default locale key");
        assertEquals("en-value", result.get());
    }

    @Test
    @DisplayName("get(Locale,String)：請求與 default 皆缺失 key → Optional.empty()")
    void bothMissing_returnsEmpty() throws IOException {
        write("en_US.yml", "present: 'yes'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        Optional<String> result = mgr.get(Locale.TRADITIONAL_CHINESE, "absent");
        assertNotNull(result, "get() 永遠不應回傳 null");
        assertFalse(result.isPresent(), "兩邊都缺 key 必須回傳 empty");
    }

    @Test
    @DisplayName("get(Locale,String)：不寫入全域 current / currentLocale state")
    void localeRead_doesNotMutateGlobalState() throws IOException {
        write("en_US.yml", "hello: 'world'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals(Locale.US, mgr.getCurrentLocale(), "load 後 currentLocale 應為 default");
        // 讀取一個不存在的 locale 不應改變全域 currentLocale
        mgr.get(Locale.FRENCH, "hello");
        assertEquals(Locale.US, mgr.getCurrentLocale(),
            "get(Locale,String) 不得改寫全域 currentLocale");
    }

    @Test
    @DisplayName("get(Locale,String)：reload 後快取失效，新值即時生效")
    void reload_invalidatesCache() throws IOException {
        write("en_US.yml", "hello: 'v1'\n");
        LangManager mgr = new LangManager(plugin, Locale.US);
        mgr.load();
        assertEquals("v1", mgr.get(Locale.US, "hello").orElse(null));
        // 模擬管理員修改檔案
        write("en_US.yml", "hello: 'v2'\n");
        mgr.reload();
        assertEquals("v2", mgr.get(Locale.US, "hello").orElse(null),
            "reload 後 get(Locale,String) 必須反映新值（快取失效）");
    }
}
