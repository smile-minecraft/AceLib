package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataService#getOfflineDataAsync(UUID)} 與生命週期的交錯測試。
 *
 * <p>以 latch 控制 store 阻塞，決定性重現：讀取阻塞期間的 {@code shutdown()}、
 * quit 保存進行期間的讀取、呼叫端取消 future 三種情境。</p>
 */
@DisplayName("PlayerDataService#getOfflineDataAsync（生命週期交錯）")
class PlayerDataServiceOfflineAsyncLifecycleTest {

    private static final long MANUAL_INTERVAL_MS = 60_000L;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private PlayerDataStore store;
    private final List<PlayerDataStore> extraStores = new ArrayList<>();
    private final List<PlayerDataService> extraServices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("players.db");
        store = openStore();
    }

    @AfterEach
    void tearDown() {
        for (PlayerDataService svc : extraServices) {
            if (!svc.isShutdown()) {
                try {
                    svc.shutdown();
                } catch (PlayerStateException ignored) {
                    // 測試/store 替身造成的關閉期保存失敗：不影響本次斷言。
                }
            }
        }
        for (PlayerDataStore opened : extraStores) {
            if (!opened.isClosed()) {
                opened.close();
            }
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

    private PlayerDataService newService(PlayerDataStore target) {
        PlayerDataService svc =
            new PlayerDataService(target, Runnable::run, MANUAL_INTERVAL_MS);
        extraServices.add(svc);
        return svc;
    }

    private static PlayerStateException awaitFailure(CompletableFuture<?> future,
            long timeoutSeconds) throws Exception {
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
            fail("預期 future 以失敗完成，但它成功了");
            throw new AssertionError("unreachable");
        } catch (ExecutionException ee) {
            assertTrue(ee.getCause() instanceof PlayerStateException,
                "async 讀取失敗必須以 PlayerStateException 表達，實際為："
                    + ee.getCause());
            return (PlayerStateException) ee.getCause();
        }
    }

    private static int inFlightCount(PlayerDataService svc) throws Exception {
        var field = PlayerDataService.class.getDeclaredField("inFlightOps");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(svc)).get();
    }

    private static void assertInFlightZero(PlayerDataService svc, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (inFlightCount(svc) != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(0, inFlightCount(svc), "in-flight 計數必須歸零（不得洩漏、不得重複扣回）");
    }

    @Test
    @DisplayName("讀取阻塞期間 shutdown：未完成的讀取以 008 完成、計數歸零")
    void shutdownDuringBlockedReads_unfinishedReadsComplete008() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(first, "coins", 1),
            PlayerDataStore.FieldChange.upsert(second, "coins", 2)));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlayerDataStore gatedStore = new GatedLoadStore(openStore(), entered, release);
        extraStores.add(gatedStore);
        PlayerDataService gated = newService(gatedStore);

        CompletableFuture<Void> shutdownDone = new CompletableFuture<>();
        try {
            CompletableFuture<Optional<Record>> read1 = gated.getOfflineDataAsync(first);
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                "第一個讀取必須已在 serial 執行緒開始（第二個才會排隊）");
            CompletableFuture<Optional<Record>> read2 = gated.getOfflineDataAsync(second);

            Thread shutdownThread = new Thread(() -> {
                try {
                    gated.shutdown();
                    shutdownDone.complete(null);
                } catch (Throwable failure) {
                    shutdownDone.completeExceptionally(failure);
                }
            }, "test-shutdown");
            shutdownThread.setDaemon(true);
            shutdownThread.start();

            PlayerStateException failure2 = awaitFailure(read2, 25);
            assertEquals("ACELIB-PLAYER-008", failure2.getCode(),
                "佇列中未執行的讀取不得永久 pending");
            PlayerStateException failure1 = awaitFailure(read1, 25);
            assertEquals("ACELIB-PLAYER-008", failure1.getCode(),
                "強制終止時仍被阻塞的讀取以 008 完成");
        } finally {
            release.countDown();
        }
        shutdownDone.get(30, TimeUnit.SECONDS);
        assertEquals(0, inFlightCount(gated), "每個提交的讀取必須恰好扣回一次");
    }

    @Test
    @DisplayName("quit 保存阻塞期間讀取：排隊等保存完成後拿到新值")
    void readDuringPendingQuit_waitsForSaveAndReadsPersisted() throws Exception {
        UUID uuid = UUID.randomUUID();
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch saveRelease = new CountDownLatch(1);
        PlayerDataStore gatedStore = new GatedSaveStore(openStore(), saveEntered, saveRelease);
        extraStores.add(gatedStore);
        PlayerDataService gated = newService(gatedStore);
        try {
            gated.onPlayerJoin(uuid, "p").join();
            gated.getData(uuid).orElseThrow().set("coins", 42);
            gated.markDirty(uuid);

            CompletableFuture<Void> quit = CompletableFuture.runAsync(
                () -> gated.onPlayerQuit(uuid).join());
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS),
                "quit 保存必須已在 serial 執行緒開始");
            CompletableFuture<Optional<Record>> read = gated.getOfflineDataAsync(uuid);

            saveRelease.countDown();
            quit.get(10, TimeUnit.SECONDS);
            Optional<Record> result = read.get(10, TimeUnit.SECONDS);
            assertTrue(result.isPresent());
            assertEquals(42, result.orElseThrow().getInt("coins", -1),
                "保存完成後的讀取必須拿到已持久化內容");
        } finally {
            saveRelease.countDown();
        }
    }

    @Test
    @DisplayName("呼叫端取消 future：讀取照跑、結果丟棄、計數歸零、executor 正常")
    void callerCancel_doesNotDisturbExecutor() throws Exception {
        UUID uuid = UUID.randomUUID();
        store.applyChanges(List.of(
            PlayerDataStore.FieldChange.upsert(uuid, "coins", 3)));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlayerDataStore gatedStore = new GatedLoadStore(openStore(), entered, release);
        extraStores.add(gatedStore);
        PlayerDataService gated = newService(gatedStore);
        try {
            CompletableFuture<Optional<Record>> read = gated.getOfflineDataAsync(uuid);
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                "讀取必須已在 serial 執行緒開始");
            assertFalse(read.isDone(), "store 阻塞時 future 不得已完成");
            assertTrue(read.cancel(false), "必須能取消尚未完成的讀取");
            assertTrue(read.isCancelled());

            release.countDown();
            assertInFlightZero(gated, 5_000);

            Optional<Record> again =
                gated.getOfflineDataAsync(uuid).get(5, TimeUnit.SECONDS);
            assertTrue(again.isPresent());
            assertEquals(3, again.orElseThrow().getInt("coins", -1),
                "取消不得影響 executor 後續讀取");
        } finally {
            release.countDown();
        }
    }

    // -----------------------------------------------------------------
    // 測試替身
    // -----------------------------------------------------------------

    /** 讀取時阻塞的 store 轉接（寫入原樣通過）。 */
    private static final class GatedLoadStore implements PlayerDataStore {

        private final PlayerDataStore target;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        GatedLoadStore(PlayerDataStore target, CountDownLatch entered,
                CountDownLatch release) {
            this.target = target;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String name() {
            return "gated-load-lifecycle";
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
            entered.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
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

    /** 寫入時阻塞的 store 轉接（讀取原樣通過）。 */
    private static final class GatedSaveStore implements PlayerDataStore {

        private final PlayerDataStore target;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        GatedSaveStore(PlayerDataStore target, CountDownLatch entered,
                CountDownLatch release) {
            this.target = target;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String name() {
            return "gated-save-lifecycle";
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
        public void applyChanges(List<FieldChange> changes) {
            entered.countDown();
            try {
                assertTrue(release.await(15, TimeUnit.SECONDS), "測試必須放行保存");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
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
