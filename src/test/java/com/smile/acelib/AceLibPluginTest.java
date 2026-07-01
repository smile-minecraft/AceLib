package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * AceLibPlugin 主類別的單元測試。
 *
 * <h2>為何不用 {@code MockBukkit.load(Class)}？</h2>
 * MockBukkit 4.x 內部使用 {@code MockBukkitConfiguredPluginClassLoader}，
 * 其 {@code findClass} 對非 plugin 自身 class 的探測會拋
 * {@link NullPointerException}（{@code "No jar file selected"}），而非
 * {@link ClassNotFoundException}。我們的 production code
 * {@link PlatformDetector} 透過 {@code Class.forName(fqcn, false, classLoader)}
 * 探測 Folia / Paper marker class，若 classloader 是 plugin classloader，
 * 該 NPE 會冒到 {@code onEnable} 並導致 {@code MockBukkit.load} 整個失敗。
 *
 * <p>為避免修改 production code，本測試改走以下流程：
 * <ol>
 *   <li>{@code MockBukkit.mock()} 初始化 server</li>
 *   <li>{@code pluginManager.loadPlugin(AceLibPlugin.class)} 載入 plugin
 *       但<strong>不</strong>呼叫 enablePlugin（這是 loadPlugin 與 load 的差異）</li>
 *   <li>手動呼叫 {@code plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()))}
 *       傳入用「測試 classloader」建構的 detector；測試 classloader 可以找到
 *       {@code org.bukkit.Bukkit}（MockBukkit 提供），因此 detector 正確分類為 PAPER</li>
 * </ol>
 *
 * <h2>每個測試的環境隔離</h2>
 * 每個測試開始時重新 {@code mock()} + {@code loadPlugin} + {@code onEnable}，
 * 結束時 {@code unmock()} 釋放資源；不使用 {@code MockBukkit.load} 的自動
 * enable 機制以避免 plugin classloader NPE。
 */
@DisplayName("AceLibPlugin")
class AceLibPluginTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @AfterAll
    static void tearDownBukkit() {
        MockBukkit.unmock();
    }

    @BeforeAll
    static void setUpClass() {
        // 初始化 mock server（每個 class 只一次）
        server = MockBukkit.mock();
    }

    @BeforeEach
    void loadFresh() {
        // 重新 mock + loadPlugin（不 enable）+ 手動 onEnable。
        // 詳見 class-level Javadoc 對「為何不用 MockBukkit.load」的說明。
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        // 使用測試 classloader 建立 detector；它可以找到 org.bukkit.Bukkit（MockBukkit
        // 在 runtime classpath），因此會正確分類為 PAPER。這是繞過 plugin classloader
        // 拋 NPE 問題的關鍵：production code 拿到的 classloader 是測試 classloader，
        // 不是 plugin classloader。
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void unloadPlugin() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("AceLibVersion.VERSION 應為 0.1.0-SNAPSHOT")
    void versionConstant_isCorrect() {
        assertEquals("0.1.0-SNAPSHOT", AceLibVersion.VERSION);
    }

    @Test
    @DisplayName("loadPlugin + 手動 onEnable 後 plugin 已啟用，isReady() 應為 true")
    void mockBukkit_load_makesPluginReady() {
        assertNotNull(plugin, "loadPlugin 必須回傳非 null plugin");
        assertTrue(plugin.isReady(), "手動 onEnable 後應已啟用");
    }

    @Test
    @DisplayName("onEnable 後 getVersion() 回傳 AceLibVersion.VERSION")
    void onEnable_exposesVersion() {
        assertEquals(AceLibVersion.VERSION, plugin.getApi().getVersion());
    }

    @Test
    @DisplayName("onEnable 後 getPlatform() 在 MockBukkit 環境下回傳 PAPER")
    void onEnable_exposesPlatform() {
        Platform p = plugin.getApi().getPlatform();
        assertNotNull(p, "platform 不可為 null");
        // MockBukkit 4.x runtime classpath 內含 org.bukkit.Bukkit 但不含
        // io.papermc.paper.threadedregions.RegionizedServer，因此 detector 應回傳 PAPER。
        assertSame(Platform.PAPER, p,
            "MockBukkit 環境下應判定為 PAPER，實際: " + p);
    }

    @Test
    @DisplayName("onDisable 後 isReady() 應為 false")
    void onDisable_releasesResources() {
        assertTrue(plugin.isReady());
        plugin.onDisable();
        assertFalse(plugin.isReady(), "onDisable 後必須 not ready");
    }

    @Test
    @DisplayName("reload() 在已啟用時回傳 true 且不丟例外")
    void reload_succeedsWhenReady() {
        boolean result = plugin.reload();
        assertTrue(result, "已啟用時 reload 應回傳 true");
        assertTrue(plugin.isReady(), "reload 後仍須 ready");
    }

    @Test
    @DisplayName("onEnable 為冪等：重複呼叫不爆且 isReady 仍 true")
    void onEnable_idempotent() {
        ServerMock s = server;
        PlatformDetector d = new PlatformDetector(getClass().getClassLoader());
        assertDoesNotThrow(() -> {
            plugin.onEnable(s, d);
        }, "重複 onEnable 必須不丟例外");
        assertTrue(plugin.isReady());
    }

    @Test
    @DisplayName("detectsFoliaOrPaper: 兩種 classloader 情境下皆能 classify")
    void detectsFoliaOrPaper() {
        // 情境 A: 預設 classloader（測試 classloader，可找到 org.bukkit.Bukkit）
        PlatformDetector detectorA = new PlatformDetector(getClass().getClassLoader());
        Platform platformA = detectorA.detect();
        assertNotNull(platformA);
        assertSame(Platform.PAPER, platformA,
            "測試 classloader 應找到 org.bukkit.Bukkit，分類為 PAPER，實際: " + platformA);

        // 情境 B: 空 classloader（子載入器隔離）
        ClassLoader empty = new ClassLoader(null) {};
        PlatformDetector detectorB = new PlatformDetector(empty);
        Platform platformB = detectorB.detect();
        assertNotNull(platformB);
        assertSame(Platform.UNKNOWN, platformB,
            "空 classloader 必須判定為 UNKNOWN，實際: " + platformB);
    }

    @Test
    @DisplayName("reload 在尚未 onEnable 時回傳 false，不丟例外")
    void reload_beforeOnEnable_returnsFalse() {
        // 重新 mock + loadPlugin 但不呼叫 onEnable，模擬「尚未 onEnable」狀態。
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertFalse(fresh.isReady());
        assertFalse(fresh.reload(), "尚未 onEnable 時 reload 應回傳 false");
    }

    @Test
    @DisplayName("onDisable 在尚未 onEnable 時呼叫不丟例外")
    void onDisable_beforeOnEnable_safe() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertDoesNotThrow(fresh::onDisable);
        assertFalse(fresh.isReady());
    }

    @Test
    @DisplayName("getApi() 在 onEnable 之前回傳 default instance，不丟例外")
    void getApi_beforeOnEnable_safeToCall() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertDoesNotThrow(fresh::getApi);
        assertNotNull(fresh.getApi());
        assertEquals(AceLibVersion.VERSION, fresh.getApi().getVersion());
    }
}
