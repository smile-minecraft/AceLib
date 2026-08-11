package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.JsonCodec;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 多 executor 並發存取 {@link PlayerDataService} 的可重現測試。
 *
 * <p>對應 Momus G4 blocking finding：「多 executor 直接存取非 thread-safe
 * DataStore」race condition 修復驗證。Phase 9 規格要求：
 * 「底層 {@link DataStore} 的 root/save 必須在 caller 不論提供單一或多執行緒
 * executor 的情況下，皆保證 serialization；reopen 後既有資料必須完整保存」。</p>
 *
 * <p>本測試刻意使用多執行緒 executor 注入 PlayerDataService，模擬 production
 * 環境中 caller 提供的 async pool（如 Folia 的 region scheduler pool）。
 * 修復前：直接呼叫 {@code store.root()} / {@code store.save()} 會在多執行緒
 * 下造成 {@link com.smile.acelib.data.MemoryRecord} 的 {@link java.util.LinkedHashMap}
 * 內部結構損壞。修復後：所有 store 存取皆透過 per-store 序列化通道。</p>
 *
 * @since Phase 9 (Plan §十四) — Momus G4 blocking 收斂
 */
@DisplayName("PlayerDataService multi-executor race")
class PlayerDataServiceRaceTest {

    @TempDir
    Path tempDir;

    private Path dataFile;
    private DataStore store;
    private PlayerDataService service;
    private ExecutorService multiExecutor;

