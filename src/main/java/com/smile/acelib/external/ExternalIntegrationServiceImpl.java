package com.smile.acelib.external;

import com.smile.acelib.diagnostics.ModuleState;
import java.util.Objects;
import java.util.Set;

/**
 * 實際 {@link ExternalIntegrationService} 實作：包裝 {@link IntegrationRegistry}，
 * 提供整合狀態查詢、模組狀態聚合與資源釋放。
 *
 * <p>所有查詢方法永不回 null；未知 integration id 回傳 {@link IntegrationStatus#INIT_FAILED}
 * 結果（不拋例外），null id 依契約拋 {@link IllegalArgumentException}。</p>
 *
 * <h2>模組狀態聚合（{@link #getModuleStatus()}）</h2>
 * <ul>
 *   <li>無註冊 adapter → {@code NOT_INITIALIZED}</li>
 *   <li>全部可用 → {@code AVAILABLE}</li>
 *   <li>部分可用、部分異常 → {@code DEGRADED}</li>
 *   <li>全部異常 → {@code FAILED}</li>
 *   <li>已呼叫 {@link #shutdown()} → {@code SHUTDOWN}</li>
 * </ul>
 *
 * <p>{@link #toModuleState()} 將上述聚合結果轉換為 {@code DiagnosticsService} 的
 * {@link ModuleState}，供 {@code AceLibPlugin} 後續呼叫
 * {@code DiagnosticsService.registerModuleState(String, ModuleState)} 使用。</p>
 *
 * @see IntegrationRegistry
 * @see ExternalIntegrationErrorCodes
 */
public final class ExternalIntegrationServiceImpl implements ExternalIntegrationService {

    /** 診斷模組名稱（對應 DiagnosticsService.MODULE_INTEGRATION）。 */
    static final String MODULE_NAME = "integration";

    private final IntegrationRegistry registry;
    private volatile boolean shutDown = false;

    /**
     * 建構子。
     *
     * @param registry 被包裝的整合 registry；不可為 null
     * @throws NullPointerException 當 {@code registry} 為 null
     */
    public ExternalIntegrationServiceImpl(IntegrationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public IntegrationProbeResult getStatus(String integrationId) {
        if (integrationId == null) {
            throw new IllegalArgumentException("integrationId must not be null");
        }
        if (!registry.isRegistered(integrationId)) {
            return IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "integration '" + integrationId + "' is not registered");
        }
        return registry.getStatus(integrationId);
    }

    @Override
    public String getModuleStatus() {
        if (shutDown) {
            return "SHUTDOWN";
        }
        Set<String> ids = registry.getRegisteredIds();
        if (ids.isEmpty()) {
            return "NOT_INITIALIZED";
        }
        boolean anyAvailable = false;
        boolean anyNotWorking = false;
        for (String id : ids) {
            IntegrationStatus status = registry.getStatus(id).status();
            if (status == IntegrationStatus.AVAILABLE) {
                anyAvailable = true;
            } else {
                anyNotWorking = true;
            }
        }
        if (anyAvailable && anyNotWorking) {
            return "DEGRADED";
        }
        if (anyNotWorking) {
            return "FAILED";
        }
        return "AVAILABLE";
    }

    @Override
    public void shutdown() {
        shutDown = true;
        registry.shutdownAll();
    }

    /**
     * 將目前 registry 狀態轉換為 {@code DiagnosticsService} 的 {@link ModuleState}。
     *
     * <p>對應 {@link #getModuleStatus()} 的聚合結果；失敗狀態攜帶
     * {@link ExternalIntegrationErrorCodes} 錯誤代碼，供診斷報告聚合。</p>
     *
     * @return 非 null 的 {@link ModuleState}
     */
    public ModuleState toModuleState() {
        return switch (getModuleStatus()) {
            case "AVAILABLE" ->
                ModuleState.ready(MODULE_NAME, "all external integrations available");
            case "DEGRADED" ->
                ModuleState.degraded(MODULE_NAME, "some external integrations failed");
            case "FAILED" ->
                ModuleState.failed(MODULE_NAME,
                    "all external integrations failed",
                    ExternalIntegrationErrorCodes.ACELIB_EXT_INIT_FAILED);
            case "SHUTDOWN" ->
                ModuleState.failed(MODULE_NAME,
                    "external integration service has been shut down",
                    ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED);
            case "NOT_INITIALIZED" ->
                ModuleState.notInitialized(MODULE_NAME,
                    "no external integrations registered");
            default ->
                ModuleState.notInitialized(MODULE_NAME,
                    "unknown external integration state");
        };
    }
}
