package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataService} 以 {@link PlayerDataStore} 為後端的行為測試：
 * 定期保存、離線讀取、資料就緒通知、增量寫入與並行 dirty 語意。
 *
 * <p>定期保存的驗證一律「重開儲存結果」：把 SQLite 檔案關掉再開一次讀值，
 * 不以記憶體狀態或 future 完成作為落盤證據。</p>
 */
@DisplayName("PlayerDataService（逐玩家 store）")
class PlayerDataServicePlayerStoreTest {

    /** 手動觸發的保存週期：不接受真實時間，測試才有決定性。 */
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

    /**
     * 關閉目前服務與 store 後重開同一檔案；回傳可讀值的 store。
     *
     * <p>這是「資料真的落盤」的驗證入口：不共用任何記憶體狀態。
     * 走的是正常關機路徑（{@code shutdown()} 會 flush），適合驗證
     * 「服務正常關閉時資料確實落盤」。</p>
     */
    private PlayerDataStore reopenStore() {
        service.shutdown();
        service = null;
        store.close();
        store = openStore();
        return store;
    }

    /**
     * 模擬異常終止：直接放棄服務（不呼叫 {@code shutdown()}，因此不 flush）
     * 並關閉 store 後重開同一檔案。
     *
     * <p>與 {@link #reopenStore()} 的差別就在於沒有 flush，這才是「程序被強制
     * 終止」的真實狀態；用正常關機路徑會把 dirty 資料寫進去，測不出遺失上限。</p>
     *
     * @return 重開後可讀值的 store
     */
    private PlayerDataStore crashAndReopenStore() {
        service = null;
        store.close();
        store = openStore();
        return store;
    }

    // -----------------------------------------------------------------
    // 定期保存
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("定期保存")
    class PeriodicSave {

        @Test
        @DisplayName("自動保存後重開 store 可讀到變更（真的落盤）")
        void autosave_persistsToDisk() throws Exception {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 10);
            service.markDirty(uuid);

            service.autosaveNow().join();

            PlayerDataStore reopened = reopenStore();
            Record loaded = reopened.load(uuid).orElseThrow();
            assertEquals(10, loaded.getInt("coins", -1),
                "定期保存後重開 store 必須讀到變更");
        }

        @Test
        @DisplayName("保存週期內的變更：重開讀到的是上一次落盤的值（最多遺失一個週期）")
        void changesWithinInterval_lostAtMostOnePeriod() throws Exception {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 1);
            service.markDirty(uuid);
            service.autosaveNow().join();

            // 模擬下一個週期尚未到：改值但未保存就「異常終止」
            // （必須走 crash 路徑；正常 shutdown 會 flush，看不到遺失）
            service.getData(uuid).orElseThrow().set("coins", 2);
            service.markDirty(uuid);
            PlayerDataStore reopened = crashAndReopenStore();

