package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.config.ConfigBindingException;
import com.smile.acelib.config.ConfigException;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.Record;
import com.smile.acelib.message.BedrockFallbackStyle;
import com.smile.acelib.message.RenderStatus;
import com.smile.acelib.message.SendResult;
import com.smile.acelib.player.PlayerDataService;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AceLib 1.5.0 三條線 consumer 範例的下游視角測試。
 *
 * <p>consumer 測試路徑只有 AceLib 主 jar 與 JUnit（無 Bukkit／Adventure），
 * 因此這裡只執行不依賴伺服器的進入點：訊息線的狀態分類與發送摘要、
 * 設定線的規則與攔截、資料線以檔案後端實際執行寫入與離線讀取。
 * 需要伺服器的接線（{@code MessageService}／{@code ConfigManager}／{@code Player}）
 * 由主程式碼編譯證明，隨伺服器啟動執行。</p>
 */
class V150ExamplesTest {

    @Test
    void messageDescribe_reportsKeyMissing() {
        String summary = MessageV150Example.describe(
            RenderStatus.KEY_MISSING, "missing key greeting");
        assertTrue(summary.contains("KEY_MISSING"));
        assertTrue(summary.contains("greeting"));
    }

    @Test
    void messageDescribe_reportsOtherStates() {
        assertTrue(MessageV150Example.describe(
            RenderStatus.LOCALE_NOT_LOADED, "locale empty").contains("LOCALE_NOT_LOADED"));
        assertTrue(MessageV150Example.describe(
            RenderStatus.RENDER_FAILED, "boom").contains("RENDER_FAILED"));
        assertTrue(MessageV150Example.describe(RenderStatus.OK, "").contains("OK"));
    }

    @Test
    void messageSummarizeSend_reportsDeliveredAndFallback() {
        String applied = MessageV150Example.summarizeSend(new SendResult(true, true));
        assertTrue(applied.contains("delivered=true"));
        assertTrue(applied.contains("fallbackApplied=true"));
        String missed = MessageV150Example.summarizeSend(new SendResult(false, false));
        assertTrue(missed.contains("delivered=false"));
        assertTrue(missed.contains("fallbackApplied=false"));
    }

    @Test
    void messageFallbackStyles_coverBothVariants() {
        assertEquals(BedrockFallbackStyle.HINTS, MessageV150Example.defaultFallbackStyle());
        assertEquals(BedrockFallbackStyle.PLAIN_TEXT, MessageV150Example.plainTextStyle());
    }

    @Test
    void configSummarizeLimits_reportsGenerationAndNumbers() {
        String summary = ConfigV150Example.summarizeLimits(3L, 5L, 10L, 0.75);
        assertTrue(summary.contains("generation=3"));
        assertTrue(summary.contains("min=5"));
        assertTrue(summary.contains("max=10"));
        assertTrue(summary.contains("0.75"));
    }

    @Test
    void configRule_rejectsMinGreaterThanMax() {
        assertThrows(ConfigBindingException.class,
            () -> ConfigV150Example.checkMinNotGreaterThanMax(10L, 5L));
        ConfigV150Example.checkMinNotGreaterThanMax(3L, 5L);
        assertNotNull(ConfigV150Example.minNotGreaterThanMax());
    }

    @Test
    void configMissingFileHandler_rejectsRestore() {
        assertNotNull(ConfigV150Example.rejectRestore());
        assertThrows(ConfigException.class, () -> ConfigV150Example.rejectRestore()
            .onMissingFile(new File("missing-config.yml")));
    }

    @Test
    void playerStore_conditionalWriteAndOfflineRead(@TempDir Path dir) throws Exception {
        PlayerDataStore store = PlayerStoreV150Example.fileBackedStore(dir, "player-data.json");
        PlayerDataService service = PlayerStoreV150Example.openService(store);
        try {
            UUID player = UUID.randomUUID();

            CompletableFuture<Optional<Record>> before =
                PlayerStoreV150Example.readOffline(service, player);
            assertTrue(before.get(5, TimeUnit.SECONDS).isEmpty());

            PlayerDataStore.ConditionalWriteResult first =
                PlayerStoreV150Example.grantCoinsIfExpected(store, player, 100, 0L);
            assertTrue(first.applied());
            assertEquals(1L, first.currentRevision());

            PlayerDataStore.ConditionalWriteResult stale =
                PlayerStoreV150Example.grantCoinsIfExpected(store, player, 200, 0L);
            assertTrue(!stale.applied());
            assertEquals(1L, stale.currentRevision());

            assertEquals(PlayerDataStore.Outcome.APPLIED,
                PlayerStoreV150Example.grantBonusOnce(store, player, 50).outcome());
            assertEquals(PlayerDataStore.Outcome.CHECK_REJECTED,
                PlayerStoreV150Example.grantBonusOnce(store, player, 50).outcome());

            Optional<Record> loaded =
                PlayerStoreV150Example.readOffline(service, player).get(5, TimeUnit.SECONDS);
            assertTrue(loaded.isPresent());
            assertEquals(100, ((Number) loaded.orElseThrow().get("coins")).intValue());
        } finally {
            service.shutdown();
        }
    }
}
