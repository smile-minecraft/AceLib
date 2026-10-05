package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code joinAfterQuit} mapping-function 競態迴歸測試。
 *
 * <p>重現條件：quit future 已在 {@code pendingQuits.get} 與建鏈之間完成，
 * 且 {@code startJoinLoad} 同步失敗（此處以已 shutdown 的服務觸發
 * {@code ACELIB-PLAYER-007}，等價於任何同步拒絕／executor 拒派）。
 * 舊實作在 {@code computeIfAbsent} mapping function 內同步建鏈並立即
 * {@code remove(key)}（當時 entry 尚未寫入，為 no-op），導致已完成的
 * future 永久殘留，後續重連拿到不會載入資料的 stale future。</p>
 */
@DisplayName("joinAfterQuit race")
class PlayerDataServiceJoinAfterQuitRaceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("已完成的 quit＋同步建鏈失敗：不殘留 entry，後續重連可建新鏈")
    void joinAfterQuit_completedQuitWithSyncFailure_leavesNoResidualEntry() throws Exception {
        DataStore store = newStore("rejoin-race.json");
        PlayerDataService service = new PlayerDataService(store, Runnable::run);
        try {
            service.shutdown();

            UUID uuid = UUID.randomUUID();
            CompletableFuture<Void> completedQuit = new CompletableFuture<>();
            completedQuit.complete(null);

            CompletableFuture<Void> first = joinAfterQuit(service, uuid, "racer", completedQuit);
            assertThrows(CompletionException.class, first::join,
                "已 shutdown 下重試建鏈必須以 PLAYER-007 失敗");
            assertEquals(0, service.pendingReconnectCountForTest(),
                "建鏈同步失敗不得殘留 completed entry");

            CompletableFuture<Void> second = joinAfterQuit(service, uuid, "racer", completedQuit);
            assertNotSame(first, second, "不得重用殘留的 stale future，必須建新鏈");
            assertThrows(CompletionException.class, second::join);
            assertEquals(0, service.pendingReconnectCountForTest(),
                "第二次建鏈失敗後同樣不得殘留");
            assertTrue(service.isShutdown());
        } finally {
            store.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> joinAfterQuit(PlayerDataService service,
            UUID uuid, String name, CompletableFuture<Void> quitFuture) throws Exception {
        Method method = PlayerDataService.class.getDeclaredMethod("joinAfterQuit",
            UUID.class, String.class, CompletableFuture.class);
        method.setAccessible(true);
        return (CompletableFuture<Void>) method.invoke(service, uuid, name, quitFuture);
    }

    private DataStore newStore(String fileName) throws java.io.IOException {
        DataStore store = new JsonFileDataStore("rejoin-race", tempDir.resolve(fileName),
            SchemaVersion.V1_0, new JsonCodecImpl());
        store.init();
        return store;
    }
}
