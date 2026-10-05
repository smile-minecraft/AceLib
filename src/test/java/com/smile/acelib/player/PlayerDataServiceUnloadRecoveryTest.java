package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * quit 保存失敗與 UNLOADING 重連語意的恢復測試。
 *
 * <p>鎖定語意：quit 保存失敗時 session 不可永久卡在 UNLOADING
 * 阻擋後續所有 join（PLAYER-004）；dirty 資料保留且重登可取回；
 * UNLOADING 進行中重連採「明確失敗、可重試」（PLAYER-004 fast-fail，
 * quit 完成後重連成功）。</p>
 */
@DisplayName("PlayerDataService unload recovery")
class PlayerDataServiceUnloadRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("quit 保存失敗：session 不卡 UNLOADING、後續 join 不再永久 PLAYER-004、dirty 不丟")
    void quitSaveFailure_endsSession_unblocksRejoinAndRetainsDirty() throws IOException {
        DataStore delegate = newStore("unload-recovery.json");
        DataStore alwaysFailing = proxy(delegate);
        PlayerDataService service = new PlayerDataService(alwaysFailing, Runnable::run);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").join();
            service.getData(uuid).orElseThrow().set("important", "retain");
            service.markDirty(uuid);

            CompletionException quitFailure = assertThrows(CompletionException.class,
                () -> service.onPlayerQuit(uuid).join(),
                "保存失敗時 quit future 必須失敗");
            assertTrue(quitFailure.getCause() instanceof PlayerStateException playerFailure
                    && "ACELIB-PLAYER-003".equals(playerFailure.getCode()),
                "quit 失敗必須攜帶 ACELIB-PLAYER-003；實際: " + quitFailure.getCause());

            assertTrue(service.getSession(uuid).isEmpty(),
                "保存失敗後 session 不可殘留（否則後續 join 永久 PLAYER-004）；實際: "
                    + service.getSession(uuid));

            // 後續重連必須成功（不再永久 PLAYER-004）
            service.onPlayerJoin(uuid, "alice").join();
            assertTrue(service.getSession(uuid).isPresent(),
                "保存失敗後重連必須建立新 session");
            assertEquals(PlayerSessionState.READY,
                service.getSession(uuid).orElseThrow().getState());

            // dirty 資料保留且重登可取回
            Record recovered = service.getData(uuid).orElseThrow(
                () -> new AssertionError("重登後資料必須可取回"));
            assertEquals("retain", recovered.get("important"),
                "保存失敗的 dirty 資料不可遺失");
        } finally {
            delegate.close();
        }
    }

    @Test
    @DisplayName("UNLOADING 保存中重連：鏈接舊 quit、完成後自動重試成功（不需手動再 join）")
    void unloadingReconnect_chainsOntoQuitAndRetriesAutomatically()
        throws Exception {
        DataStore delegate = newStore("unloading-reconnect.json");
        CountDownLatch releaseSave = new CountDownLatch(1);
        DataStore blockingStore = proxyBlocking(delegate, releaseSave);
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(blockingStore, ioExecutor);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").get(5, TimeUnit.SECONDS);
            service.getData(uuid).orElseThrow().set("coins", 100);
            service.markDirty(uuid);

            CompletableFuture<Void> quit = service.onPlayerQuit(uuid);
            assertTrue(waitForUnloadState(service, uuid),
                "前置：quit 必須先進入 UNLOADING");

            // UNLOADING 進行中重連不可同步拋出 — 回傳等待中的 future，
            // quit 完成後自動重試，不需 caller 手動再 join。
            CompletableFuture<Void> rejoin = service.onPlayerJoin(uuid, "alice");

            Thread.sleep(200L);
            assertFalse(rejoin.isDone(),
                "保存被阻擋期間重試 future 必須仍在等待，不可提前完成");

            releaseSave.countDown();
            quit.get(10, TimeUnit.SECONDS);

            // quit 完成後同一 future 自動重試成功 — 不需第二次 onPlayerJoin
            rejoin.get(10, TimeUnit.SECONDS);
            assertEquals(PlayerSessionState.READY,
                service.getSession(uuid).orElseThrow().getState());
            assertEquals(100, service.getData(uuid).orElseThrow().get("coins"));
            assertEquals(0, service.pendingReconnectCountForTest(),
                "重試完成後 pending 交接不可殘留");
        } finally {
            releaseSave.countDown();
            try {
                service.shutdown();
            } catch (PlayerStateException expectedOnDirtyShutdown) {
                // 保存曾被阻擋的 dirty 可能於 flush 再次失敗 — 清理期可接受
            }
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("UNLOADING 保存中重連：quit 保存失敗後仍自動重試成功、dirty 保留")
    void unloadingReconnect_quitSaveFailureStillRetriesAutomatically() throws Exception {
        DataStore delegate = newStore("unloading-reconnect-save-failure.json");
        CountDownLatch releaseSave = new CountDownLatch(1);
        DataStore blockingThenFailing = proxyBlockingThenFailing(delegate, releaseSave);
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io-save-failure");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(blockingThenFailing, ioExecutor);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").get(5, TimeUnit.SECONDS);
            service.getData(uuid).orElseThrow().set("important", "retain");
            service.markDirty(uuid);

            CompletableFuture<Void> quit = service.onPlayerQuit(uuid);
            assertTrue(waitForUnloadState(service, uuid),
                "前置：quit 必須先進入 UNLOADING");

            CompletableFuture<Void> rejoin = service.onPlayerJoin(uuid, "alice");

            Thread.sleep(200L);
            assertFalse(rejoin.isDone(),
                "保存被阻擋期間重試 future 必須仍在等待，不可提前完成");

            releaseSave.countDown();
            CompletionException quitFailure = assertThrows(CompletionException.class,
                () -> quit.join(),
                "保存失敗時 quit future 必須失敗");
            assertTrue(quitFailure.getCause() instanceof PlayerStateException playerFailure
                    && "ACELIB-PLAYER-003".equals(playerFailure.getCode()),
                "quit 失敗必須攜帶 ACELIB-PLAYER-003；實際: " + quitFailure.getCause());

            // quit 失敗完成後同一 future 仍自動重試成功
            rejoin.get(10, TimeUnit.SECONDS);
            assertEquals(PlayerSessionState.READY,
                service.getSession(uuid).orElseThrow().getState());
            assertEquals("retain", service.getData(uuid).orElseThrow().get("important"));
            assertEquals(0, service.pendingReconnectCountForTest(),
                "重試完成後 pending 交接不可殘留");
        } finally {
            releaseSave.countDown();
            try {
                service.shutdown();
            } catch (PlayerStateException expectedOnDirtyShutdown) {
                // 失敗 store 的 flush 仍可失敗 — 清理期可接受
            }
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("刪除鍵語意：quit 失敗前 remove 的 key 重登後不得復活，shutdown 落盤後亦然")
    void removedKey_doesNotResurrectAfterFailedQuitRejoinAndShutdownFlush() throws Exception {
        Path dataFile = tempDir.resolve("removed-key.json");
        DataStore delegate = new JsonFileDataStore("unload-recovery",
            dataFile, SchemaVersion.V1_0, new JsonCodecImpl());
        delegate.init();
        AtomicInteger saveCalls = new AtomicInteger();
        UUID uuid = UUID.randomUUID();
        DataStore failSecondSaveStore = (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("save") && saveCalls.incrementAndGet() == 2) {
                    // 模擬具 rollback 語意的 store：保存失敗不污染可見狀態，
                    // store 舊值（含已刪除的 bye）仍為重登時載入的內容，
                    // 才能真正覆蓋「遺留合併」的刪除鍵語意。
                    Map<String, Object> rolledBack = new LinkedHashMap<>();
                    rolledBack.put("keep", "yes");
                    rolledBack.put("bye", "stale");
                    delegate.root().set("players." + uuid, rolledBack);
                    throw new DataStoreException("ACELIB-DATA-001", "injected second save failure");
                }
                return method.invoke(delegate, args);
            });
        PlayerDataService service = new PlayerDataService(failSecondSaveStore, Runnable::run);
        try {
            // 先落盤 keep + bye，讓 store 舊值含有 bye
            service.onPlayerJoin(uuid, "alice").join();
            Record first = service.getData(uuid).orElseThrow();
            first.set("keep", "yes");
            first.set("bye", "stale");
            service.markDirty(uuid);
            service.onPlayerQuit(uuid).join();

            // 重登後刪除 bye 並標 dirty，第二次保存失敗
            service.onPlayerJoin(uuid, "alice").join();
            Record second = service.getData(uuid).orElseThrow();
            assertEquals("stale", second.get("bye"));
            second.remove("bye");
            service.markDirty(uuid);
            CompletionException quitFailure = assertThrows(CompletionException.class,
                () -> service.onPlayerQuit(uuid).join(),
                "第二次保存必須失敗");
            assertTrue(quitFailure.getCause() instanceof PlayerStateException playerFailure
                    && "ACELIB-PLAYER-003".equals(playerFailure.getCode()),
                "quit 失敗必須攜帶 ACELIB-PLAYER-003；實際: " + quitFailure.getCause());

            // 重登：遺留整體採用 — bye 不得因 store 舊值復活
            service.onPlayerJoin(uuid, "alice").join();
            Record recovered = service.getData(uuid).orElseThrow(
                () -> new AssertionError("重登後資料必須可取回"));
            assertEquals("yes", recovered.get("keep"));
            assertFalse(recovered.has("bye"),
                "quit 失敗前已 remove 的 key 不得復活；實際值=" + recovered.get("bye"));
            assertNull(recovered.get("bye"));
            assertEquals(0, service.pendingReconnectCountForTest(),
                "重登合併完成後 pending 交接不可殘留");

            // shutdown flush（第三次保存成功）落盤後重開亦不得復活
            service.shutdown();

            DataStore reopened = new JsonFileDataStore("unload-recovery",
                dataFile, SchemaVersion.V1_0, new JsonCodecImpl());
            reopened.init();
            try {
                Record playersNode = reopened.root().getRecord("players", null);
                assertTrue(playersNode != null);
                assertEquals("yes", playersNode.getString(uuid + ".keep", null));
                assertFalse(playersNode.has(uuid + ".bye"),
                    "落盤後重開 bye 仍不得復活");
            } finally {
                reopened.close();
            }
        } finally {
            delegate.close();
        }
    }

    @Test
    @DisplayName("重試有界：等待期間 shutdown，重試以 PLAYER-007 失敗、不建 session、不殘留")
    void reconnectWait_shutdownAbortsRetryWithPlayer007() throws Exception {
        DataStore delegate = newStore("unloading-shutdown.json");
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch enteredSave = new CountDownLatch(1);
        DataStore blockingStore = proxyBlockingCounted(delegate, releaseSave, enteredSave);
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io-shutdown");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(blockingStore, ioExecutor);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").get(5, TimeUnit.SECONDS);
            service.getData(uuid).orElseThrow().set("coins", 7);
            service.markDirty(uuid);

            CompletableFuture<Void> quit = service.onPlayerQuit(uuid);
            assertTrue(waitForUnloadState(service, uuid),
                "前置：quit 必須先進入 UNLOADING");
            assertTrue(enteredSave.await(5, TimeUnit.SECONDS),
                "前置：quit 必須已取走 view 並進入保存（否則 shutdown flush 會掃到該 dirty）");
            CompletableFuture<Void> rejoin = service.onPlayerJoin(uuid, "alice");

            // 500ms 後放行保存：quit 必在 shutdown 已設 flag 之後才完成，
            // 重試撞見 flag 放棄（PLAYER-007）為強制有序，不依賴任何逾時競爭。
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                releaseSave.countDown();
            });
            releaser.setDaemon(true);
            releaser.start();

            // 等待期間 shutdown：不再重試建立 session
            service.shutdown();
            assertTrue(service.isShutdown());
            // quit 結果（成功或 PLAYER-008/003）不影響本測試斷言 — 只等待其結束
            try {
                quit.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // quit 於 shutdown 後完成，成功或失敗皆可接受
            }

            CompletionException retryFailure = assertThrows(CompletionException.class,
                () -> rejoin.join(),
                "shutdown 後重試不可建立 session，必須失敗");
            assertTrue(retryFailure.getCause() instanceof PlayerStateException playerFailure
                    && "ACELIB-PLAYER-007".equals(playerFailure.getCode()),
                "重試必須以 ACELIB-PLAYER-007 失敗；實際: " + retryFailure.getCause());
            assertTrue(service.getSession(uuid).isEmpty(),
                "shutdown 後不可殘留或新建 session");
            assertEquals(0, service.pendingReconnectCountForTest(),
                "重試結束後 pending 交接不可殘留");

            // shutdown 後新 join 直接拒絕，不再排鏈
            PlayerStateException rejected = assertThrows(PlayerStateException.class,
                () -> service.onPlayerJoin(uuid, "alice"));
            assertEquals("ACELIB-PLAYER-007", rejected.getCode());
        } finally {
            releaseSave.countDown();
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("併發重連共享單一 pending chain：同一 future、單一 session")
    void concurrentReconnectDuringUnload_sharesSingleChain() throws Exception {
        DataStore delegate = newStore("unloading-shared-chain.json");
        CountDownLatch releaseSave = new CountDownLatch(1);
        DataStore blockingStore = proxyBlocking(delegate, releaseSave);
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io-shared");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(blockingStore, ioExecutor);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").get(5, TimeUnit.SECONDS);
            service.getData(uuid).orElseThrow().set("coins", 42);
            service.markDirty(uuid);

            CompletableFuture<Void> quit = service.onPlayerQuit(uuid);
            assertTrue(waitForUnloadState(service, uuid),
                "前置：quit 必須先進入 UNLOADING");

            CompletableFuture<Void> first = service.onPlayerJoin(uuid, "alice");
            CompletableFuture<Void> second = service.onPlayerJoin(uuid, "alice");
            assertSame(first, second,
                "同 UUID 等待中重連必須共享單一 pending chain，避免 quit 完成瞬間重建風暴");

            releaseSave.countDown();
            quit.get(10, TimeUnit.SECONDS);
            first.get(10, TimeUnit.SECONDS);
            assertEquals(1, service.activeSessionCount(),
                "重試完成後必須只有一個 session");
            assertEquals(PlayerSessionState.READY,
                service.getSession(uuid).orElseThrow().getState());
            assertEquals(42, service.getData(uuid).orElseThrow().get("coins"));
            assertEquals(0, service.pendingReconnectCountForTest(),
                "重試完成後 pending 交接不可殘留");
        } finally {
            releaseSave.countDown();
            try {
                service.shutdown();
            } catch (PlayerStateException expectedOnDirtyShutdown) {
                // 保存曾被阻擋的 dirty 可能於 flush 再次失敗 — 清理期可接受
            }
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("withLoadedData：session 已 READY 時 callback 在 caller 執行緒執行")
    void withLoadedData_readyRunsCallbackOnCallerThread() throws IOException {
        DataStore delegate = newStore("caller-thread.json");
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io-caller");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(delegate, ioExecutor);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").join();
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            String result = service.withLoadedData(uuid, record -> {
                callbackThread.set(Thread.currentThread());
                return "ok";
            }).join();
            assertEquals("ok", result);
            assertEquals(caller, callbackThread.get(),
                "READY session 的 withLoadedData 必須於 caller 執行緒執行 callback "
                    + "（與 Javadoc 約定一致）；實際執行緒=" + callbackThread.get());
        } finally {
            try {
                service.shutdown();
            } catch (PlayerStateException expectedOnDirtyShutdown) {
                fail("本測試無 dirty，shutdown 不應失敗: " + expectedOnDirtyShutdown);
            }
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("executor 邊界：外部 ioExecutor 不被 shutdown 關閉，僅內部 serial executor 終止")
    void executorBoundary_externalSurvivesShutdown_serialTerminated() throws Exception {
        DataStore delegate = newStore("executor-boundary.json");
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unload-recovery-io-boundary");
            t.setDaemon(true);
            return t;
        });
        PlayerDataService service = new PlayerDataService(delegate, ioExecutor);
        try {
            service.shutdown();

            assertTrue(service.isShutdown());
            assertTrue(service.isSerialExecutorTerminated(),
                "shutdown 必須終止內部 serial store executor");
            assertTrue(!ioExecutor.isShutdown() && !ioExecutor.isTerminated(),
                "外部注入的 ioExecutor 不可被 PlayerDataService 關閉");
            // 外部 executor 關閉後仍可用於其他工作
            assertEquals("ok",
                ioExecutor.submit(() -> "ok").get(5, TimeUnit.SECONDS));
        } finally {
            ioExecutor.shutdownNow();
            delegate.close();
        }
    }

    @Test
    @DisplayName("waitForState：終態不 sleep、逾時可注入假 sleeper 決定性驗證")
    void waitForState_seamIsDeterministic() throws IOException {
        DataStore delegate = newStore("wait-seam.json");
        PlayerDataService service = new PlayerDataService(delegate, Runnable::run);
        UUID uuid = UUID.randomUUID();
        try {
            PlayerSession loading = new PlayerSession(uuid, "alice", PlayerSessionState.LOADING);
            AtomicInteger sleepCalls = new AtomicInteger();
            boolean timedOut = service.waitForState(loading, PlayerSessionState.READY, 50L,
                millis -> sleepCalls.incrementAndGet());
            assertFalse(timedOut, "LOADING 等 READY 必須逾時回 false");
            assertTrue(sleepCalls.get() > 0, "輪詢必須經由注入的 sleeper");

            PlayerSession ended = new PlayerSession(uuid, "alice", PlayerSessionState.LOADING);
            ended.transitionTo(PlayerSessionState.ENDED);
            AtomicInteger terminalSleeps = new AtomicInteger();
            assertTrue(service.waitForState(ended, PlayerSessionState.ENDED, 5000L,
                    millis -> terminalSleeps.incrementAndGet()),
                "已 ENDED session 等 ENDED 必須立即回 true");
            assertEquals(0, terminalSleeps.get(), "終態不可再 sleep");
        } finally {
            try {
                service.shutdown();
            } catch (PlayerStateException expectedOnDirtyShutdown) {
                fail("本測試無 dirty，shutdown 不應失敗: " + expectedOnDirtyShutdown);
            }
            delegate.close();
        }
    }

    @Test
    @DisplayName("交錯：quit 保存失敗 → 重登合併 → shutdown flush 重試，資料落地")
    void quitSaveFailure_rejoinThenShutdownFlush_persists() throws Exception {
        Path dataFile = tempDir.resolve("interleave-retry.json");
        DataStore delegate = new JsonFileDataStore("unload-recovery",
            dataFile, SchemaVersion.V1_0, new JsonCodecImpl());
        delegate.init();
        AtomicInteger saveCalls = new AtomicInteger();
        DataStore failOnceStore = (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("save") && saveCalls.incrementAndGet() == 1) {
                    throw new DataStoreException("ACELIB-DATA-001", "injected first save failure");
                }
                return method.invoke(delegate, args);
            });
        PlayerDataService service = new PlayerDataService(failOnceStore, Runnable::run);
        UUID uuid = UUID.randomUUID();
        try {
            service.onPlayerJoin(uuid, "alice").join();
            service.getData(uuid).orElseThrow().set("important", "retain");
            service.markDirty(uuid);

            assertThrows(CompletionException.class, () -> service.onPlayerQuit(uuid).join());
            assertTrue(service.getSession(uuid).isEmpty());

            // 重登取回 dirty（不 quit，直接 shutdown — flush 必須重試保存）
            service.onPlayerJoin(uuid, "alice").join();
            assertEquals("retain", service.getData(uuid).orElseThrow().get("important"));
            service.shutdown();

            DataStore reopened = new JsonFileDataStore("unload-recovery",
                dataFile, SchemaVersion.V1_0, new JsonCodecImpl());
            reopened.init();
            try {
                Record playersNode = reopened.root().getRecord("players", null);
                assertTrue(playersNode != null);
                assertEquals("retain",
                    playersNode.getString(uuid + ".important", null),
                    "shutdown flush 必須將重登合併的 dirty 落地");
            } finally {
                reopened.close();
            }
        } finally {
            delegate.close();
        }
    }

    private boolean waitForUnloadState(PlayerDataService service, UUID uuid) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (service.getSession(uuid).map(s -> s.getState())
                .orElse(null) == PlayerSessionState.UNLOADING) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private DataStore newStore(String fileName) throws IOException {
        DataStore store = new JsonFileDataStore("unload-recovery",
            tempDir.resolve(fileName), SchemaVersion.V1_0, new JsonCodecImpl());
        store.init();
        return store;
    }

    private DataStore proxy(DataStore delegate) {
        return (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("save")) {
                    throw new DataStoreException("ACELIB-DATA-001", "injected save failure");
                }
                return method.invoke(delegate, args);
            });
    }

    private DataStore proxyBlockingThenFailing(DataStore delegate, CountDownLatch releaseSave) {
        return (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("save")) {
                    try {
                        if (!releaseSave.await(10, TimeUnit.SECONDS)) {
                            throw new DataStoreException("ACELIB-DATA-007",
                                "injected save timeout");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DataStoreException("ACELIB-DATA-007",
                            "injected save interrupted", interrupted);
                    }
                    throw new DataStoreException("ACELIB-DATA-001",
                        "injected save failure after release");
                }
                return method.invoke(delegate, args);
            });
    }

    private DataStore proxyBlocking(DataStore delegate, CountDownLatch releaseSave) {
        return proxyBlockingCounted(delegate, releaseSave, new CountDownLatch(0));
    }

    /**
     * 阻擋式 save 代理：進入 save 時先倒數 {@code enteredSave}，
     * 讓測試能確定性等待「quit 已取走 view 並進入保存」
     *（同執行緒程式順序保證此時 {@code records.remove} 已完成，
     * shutdown flush 不會掃到該 dirty）。
     */
    private DataStore proxyBlockingCounted(DataStore delegate, CountDownLatch releaseSave,
            CountDownLatch enteredSave) {
        return (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (p, method, args) -> {
                if (method.getName().equals("save")) {
                    enteredSave.countDown();
                    try {
                        if (!releaseSave.await(10, TimeUnit.SECONDS)) {
                            throw new DataStoreException("ACELIB-DATA-007",
                                "injected save timeout");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DataStoreException("ACELIB-DATA-007",
                            "injected save interrupted", interrupted);
                    }
                    return null;
                }
                return method.invoke(delegate, args);
            });
    }
}
