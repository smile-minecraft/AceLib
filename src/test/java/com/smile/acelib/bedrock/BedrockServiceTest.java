package com.smile.acelib.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BedrockService} 三態 facade 與 lookup 委派測試。
 *
 * <p>涵蓋：production 綁定（真實 / absent lookup）、unavailable facade 的
 * NOT_READY / SHUTDOWN 拒絕行為（含 forms() 拒絕）、null 輸入契約與
 * shutdown 後拒絕。</p>
 */
@DisplayName("BedrockService")
class BedrockServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000001");

    private static BedrockPlayerInfo sampleInfo() {
        return new BedrockPlayerInfo(PLAYER_ID, "bedrocker",
            BedrockPlayerInfo.DeviceOs.IOS, BedrockPlayerInfo.InputMode.TOUCH,
            "zh_TW", BedrockPlayerInfo.LinkState.UNLINKED, null);
    }

    // ----- production binding -----

    @Test
    @DisplayName("forProduction + absent lookup：缺席環境零影響（isBedrockPlayer=false、info=empty、forms() 非 null）")
    void production_absentLookup_zeroImpact() {
        BedrockService service = BedrockService.forProduction(BedrockService.PlayerLookup.absent());
        assertFalse(service.isBedrockPlayer(PLAYER_ID),
            "Floodgate 缺席時查詢必須安全回 false，不得拋例外");
        assertTrue(service.getPlayerInfo(PLAYER_ID).isEmpty());
        assertNotNull(service.forms(), "forms() 永不為 null");
        assertEquals("READY", service.getModuleStatus());
    }

    @Test
    @DisplayName("forProduction + stub lookup：查詢委派到 lookup")
    void production_delegatesToLookup() {
        AtomicInteger lookupCalls = new AtomicInteger();
        BedrockService.PlayerLookup stub = new BedrockService.PlayerLookup() {
            @Override
            public boolean isBedrockPlayer(UUID playerId) {
                lookupCalls.incrementAndGet();
                return true;
            }

            @Override
            public Optional<BedrockPlayerInfo> lookup(UUID playerId) {
                return Optional.of(sampleInfo());
            }
        };
        BedrockService service = BedrockService.forProduction(stub);
        assertTrue(service.isBedrockPlayer(PLAYER_ID));
        assertEquals(1, lookupCalls.get(), "isBedrockPlayer 必須委派 lookup，不得自行判斷");
        Optional<BedrockPlayerInfo> info = service.getPlayerInfo(PLAYER_ID);
        assertTrue(info.isPresent());
        assertSame(sampleInfo().deviceOs(), info.get().deviceOs());
        assertEquals("bedrocker", info.get().username());
    }

    @Test
    @DisplayName("production shutdown 後查詢一律 SHUTDOWN 拒絕")
    void production_shutdown_rejectsQueries() {
        BedrockService service = BedrockService.forProduction(BedrockService.PlayerLookup.absent());
        service.shutdown();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.isBedrockPlayer(PLAYER_ID));
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN),
            "shutdown 後拒絕必須攜帶 ACELIB-BED-002，實際：" + ex.getMessage());
        assertEquals("FAILED", service.getModuleStatus());
        // 冪等：重複 shutdown 不拋例外
        service.shutdown();
    }

    // ----- unavailable facade -----

    @Test
    @DisplayName("forUnavailable(NOT_READY)：所有操作（含 forms()）以 NOT_READY 拒絕")
    void unavailable_notReady_rejectsEverything() {
        BedrockService service = BedrockService.forUnavailable(BedrockService.NOT_READY);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class,
            () -> service.isBedrockPlayer(PLAYER_ID));
        assertTrue(ex1.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY));
        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
            () -> service.getPlayerInfo(PLAYER_ID));
        assertTrue(ex2.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY));
        IllegalStateException ex3 = assertThrows(IllegalStateException.class, service::forms);
        assertTrue(ex3.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY),
            "unavailable facade 的 forms() 必須拒絕且攜帶代碼");
        assertEquals("NOT_INITIALIZED", service.getModuleStatus());
    }

    @Test
    @DisplayName("forUnavailable(SHUTDOWN)：所有操作以 SHUTDOWN 拒絕、模組狀態 FAILED")
    void unavailable_shutdown_rejectsEverything() {
        BedrockService service = BedrockService.forUnavailable(BedrockService.SHUTDOWN);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.isBedrockPlayer(PLAYER_ID));
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN));
        assertEquals("FAILED", service.getModuleStatus());
    }

    @Test
    @DisplayName("forUnavailable：非 NOT_READY/SHUTDOWN 代碼必須拋 IllegalArgumentException（不吞錯）")
    void unavailable_invalidCode_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> BedrockService.forUnavailable("ACELIB-BED-999"));
        assertThrows(IllegalArgumentException.class, () -> BedrockService.forUnavailable(null));
    }

    @Test
    @DisplayName("unavailable facade shutdown() 為冪等 no-op")
    void unavailable_shutdownIdempotent() {
        BedrockService service = BedrockService.forUnavailable(BedrockService.NOT_READY);
        service.shutdown();
        service.shutdown();
        assertEquals("FAILED", service.getModuleStatus(),
            "unavailable facade shutdown 後模組狀態為 FAILED");
    }

    // ----- input contract -----

    @Test
    @DisplayName("null 輸入必須拋 IllegalArgumentException 並攜帶 INVALID_INPUT 代碼")
    void nullInputs_throwWithInvalidInputCode() {
        BedrockService service = BedrockService.forProduction(BedrockService.PlayerLookup.absent());
        assertThrows(IllegalArgumentException.class, () -> service.isBedrockPlayer(null));
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerInfo(null));
        BedrockService unavailable = BedrockService.forUnavailable(BedrockService.NOT_READY);
        assertThrows(IllegalArgumentException.class, () -> unavailable.isBedrockPlayer(null));
    }
}
