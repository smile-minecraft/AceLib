package com.smile.acelib.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.world.WorldService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AceLibApi BedrockService 接線測試。
 *
 * <p>比照 {@code AceLibApiExternalIntegrationTest}：驗證
 * {@code getBedrockService()} 在 uninitialized / 全部 ready(...) overload /
 * shutDown 兩種簽章下永不為 null，且舊簽章自動填入 NOT_READY unavailable
 * facade、shutDown 填入 SHUTDOWN facade（含 forms() 拒絕）。</p>
 */
@DisplayName("AceLibApi bedrock service 接線")
class AceLibApiBedrockWiringTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000002");

    private static WorldService dummyWorldService() {
        return AceLibApi.uninitialized().getWorldService();
    }

    private static GuiService dummyGuiService() {
        return AceLibApi.uninitialized().getGuiService();
    }

    private static void assertNotReadyFacade(BedrockService svc) {
        assertNotNull(svc, "bedrockService 必須永遠不為 null");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.isBedrockPlayer(PLAYER_ID),
            "NOT_READY facade 的查詢必須拒絕");
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY),
            "拒絕必須攜帶 ACELIB-BED-001，實際：" + ex.getMessage());
        assertThrows(IllegalStateException.class, svc::forms, "forms() 必須一併拒絕");
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
    }

    private static void assertShutdownFacade(BedrockService svc) {
        assertNotNull(svc);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.isBedrockPlayer(PLAYER_ID));
        assertTrue(ex.getMessage().contains(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN));
        assertEquals("FAILED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("uninitialized().getBedrockService() 非 null 且為 NOT_READY facade")
    void uninitialized_bedrockServiceIsNotReadyFacade() {
        assertNotReadyFacade(AceLibApi.uninitialized().getBedrockService());
    }

    @Test
    @DisplayName("9 參數 canonical ready(...) 攜帶傳入的 bedrockService 同一實例")
    void ready9_carriesProvidedBedrockService() {
        BedrockService bedrock = BedrockService.forProduction(BedrockService.PlayerLookup.absent());
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            dummyGuiService(),
            AceLibApi.uninitialized().getExternalIntegrationService(),
            bedrock,
            () -> true,
            () -> { /* no-op */ }
        );
        assertSame(bedrock, api.getBedrockService());
    }

    @Test
    @DisplayName("8 參數 ready(...)（既有）自動填入 NOT_READY bedrock facade")
    void ready8_fillsNotReadyBedrockFacade() {
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            dummyGuiService(),
            AceLibApi.uninitialized().getExternalIntegrationService(),
            () -> true,
            () -> { /* no-op */ }
        );
        assertNotReadyFacade(api.getBedrockService());
    }

    @Test
    @DisplayName("7 參數 ready(...)（既有）自動填入 NOT_READY bedrock facade")
    void ready7_fillsNotReadyBedrockFacade() {
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            dummyGuiService(),
            () -> true,
            () -> { /* no-op */ }
        );
        assertNotReadyFacade(api.getBedrockService());
    }

    @Test
    @DisplayName("6 參數 deprecated ready(...) 自動填入 NOT_READY bedrock facade")
    void ready6_fillsNotReadyBedrockFacade() {
        @SuppressWarnings("deprecation")
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            () -> true,
            () -> { /* no-op */ }
        );
        assertNotReadyFacade(api.getBedrockService());
    }

    @Test
    @DisplayName("5 參數 deprecated ready(...) 自動填入 NOT_READY bedrock facade")
    void ready5_fillsNotReadyBedrockFacade() {
        @SuppressWarnings("deprecation")
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            () -> true,
            () -> { /* no-op */ }
        );
        assertNotReadyFacade(api.getBedrockService());
    }

    @Test
    @DisplayName("4 參數 deprecated ready(...) 自動填入 NOT_READY bedrock facade")
    void ready4_fillsNotReadyBedrockFacade() {
        @SuppressWarnings("deprecation")
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            () -> true,
            () -> { /* no-op */ }
        );
        assertNotReadyFacade(api.getBedrockService());
    }

    @Test
    @DisplayName("shutDown(worldService) 補 SHUTDOWN bedrock facade")
    void shutDown1_bedrockServiceIsShutdownFacade() {
        assertShutdownFacade(AceLibApi.shutDown(dummyWorldService()).getBedrockService());
    }

    @Test
    @DisplayName("shutDown(worldService, guiService) 補 SHUTDOWN bedrock facade")
    void shutDown2_bedrockServiceIsShutdownFacade() {
        assertShutdownFacade(
            AceLibApi.shutDown(dummyWorldService(), dummyGuiService()).getBedrockService());
    }
}
