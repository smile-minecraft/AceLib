package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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

    // ---------------------------------------------------------------------
    // Phase 1 新增測試：平台警告 / capability 對外暴露
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onEnable 偵測 UNKNOWN platform 時輸出 ACELIB-PLAT-004 warning")
    void onEnable_unknownPlatform_logsWarning() {
        // 隔離 Bukkit 環境，重新 mock + loadPlugin，再用空 classloader 觸發 UNKNOWN
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);

        // 附加 log capture handler 到 root + "AceLib" logger，捕獲所有 LogRecord
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            PlatformDetector emptyDetector = new PlatformDetector(new ClassLoader(null) {});
            fresh.onEnable(server, emptyDetector);

            // 驗證 ready 狀態 + 平台已記錄
            assertTrue(fresh.isReady(), "onEnable 必須仍將 plugin 標記為 ready");
            assertSame(Platform.UNKNOWN, fresh.getApi().getPlatform(),
                "空 classloader 偵測結果必須為 UNKNOWN");

            // 驗證 log 輸出包含 ACELIB-PLAT-004
            boolean hasWarning = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAT-004"));
            assertTrue(hasWarning,
                "UNKNOWN 平台必須輸出含 ACELIB-PLAT-004 的 warning。實際訊息: "
                    + captured.stream().map(LogRecord::getMessage).toList());
        } finally {
            scope.detach();
        }
    }

    @Test
    @DisplayName("onEnable 偵測 PAPER（測試 classloader 無 Folia）時輸出 fine-level 提示")
    void onEnable_paperClasspathOnly_logsFine() {
        // 此情境下 plugin 已被 @BeforeEach 用 test classloader 啟用（會是 PAPER）
        // 我們重新走一次流程並驗 log
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);

        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            PlatformDetector paperOnlyDetector = new PlatformDetector(getClass().getClassLoader());
            fresh.onEnable(server, paperOnlyDetector);

            assertSame(Platform.PAPER, fresh.getApi().getPlatform());
            // 不要求 ACELIB-PLAT-004（因為不是 UNKNOWN）
            boolean hasPlat004 = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAT-004"));
            assertFalse(hasPlat004,
                "PAPER 環境不應輸出 ACELIB-PLAT-004 warning。實際: "
                    + captured.stream().map(LogRecord::getMessage).toList());

            // 期望有 non-Folia 提示（fine level — 因此必須先把 logger level 調為 ALL）
            boolean hasFineNote = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("non-Folia environment"));
            assertTrue(hasFineNote,
                "PAPER 環境應輸出 non-Folia 提示。實際: "
                    + captured.stream().map(LogRecord::getMessage).toList());
        } finally {
            scope.detach();
        }
    }

    @Test
    @DisplayName("getPlatformCapability() 回傳對應 PAPER 的 capability")
    void getPlatformCapability_returnsPaperCapability() {
        // @BeforeEach 已將 plugin 用 test classloader 啟用（MockBukkit 環境 = PAPER）
        PlatformCapability cap = plugin.getPlatformCapability();
        assertNotNull(cap, "getPlatformCapability 不可為 null");
        assertEquals(PlatformCapability.forPlatform(Platform.PAPER), cap,
            "MockBukkit 環境下 capability 應與 Platform.PAPER 一致");
    }

    @Test
    @DisplayName("getApi().getPlatformCapability() 與 getPlatformCapability() 回傳相同實例")
    void getApiAndPlugin_getPlatformCapability_consistent() {
        assertEquals(
            plugin.getApi().getPlatformCapability(),
            plugin.getPlatformCapability(),
            "plugin facade 與 plugin 本體暴露的 capability 必須一致");
    }

    // ---------------------------------------------------------------------
    // Helper: LogRecord recorder + 配套 scope
    // ---------------------------------------------------------------------

    /**
     * 簡單的 JUL Handler，把所有收到的 LogRecord 加入外部 List。
     * 測試結束後由 caller 負責從 Logger 移除。
     */
    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> sink;

        RecordingHandler(List<LogRecord> sink) {
            this.sink = sink;
        }

        @Override
        public void publish(LogRecord record) {
            sink.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * 連同「附加 handler」與「暫時把 logger 與 root level 調為 ALL」一起打包的 scope。
     * 測試結束時呼叫 {@link #detach()} 還原 level 並移除 handler，避免污染其他測試。
     *
     * <p>為何需要把 level 調成 ALL？JavaPlugin 在 MockBukkit 環境下取出的 logger
     * 預設 level 為 INFO，會在 handler 處理之前就過濾掉 FINE 訊息。
     * 為驗證 fine-level 輸出，必須先把 level 拉低。</p>
     */
    private static final class LogCaptureScope {
        private final List<Logger> touched;
        private final List<Level> previousLevels;
        private final Handler handler;
        private final Logger root;
        private final Level previousRootLevel;

        private LogCaptureScope(List<Logger> touched, List<Level> previousLevels,
                                Handler handler, Logger root, Level previousRootLevel) {
            this.touched = touched;
            this.previousLevels = previousLevels;
            this.handler = handler;
            this.root = root;
            this.previousRootLevel = previousRootLevel;
        }

        /**
         * 附加 handler 到 root 與指定名稱的 logger，並把兩者 level 暫時調成 ALL。
         *
         * @param pluginLoggerName plugin logger 的名稱（"AceLib"）
         * @param handler          已建構的 Handler
         * @return 可在 finally 呼叫 {@link #detach()} 的 scope
         */
        static LogCaptureScope attachAndLower(String pluginLoggerName, Handler handler) {
            Logger root = Logger.getLogger("");
            Level previousRoot = root.getLevel();
            root.setLevel(Level.ALL);
            root.addHandler(handler);

            Logger named = Logger.getLogger(pluginLoggerName);
            List<Logger> touched = new ArrayList<>();
            List<Level> previous = new ArrayList<>();
            touched.add(named);
            previous.add(named.getLevel());
            named.setLevel(Level.ALL);
            named.addHandler(handler);
            return new LogCaptureScope(touched, previous, handler, root, previousRoot);
        }

        void detach() {
            for (int i = 0; i < touched.size(); i++) {
                Logger lg = touched.get(i);
                lg.removeHandler(handler);
                lg.setLevel(previousLevels.get(i));
            }
            root.removeHandler(handler);
            root.setLevel(previousRootLevel);
        }
    }
}
