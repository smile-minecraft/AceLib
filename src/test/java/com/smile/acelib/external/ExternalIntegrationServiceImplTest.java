package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.ModuleState;
import com.smile.acelib.diagnostics.ModuleStatus;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExternalIntegrationServiceImpl 實作測試（Phase 13 實際 implementation）。
 *
 * <p>以測試內 {@link IntegrationAdapter} 手動實作（{@link FakeAdapter}）隔離
 * registry 行為，不依賴任何具體外部插件 adapter。驗證：registry active adapter
 * → AVAILABLE；未知 id → INIT_FAILED + reason；空 registry → NOT_INITIALIZED；
 * 部分失敗 → DEGRADED 且保留每個 reason；完全失敗 → FAILED；shutdown 冪等；
 * ACELIB-EXT 常數格式；unavailable facade 使用常數；registry → ModuleState 轉換。</p>
 */
@DisplayName("ExternalIntegrationServiceImpl")
class ExternalIntegrationServiceImplTest {

    /** 可配置啟用狀態與失敗的測試 double。 */
    static final class FakeAdapter implements IntegrationAdapter {
        private final String id;
        private final IntegrationStatus activeStatus;
        private final boolean failOnInitialize;
        private boolean active = false;
        private IntegrationProbeResult status;

        FakeAdapter(String id) {
            this(id, IntegrationStatus.AVAILABLE, false);
        }

        FakeAdapter(String id, IntegrationStatus activeStatus) {
            this(id, activeStatus, false);
        }

        FakeAdapter(String id, boolean failOnInitialize) {
            this(id, IntegrationStatus.AVAILABLE, failOnInitialize);
        }

        FakeAdapter(String id, IntegrationStatus activeStatus, boolean failOnInitialize) {
            this.id = id;
            this.activeStatus = activeStatus;
            this.failOnInitialize = failOnInitialize;
            this.status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' has not been initialized");
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public IntegrationProbeResult getStatus() {
            return status;
        }

        @Override
        public void initialize() {
            if (active) {
                return;
            }
            if (failOnInitialize) {
                status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                    "adapter '" + id + "' failed to initialize: boom");
                throw new IllegalStateException("init failed: " + id);
            }
            active = true;
            status = IntegrationProbeResult.of(activeStatus,
                "adapter '" + id + "' active with " + activeStatus);
        }

        @Override
        public void shutdown() {
            if (!active) {
                return;
            }
            active = false;
            status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' has been shut down");
        }
    }

    private static final Pattern EXT_CODE = Pattern.compile("ACELIB-EXT-\\d+");

