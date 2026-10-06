package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 檔案監看 Red 測試（決定性：以受控輪詢驅動，不依賴真實時間）。
 *
 * <p>鎖住：自動重載、無效新內容保留舊快照並診斷、寫回不觸發迴圈、
 * reload／disable 不殘留監看器。</p>
 */
@DisplayName("ConfigManager 檔案監看（自動重載／無效保舊／清理）")
class ConfigWatcherTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private ConfigSchema schema;
    private final ConfigVersion currentVersion = new ConfigVersion(1, 0);

    private static final class RecordingListener implements ConfigChangeListener {
        final List<ConfigSnapshot> reloaded = new CopyOnWriteArrayList<>();
        final List<String> invalidCodes = new CopyOnWriteArrayList<>();
        final List<String> invalidDetails = new CopyOnWriteArrayList<>();

        @Override
        public void onReload(ConfigSnapshot snapshot) {
            reloaded.add(snapshot);
        }

        @Override
        public void onInvalidReload(String code, String detail) {
            invalidCodes.add(code);
            invalidDetails.add(detail);
        }
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

    private ConfigManager loadedManager(String fileName, String yaml) throws Exception {
        Files.writeString(dataFolder.toPath().resolve(fileName), yaml, StandardCharsets.UTF_8);
        ConfigManager mgr = new ConfigManager(plugin, fileName, schema, currentVersion);
        mgr.load();
        return mgr;
    }

    @Test
    @DisplayName("外部修改後輪詢 → 自動重載並通知，新快照生效")
    void watcher_externalChange_reloadsAndNotifies() throws Exception {
        ConfigManager mgr = loadedManager("watch-ok.yml",
            "version: '1.0'\ngreeting: 'before'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);

        Files.writeString(dataFolder.toPath().resolve("watch-ok.yml"),
            "version: '1.0'\ngreeting: 'after'\n", StandardCharsets.UTF_8);
        mgr.pollWatcherOnce();

        assertEquals(1, listener.reloaded.size(), "有效變更必須通知一次");
        assertEquals("after", listener.reloaded.get(0).getString("greeting", null));
        assertEquals("after", mgr.get("greeting"));
        mgr.close();
    }

    @Test
    @DisplayName("無效新內容 → 保留舊快照並以錯誤碼診斷，原檔不被覆寫")
    void watcher_invalidChange_keepsOldSnapshot_andDiagnoses() throws Exception {
        Path file = dataFolder.toPath().resolve("watch-bad.yml");
        ConfigManager mgr = loadedManager("watch-bad.yml",
            "version: '1.0'\ngreeting: 'steady'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);
        ConfigSnapshot before = mgr.snapshot();

        byte[] broken = "greeting: [unclosed\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, broken);
        mgr.pollWatcherOnce();

        assertTrue(listener.reloaded.isEmpty(), "無效內容不得發布新快照");
        assertEquals(1, listener.invalidCodes.size(), "無效內容必須診斷一次");
        assertTrue(listener.invalidCodes.get(0).startsWith("ACELIB-CFG-"),
            "診斷必須帶錯誤碼，實際：" + listener.invalidCodes);
        assertTrue(mgr.snapshot() == before, "舊快照實例必須保留");
        assertEquals("steady", mgr.get("greeting"));
        assertArrayEquals(broken, Files.readAllBytes(file), "監看重載失敗不得改動原檔");
        mgr.close();
    }

    @Test
    @DisplayName("連續外部事件只在內容真的變了才重載；無變化輪詢不打擾")
    void watcher_consecutivePollsWithoutChange_doNotReload() throws Exception {
        ConfigManager mgr = loadedManager("watch-quiet.yml",
            "version: '1.0'\ngreeting: 'same'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);

        mgr.pollWatcherOnce();
        mgr.pollWatcherOnce();

        assertTrue(listener.reloaded.isEmpty(), "內容沒變時不得重載");
        assertTrue(listener.invalidCodes.isEmpty());
        mgr.close();
    }

    @Test
    @DisplayName("自己的寫回不觸發重載迴圈")
    void watcher_ownWriteback_doesNotTriggerReloadLoop() throws Exception {
        ConfigManager mgr = loadedManager("watch-self.yml",
            "version: '1.0'\ngreeting: 'v1'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);

        mgr.set("greeting", "v2");
        mgr.save();
        mgr.pollWatcherOnce();

        assertTrue(listener.reloaded.isEmpty(), "自己的寫回不得觸發重載");
        assertTrue(listener.invalidCodes.isEmpty());
        mgr.close();
    }

    @Test
    @DisplayName("reload 不殺掉監看器；close 後不殘留執行緒")
    void watcher_reloadKeepsWatching_closeCleansUp() throws Exception {
        ConfigManager mgr = loadedManager("watch-life.yml",
            "version: '1.0'\ngreeting: 'v1'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);
        assertTrue(mgr.isWatching());

        Files.writeString(dataFolder.toPath().resolve("watch-life.yml"),
            "version: '1.0'\ngreeting: 'v2'\n", StandardCharsets.UTF_8);
        assertTrue(mgr.reload(), "reload 本體必須成功");
        assertTrue(mgr.isWatching(), "reload 不得關掉監看器");

        // 重複 start 不得洩漏第二條執行緒
        int threadsBefore = countWatcherThreads();
        mgr.startWatching(listener);
        assertEquals(threadsBefore, countWatcherThreads(), "重複啟動監看不得新增執行緒");

        mgr.close();
        assertFalse(mgr.isWatching());
        // 給 daemon 執行緒一點收尾時間，但斷言本身不依賴時間：重複 close 必須安全
        mgr.close();
        assertEquals(0, countWatcherThreadsFor(mgr), "close 後不得殘留該 manager 的監看執行緒");
    }

    @Test
    @DisplayName("監看器執行緒是 daemon：不得擋住 JVM 退出")
    void watcher_thread_isDaemon() throws Exception {
        ConfigManager mgr = loadedManager("watch-daemon.yml",
            "version: '1.0'\ngreeting: 'v1'\n");
        mgr.startWatching(new RecordingListener());
        try {
            Thread watcher = findWatcherThread();
            assertNotNull(watcher, "啟動監看後必須有監看執行緒");
            assertTrue(watcher.isDaemon(), "監看執行緒必須是 daemon");
        } finally {
            mgr.close();
        }
    }

    @Test
    @DisplayName("stopWatching 後外部變更不再通知")
    void watcher_afterStop_noMoreNotifications() throws Exception {
        ConfigManager mgr = loadedManager("watch-stop.yml",
            "version: '1.0'\ngreeting: 'v1'\n");
        RecordingListener listener = new RecordingListener();
        mgr.startWatching(listener);
        mgr.stopWatching();

        Files.writeString(dataFolder.toPath().resolve("watch-stop.yml"),
            "version: '1.0'\ngreeting: 'v2'\n", StandardCharsets.UTF_8);
        mgr.pollWatcherOnce();

        assertTrue(listener.reloaded.isEmpty());
        assertEquals("v1", mgr.get("greeting"), "停止監看後不得自動套用外部變更");
        mgr.close();
    }

    private static int countWatcherThreads() {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().startsWith("acelib-config-watch-") && t.isAlive()) {
                count.set(count.get() + 1);
            }
        });
        return count.get();
    }

    private static int countWatcherThreadsFor(ConfigManager mgr) {
        String suffix = "@" + Integer.toHexString(System.identityHashCode(mgr));
        AtomicReference<Integer> count = new AtomicReference<>(0);
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().startsWith("acelib-config-watch-")
                && t.getName().endsWith(suffix)
                && t.isAlive()) {
                // 剛 close 的執行緒可能還在收尾：等它一下再數
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (t.isAlive()) {
                    count.set(count.get() + 1);
                }
            }
        });
        return count.get();
    }

    private static Thread findWatcherThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("acelib-config-watch-") && t.isAlive()) {
                return t;
            }
        }
        return null;
    }

    @Test
    @DisplayName("連續啟停監看不殘留執行緒（WatchService 關閉迴歸）")
    void watcher_rapidStartStop_leavesNoThreads() throws Exception {
        ConfigManager mgr = loadedManager("watch-cycles.yml",
            "version: '1.0'\ngreeting: 'v1'\n");
        RecordingListener listener = new RecordingListener();
        try {
            for (int i = 0; i < 20; i++) {
                mgr.startWatching(listener);
                mgr.stopWatching();
            }
            assertFalse(mgr.isWatching());
            assertEquals(0, countWatcherThreadsFor(mgr), "連續啟停不得殘留監看執行緒");
        } finally {
            mgr.close();
        }
    }
}
