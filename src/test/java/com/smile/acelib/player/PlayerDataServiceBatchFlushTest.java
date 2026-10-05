package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.DataStoreException;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * shutdown 批次 flush 行為測試。
 *
 * <p>shutdown 時 N 位 dirty 玩家必須只觸發一次 {@code store.save()}：
 * 先把全部 dirty snapshot 寫回 store，最後落盤一次。失敗時整批視為未落盤，
 * dirty 全保留且 shutdown flag 回滾，可重試。</p>
 */
@DisplayName("PlayerDataService batch flush")
class PlayerDataServiceBatchFlushTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("3 位 dirty 玩家 shutdown 只呼叫一次 store.save")
    void shutdown_threeDirtyPlayers_savesOnce() throws IOException {
        AtomicInteger saveCalls = new AtomicInteger();
        DataStore store = countingStore("batch-once.json", saveCalls, null);
        PlayerDataService service = new PlayerDataService(store, Runnable::run);
        List<UUID> uuids = joinDirtyPlayers(service, 3);

        service.shutdown();

        assertEquals(1, saveCalls.get(),
            "N 位 dirty 玩家 shutdown 必須只觸發一次 store.save");
        for (UUID uuid : uuids) {
            assertTrue(service.getSession(uuid).isEmpty());
        }
        store.close();
    }

    @Test
    @DisplayName("批次 save 失敗：dirty 全保留、flag 回滾、可重試成功且總共 2 次 save")
    void shutdown_batchSaveFailure_retainsAllDirtyAndRetrySucceeds() throws IOException {
        AtomicInteger saveCalls = new AtomicInteger();
        AtomicBoolean failSave = new AtomicBoolean(true);
        DataStore store = countingStore("batch-retry.json", saveCalls, failSave);
        PlayerDataService service = new PlayerDataService(store, Runnable::run);
        List<UUID> uuids = joinDirtyPlayers(service, 3);

        PlayerStateException failure = assertThrows(PlayerStateException.class, service::shutdown);
        assertEquals("ACELIB-PLAYER-003", failure.getCode());
        assertEquals(1, saveCalls.get(), "失敗的那次批次 flush 必須只嘗試一次 save");
        assertFalse(service.isShutdown(), "flush 失敗必須回滾 shutdown flag 才能重試");
        for (UUID uuid : uuids) {
            assertTrue(service.getData(uuid).isPresent(),
                "批次失敗不得遺棄任何一位玩家的 dirty 資料：uuid=" + uuid);
        }

        failSave.set(false);
        service.shutdown();

        assertEquals(2, saveCalls.get(), "重試成功只需再一次 save");
        assertTrue(service.isShutdown());
        store.close();

        // 重試後的落盤必須真的包含 3 位玩家的資料
        DataStore reopened = reopen("batch-retry.json");
        PlayerDataService reloaded = new PlayerDataService(reopened, Runnable::run);
        try {
            for (UUID uuid : uuids) {
                reloaded.onPlayerJoin(uuid, "reloaded").join();
                assertEquals("v-" + uuid,
                    reloaded.getData(uuid).orElseThrow().get("k"),
                    "重試 flush 後資料必須已落盤");
            }
            reloaded.shutdown();
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("0 dirty shutdown 不呼叫 store.save")
    void shutdown_noDirty_neverSaves() throws IOException {
        AtomicInteger saveCalls = new AtomicInteger();
        DataStore store = countingStore("batch-clean.json", saveCalls, null);
        PlayerDataService service = new PlayerDataService(store, Runnable::run);
        UUID uuid = UUID.randomUUID();
        service.onPlayerJoin(uuid, "clean").join();

        service.shutdown();

        assertEquals(0, saveCalls.get(), "沒有 dirty 時 shutdown 不可觸發 save");
        store.close();
    }

    private List<UUID> joinDirtyPlayers(PlayerDataService service, int count) {
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            service.onPlayerJoin(uuid, "p" + i).join();
            service.getData(uuid).orElseThrow().set("k", "v-" + uuid);
            service.markDirty(uuid);
            uuids.add(uuid);
        }
        return uuids;
    }

    private DataStore countingStore(String fileName, AtomicInteger saveCalls,
            AtomicBoolean failSave) throws IOException {
        DataStore delegate = newStore(fileName);
        return (DataStore) Proxy.newProxyInstance(
            DataStore.class.getClassLoader(),
            new Class<?>[] {DataStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("save")) {
                    saveCalls.incrementAndGet();
                    if (failSave != null && failSave.get()) {
                        throw new DataStoreException("ACELIB-DATA-001", "injected batch failure");
                    }
                    return null;
                }
                return method.invoke(delegate, args);
            });
    }

    private DataStore newStore(String fileName) throws IOException {
        DataStore store = new JsonFileDataStore("batch-flush", tempDir.resolve(fileName),
            SchemaVersion.V1_0, new JsonCodecImpl());
        store.init();
        return store;
    }

    private DataStore reopen(String fileName) throws IOException {
        return newStore(fileName);
    }
}