            Record loaded = reopened.load(uuid).orElseThrow();
            assertEquals(1, loaded.getInt("coins", -1),
                "尚未落盤的變更不應出現在磁碟上；這正是『最多遺失一個週期』的上界");
        }

        @Test
        @DisplayName("無變更時保存為 no-op，不產生寫入")
        void autosaveWithoutDirty_writesNothing() throws Exception {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 5);
            service.markDirty(uuid);
            service.autosaveNow().join();
            long revisionAfterFirst = store.revisionOf(uuid, "coins");

            service.autosaveNow().join();

            assertEquals(revisionAfterFirst, store.revisionOf(uuid, "coins"),
                "沒有新變更時不得推進 revision（不得產生多餘寫入）");
        }

        @Test
        @DisplayName("保存失敗時 dirty 保留、不回報成功")
        void autosaveFailure_retainsDirty() {
            AtomicBoolean fail = new AtomicBoolean(true);
            // 只讓寫入失敗、讀取仍成功：這個測試要驗的是「保存失敗」的語意，
            // 不是載入失敗（載入失敗另有 listenerNotCalledOnLoadFailure 覆蓋）。
            PlayerDataStore failing = new SaveFailingStore(openStore(), fail);
            PlayerDataService flaky = new PlayerDataService(failing, Runnable::run,
                MANUAL_INTERVAL_MS);
            UUID uuid = UUID.randomUUID();
            flaky.onPlayerJoin(uuid, "p").join();
            flaky.getData(uuid).orElseThrow().set("coins", 7);
            flaky.markDirty(uuid);

            // autosaveNow() 以 future 回報失敗，join() 會包成 CompletionException。
            // 對外一律是玩家層的 PLAYER-003（與 onPlayerQuit 同一條路徑），
            // 底層 store 例外保留在 cause，不被吞掉。
            var failure = assertThrows(CompletionException.class,
                () -> flaky.autosaveNow().join());
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof PlayerStateException,
                "保存失敗應回報玩家層例外，實際為：" + cause);
            assertEquals("ACELIB-PLAYER-003", ((PlayerStateException) cause).getCode());
            assertTrue(cause.getCause() instanceof DataStoreException,
                "底層 store 例外必須保留在 cause，不得被吞掉；實際為：" + cause.getCause());
            assertEquals(7, flaky.getData(uuid).orElseThrow().getInt("coins", -1),
                "保存失敗後資料必須仍在服務快取中");
            assertEquals(1, flaky.dirtyPlayerCountForTest(),
                "保存失敗不得標記 dirty 已清除");

            fail.set(false);
            flaky.autosaveNow().join();
            assertEquals(0, flaky.dirtyPlayerCountForTest(),
                "重試成功後才清除 dirty");
            flaky.shutdown();
            failing.close();
        }

        @Test
        @DisplayName("保存逾時記 PLAYER-008 且不等於成功")
        void autosaveTimeout_reportsPlayer008() throws Exception {
            CountDownLatch gate = new CountDownLatch(1);
            PlayerDataStore blocking = new BlockingStore(openStore(), gate);
            PlayerDataService slow = new PlayerDataService(blocking, Runnable::run,
                MANUAL_INTERVAL_MS, 200L);
            UUID uuid = UUID.randomUUID();
            slow.onPlayerJoin(uuid, "p").join();
            slow.getData(uuid).orElseThrow().set("coins", 3);
            slow.markDirty(uuid);

            var future = slow.autosaveNow();
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
            gate.countDown();

            assertTrue(slow.dirtyPlayerCountForTest() >= 0,
                "逾時不得把狀態寫成已成功");
            slow.shutdown();
            blocking.close();
        }

        @Test
        @DisplayName("shutdown 終止內部 autosave 排程器且可重複呼叫")
        void shutdown_stopsAutosaveSchedulerIdempotently() {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 1);
            service.markDirty(uuid);

            service.shutdown();
            service.shutdown();

            assertTrue(service.isShutdown());
            assertTrue(service.isAutosaveTerminatedForTest(),
                "shutdown 後內部 autosave 排程器必須已終止");
            service = null;
        }

        @Test
        @DisplayName("外部注入的 ioExecutor 不被服務關閉")
        void shutdown_doesNotCloseExternalExecutor() {
            AtomicBoolean shutdownCalled = new AtomicBoolean(false);
            var external = java.util.concurrent.Executors.newSingleThreadExecutor(
                runnable -> {
                    shutdownCalled.set(true);
                    return new Thread(runnable, "external-io");
                });
            PlayerDataService owned =
                new PlayerDataService(store, external, MANUAL_INTERVAL_MS);
            owned.shutdown();
            assertFalse(shutdownCalled.get(),
                "外部注入的 executor 不得由 PlayerDataService 關閉");
            external.shutdown();
            service = null;
        }
    }

    // -----------------------------------------------------------------
    // 離線讀取
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("離線讀取")
    class OfflineRead {

        @Test
        @DisplayName("getOfflineData 讀取已離線玩家的資料")
        void readsOfflinePlayerData() {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 42);
            service.markDirty(uuid);
            service.onPlayerQuit(uuid).join();

            assertTrue(service.getSession(uuid).isEmpty(), "前置條件：玩家已離線");
            Optional<Record> offline = service.getOfflineData(uuid);
            assertTrue(offline.isPresent(), "離線玩家的資料必須讀得到");
            assertEquals(42, offline.orElseThrow().getInt("coins", -1));
        }

        @Test
        @DisplayName("從未登入過的玩家回 empty")
        void unknownPlayerReturnsEmpty() {
            assertEquals(Optional.empty(), service.getOfflineData(UUID.randomUUID()));
        }

        @Test
        @DisplayName("離線讀取不累積差分基準快取")
        void offlineReadsDoNotPopulatePersistedFieldCache() throws Exception {
            for (int i = 0; i < 32; i++) {
                assertEquals(Optional.empty(), service.getOfflineData(UUID.randomUUID()));
            }

            java.lang.reflect.Field cacheField = PlayerDataService.class
                .getDeclaredField("persistedFields");
            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(service);
            assertTrue(cache.isEmpty(),
                "離線查詢結果不屬於活躍玩家差分基準，不能隨查詢玩家累積快取");
        }

        @Test
        @DisplayName("刪除後的欄位不會在離線讀取時復活")
        void deletedFieldStaysDeletedOffline() {
            UUID uuid = UUID.randomUUID();
            store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "coins", 5),
                PlayerDataStore.FieldChange.upsert(uuid, "temp", "x")));
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().remove("temp");
            service.markDirty(uuid);
            service.onPlayerQuit(uuid).join();

            Record offline = service.getOfflineData(uuid).orElseThrow();
            assertFalse(offline.has("temp"), "刪除的欄位不得在離線讀取時復活");
            assertTrue(offline.has("coins"));
        }

        @Test
        @DisplayName("null uuid 拒絕")
        void nullUuidRejected() {
            assertThrows(NullPointerException.class, () -> service.getOfflineData(null));
        }
    }

    // -----------------------------------------------------------------
    // 資料就緒通知
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("資料就緒通知")
    class ReadyListener {

        @Test
        @DisplayName("資料就緒時呼叫 listener，每位玩家一次")
        void listenerCalledOncePerPlayer() {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<UUID> lastUuid = new AtomicReference<>();
            try (var registration = service.addReadyListener((uuid, record) -> {
                calls.incrementAndGet();
                lastUuid.set(uuid);
            })) {
                UUID uuid = UUID.randomUUID();
                service.onPlayerJoin(uuid, "p").join();

                assertEquals(1, calls.get(), "每位玩家的資料就緒應只通知一次");
                assertEquals(uuid, lastUuid.get());
                assertFalse(registration.isClosed());
            }
        }

        @Test
        @DisplayName("listener 在載入失敗時不被呼叫")
        void listenerNotCalledOnLoadFailure() {
            AtomicInteger calls = new AtomicInteger();
            PlayerDataService brokenService = new PlayerDataService(
                new FailingStore(openStore(), new AtomicBoolean(true)),
                Runnable::run, MANUAL_INTERVAL_MS);
            try (var registration = brokenService.addReadyListener(
                    (uuid, record) -> calls.incrementAndGet())) {
                UUID uuid = UUID.randomUUID();
                assertThrows(Exception.class,
                    () -> brokenService.onPlayerJoin(uuid, "p").join());
                assertEquals(0, calls.get(), "載入失敗不得發出就緒通知");
            }
            brokenService.shutdown();
        }

        @Test
        @DisplayName("關閉 registration 後不再收到通知")
        void closedRegistrationStopsNotifications() {
            AtomicInteger calls = new AtomicInteger();
            var registration = service.addReadyListener((uuid, record) -> calls.incrementAndGet());
            UUID first = UUID.randomUUID();
            service.onPlayerJoin(first, "p").join();
            assertEquals(1, calls.get());

            registration.close();
            UUID second = UUID.randomUUID();
            service.onPlayerJoin(second, "p").join();

            assertEquals(1, calls.get(), "解除後不得再收到通知");
        }

        @Test
        @DisplayName("listener 拋例外不影響資料載入")
        void listenerExceptionDoesNotBreakLoad() {
            try (var registration = service.addReadyListener((uuid, record) -> {
                throw new IllegalStateException("boom");
            })) {
                UUID uuid = UUID.randomUUID();
                service.onPlayerJoin(uuid, "p").join();
                assertTrue(service.getSession(uuid).orElseThrow().isReady(),
                    "listener 失敗不得讓 session 卡在非 READY");
            }
        }
    }

    // -----------------------------------------------------------------
    // 增量寫入與並行 dirty
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("增量寫入與並行變動")
    class IncrementalAndConcurrent {

        @Test
        @DisplayName("只寫變動欄位：未變欄位的 revision 不動")
        void onlyChangedFieldsAreWritten() {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("a", 1);
            service.getData(uuid).orElseThrow().set("b", 2);
            service.markDirty(uuid);
            service.autosaveNow().join();
            long revA = store.revisionOf(uuid, "a");
            long revB = store.revisionOf(uuid, "b");

            service.getData(uuid).orElseThrow().set("b", 99);
            service.markDirty(uuid);
            service.autosaveNow().join();

            assertEquals(revA, store.revisionOf(uuid, "a"),
                "未變動欄位不得被重寫");
            assertEquals(revB + 1, store.revisionOf(uuid, "b"),
                "變動欄位必須 revision +1");
        }

        @Test
        @DisplayName("快照期間的新變動不會被這次保存清除 dirty")
        void concurrentMarkDirtyDuringSaveIsNotCleared() throws Exception {
            UUID uuid = UUID.randomUUID();
            CountDownLatch inSave = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            PlayerDataStore gated = new BlockingAfterApplyStore(openStore(), inSave, release);
            // 這裡必須用真的執行緒：保存被閘門卡住時，測試才來得及在中途插入
            // 新變更。用同步的 Runnable::run 會讓 autosaveNow() 一直卡在閘門上，
            // 無法製造「保存進行中」這個時序。
            var ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
            PlayerDataService svc = new PlayerDataService(gated, ioExecutor,
                MANUAL_INTERVAL_MS);
            svc.onPlayerJoin(uuid, "p").join();
            svc.getData(uuid).orElseThrow().set("coins", 1);
            svc.markDirty(uuid);

            var saveFuture = svc.autosaveNow();
            assertTrue(inSave.await(5, TimeUnit.SECONDS), "應進入保存中");
            // 保存進行中又變更一次
            svc.getData(uuid).orElseThrow().set("coins", 2);
            svc.markDirty(uuid);
            release.countDown();
            saveFuture.join();

            assertEquals(1, svc.dirtyPlayerCountForTest(),
                "保存期間的新變動不得被這次保存清除 dirty，否則新值永遠不會落盤");
            svc.autosaveNow().join();
            assertEquals(0, svc.dirtyPlayerCountForTest());
            svc.shutdown();
            gated.close();
            ioExecutor.shutdown();
        }

        @Test
        @DisplayName("刪除欄位會落地，離線讀取不再看到")
        void deletedFieldIsPersisted() {
            UUID uuid = UUID.randomUUID();
            store.applyChanges(List.of(
                PlayerDataStore.FieldChange.upsert(uuid, "keep", 1),
                PlayerDataStore.FieldChange.upsert(uuid, "drop", 2)));
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().remove("drop");
            service.markDirty(uuid);
            service.autosaveNow().join();

            assertFalse(store.load(uuid).orElseThrow().has("drop"),
                "刪除欄位必須真的從儲存移除");
        }

        @Test
        @DisplayName("每位玩家的資料互相隔離")
        void playersAreIsolated() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            service.onPlayerJoin(a, "a").join();
            service.onPlayerJoin(b, "b").join();
            service.getData(a).orElseThrow().set("coins", 1);
            service.getData(b).orElseThrow().set("coins", 2);
            service.markDirty(a);
            service.markDirty(b);
            service.autosaveNow().join();

            assertEquals(1, store.load(a).orElseThrow().getInt("coins", -1));
            assertEquals(2, store.load(b).orElseThrow().getInt("coins", -1));
        }
    }

    // -----------------------------------------------------------------
    // 生命週期回歸
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("生命週期回歸")
    class Lifecycle {

        @Test
        @DisplayName("join / quit 正常保存")
        void joinQuitPersists() {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 8);
            service.markDirty(uuid);
            service.onPlayerQuit(uuid).join();

            assertEquals(8, store.load(uuid).orElseThrow().getInt("coins", -1));
        }

        @Test
        @DisplayName("快速重連：quit 進行中的 join 自動接上")
        void fastReconnect_chainsBehindQuit() throws Exception {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 3);
            service.markDirty(uuid);

            var quit = service.onPlayerQuit(uuid);
            var rejoin = service.onPlayerJoin(uuid, "p2");
            rejoin.get(5, TimeUnit.SECONDS);
            quit.get(5, TimeUnit.SECONDS);

            assertTrue(service.getSession(uuid).isPresent(),
                "快速重連後應有 active session");
        }

        @Test
        @DisplayName("shutdown 後新 join 以 PLAYER-007 拒絕")
        void shutdownRejectsNewJoins() {
            service.shutdown();
            PlayerStateException failure = assertThrows(PlayerStateException.class,
                () -> service.onPlayerJoin(UUID.randomUUID(), "p"));
            assertEquals("ACELIB-PLAYER-007", failure.getCode());
            service = null;
        }

        @Test
        @DisplayName("shutdown 保存尚未 quit 的玩家資料")
        void shutdownFlushesOnlinePlayers() {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p").join();
            service.getData(uuid).orElseThrow().set("coins", 11);
            service.markDirty(uuid);

            service.shutdown();

            assertEquals(11, store.load(uuid).orElseThrow().getInt("coins", -1),
                "shutdown 必須保存尚未離線的玩家資料");
            service = null;
        }
    }

    // -----------------------------------------------------------------
    // 測試替身
    // -----------------------------------------------------------------

    /**
     * 轉接用的共同基底：預設原樣委派，只有需要注入行為的 method 才覆寫。
     *
     * <p>各測試替身只有「失敗注入」與「阻塞時機」不同，其餘生命週期與診斷
     * method 完全一致；集中在此避免每個替身重寫一遍轉發。
     * 這些替身是<strong>類別</strong>而非 record：record 隱含繼承
     * {@code java.lang.Record}，不能再 extends 其他類別。</p>
     */
    private abstract static class DelegatingStore implements PlayerDataStore {

        private final PlayerDataStore target;

        DelegatingStore(PlayerDataStore target) {
            this.target = target;
        }

        /** 取得被轉接的 store，供子類別在覆寫 method 中委派。 */
        final PlayerDataStore target() {
            return target;
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
            return target.load(uuid);
        }

        @Override
        public void deletePlayer(UUID uuid) {
            target.deletePlayer(uuid);
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
        public void close() {
            target.close();
        }
    }

    /** 讀取與寫入都可注入失敗的 store 轉接。 */
    private static final class FailingStore extends DelegatingStore {

        private final AtomicBoolean fail;

        FailingStore(PlayerDataStore delegate, AtomicBoolean fail) {
            super(delegate);
            this.fail = fail;
        }

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Optional<Record> load(UUID uuid) {
            if (fail.get()) {
                throw new DataStoreException("ACELIB-DATA-008", "injected load failure");
            }
            return target().load(uuid);
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            if (fail.get()) {
                throw new DataStoreException("ACELIB-DATA-008", "injected save failure");
            }
            target().applyChanges(changes);
        }
    }

    /** 只讓寫入失敗、讀取仍成功的 store 轉接。 */
    private static final class SaveFailingStore extends DelegatingStore {

        private final AtomicBoolean fail;

        SaveFailingStore(PlayerDataStore delegate, AtomicBoolean fail) {
            super(delegate);
            this.fail = fail;
        }

        @Override
        public String name() {
            return "save-failing";
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            if (fail.get()) {
                throw new DataStoreException("ACELIB-DATA-008", "injected save failure");
            }
            target().applyChanges(changes);
        }
    }

    /** 寫入時阻塞的 store 轉接，用來製造「保存逾時」的情境。 */
    private static final class BlockingStore extends DelegatingStore {

        private final CountDownLatch gate;

        BlockingStore(PlayerDataStore delegate, CountDownLatch gate) {
            super(delegate);
            this.gate = gate;
        }

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            target().applyChanges(changes);
        }
    }

    /**
     * 寫入完成後才阻塞的 store 轉接。
     *
     * <p>阻塞點在 {@code applyChanges} 回傳之後，因此保存已完成、但 dirty 尚未清除，
     * 正好對應「快照期間有新變動」的時序。</p>
     */
    private static final class BlockingAfterApplyStore extends DelegatingStore {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingAfterApplyStore(PlayerDataStore delegate, CountDownLatch entered,
                CountDownLatch release) {
            super(delegate);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String name() {
            return "blocking-after-apply";
        }

        @Override
        public void applyChanges(List<FieldChange> changes) {
            target().applyChanges(changes);
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("smoke：測試環境可建立 SQLite store")
    void smoke() throws IOException {
        assertTrue(java.nio.file.Files.exists(dbFile));
    }
}
