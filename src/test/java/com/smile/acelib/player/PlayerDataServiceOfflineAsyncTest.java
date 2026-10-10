package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataService#getOfflineDataAsync(UUID)} 的行為測試。
 *
 * <p>非同步入口與同步版 {@code getOfflineData} 語意一致：資料來自 store
 * 的實際內容；同玩家在線時拿到的是已持久化內容，未 {@code flush} 的
 * session 變更不在內。呼叫端不阻塞，讀取在內部 serial store executor 上執行。
 */
@DisplayName("PlayerDataService#getOfflineDataAsync")
class PlayerDataServiceOfflineAsyncTest {

    private static final long MANUAL_INTERVAL_MS = 60_000L;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private PlayerDataStore store;
    private PlayerDataService service;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("players.db");
        store = openStore();
        service = new PlayerDataService(store, Runnable::run, MANUAL_INTERVAL_MS);
    }

    @AfterEach
    void tearDown() {
        if (service != null && !service.isShutdown()) {
            service.shutdown();
        }
        if (store != null && !store.isClosed()) {
            store.close();
        }
    }

    private PlayerDataStore openStore() {
        PlayerDataStore opened = PlayerDataStores.sqlite(dbFile, SchemaVersion.V1_0);
        opened.init();
        return opened;
    }

    private static PlayerStateException joinFailure(CompletableFuture<?> future) {
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertTrue(thrown.getCause() instanceof PlayerStateException,
            "async 讀取失敗必須以 PlayerStateException 表達，實際為："
                + thrown.getCause());
        return (PlayerStateException) thrown.getCause();
    }

    @Test
    @DisplayName("從未寫入的玩家完成為 empty")
    void unknownPlayer_completesEmpty() throws Exception {
        Optional<Record> result = service.getOfflineDataAsync(UUID.randomUUID())
            .get(5, TimeUnit.SECONDS);
        assertEquals(Optional.empty(), result);
    }

    @Test
    @DisplayName("有資料者完成為該內容")
    void persistedData_completesWithContent() throws Exception {
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "p").join();
        service.getData(uuid).orElseThrow().set("coins", 42);
        service.markDirty(uuid);
        service.onPlayerQuit(uuid).join();

        Optional<Record> result = service.getOfflineDataAsync(uuid)
            .get(5, TimeUnit.SECONDS);
        assertTrue(result.isPresent(), "離線玩家的資料必須讀得到");
        assertEquals(42, result.orElseThrow().getInt("coins", -1));
    }

    @Test
    @DisplayName("同玩家在線：結果為已持久化內容，未 flush 的 session 變更不在內")
    void onlinePlayer_readsPersistedContentNotUnflushedSessionChanges() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "coins", 5)));
        service.onPlayerJoin(uuid, "p").join();

        // session 內改值但尚未落盤（未 quit、未 autosave）
        service.getData(uuid).orElseThrow().set("coins", 99);
        service.markDirty(uuid);

        Optional<Record> result = service.getOfflineDataAsync(uuid)
            .get(5, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        assertEquals(5, result.orElseThrow().getInt("coins", -1),
            "async 讀取必須是 store 已持久化內容，未 flush 的 session 變更不在內");
        assertEquals(99, service.getData(uuid).orElseThrow().getInt("coins", -1),
            "前置條件：session 內的未保存變更仍為 99，證明兩者確實不同");
    }

    @Test
    @DisplayName("退服後讀取為最後一次落盤內容")
    void quitThenRead_returnsLastPersisted() throws Exception {
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "p").join();
        service.getData(uuid).orElseThrow().set("coins", 7);
        service.markDirty(uuid);
        service.onPlayerQuit(uuid).join();

        assertTrue(service.getSession(uuid).isEmpty(), "前置條件：玩家已離線");
        Optional<Record> result = service.getOfflineDataAsync(uuid)
            .get(5, TimeUnit.SECONDS);
        assertEquals(7, result.orElseThrow().getInt("coins", -1));
    }

    @Test
    @DisplayName("重連後讀取為最新落盤內容")
    void rejoinThenRead_returnsLatest() throws Exception {
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "p").join();
        service.getData(uuid).orElseThrow().set("coins", 1);
        service.markDirty(uuid);
        service.onPlayerQuit(uuid).join();

        service.onPlayerJoin(uuid, "p").join();
        service.getData(uuid).orElseThrow().set("coins", 2);
        service.markDirty(uuid);
        service.onPlayerQuit(uuid).join();

        Optional<Record> result = service.getOfflineDataAsync(uuid)
            .get(5, TimeUnit.SECONDS);
        assertEquals(2, result.orElseThrow().getInt("coins", -1));
    }

    @Test
    @DisplayName("服務重建（reload 語意）後讀取正常")
    void rebuiltService_readsPersisted() throws Exception {
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "p").join();
        service.getData(uuid).orElseThrow().set("coins", 11);
        service.markDirty(uuid);
        service.onPlayerQuit(uuid).join();

        service.shutdown();
        service = null;
        store.close();
        store = openStore();
        service = new PlayerDataService(store, Runnable::run, MANUAL_INTERVAL_MS);

        Optional<Record> result = service.getOfflineDataAsync(uuid)
            .get(5, TimeUnit.SECONDS);
        assertEquals(11, result.orElseThrow().getInt("coins", -1));
    }

    @Test
    @DisplayName("服務關閉後呼叫以 ACELIB-PLAYER-007 exceptional 完成")
    void shutdown_completesExceptionally007() {
        service.shutdown();
        try {
            PlayerStateException failure =
                joinFailure(service.getOfflineDataAsync(UUID.randomUUID()));
            assertEquals("ACELIB-PLAYER-007", failure.getCode());
        } finally {
            service = null;
        }
    }

    @Test
    @DisplayName("store 讀取拋錯以 ACELIB-PLAYER-002 exceptional 完成")
    void loadFailure_completesExceptionally002() {
        AtomicBoolean fail = new AtomicBoolean(true);
        PlayerDataService broken = new PlayerDataService(
            new LoadFailingStore(openStore(), fail), Runnable::run, MANUAL_INTERVAL_MS);
        try {
            PlayerStateException failure =
                joinFailure(broken.getOfflineDataAsync(UUID.randomUUID()));
            assertEquals("ACELIB-PLAYER-002", failure.getCode());
        } finally {
            broken.shutdown();
        }
    }

    @Test
    @DisplayName("內部 executor 已終止以 ACELIB-PLAYER-008 exceptional 完成")
    void terminatedExecutor_completesExceptionally008() throws Exception {
        var flag = PlayerDataService.class.getDeclaredField("serialExecutorTerminated");
        flag.setAccessible(true);
        ((AtomicBoolean) flag.get(service)).set(true);
        try {
            PlayerStateException failure =
                joinFailure(service.getOfflineDataAsync(UUID.randomUUID()));
            assertEquals("ACELIB-PLAYER-008", failure.getCode());
        } finally {
            ((AtomicBoolean) flag.get(service)).set(false);
        }
    }

    @Test
    @DisplayName("派送被拒以 ACELIB-PLAYER-008 exceptional 完成")
    void dispatchRejected_completesExceptionally008() throws Exception {
        var field = PlayerDataService.class.getDeclaredField("serialStoreExecutor");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(service)).shutdownNow();

        PlayerStateException failure =
            joinFailure(service.getOfflineDataAsync(UUID.randomUUID()));
        assertEquals("ACELIB-PLAYER-008", failure.getCode());
    }

    @Test
    @DisplayName("不阻塞：呼叫立即回傳，讀取在背景 serial 執行緒完成")
    void nonBlocking_readRunsOnSerialThread() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "coins", 3)));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> readerThread = new AtomicReference<>();
        PlayerDataService gated = new PlayerDataService(
            new GatedLoadStore(openStore(), entered, release, readerThread),
            Runnable::run, MANUAL_INTERVAL_MS);
        try {
            String callerThread = Thread.currentThread().getName();
            CompletableFuture<Optional<Record>> future =
                gated.getOfflineDataAsync(uuid);

            assertTrue(entered.await(5, TimeUnit.SECONDS),
                "讀取必須在背景開始執行，而呼叫端不受阻");
            assertFalse(future.isDone(), "store 阻塞時 future 不得已完成");
            release.countDown();

            Optional<Record> result = future.get(5, TimeUnit.SECONDS);
            assertEquals(3, result.orElseThrow().getInt("coins", -1));
            assertTrue(readerThread.get().startsWith("acelib-player-store-serial-"),
                "讀取必須在內部 serial 執行緒執行，實際為：" + readerThread.get());
            assertTrue(!readerThread.get().equals(callerThread),
                "讀取執行緒不得是呼叫執行緒");
        } finally {
            release.countDown();
            gated.shutdown();
        }
    }

    @Test
    @DisplayName("null uuid 同步拒絕")
    void nullUuid_rejected() {
        assertThrows(NullPointerException.class,
            () -> service.getOfflineDataAsync(null));
    }

    @Test
    @DisplayName("舊 DataStore 建構的服務以 ACELIB-PLAYER-006 exceptional 完成")
    void legacyStore_completesExceptionally006() throws Exception {
        Path dataFile = tempDir.resolve("legacy.json");
        DataStore legacy = new JsonFileDataStore("legacy", dataFile,
            SchemaVersion.V1_0, new JsonCodecImpl());
        legacy.init();
        PlayerDataService legacyService =
            new PlayerDataService(legacy, Runnable::run);
        try {
            PlayerStateException failure =
                joinFailure(legacyService.getOfflineDataAsync(UUID.randomUUID()));
            assertEquals("ACELIB-PLAYER-006", failure.getCode());
        } finally {
            legacyService.shutdown();
            legacy.close();
        }
    }

    // -----------------------------------------------------------------
    // 測試替身
    // -----------------------------------------------------------------

    /** 讀取可注入失敗的 store 轉接（寫入原樣委派）。 */
    private static final class LoadFailingStore implements PlayerDataStore {

        private final PlayerDataStore target;
        private final AtomicBoolean fail;

        LoadFailingStore(PlayerDataStore target, AtomicBoolean fail) {
            this.target = target;
            this.fail = fail;
        }

        @Override
        public String name() {
            return "load-failing";
        }

        @Override
        public String owner() {
            return target.owner();
        }

        @Override
        public SchemaVersion schemaVersion() {
            return target.schemaVersion();
        }

        @Override
        public boolean isInitialized() {
            return target.isInitialized();
        }

        @Override
        public boolean isClosed() {
            return target.isClosed();
        }

        @Override
        public void init() {
            target.init();
        }

        @Override
        public Optional<Record> load(UUID uuid) {
            if (fail.get()) {
                throw new com.smile.acelib.data.DataStoreException(
                    "ACELIB-DATA-008", "injected load failure");
            }
            return target.load(uuid);
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            target.applyChanges(changes);
        }

        @Override
        public long revisionOf(UUID uuid, String field) {
            return target.revisionOf(uuid, field);
        }

        @Override
        public int fieldCount(UUID uuid) {
            return target.fieldCount(uuid);
        }

        @Override
        public void deletePlayer(UUID uuid) {
            target.deletePlayer(uuid);
        }

        @Override
        public void close() {
            target.close();
        }
    }

    /** 讀取時阻塞的 store 轉接，用來證明呼叫端不被阻塞。 */
    private static final class GatedLoadStore implements PlayerDataStore {

        private final PlayerDataStore target;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicReference<String> readerThread;

        GatedLoadStore(PlayerDataStore target, CountDownLatch entered,
                CountDownLatch release, AtomicReference<String> readerThread) {
            this.target = target;
            this.entered = entered;
            this.release = release;
            this.readerThread = readerThread;
        }

        @Override
        public String name() {
            return "gated-load";
        }

        @Override
        public String owner() {
            return target.owner();
        }

        @Override
        public SchemaVersion schemaVersion() {
            return target.schemaVersion();
        }

        @Override
        public boolean isInitialized() {
            return target.isInitialized();
        }

        @Override
        public boolean isClosed() {
            return target.isClosed();
        }

        @Override
        public void init() {
            target.init();
        }

        @Override
        public Optional<Record> load(UUID uuid) {
            readerThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "測試必須放行讀取");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return target.load(uuid);
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            target.applyChanges(changes);
        }

        @Override
        public long revisionOf(UUID uuid, String field) {
            return target.revisionOf(uuid, field);
        }

        @Override
        public int fieldCount(UUID uuid) {
            return target.fieldCount(uuid);
        }

        @Override
        public void deletePlayer(UUID uuid) {
            target.deletePlayer(uuid);
        }

        @Override
        public void close() {
            target.close();
        }
    }
}
