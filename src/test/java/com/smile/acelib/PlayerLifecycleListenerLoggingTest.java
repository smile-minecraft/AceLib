package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.player.PlayerDataService;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link AceLibPlugin.PlayerLifecycleListener} 不向外拋例外的驗證。
 *
 * <p>join/quit 失敗（PLAYER-004/005/007 與非同步 PLAYER-002/003）必須被
 * listener 攔截並以 ACELIB-PLAYER 分類記錄，不可從 event handler 拋出
 * 污染 Bukkit 事件流程。</p>
 */
@DisplayName("PlayerLifecycleListener failure logging")
class PlayerLifecycleListenerLoggingTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DataStore store;
    private final List<DataStore> extraStores = new ArrayList<>();
    private final List<PlayerDataService> services = new ArrayList<>();

    private Logger testLogger;
    private Handler capturing;
    private final List<LogRecord> records = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        store = newStore("listener-logging.json");

        testLogger = Logger.getLogger("acelib-test-listener-" + UUID.randomUUID());
        testLogger.setUseParentHandlers(false);
        capturing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        testLogger.addHandler(capturing);
    }

    @AfterEach
    void tearDown() {
        for (PlayerDataService service : services) {
            try {
                service.shutdown();
            } catch (RuntimeException expectedOnDirtyShutdown) {
                // 保存失敗情境的 dirty 於清理期 flush 仍可失敗 — 可接受
            }
        }
        for (DataStore extra : extraStores) {
            extra.close();
        }
        if (store != null) {
            store.close();
        }
        if (testLogger != null && capturing != null) {
            testLogger.removeHandler(capturing);
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("重複 join（PLAYER-004）不向外拋並記錄分類")
    void duplicateJoin_doesNotThrowAndLogsPlayer004() {
        PlayerDataService service = newService(store);
        AceLibPlugin.PlayerLifecycleListener listener =
            new AceLibPlugin.PlayerLifecycleListener(service, testLogger);
        PlayerMock player = server.addPlayer();
        PlayerJoinEvent event = new PlayerJoinEvent(player, Component.empty());

        listener.onPlayerJoin(event);
        assertDoesNotThrow(() -> listener.onPlayerJoin(event),
            "重複 join 不可從 event handler 拋出");

        assertTrue(records.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAYER-004")),
            "重複 join 必須以 ACELIB-PLAYER-004 分類記錄；實際記錄=" + messages());
    }

    @Test
    @DisplayName("未知 UUID quit（PLAYER-005）不向外拋並記錄分類")
    void quitUnknown_doesNotThrowAndLogsPlayer005() {
        PlayerDataService service = newService(store);
        AceLibPlugin.PlayerLifecycleListener listener =
            new AceLibPlugin.PlayerLifecycleListener(service, testLogger);
        PlayerMock player = server.addPlayer();
        PlayerQuitEvent event = new PlayerQuitEvent(player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED);

        // PlayerMock 的 addPlayer 只觸發 plugin 自身 service 的 join；
        // 此處 listener 背後的 service 從未見過該 UUID → PLAYER-005。
        assertDoesNotThrow(() -> listener.onPlayerQuit(event),
            "未知 session quit 不可從 event handler 拋出");
        assertTrue(records.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAYER-005")),
            "未知 session quit 必須以 ACELIB-PLAYER-005 分類記錄；實際記錄=" + messages());
    }

    @Test
    @DisplayName("shutdown 後 join（PLAYER-007）不向外拋並記錄分類")
    void joinAfterShutdown_doesNotThrowAndLogsPlayer007() {
        PlayerDataService service = newService(store);
        service.shutdown();
        AceLibPlugin.PlayerLifecycleListener listener =
            new AceLibPlugin.PlayerLifecycleListener(service, testLogger);
        PlayerMock player = server.addPlayer();
        PlayerJoinEvent event = new PlayerJoinEvent(player, Component.empty());

        assertDoesNotThrow(() -> listener.onPlayerJoin(event),
            "shutdown 後 join 不可從 event handler 拋出");
        assertTrue(records.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAYER-007")),
            "shutdown 後 join 必須以 ACELIB-PLAYER-007 分類記錄；實際記錄=" + messages());
    }

    @Test
    @DisplayName("非同步載入失敗（PLAYER-002）不向外拋並記錄分類")
    void asyncLoadFailure_doesNotThrowAndLogsPlayer002() throws IOException {
        DataStore delegate = newStore("listener-load-failure.json");
        extraStores.add(delegate);
        DataStore loadFailing = (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("root")) {
                    throw new DataStoreException("ACELIB-DATA-001", "injected load failure");
                }
                return method.invoke(delegate, args);
            });
        PlayerDataService service = newService(loadFailing);
        AceLibPlugin.PlayerLifecycleListener listener =
            new AceLibPlugin.PlayerLifecycleListener(service, testLogger);
        PlayerMock player = server.addPlayer();
        PlayerJoinEvent event = new PlayerJoinEvent(player, Component.empty());

        assertDoesNotThrow(() -> listener.onPlayerJoin(event),
            "非同步載入失敗不可從 event handler 拋出");

        long deadline = System.currentTimeMillis() + 5000L;
        while (records.stream().noneMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAYER-002"))
            && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(records.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAYER-002")),
            "非同步載入失敗必須以 ACELIB-PLAYER-002 分類記錄；實際記錄=" + messages());
    }

    private PlayerDataService newService(DataStore dataStore) {
        PlayerDataService service = new PlayerDataService(dataStore, Runnable::run);
        services.add(service);
        return service;
    }

    private DataStore newStore(String fileName) throws IOException {
        DataStore dataStore = new JsonFileDataStore("listener-logging",
            tempDir.resolve(fileName), SchemaVersion.V1_0, new JsonCodecImpl());
        dataStore.init();
        return dataStore;
    }

    private List<String> messages() {
        List<String> out = new ArrayList<>();
        for (LogRecord record : records) {
            out.add(record.getLevel() + " " + record.getMessage());
        }
        return out;
    }
}
