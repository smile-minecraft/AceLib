package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerDataServiceShutdownFailureTest {

    @TempDir
    Path tempDir;

    @Test
    void shutdown_saveFailure_isObservableAndRetainsDirtyRecord() throws IOException {
        DataStore delegate = newStore("save-failure.json");
        DataStore failingStore = proxy(delegate, (method, args) -> {
            if (method.getName().equals("save")) {
                throw new DataStoreException("ACELIB-DATA-001", "injected save failure");
            }
            return null;
        });
        PlayerDataService service = new PlayerDataService(failingStore, Runnable::run);
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "alice").join();
        service.getData(uuid).orElseThrow().set("important", "retain");
        service.markDirty(uuid);

        PlayerStateException failure = assertThrows(PlayerStateException.class, service::shutdown);

        assertEquals("ACELIB-PLAYER-003", failure.getCode());
        assertTrue(failure.getMessage().contains(uuid.toString()));
        assertTrue(failure.getMessage().contains("dirtyCount=1"));
        assertTrue(service.getData(uuid).isPresent(), "failed flush must retain dirty state");
        delegate.close();
    }

    @Test
    void shutdown_saveTimeout_isObservableAndRetainsDirtyRecord() throws Exception {
        DataStore delegate = newStore("save-timeout.json");
        CountDownLatch releaseSave = new CountDownLatch(1);
        DataStore blockingStore = proxy(delegate, (method, args) -> {
            if (method.getName().equals("save")) {
                try {
                    if (!releaseSave.await(10, TimeUnit.SECONDS)) {
                        throw new DataStoreException("ACELIB-DATA-007", "injected save timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DataStoreException("ACELIB-DATA-007", "injected save interrupted",
                        interrupted);
                }
            }
            return null;
        });
        PlayerDataService service = new PlayerDataService(blockingStore, Runnable::run);
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "alice").join();
        service.getData(uuid).orElseThrow().set("important", "retain");
        service.markDirty(uuid);

        try {
            PlayerStateException failure = assertThrows(PlayerStateException.class, service::shutdown);
            assertEquals("ACELIB-PLAYER-008", failure.getCode());
            assertTrue(failure.getMessage().contains(uuid.toString()));
            assertTrue(failure.getMessage().contains("dirtyCount=1"));
            assertTrue(service.getData(uuid).isPresent(), "timed out flush must retain dirty state");
        } finally {
            releaseSave.countDown();
            delegate.close();
        }
    }

    @Test
    void quit_saveFailure_retainsDirtyRecordForLaterShutdownRetry() throws IOException {
        DataStore delegate = newStore("quit-save-retry.json");
        AtomicInteger saveCalls = new AtomicInteger();
        DataStore failOnceStore = proxy(delegate, (method, args) -> {
            if (saveCalls.incrementAndGet() == 1) {
                throw new DataStoreException("ACELIB-DATA-001", "injected first save failure");
            }
            return null;
        });
        PlayerDataService service = new PlayerDataService(failOnceStore, Runnable::run);
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "alice").join();
        service.getData(uuid).orElseThrow().set("important", "retain");
        service.markDirty(uuid);

        assertThrows(CompletionException.class, () -> service.onPlayerQuit(uuid).join());
        // 保存失敗後 session 已 ENDED 並從 registry 移除（不再卡 UNLOADING）—
        // 重試路徑為重登（dirty 合併取回）而非再次 quit。
        assertTrue(service.getSession(uuid).isEmpty(),
            "保存失敗後 session 不可殘留");
        service.onPlayerJoin(uuid, "alice").join();
        assertEquals("retain", service.getData(uuid).orElseThrow().get("important"),
            "重登必須取回保存失敗的 dirty 資料");
        service.onPlayerQuit(uuid).join();
        service.shutdown();

        assertEquals(2, saveCalls.get(), "shutdown 前 quit 重試必須成功保存 dirty record");
        delegate.close();
    }

    @Test
    void shutdown_batchSaveFailure_keepsAllPlayersDirtyForRetry() throws IOException {
        DataStore delegate = newStore("batch-save-retry.json");
        AtomicInteger saveCalls = new AtomicInteger();
        // 第一次 save() 失敗、第二次成功：驗證失敗的那一輪沒有把任何一位
        // 玩家標記成已保存，否則重試時資料會被當成已落盤而永遠不重寫。
        DataStore failOnceStore = proxy(delegate, (method, args) -> {
            if (method.getName().equals("save")
                && saveCalls.incrementAndGet() == 1) {
                throw new DataStoreException("ACELIB-DATA-001", "injected first save failure");
            }
            return null;
        });
        PlayerDataService service = new PlayerDataService(failOnceStore, Runnable::run);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.onPlayerJoin(first, "alice").join();
        service.onPlayerJoin(second, "bob").join();
        service.getData(first).orElseThrow().set("keep", "first");
        service.getData(second).orElseThrow().set("keep", "second");
        service.markDirty(first);
        service.markDirty(second);

        assertThrows(PlayerStateException.class, service::shutdown);

        // 失敗後 shutdown flag 回滾、服務仍可用：重試必須真的再寫一次，
        // 而且兩位玩家的資料都要落地（成功 shutdown 會清掉記憶體快取，
        // 因此驗證的是 store 裡的實際內容）。
        service.shutdown();
        assertTrue(saveCalls.get() >= 2,
            "第一次 save 失敗後重試必須再次呼叫 save()，實際呼叫次數："
                + saveCalls.get());
        Record root = delegate.root();
        String firstPath = "players." + first;
        String secondPath = "players." + second;
        assertEquals("first", root.getString(firstPath + ".keep", null),
            "重試後第一位玩家的資料必須真的落地");
        assertEquals("second", root.getString(secondPath + ".keep", null),
            "重試後第二位玩家的資料必須真的落地");
        delegate.close();
    }

    private DataStore newStore(String fileName) throws IOException {
        DataStore store = new JsonFileDataStore("shutdown-failure", tempDir.resolve(fileName),
            SchemaVersion.V1_0, new JsonCodecImpl());
        store.init();
        return store;
    }

    private DataStore proxy(DataStore delegate, SaveHandler saveHandler) {
        return (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("save")) {
                    saveHandler.invoke(method, args);
                    return null;
                }
                return method.invoke(delegate, args);
            });
    }

    @FunctionalInterface
    private interface SaveHandler {
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
