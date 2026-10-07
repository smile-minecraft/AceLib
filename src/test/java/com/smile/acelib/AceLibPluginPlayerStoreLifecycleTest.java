package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.player.PlayerDataService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 驗證逐玩家 store（{@code players.db}）的資源釋放時機。
 *
 * <p>store 持有底層連線／檔案句柄，必須在舊 {@code PlayerDataService} shutdown
 * （已完成最後一批 flush）之後、釋放舊 io pool 的同一段生命週期轉換中關閉。
 * 本測試以「reload 後舊 store 已 {@code isClosed()}」觀察這個不變條件，
 * 涵蓋兩條會遺漏資源的路徑：</p>
 * <ul>
 *   <li>reload 成功路徑（先關舊 store，再 bind 新 service）</li>
 *   <li>reload(INCOMPATIBLE) teardown（整體降級，沒有新 service 接替）</li>
 * </ul>
 */
@DisplayName("AceLibPlugin player store lifecycle")
class AceLibPluginPlayerStoreLifecycleTest {

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
    }

    private void enablePlugin() {
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 私有欄位讀取（package-private accessor 未開放時以反射觀察）
    // -----------------------------------------------------------------

    private static PlayerDataStore currentStore(AceLibPlugin p) {
        try {
            java.lang.reflect.Field f = AceLibPlugin.class
                .getDeclaredField("playerDataStore");
            f.setAccessible(true);
            return (PlayerDataStore) f.get(p);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("test reflection failed: " + ex, ex);
        }
    }

    // -----------------------------------------------------------------
    // cases
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onEnable 後必須已建立 store 且未關閉")
    void enable_opensStore() {
        enablePlugin();
        PlayerDataStore store = currentStore(plugin);
        assertNotNull(store, "onEnable 後必須建立逐玩家 store");
        assertTrue(store.isInitialized(), "onEnable 後 store 必須已初始化");
        assertFalse(store.isClosed(), "onEnable 後 store 不可為已關閉");
    }

    @Test
    @DisplayName("reload 成功路徑：舊 store 必須關閉，新 store 必須另行建立且未關閉")
    void reload_success_closesOldStoreBeforeRebinding() {
        enablePlugin();
        PlayerDataStore before = currentStore(plugin);
        assertNotNull(before);
        assertFalse(before.isClosed());

        assertTrue(plugin.reload(), "已啟用時 reload 必須成功");

        assertTrue(before.isClosed(),
            "reload 成功路徑必須關閉舊 store，否則 SQLite 連線洩漏");
        PlayerDataStore after = currentStore(plugin);
        assertNotNull(after, "reload 後必須有新的 store");
        assertNotSame(before, after, "reload 後必須是新的 store 實例");
        assertFalse(after.isClosed(), "reload 後的新 store 不可為已關閉");
    }

    @Test
    @DisplayName("reload(INCOMPATIBLE) teardown：舊 store 必須關閉")
    void reload_incompatible_closesStore() {
        enablePlugin();
        PlayerDataStore before = currentStore(plugin);
        assertNotNull(before);
        assertFalse(before.isClosed());

        plugin.compatibilityOverride =
            ignored -> CompatibilityStatus.incompatible("forced incompatible", "fp-store");

        assertFalse(plugin.reload(), "INCOMPATIBLE reload 必須回傳 false");
        assertTrue(before.isClosed(),
            "reload(INCOMPATIBLE) teardown 必須關閉舊 store，否則 SQLite 連線洩漏");
        assertNull(currentStore(plugin),
            "INCOMPATIBLE teardown 後不得留下已釋放的 store reference");
    }

    @Test
    @DisplayName("onDisable：store 必須關閉且 reference 清空（冪等）")
    void disable_closesStoreIdempotently() {
        enablePlugin();
        PlayerDataStore before = currentStore(plugin);
        assertNotNull(before);

        plugin.onDisable();
        assertTrue(before.isClosed(), "onDisable 必須關閉 store");

        // 冪等：重複 disable 不得丟例外，也不得影響其他狀態
        plugin.onDisable();
        assertTrue(before.isClosed(), "重複 onDisable 後 store 仍為已關閉");
        assertNull(currentStore(plugin), "onDisable 後 store reference 必須清空");
    }

    @Test
    @DisplayName("AsyncPlayerPreLogin listener 於 reload 與 disable 解除註冊")
    void preloginListenerIsReleasedAcrossReloadAndDisable() throws Exception {
        enablePlugin();
        plugin.registerListenersForTest();
        Object oldListener = registeredPreloginListener();
        assertNotNull(oldListener, "enabled lifecycle 應註冊 AsyncPlayerPreLogin handler");
        PlayerDataService oldService = plugin.getPlayerDataService();
        UUID uuid = UUID.randomUUID();
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent("Preloaded",
            InetAddress.getLoopbackAddress(), uuid, false);
        for (org.bukkit.plugin.RegisteredListener registration
                : event.getHandlers().getRegisteredListeners()) {
            if (registration.getPlugin() == plugin && registration.getListener() == oldListener) {
                registration.callEvent(event);
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!oldService.getSession(uuid).map(session -> session.isReady()).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(oldService.getSession(uuid).map(session -> session.isReady()).orElse(false),
            "reload 前的 pre-login 應已進入 READY");

        assertTrue(plugin.reload(), "reload 必須成功");
        assertFalse(isRegistered(oldListener), "reload 不得留下舊 pre-login listener");
        assertTrue(oldService.isShutdown(), "reload 必須關閉舊 player service");
        assertEquals(0, oldService.activeSessionCount(), "reload 不得留下未 join 的 session");

        plugin.registerListenersForTest();
        Object newListener = registeredPreloginListener();
        assertNotNull(newListener, "reload 後應註冊新的 pre-login listener");
        assertNotSame(oldListener, newListener, "reload 必須替換 listener 實例");

        plugin.onDisable();
        assertFalse(isRegistered(newListener), "disable 不得留下新 pre-login listener");
    }

    @Test
    @DisplayName("轉換失敗時 fail-closed：不啟動玩家服務且保留來源與備份")
    void migrationFailureLeavesPlayerServiceUnavailableAndKeepsOtherPluginServices()
            throws Exception {
        Path dataFolder = plugin.getDataFolder().toPath();
        Files.createDirectories(dataFolder);
        Path databaseFile = dataFolder.resolve("players.db");
        PlayerDataStore prepared = PlayerDataStores.sqlite(databaseFile, SchemaVersion.V1_0);
        prepared.init();
        prepared.close();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_player_migration "
                + "BEFORE INSERT ON acelib_player_data BEGIN "
                + "SELECT RAISE(ABORT, 'injected migration failure'); END");
        }

        Path legacyFile = dataFolder.resolve("player-data.json");
        UUID uuid = UUID.randomUUID();
        String playerData = "{\"_version\":\"1.0\",\"players\":{\""
            + uuid + "\":{\"coins\":5}}}";
        Files.writeString(legacyFile, playerData);
        byte[] sourceBefore = Files.readAllBytes(legacyFile);

        List<String> logMessages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    logMessages.add(record.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        plugin.getLogger().addHandler(capture);
        try {
            enablePlugin();
        } finally {
            plugin.getLogger().removeHandler(capture);
        }

        assertNull(plugin.getPlayerDataService(),
            "轉換未完成時不得讓玩家登入建立空資料");
        assertNull(currentStore(plugin), "失敗的目標 store 不得繼續掛在 plugin 上");
        assertNotNull(plugin.getApi(), "玩家資料失敗不應停掉 plugin 其他功能");
        assertTrue(plugin.getApi().isReady(), "其他 plugin 模組仍須正常 ready");
        assertTrue(logMessages.stream().anyMatch(message ->
                message.contains("ACELIB-PLAYER-006")),
            "必須記錄服務不可用錯誤碼：" + logMessages);
        assertArrayEquals(sourceBefore, Files.readAllBytes(legacyFile),
            "轉換失敗不得修改來源檔案");
        try (var backups = Files.list(dataFolder.resolve("backup"))) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString()
                    .startsWith("player-data.json.")),
                "寫入失敗後仍須保留轉換前的備份");
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_player_migration");
        }
        assertTrue(plugin.reload(), "移除故障後 reload 應能重跑轉換");
        assertNotNull(plugin.getPlayerDataService(), "重跑成功後玩家資料服務應恢復");
        assertEquals(5, plugin.getPlayerDataService().getOfflineData(uuid)
            .orElseThrow().getInt("coins", -1));
        assertArrayEquals(sourceBefore, Files.readAllBytes(legacyFile),
            "重跑成功仍不得修改來源檔案");
    }

    private Object registeredPreloginListener() {
        return java.util.Arrays.stream(
                AsyncPlayerPreLoginEvent.getHandlerList().getRegisteredListeners())
            .filter(registered -> registered.getPlugin() == plugin)
            .map(org.bukkit.plugin.RegisteredListener::getListener)
            .findFirst().orElse(null);
    }

    private boolean isRegistered(Object listener) {
        return java.util.Arrays.stream(
                AsyncPlayerPreLoginEvent.getHandlerList().getRegisteredListeners())
            .anyMatch(registered -> registered.getPlugin() == plugin
                && registered.getListener() == listener);
    }
}