    // ----- getStatus -----
    @Test
    @DisplayName("registry active adapter → getStatus 回 AVAILABLE")
    void getStatus_activeAdapter_returnsAvailable() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("vault"));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);

        IntegrationProbeResult result = svc.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.AVAILABLE, result.status());
    }

    @Test
    @DisplayName("未知 id → INIT_FAILED + reason（不拋例外）")
    void getStatus_unknownId_returnsInitFailedWithReason() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);

        IntegrationProbeResult result = svc.getStatus("missing");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("missing"),
            "reason 應說明未知 id: " + result.reason());
    }

    @Test
    @DisplayName("getStatus(null) 依契約拋 IllegalArgumentException（不吞錯）")
    void getStatus_null_throws() {
        ExternalIntegrationServiceImpl svc =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        assertThrows(IllegalArgumentException.class, () -> svc.getStatus(null));
    }

    // ----- getModuleStatus -----
    @Test
    @DisplayName("空 registry → getModuleStatus 為 NOT_INITIALIZED")
    void getModuleStatus_emptyRegistry_isNotInitialized() {
        ExternalIntegrationServiceImpl svc =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        assertEquals("NOT_INITIALIZED", svc.getModuleStatus());
    }

    @Test
    @DisplayName("全部可用 → getModuleStatus 為 AVAILABLE")
    void getModuleStatus_allAvailable_isAvailable() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("a"));
        registry.register(new FakeAdapter("b"));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);
        assertEquals("AVAILABLE", svc.getModuleStatus());
    }

    @Test
    @DisplayName("部分失敗 → DEGRADED 且保留每個 reason")
    void getModuleStatus_partialFailure_isDegraded_andPreservesReasons() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("ok"));
        registry.register(new FakeAdapter("bad", true));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);

        assertEquals("DEGRADED", svc.getModuleStatus());

        IntegrationProbeResult ok = svc.getStatus("ok");
        IntegrationProbeResult bad = svc.getStatus("bad");
        assertEquals(IntegrationStatus.AVAILABLE, ok.status());
        assertEquals(IntegrationStatus.INIT_FAILED, bad.status());
        assertNotNull(bad.reason());
        assertTrue(bad.reason().contains("bad"),
            "失敗 reason 應保留對應 id: " + bad.reason());
    }

    @Test
    @DisplayName("完全失敗 → getModuleStatus 為 FAILED")
    void getModuleStatus_allFailed_isFailed() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("bad1", true));
        registry.register(new FakeAdapter("bad2", true));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);
        assertEquals("FAILED", svc.getModuleStatus());
    }

    // ----- shutdown -----
    @Test
    @DisplayName("shutdown 冪等：第二次呼叫不拋出，狀態維持 SHUTDOWN")
    void shutdown_idempotent() {
        IntegrationRegistry registry = new IntegrationRegistry();
        FakeAdapter a = new FakeAdapter("a");
        registry.register(a);
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);
        assertTrue(a.isActive());

        svc.shutdown();
        assertEquals("SHUTDOWN", svc.getModuleStatus());
        assertFalse(a.isActive(), "shutdown 應停用 registry 內 adapter");

        assertDoesNotThrow(svc::shutdown, "第二次 shutdown 應為 no-op");
        assertEquals("SHUTDOWN", svc.getModuleStatus());
    }

    // ----- error codes -----
    @Test
    @DisplayName("ACELIB-EXT-* 常數格式為 ACELIB-EXT-<數字>")
    void errorCodes_matchFormat() {
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_INIT_FAILED).matches());
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_VERSION_UNSUPPORTED).matches());
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_NOT_INSTALLED_OR_ENABLED).matches());
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED).matches());
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_NOT_READY).matches());
        assertTrue(EXT_CODE.matcher(
            ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_SHUTDOWN).matches());
    }

    @Test
    @DisplayName("unavailable facade 的 NOT_READY / SHUTDOWN 使用 ACELIB-EXT-* 常數")
    void unavailableFacade_usesConstants() {
        assertEquals(ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_NOT_READY,
            ExternalIntegrationService.NOT_READY);
        assertEquals(ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_SHUTDOWN,
            ExternalIntegrationService.SHUTDOWN);

        ExternalIntegrationService svc = ExternalIntegrationService.forUnavailable(
            ExternalIntegrationService.NOT_READY);
        IntegrationProbeResult result = svc.getStatus("vault");
        assertTrue(result.reason().contains(
            ExternalIntegrationErrorCodes.ACELIB_EXT_SERVICE_NOT_READY),
            "unavailable facade reason 應包含 ACELIB-EXT 常數: " + result.reason());
    }

    // ----- registry → ModuleState -----
    @Test
    @DisplayName("toModuleState 將空 registry 映射為 NOT_INITIALIZED")
    void toModuleState_empty_isNotInitialized() {
        ExternalIntegrationServiceImpl svc =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        ModuleState ms = svc.toModuleState();
        assertEquals(ModuleStatus.NOT_INITIALIZED, ms.status());
        assertEquals("integration", ms.name());
    }

    @Test
    @DisplayName("toModuleState 將全部可用映射為 READY")
    void toModuleState_available_isReady() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("a"));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);
        assertEquals(ModuleStatus.READY, svc.toModuleState().status());
    }

    @Test
    @DisplayName("toModuleState 將 shutdown 映射為 FAILED 並攜帶清理失敗代碼")
    void toModuleState_shutdown_isFailedWithCleanupCode() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new FakeAdapter("a"));
        registry.initializeAll();
        ExternalIntegrationServiceImpl svc = new ExternalIntegrationServiceImpl(registry);
        svc.shutdown();

        ModuleState ms = svc.toModuleState();
        assertEquals(ModuleStatus.FAILED, ms.status());
        assertEquals(ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED,
            ms.errorCode().orElse(null));
    }
}