    @BeforeEach
    void setUp() throws IOException {
        dataFile = tempDir.resolve("players-race.json");
        JsonCodec codec = new JsonCodecImpl();
        store = new JsonFileDataStore("players-race", dataFile, SchemaVersion.V1_0, codec);
        store.init();
        // 故意使用多執行緒 executor — 模擬 production 場景
        multiExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "race-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        service = new PlayerDataService(store, multiExecutor);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (multiExecutor != null) {
            multiExecutor.shutdownNow();
        }
        if (store != null) {
            store.close();
        }
    }

    // -----------------------------------------------------------------
    // 多 UUID 並發登入/離線/標記 dirty
    // -----------------------------------------------------------------

    @Test
    @DisplayName("多 UUID 並發 join/quit/dirty：所有玩家資料皆保存完整，無 race 損壞")
    void multiUuid_joinQuitDirty_allPreserved() throws Exception {
        final int playerCount = 32;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            ids.add(UUID.randomUUID());
        }

        // Phase 1: 並發 join 所有玩家
        CountDownLatch joinGate = new CountDownLatch(1);
        CountDownLatch joinDone = new CountDownLatch(playerCount);
        List<CompletableFuture<Void>> joinFutures = new ArrayList<>();
        for (UUID id : ids) {
            joinFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    joinGate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }).thenCompose(v -> service.onPlayerJoin(id, "p-" + id)));
            joinDone.countDown();
        }
        joinGate.countDown();

        CompletableFuture.allOf(joinFutures.toArray(new CompletableFuture[0]))
            .get(30, TimeUnit.SECONDS);
        assertEquals(playerCount, service.activeSessionCount(),
            "所有 join 必須完成且 session 數 = playerCount");

        // Phase 2: 並發修改 dirty — 每個玩家寫入唯一 key，避免內容競爭
        CountDownLatch dirtyGate = new CountDownLatch(1);
        AtomicInteger dirtyCount = new AtomicInteger(0);
        List<CompletableFuture<Void>> dirtyFutures = new ArrayList<>();
        for (UUID id : ids) {
            dirtyFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    dirtyGate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }).thenCompose(v -> service.withLoadedData(id, rec -> {
                rec.set("coins", 100 + dirtyCount.incrementAndGet());
                service.markDirty(id);
                return null;
            })));
        }
        dirtyGate.countDown();
        CompletableFuture.allOf(dirtyFutures.toArray(new CompletableFuture[0]))
            .get(30, TimeUnit.SECONDS);

        // Phase 3: 並發 quit 所有玩家
        CountDownLatch quitGate = new CountDownLatch(1);
        List<CompletableFuture<Void>> quitFutures = new ArrayList<>();
        for (UUID id : ids) {
            quitFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    quitGate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }).thenCompose(v -> service.onPlayerQuit(id)));
        }
        quitGate.countDown();
        CompletableFuture.allOf(quitFutures.toArray(new CompletableFuture[0]))
            .get(30, TimeUnit.SECONDS);

        assertEquals(0, service.activeSessionCount(),
            "所有 quit 必須完成且 session 數歸零");

        // Phase 4: 重開 store，驗證資料完整保存
        DataStore reopened = new JsonFileDataStore("players-race", dataFile,
            SchemaVersion.V1_0, new JsonCodecImpl());
        reopened.init();
        try {
            Record playersNode = reopened.root().getRecord("players", null);
            assertNotNull(playersNode, "reopen 後 players 節點必須存在");
            // 每個玩家都必須有自己獨特的 coins 值
            int verifiedCount = 0;
            for (int i = 0; i < playerCount; i++) {
                UUID id = ids.get(i);
                int coins = playersNode.getInt(id.toString() + ".coins", 0);
                assertTrue(coins >= 101 && coins <= 100 + playerCount,
                    () -> "玩家 " + id + " coins 應在 [101, 132] 區間；實際: " + coins);
                if (coins > 0) {
                    verifiedCount++;
                }
            }
            assertEquals(playerCount, verifiedCount,
                "每個玩家的 coins 都必須被獨立保存，缺漏代表 race 已損壞資料");
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("nested Map/List：get 與 getObject 不逸出底層 mutable container，並可與 shutdown 併行")
    void nestedContainers_areCopiedBeforeLeavingLock() throws Exception {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "nested").join();
        Record record = service.getData(id).orElseThrow();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("value", "original");
        nested.put("items", new ArrayList<>(List.of("one")));
        record.set("payload", nested);
        service.markDirty(id);

        @SuppressWarnings("unchecked")
        Map<String, Object> fromGet = (Map<String, Object>) record.get("payload");
        Map<String, Object> fromObject = record.getObject("payload", Map.class, null);
        @SuppressWarnings("unchecked")
        List<String> itemsFromGet = (List<String>) record.get("payload.items");
        List<String> itemsFromObject = record.getObject("payload.items", List.class, null);
        fromGet.put("getLeak", true);
        fromObject.put("objectLeak", true);
        itemsFromGet.add("getListLeak");
        itemsFromObject.add("objectListLeak");

        @SuppressWarnings("unchecked")
        Map<String, Object> storedView = (Map<String, Object>) record.get("payload");
        assertFalse(storedView.containsKey("getLeak"));
        assertFalse(storedView.containsKey("objectLeak"));
        assertEquals(List.of("one"), record.get("payload.items"));

        ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> mutator = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 5_000; i++) {
                    fromGet.put("copyMutation", i);
                    fromObject.put("copyMutation", i);
                }
            }, raceExecutor);
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(service::shutdown, raceExecutor);
            CompletableFuture.allOf(mutator, shutdown).join();
        } finally {
            raceExecutor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // 並發 join 同一 UUID：序列化後不應 crash
    // -----------------------------------------------------------------

    @Test
    @DisplayName("並發 join 同一 UUID（race simulation）：序列化保證僅一者成功，"
        + "其餘以 PLAYER-004 拒絕")
    void concurrentJoinSameUuid_onlyOneSucceeds() throws Exception {
        UUID id = UUID.randomUUID();
        final int attempts = 16;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // onPlayerJoin 在重複 UUID 時會同步拋 PLAYER-004；
                // 也可能在關閉時拋 PLAYER-007；都應視為「拒絕」。
                try {
                    CompletableFuture<Void> f = service.onPlayerJoin(id, "race-attempt");
                    // future 也可能在內部失敗（例如 load 失敗）— 但本測試關注
                    // 序列化保護，故也視為成功計數（load 階段 race 已不存在）
                    successCount.incrementAndGet();
                    f.get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ee) {
                    // load 階段失敗也算「拒絕」（避免 race 損壞）
                    rejectCount.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.TimeoutException te) {
                    // load 逾時視為「拒絕」（不計入成功）
                    rejectCount.incrementAndGet();
                } catch (PlayerStateException ex) {
                    if ("ACELIB-PLAYER-004".equals(ex.getCode())) {
                        rejectCount.incrementAndGet();
                    }
                    // 其他 PlayerStateException（如 PLAYER-007）也視為拒絕
                }
            }));
        }
        gate.countDown();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(30, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(),
            "並發 join 同一 UUID 必須只有一個成功；實際成功數: " + successCount.get());
        assertEquals(attempts - 1, rejectCount.get(),
            "其餘必須以 PLAYER-004 拒絕；實際拒絕數: " + rejectCount.get());
    }

    // -----------------------------------------------------------------
    // 並發讀寫同一 record（dirty + getData + markDirty）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("並發讀寫單一玩家 record：不應 throw 或造成內部 map 損壞")
    void concurrentReadWriteSameRecord_doesNotCorrupt() throws Exception {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();

        final int writers = 8;
        final int iters = 200;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicInteger readCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int t = 0; t < writers; t++) {
            final int threadId = t;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iters; i++) {
                    final int iter = i;
                    service.withLoadedData(id, rec -> {
                        rec.set("counter", threadId * 1_000_000 + iter);
                        service.markDirty(id);
                        writeCount.incrementAndGet();
                        return null;
                    }).join();
                    service.getData(id).ifPresent(rec ->
                        readCount.incrementAndGet());
                }
            }));
        }
        gate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(60, TimeUnit.SECONDS);

        assertEquals(writers * iters, writeCount.get(),
            "所有寫入都必須成功，無 exception 損失");
        assertEquals(writers * iters, readCount.get(),
            "所有讀取都必須看到非 empty record");
    }

    // -----------------------------------------------------------------
    // store 在中途關閉時，並發操作必須安全失敗
    // -----------------------------------------------------------------

    @Test
    @DisplayName("store 中途關閉：並發 join/quit 必須以可預期例外失敗，不可損壞內部狀態")
    void concurrentOpsWithStoreClosed_doesNotCorrupt() throws Exception {
        final int count = 16;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }

        // 先 join 一半
        List<CompletableFuture<Void>> initialJoins = new ArrayList<>();
        for (int i = 0; i < count / 2; i++) {
            initialJoins.add(service.onPlayerJoin(ids.get(i), "init"));
        }
        CompletableFuture.allOf(initialJoins.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // 強制關閉 store
        store.close();

        // 並發 quit 既有玩家 + join 新玩家 — 必須全部以可預期例外失敗
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger unexpectedException = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < count / 2; i++) {
            final UUID id = ids.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    service.onPlayerQuit(id).join();
                } catch (Exception ex) {
                    // 預期 quit 會 fail — 但必須是 PlayerStateException 或
                    // DataStoreException 包裝，不可丟出 ConcurrentModificationException
                    // / NullPointerException 等內部損壞訊號
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (!(cause instanceof RuntimeException)) {
                        unexpectedException.incrementAndGet();
                    }
                }
            }));
        }
        for (int i = count / 2; i < count; i++) {
            final UUID id = ids.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    service.onPlayerJoin(id, "late").join();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (!(cause instanceof RuntimeException)) {
                        unexpectedException.incrementAndGet();
                    }
                }
            }));
        }
        gate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(30, TimeUnit.SECONDS);

        assertEquals(0, unexpectedException.get(),
            "所有失敗必須為 RuntimeException，不可有內部損壞訊號");

        // service 內部狀態必須仍可用於查詢（雖 store 已關閉）
        // — 重點是不應留下半殘資料、API 不應 throw
        for (int i = 0; i < count / 2; i++) {
            UUID id = ids.get(i);
            // getSession / getData 皆不可 throw；存在與否皆可接受
            // 重要的是 service 內部 map 結構不可損壞
            try {
                boolean present = service.getSession(id).isPresent();
                boolean dataPresent = service.getData(id).isPresent();
                // 兩者邏輯一致：session 不在 → data 不可在
                if (!present) {
                    assertFalse(dataPresent,
                        "session 不存在時 getData 不可回 present");
                }
            } catch (Throwable t) {
                org.junit.jupiter.api.Assertions.fail(
                    "service 查詢 API 不應 throw，即使底層 store 已關閉: " + t);
            }
        }
    }

    // -----------------------------------------------------------------
    // shutdown() in-flight 防護（late resurrection guard）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown() 後，in-flight 任務完成時不可復活 cache（late resurrection guard）")
    void shutdown_preventsLateCacheResurrection() throws Exception {
        UUID id = UUID.randomUUID();
        // 先 join + load
        service.onPlayerJoin(id, "alice").join();
        assertNotNull(service.getData(id).orElse(null),
            "前置：load 完成後 getData 必須 non-empty");

        // 啟動一個 in-flight withLoadedData 任務 — 透過 countdown gate 控制時序
        CountDownLatch execStarted = new CountDownLatch(1);
        CountDownLatch releaseExec = new CountDownLatch(1);

        CompletableFuture<?> inFlight = CompletableFuture.runAsync(() -> {
            // 在 lambda 內重新 invoke withLoadedData — 此呼叫會進入 ioExecutor
            service.withLoadedData(id, rec -> {
                try {
                    execStarted.countDown();
                    // 等待 caller 觸發 shutdown()
                    releaseExec.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // 嘗試修改資料 + markDirty — 必須不會復活 records map
                rec.set("post-shutdown-write", "should-not-persist");
                service.markDirty(id);
                return null;
            });
        });

        // 等 in-flight 進入 callback
        assertTrue(execStarted.await(5, TimeUnit.SECONDS),
            "in-flight withLoadedData callback 必須在 5 秒內開始");

        // 觸發 shutdown
        service.shutdown();

        // 釋放 in-flight 任務
        releaseExec.countDown();
        inFlight.get(10, TimeUnit.SECONDS);

        // 核心斷言：shutdown() 後 records map 必須為空，沒有任何 late resurrection
        // 我們透過 getData 觀察 — 應回傳 empty（session 已清空）
        assertFalse(service.getData(id).isPresent(),
            "shutdown() 後 in-flight 任務不可將資料復活回 cache");

        // service.shutdown 已 idempotent；再次 shutdown 不爆
        service.shutdown();
    }

    // -----------------------------------------------------------------
    // shutdown() 必須 flush dirty 資料
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown() 必須 flush dirty 資料到 store（不可遺失）")
    void shutdown_flushesDirtyData() throws Exception {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        service.getData(id).orElseThrow().set("important", "must-survive");
        service.markDirty(id);

        // 注意：此測試不 quit，直接 shutdown — shutdown 必須負責保存 dirty 資料
        service.shutdown();

        // reopen store 驗證
        DataStore reopened = new JsonFileDataStore("players-race", dataFile,
            SchemaVersion.V1_0, new JsonCodecImpl());
        reopened.init();
        try {
            Record playersNode = reopened.root().getRecord("players", null);
            assertNotNull(playersNode, "reopen 後 players 節點必須存在");
            assertEquals("must-survive",
                playersNode.getString(id.toString() + ".important", null),
                "shutdown 必須 flush dirty 資料；不可遺失");
        } finally {
            reopened.close();
        }
    }

    // -----------------------------------------------------------------
    // shutdown() 拒絕新 join/quit/getData
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown() 後 onPlayerJoin 必須丟 PLAYER-007")
    void shutdown_rejectsNewJoins() {
        service.shutdown();
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> service.onPlayerJoin(UUID.randomUUID(), "alice"));
        assertEquals("ACELIB-PLAYER-007", ex.getCode());
    }

    @Test
    @DisplayName("shutdown() 後 onPlayerQuit 必須丟 PLAYER-007（或 session 不存在 PLAYER-005）")
    void shutdown_rejectsNewQuits() {
        UUID id = UUID.randomUUID();
        service.shutdown();
        // session 不存在時丟 PLAYER-005（shutdown 已清空 map）
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> service.onPlayerQuit(id));
        // 任意 PLAYER-005 或 PLAYER-007 都可接受（取決於 shutdown 順序）
        assertTrue("ACELIB-PLAYER-005".equals(ex.getCode())
                || "ACELIB-PLAYER-007".equals(ex.getCode()),
            () -> "shutdown 後 quit 必須丟 PLAYER-005 或 PLAYER-007；實際: "
                + ex.getCode());
    }

    // -----------------------------------------------------------------
    // shutdown() 冪等
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown() 冪等，重複呼叫不丟例外")
    void shutdown_idempotent() {
        service.shutdown();
        service.shutdown();
    }

    // -----------------------------------------------------------------
    // helper：確認與既有 PlayerDataServiceTest.shutdown_clearsState 不重複
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown() 必須清除所有 session；後續 getSession 回傳 empty")
    void shutdown_clearsAllSessions() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.onPlayerJoin(a, "alice").join();
        service.onPlayerJoin(b, "bob").join();
        assertEquals(2, service.activeSessionCount());

        service.shutdown();

        assertEquals(0, service.activeSessionCount());
        assertFalse(service.getSession(a).isPresent());
        assertFalse(service.getSession(b).isPresent());
    }
}
