package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.world.WorldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AceLibApi 外部整合擴充測試（Phase 13 W2）。
 *
 * <p>比照 {@code WorldServiceFacadeLookupTest} / {@code GuiServiceFacadeLookupTest}
 * 的 lookup contract：{@code getExternalIntegrationService()} 在
 * uninitialized / ready / shutDown 三態皆永不為 null。</p>
 */
@DisplayName("AceLibApi external integration lookup")
class AceLibApiExternalIntegrationTest {

    private static WorldService dummyWorldService() {
        return AceLibApi.uninitialized().getWorldService();
    }

    private static GuiService dummyGuiService() {
        return AceLibApi.uninitialized().getGuiService();
    }

    @Test
    @DisplayName("uninitialized().getExternalIntegrationService() 不可為 null，查詢不拋出")
    void uninitializedApi_externalServiceIsNonNull() {
        AceLibApi api = AceLibApi.uninitialized();
        ExternalIntegrationService svc = api.getExternalIntegrationService();
        assertNotNull(svc, "getExternalIntegrationService 必須永遠不為 null");
        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("8 參數 ready(...) 回傳的 instance 與傳入的 externalService 同一物件")
    void ready8_externalService_isSameInstance() {
        ExternalIntegrationService ext = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            dummyGuiService(),
            ext,
            () -> true,
            () -> { /* no-op */ }
        );
        assertSame(ext, api.getExternalIntegrationService());
    }

    @Test
    @DisplayName("7 參數 ready(...)（既有）的 getExternalIntegrationService() 為非 null NOT_READY facade")
    void ready7_externalService_isNotNullNotReadyFacade() {
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            dummyWorldService(),
            dummyGuiService(),
            () -> true,
            () -> { /* no-op */ }
        );
        ExternalIntegrationService svc = api.getExternalIntegrationService();
        assertNotNull(svc, "7 參數 ready 的 externalService 必須以 NOT_READY facade 填補");
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
    }

    @Test
    @DisplayName("shutDown(worldService) 後 getExternalIntegrationService() 仍非 null（SHUTDOWN 語意）")
    void shutDown1_externalService_isShutdownFacade() {
        AceLibApi api = AceLibApi.shutDown(dummyWorldService());
        ExternalIntegrationService svc = api.getExternalIntegrationService();
        assertNotNull(svc);
        assertEquals("FAILED", svc.getModuleStatus());
        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
    }

    @Test
    @DisplayName("shutDown(worldService, guiService) 後 getExternalIntegrationService() 仍非 null（SHUTDOWN 語意）")
    void shutDown2_externalService_isShutdownFacade() {
        AceLibApi api = AceLibApi.shutDown(dummyWorldService(), dummyGuiService());
        ExternalIntegrationService svc = api.getExternalIntegrationService();
        assertNotNull(svc);
        assertEquals("FAILED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("8 參數 ready 保留既有 getWorldService/getGuiService 語意")
    void ready8_existingGettersStillWork() {
        WorldService world = dummyWorldService();
        GuiService gui = dummyGuiService();
        ExternalIntegrationService ext = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        AceLibApi api = AceLibApi.ready(
            "1.0.0",
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER),
            world,
            gui,
            ext,
            () -> true,
            () -> { /* no-op */ }
        );
        assertSame(world, api.getWorldService());
        assertSame(gui, api.getGuiService());
        assertSame(ext, api.getExternalIntegrationService());
        assertEquals("1.0.0", api.getVersion());
        assertEquals(Platform.PAPER, api.getPlatform());
    }
}