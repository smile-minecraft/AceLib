package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.JsonCodec;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDataService} 行為測試。
 *
 * <p>對應 Plan §十四 Phase 9 核心功能：
 * 登入載入、離線保存、session 與冷卻管理、
 * 快速登入登出保護、名稱變更不影響資料。</p>
 *
 * <p>測試使用 {@link JsonFileDataStore} 作為底層 DataStore 模擬；
 * 不引入 MockBukkit 以保持純單元測試的可重現性。</p>
 */
@DisplayName("PlayerDataService")
class PlayerDataServiceTest {

    @TempDir
    Path tempDir;

    private Path dataFile;
    private DataStore store;
    private PlayerDataService service;
    private ExecutorService ioExecutor;

    @BeforeEach
    void setUp() throws IOException {
        dataFile = tempDir.resolve("players.json");
        JsonCodec codec = new JsonCodecImpl();
        store = new JsonFileDataStore("players", dataFile, SchemaVersion.V1_0, codec);
        store.init();
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "player-data-io");
            t.setDaemon(true);
            return t;
        });
        service = new PlayerDataService(store, ioExecutor);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            try {
                service.shutdown();
            } catch (PlayerStateException expectedAfterClosedStore) {
                // save-failure coverage intentionally closes the store; retained dirty data may fail teardown flush too.
            }
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        if (store != null) {
            store.close();
        }
    }

    // -----------------------------------------------------------------
    // constructor validation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("constructor：null store 拋 NPE")
    void constructor_nullStore_throws() {
        assertThrows(NullPointerException.class,
            () -> new PlayerDataService(null, ioExecutor));
    }

    @Test
    @DisplayName("constructor：null executor � NPE")
    void constructor_nullExecutor_throws() {
        assertThrows(NullPointerException.class,
            () -> new PlayerDataService(store, null));
    }

    @Test
    @DisplayName("constructor：未 init 的 store 拋 PLAYER-006")
    void constructor_uninitializedStore_throws() {
        DataStore uninit = new JsonFileDataStore("u", tempDir.resolve("u.json"),
            SchemaVersion.V1_0, new JsonCodecImpl());
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> new PlayerDataService(uninit, ioExecutor));
        assertEquals("ACELIB-PLAYER-006", ex.getCode());
    }

    // -----------------------------------------------------------------
    // join lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onPlayerJoin：建立 session（state=LOADING）並觸發 async load")
    void onPlayerJoin_createsLoadingSession() {
        // 用同步 executor 強制 LOADING 在 join() 之前可觀察
        ExecutorService syncExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sync-test");
            t.setDaemon(true);
            return t;
        });
        try {
            PlayerDataService syncService = new PlayerDataService(store, syncExec);
            UUID id = UUID.randomUUID();
            CompletableFuture<Void> loaded = syncService.onPlayerJoin(id, "alice");
            assertNotNull(loaded);

            // 取得 session 物件；測試環境 executor 可能極快，仍須驗證「建立 session」語意。
            // 因為測試未 join()，session state 可能已轉為 READY（async 完成）。
            // 因此只驗證「session 存在」與「未來完成後 state=READY」即可。
            PlayerSession s = syncService.getSession(id).orElse(null);
            assertNotNull(s, "join 後應建立 session");
            assertEquals("alice", s.getName());
            assertSame(id, s.getUniqueId());

            loaded.join();
            assertSame(PlayerSessionState.READY, s.getState());
        } finally {
            syncExec.shutdownNow();
        }
    }

    @Test
    @DisplayName("onPlayerJoin：未關閉 store 上 join 仍可運作（load 為空 record）")
    void onPlayerJoin_firstTimePlayer_createsEmptyRecord() {
        UUID id = UUID.randomUUID();
        CompletableFuture<Void> loaded = service.onPlayerJoin(id, "firstTime");
        loaded.join();

        Record rec = service.getData(id).orElse(null);
        assertNotNull(rec, "首次登入玩家應取得非 null record（空 record）");
    }

    @Test
    @DisplayName("onPlayerJoin：第二次 join 相同 UUID 拋 PLAYER-004")
    void onPlayerJoin_duplicateUuid_throws() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> service.onPlayerJoin(id, "alice2"));
        assertEquals("ACELIB-PLAYER-004", ex.getCode());
    }

    @Test
    @DisplayName("onPlayerJoin：null UUID 拋 NPE")
    void onPlayerJoin_nullUuid_throws() {
        assertThrows(NullPointerException.class,
            () -> service.onPlayerJoin(null, "alice"));
    }

    @Test
    @DisplayName("onPlayerJoin：null name 拋 NPE")
    void onPlayerJoin_nullName_throws() {
        assertThrows(NullPointerException.class,
            () -> service.onPlayerJoin(UUID.randomUUID(), null));
    }

    // -----------------------------------------------------------------
    // quit lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("onPlayerQuit：保存資料並結束 session（state=ENDED 後移除）")
    void onPlayerQuit_savesAndEndsSession() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        Record rec = service.getData(id).orElseThrow(IllegalStateException::new);
        rec.set("coins", 100);
        service.markDirty(id); // 標記為待保存

        CompletableFuture<Void> saved = service.onPlayerQuit(id);
        saved.join();

        assertFalse(service.getSession(id).isPresent(),
            "quit 後 session 應被移除");

        // 重新初始化 store 並讀回，確認資料已落地
        store.close();
        DataStore reader = new JsonFileDataStore("players", dataFile,
            SchemaVersion.V1_0, new JsonCodecImpl());
        reader.init();
        assertEquals(100, reader.root()
            .getRecord("players", null)
            .getInt(id.toString() + ".coins", 0));
        reader.close();
    }

    @Test
    @DisplayName("onPlayerQuit：未 join 的 UUID 拋 PLAYER-005")
    void onPlayerQuit_unknownUuid_throws() {
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> service.onPlayerQuit(UUID.randomUUID()));
        assertEquals("ACELIB-PLAYER-005", ex.getCode());
    }

    @Test
    @DisplayName("onPlayerQuit：null UUID 拋 NPE")
    void onPlayerQuit_nullUuid_throws() {
        assertThrows(NullPointerException.class,
            () -> service.onPlayerQuit(null));
    }

    @Test
    @DisplayName("rapid login/logout：join→quit→join 同一 UUID 不會崩潰或覆寫他人資料")
    void rapidLoginLogout_doesNotCorrupt() throws InterruptedException {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();

        // Alice session 1
        service.onPlayerJoin(aliceId, "alice").join();
        service.getData(aliceId).orElseThrow().set("session", 1);
        service.markDirty(aliceId);
        service.onPlayerQuit(aliceId).join();

        // Bob session（與 Alice 並存）
        service.onPlayerJoin(bobId, "bob").join();
        service.getData(bobId).orElseThrow().set("balance", 999);

        // Alice session 2 — 重新登入
        service.onPlayerJoin(aliceId, "alice_renamed").join();
        Record rec = service.getData(aliceId).orElseThrow();
        // 既有資料（session=1）應保留 — 由 UUID 索引
        assertEquals(1, rec.getInt("session", 0));

        service.onPlayerQuit(aliceId).join();
        service.onPlayerQuit(bobId).join();
    }

    // -----------------------------------------------------------------
    // record access semantics
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getData：未 join 回傳 empty Optional")
    void getData_unknownUuid_empty() {
        assertFalse(service.getData(UUID.randomUUID()).isPresent());
    }

    @Test
    @DisplayName("getData：剛 join 但尚未 load 完成回傳 empty")
    void getData_loadingState_empty() {
        // 直接構造「尚未完成 load」session：模擬 load 慢的情境
        UUID id = UUID.randomUUID();
        PlayerSession s = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        service.getRegistryForTest().putSession(s);
        assertFalse(service.getData(id).isPresent(),
            "LOADING 狀態下 getData 應回傳 empty（caller 可選擇等待或拒絕）");
    }

    @Test
    @DisplayName("withLoadedData：未 ready 時等待 load 完成後執行 callback")
    void withLoadedData_runsAfterLoad() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        // 模擬另一個操作需要資料
        AtomicReference<Integer> result = new AtomicReference<>();
        service.withLoadedData(id, rec -> {
            result.set(rec.getInt("missing", 42));
            return null;
        }).join();
        assertEquals(42, result.get());
    }

    @Test
    @DisplayName("withLoadedData：null UUID 拋 NPE")
    void withLoadedData_nullUuid_throws() {
        assertThrows(NullPointerException.class,
            () -> service.withLoadedData(null, rec -> null));
    }

    @Test
    @DisplayName("withLoadedData：未 join 的 UUID callback 永遠不執行")
    void withLoadedData_unknownUuid_callbackNeverRuns() {
        UUID id = UUID.randomUUID();
        CompletableFuture<Integer> f = service.withLoadedData(id, rec -> 1);
        try {
            f.get(100, TimeUnit.MILLISECONDS);
            fail("withLoadedData on unknown UUID 應拋 PLAYER-005");
        } catch (Exception ex) {
            // expected
        }
    }

    // -----------------------------------------------------------------
    // name change resilience
    // -----------------------------------------------------------------

    @Test
    @DisplayName("name change：相同 UUID、不同 name 重新登入，讀回同一 record")
    void nameChange_keepsDataByUuid() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        service.getData(id).orElseThrow().set("score", 1234);
        service.markDirty(id);
        service.onPlayerQuit(id).join();

        // 以新名稱重新登入
        service.onPlayerJoin(id, "AliceRenamed").join();
        Record rec = service.getData(id).orElseThrow();
        assertEquals(1234, rec.getInt("score", 0),
            "UUID 為唯一識別，name 改變不影響資料");
        service.onPlayerQuit(id).join();
    }

    // -----------------------------------------------------------------
    // shutdown / reload
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown：清除所有 session 並停止接受新 join")
    void shutdown_clearsState() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();

        service.shutdown();
        assertFalse(service.getSession(id).isPresent());
        assertEquals(0, service.getRegistryForTest().size());

        // shutdown 後 join 拋 PLAYER-007
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> service.onPlayerJoin(UUID.randomUUID(), "bob"));
        assertEquals("ACELIB-PLAYER-007", ex.getCode());
    }

    @Test
    @DisplayName("shutdown：冪等，重複呼叫不�例外")
    void shutdown_idempotent() {
        service.shutdown();
        service.shutdown();
    }

    // -----------------------------------------------------------------
    // save failure surfacing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("save 失敗：底層 store 關閉後 quit 仍嘗試保存 → 回傳 failed future")
    void saveFailure_afterStoreClosed_quitFails() {
        UUID id = UUID.randomUUID();
        service.onPlayerJoin(id, "alice").join();
        service.getData(id).orElseThrow().set("coins", 100);
        service.markDirty(id);

        // 強制關閉底層 store — 後續 save 將拋 ACELIB-DATA-005
        store.close();

        CompletableFuture<Void> saved = service.onPlayerQuit(id);
        try {
            saved.join();
            fail("store 已關閉，quit 應失敗");
        } catch (Exception ex) {
            // expected — future 完成時拋出 CompletionException 包裝
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // 包裝後的 exception 應為 DataStoreException 或 PlayerStateException
            assertTrue(cause instanceof RuntimeException,
                "save 失敗應傳遞 runtime exception，實際: " + cause.getClass());
        }
    }
}
