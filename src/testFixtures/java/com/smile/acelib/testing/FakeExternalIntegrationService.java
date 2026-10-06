package com.smile.acelib.testing;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外部整合假服務（下游單元測試用）。
 *
 * <p>取代各下游自寫的 provider stub：provider 註冊／狀態／移除皆可編排，
 * 語意與 production {@code ExternalIntegrationServiceImpl} 對齊：</p>
 * <ul>
 *   <li>未知 id → {@code INIT_FAILED}（缺席）</li>
 *   <li>已註冊 provider 可設為任意狀態（含 {@code NOT_ENABLED} 停用）</li>
 *   <li>{@code unregisterProvider} 後再查 → {@code INIT_FAILED}（缺席）；
 *       重新 {@code registerProvider} → 恢復 {@code AVAILABLE}（重新取得）</li>
 *   <li>{@code shutdown()} 後已註冊 provider 一律回 {@code NOT_ENABLED}，
 *       模組狀態為 {@code SHUTDOWN}</li>
 * </ul>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 *
 * @since 1.4.0
 */
public final class FakeExternalIntegrationService implements ExternalIntegrationService {

    private final Map<String, IntegrationProbeResult> providers = new LinkedHashMap<>();
    private boolean shutDown;

    /**
     * 註冊 provider（預設 {@code AVAILABLE}）。
     *
     * @param integrationId 整合識別；不可為 null
     * @throws IllegalArgumentException id 為 null 或已註冊
     */
    public void registerProvider(String integrationId) {
        if (integrationId == null) {
            throw new IllegalArgumentException("integrationId must not be null");
        }
        if (providers.containsKey(integrationId)) {
            throw new IllegalArgumentException(
                "integration provider already registered: " + integrationId);
        }
        providers.put(integrationId, IntegrationProbeResult.of(
            IntegrationStatus.AVAILABLE, null));
    }

    /**
     * 更新已註冊 provider 的狀態。
     *
     * @param integrationId 整合識別；不可為 null
     * @param status        新狀態；不可為 null
     * @param reason        說明；可為 null（採用狀態預設）
     * @throws IllegalArgumentException id 為 null 或尚未註冊
     */
    public void updateProviderStatus(String integrationId, IntegrationStatus status,
            String reason) {
        if (integrationId == null) {
            throw new IllegalArgumentException("integrationId must not be null");
        }
        Objects.requireNonNull(status, "status");
        if (!providers.containsKey(integrationId)) {
            throw new IllegalArgumentException(
                "integration provider not registered: " + integrationId);
        }
        providers.put(integrationId, IntegrationProbeResult.of(status, reason));
    }

    /**
     * 移除 provider（模擬外部 plugin 缺席／解除安裝）。
     *
     * @param integrationId 整合識別；不可為 null
     * @throws IllegalArgumentException id 為 null 或尚未註冊
     */
    public void unregisterProvider(String integrationId) {
        if (integrationId == null) {
            throw new IllegalArgumentException("integrationId must not be null");
        }
        if (providers.remove(integrationId) == null) {
            throw new IllegalArgumentException(
                "integration provider not registered: " + integrationId);
        }
    }

    /** @return 目前已註冊的 provider id（不可變快照） */
    public Set<String> registeredIds() {
        return Set.copyOf(providers.keySet());
    }

    @Override
    public IntegrationProbeResult getStatus(String integrationId) {
        if (integrationId == null) {
            throw new IllegalArgumentException("integrationId must not be null");
        }
        IntegrationProbeResult registered = providers.get(integrationId);
        if (registered == null) {
            return IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "integration '" + integrationId + "' is not registered");
        }
        if (shutDown) {
            return IntegrationProbeResult.of(IntegrationStatus.NOT_ENABLED,
                "integration '" + integrationId + "' has been shut down");
        }
        return registered;
    }

    @Override
    public String getModuleStatus() {
        if (shutDown) {
            return "SHUTDOWN";
        }
        if (providers.isEmpty()) {
            return "NOT_INITIALIZED";
        }
        boolean anyAvailable = false;
        boolean anyNotWorking = false;
        for (IntegrationProbeResult result : providers.values()) {
            if (result.status() == IntegrationStatus.AVAILABLE) {
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
    }
}
