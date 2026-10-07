package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.player.PlayerDataService;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

@DisplayName("Player lifecycle login preloading")
class PlayerLifecyclePreloadTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private AceLibPlugin plugin;
    private DataStore store;
    private PlayerDataService service;
    private ExecutorService ioExecutor;
    private AceLibPlugin.PlayerLifecycleListener listener;
    private Logger logger;
    private final AtomicReference<Thread> ioWorker = new AtomicReference<>();
    private final java.util.List<LogRecord> logRecords = new CopyOnWriteArrayList<>();
    private Handler logHandler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        store = newStore("preload.json");
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-player-preload-io");
            thread.setDaemon(true);
            ioWorker.set(thread);
            return thread;
        });
        service = new PlayerDataService(store, ioExecutor);
        logger = Logger.getLogger("acelib-preload-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        logger.addHandler(logHandler);
        listener = new AceLibPlugin.PlayerLifecycleListener(service, logger, 5_000L);
        register(listener);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
            HandlerList.unregisterAll(listener);
        }
        if (service != null) {
            try {
                service.shutdown();
            } catch (RuntimeException ignored) {
                // 清理期間仍保留主要測試失敗原因。
            }
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        if (store != null) {
            store.close();
        }
        if (logger != null && logHandler != null) {
            logger.removeHandler(logHandler);
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("pre-login 預載後 join 重用同一 session 且只通知一次")
    void preloginLoadsBeforeJoinAndJoinDoesNotReadAgain() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        DataStore countedStore = countingStore("preload-counted.json", reads, false);
        replaceService(countedStore);
        UUID uuid = UUID.randomUUID();
        AtomicInteger readyCalls = new AtomicInteger();
        service.addReadyListener((readyUuid, record) -> readyCalls.incrementAndGet());

        AsyncPlayerPreLoginEvent prelogin = preLogin("Preloaded", uuid);
        assertTrue(prelogin.isAsynchronous(), "測試事件應代表非同步 pre-login context");
        fire(prelogin);
        fire(preLogin("Preloaded", uuid));
        awaitReady(uuid);
        assertEquals(1, readyCalls.get(), "pre-login 載入完成後應通知一次");
        assertEquals(1, reads.get(), "pre-login 應執行一次 store 讀取");
        assertNotSame(Thread.currentThread(), ioWorker.get(), "I/O 不得在 pre-login 呼叫執行緒執行");

        fire(join("Preloaded", uuid));
        assertTrue(service.getSession(uuid).orElseThrow().isReady(),
            "join 應沿用預載完成的 session");
        assertEquals(1, reads.get(), "join 不得重複讀取 store");
        assertEquals(1, readyCalls.get(), "同一位玩家不得重複通知資料就緒");
        assertFalse(hasLogCode("ACELIB-PLAYER-004"), "預載 session 不得被當成重複登入");
    }

    @Test
    @DisplayName("沒有 pre-login 預載時維持原 join 載入行為")
    void joinWithoutPreloginUsesExistingLoadPath() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        replaceService(countingStore("join-without-preload.json", reads, false));
        UUID uuid = UUID.randomUUID();
        AtomicInteger readyCalls = new AtomicInteger();
        service.addReadyListener((readyUuid, record) -> readyCalls.incrementAndGet());

        fire(join("NoPrelogin", uuid));
        awaitReady(uuid);

        assertEquals(1, reads.get(), "無預載時 join 應沿用既有單次讀取");
        assertEquals(1, readyCalls.get(), "既有 join 載入仍只通知一次");
    }

    @Test
    @DisplayName("預載失敗後 join 回退既有載入流程")
    void failedPreloadFallsBackToJoinLoad() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        DataStore failOnceStore = countingStore("preload-fail-once.json", reads, true);
        replaceService(failOnceStore);
        UUID uuid = UUID.randomUUID();
        AtomicInteger readyCalls = new AtomicInteger();
        service.addReadyListener((readyUuid, record) -> readyCalls.incrementAndGet());

        fire(preLogin("RetryAfterFailure", uuid));
        await(() -> reads.get() == 1 && service.getSession(uuid).isEmpty());
        fire(join("RetryAfterFailure", uuid));
        awaitReady(uuid);

        assertEquals(2, reads.get(), "join 應在預載失敗後重新讀取");
        assertEquals(1, readyCalls.get(), "失敗的預載不得通知；回退成功只通知一次");
    }

    @Test
    @DisplayName("join 與預載失敗競爭時共用失敗結果後只回退一次")
    void joinDuringFailedPreloadFallsBackOnceAfterPreloadEnds() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch firstReadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        DataStore blockedFailure = blockedFailFirstStore(
            "preload-blocked-failure.json", reads, firstReadStarted, releaseFirstRead);
        replaceService(blockedFailure);
        UUID uuid = UUID.randomUUID();
        AtomicInteger readyCalls = new AtomicInteger();
        service.addReadyListener((readyUuid, record) -> readyCalls.incrementAndGet());

        fire(preLogin("JoinDuringFailure", uuid));
        assertTrue(firstReadStarted.await(2, TimeUnit.SECONDS), "預載必須開始讀取");
        fire(join("JoinDuringFailure", uuid));
        releaseFirstRead.countDown();
        awaitReady(uuid);

        assertEquals(2, reads.get(), "預載失敗後應只有一筆 join 回退讀取");
        assertEquals(1, readyCalls.get(), "回退載入只通知一次");
    }

    @Test
    @DisplayName("未 join 的成功預載會逾時清理 session 與預載標記")
    void preloginWithoutJoinExpiresWithoutResidualSession() throws Exception {
        replaceListener(50L);
        UUID uuid = UUID.randomUUID();
        fire(preLogin("NeverJoined", uuid));
        awaitReady(uuid);

        await(() -> service.getSession(uuid).isEmpty());
        assertTrue(service.getSession(uuid).isEmpty(), "未 join 的預載 session 必須清除");
        assertEquals(0, listener.pendingPreloginCount(), "逾時後不得殘留預載標記");
        assertEquals(0, serviceMapSize("records"), "逾時後不得殘留玩家資料快取");
        assertEquals(0, serviceMapSize("persistedFields"), "逾時後不得殘留差分快取");
    }

    @Test
    @DisplayName("服務停用後 AsyncPlayerPreLoginEvent 預載遭拒且不建立 session")
    void shutdownRejectsPreloginLoad() throws Exception {
        service.shutdown();
        UUID uuid = UUID.randomUUID();

        fire(preLogin("AfterShutdown", uuid));

        assertTrue(service.getSession(uuid).isEmpty(), "停用後不得建立預載 session");
        assertTrue(hasLogCode("ACELIB-PLAYER-007"), "拒派應記錄 PLAYER-007");
        assertEquals(0, listener.pendingPreloginCount(), "拒派不得留下預載標記");
    }

    @Test
    @DisplayName("listener 關閉後拒絕尚未處理的 pre-login")
    void closedListenerRejectsPrelogin() throws Exception {
        listener.close();
        UUID uuid = UUID.randomUUID();

        listener.onAsyncPlayerPreLogin(preLogin("AfterListenerClose", uuid));

        assertTrue(service.getSession(uuid).isEmpty(), "關閉的 listener 不得建立 session");
        assertTrue(hasLogCode("ACELIB-PLAYER-007"), "關閉後拒派須記錄 PLAYER-007");
        assertEquals(0, listener.pendingPreloginCount(), "關閉後不得新增預載標記");
    }

    private void replaceService(DataStore replacement) {
        service.shutdown();
        store.close();
        store = replacement;
        service = new PlayerDataService(store, ioExecutor);
        replaceListener(5_000L);
    }

    private void replaceListener(long leaseMillis) {
        if (listener != null) {
            listener.close();
            HandlerList.unregisterAll(listener);
        }
        listener = new AceLibPlugin.PlayerLifecycleListener(service, logger, leaseMillis);
        register(listener);
    }

    private DataStore newStore(String fileName) throws IOException {
        DataStore result = new JsonFileDataStore("preload-test", tempDir.resolve(fileName),
            SchemaVersion.V1_0, new JsonCodecImpl());
        result.init();
        return result;
    }

    private DataStore countingStore(String fileName, AtomicInteger reads, boolean failFirst)
            throws IOException {
        DataStore delegate = newStore(fileName);
        return (DataStore) Proxy.newProxyInstance(DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("root")) {
                    int read = reads.incrementAndGet();
                    if (failFirst && read == 1) {
                        throw new DataStoreException("ACELIB-DATA-001", "injected first read failure");
                    }
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private DataStore blockedFailFirstStore(String fileName, AtomicInteger reads,
            CountDownLatch started, CountDownLatch release) throws IOException {
        DataStore delegate = newStore(fileName);
        return (DataStore) Proxy.newProxyInstance(DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("root") && reads.incrementAndGet() == 1) {
                    started.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new DataStoreException("ACELIB-DATA-001", "test read gate expired");
                    }
                    throw new DataStoreException("ACELIB-DATA-001", "injected first read failure");
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private void register(AceLibPlugin.PlayerLifecycleListener target) {
        Map<Class<? extends Event>, Set<RegisteredListener>> byEvent =
            plugin.getPluginLoader().createRegisteredListeners(target, plugin);
        byEvent.forEach((eventType, registrations) -> {
            try {
                Method method = eventType.getMethod("getHandlerList");
                HandlerList handlers = (HandlerList) method.invoke(null);
                registrations.forEach(handlers::register);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("could not register test listener for " + eventType,
                    failure);
            }
        });
    }

    private void fire(Event event) throws org.bukkit.event.EventException {
        for (RegisteredListener registration : event.getHandlers().getRegisteredListeners()) {
            if (registration.getListener() == listener) {
                registration.callEvent(event);
            }
        }
    }

    private AsyncPlayerPreLoginEvent preLogin(String name, UUID uuid) throws Exception {
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), uuid, false);
    }

    private PlayerJoinEvent join(String name, UUID uuid) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        return new PlayerJoinEvent(player, Component.empty());
    }

    private void awaitReady(UUID uuid) throws InterruptedException {
        await(() -> service.getSession(uuid).map(session -> session.isReady()).orElse(false));
        assertNotNull(service.getData(uuid).orElse(null), "資料就緒時應可讀取玩家資料");
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), "等待條件逾時");
    }

    private boolean hasLogCode(String code) {
        return logRecords.stream().anyMatch(record -> record.getMessage() != null
            && record.getMessage().contains(code));
    }

    private int serviceMapSize(String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = PlayerDataService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(service)).size();
    }

}
