package com.smile.acelib.testing;

import com.smile.acelib.external.BuildCheckProvider;
import com.smile.acelib.external.BuildCheckResult;
import com.smile.acelib.external.EconomyProvider;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalOperationResult;
import com.smile.acelib.external.ExternalResultState;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.external.PermissionProvider;
import com.smile.acelib.external.PermissionResult;
import com.smile.acelib.external.PlaceholderHandler;
import com.smile.acelib.external.PlaceholderProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

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
        economyProvider = null;
        permissionProvider = null;
        placeholderProvider = null;
        buildCheckProvider = null;
    }

    // ----- 業務門面（委派給可注入的假提供者；缺席時回 UNAVAILABLE） -----

    private volatile EconomyProvider economyProvider;
    private volatile PermissionProvider permissionProvider;
    private volatile PlaceholderProvider placeholderProvider;
    private volatile BuildCheckProvider buildCheckProvider;

    @Override
    public EconomyResult getBalance(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        EconomyProvider provider = currentEconomy();
        if (provider == null) {
            return EconomyResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-007", "economy provider is not available");
        }
        try {
            EconomyResult result = provider.getBalance(player);
            return Objects.requireNonNullElseGet(result, () -> EconomyResult.failure(
                ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy provider returned null result"));
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy balance lookup failed: " + e);
        }
    }

    @Override
    public EconomyResult withdraw(OfflinePlayer player, double amount) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        requireValidAmount(amount);
        EconomyProvider provider = currentEconomy();
        if (provider == null) {
            return EconomyResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-007", "economy provider is not available");
        }
        try {
            EconomyResult result = provider.withdraw(player, amount);
            return Objects.requireNonNullElseGet(result, () -> EconomyResult.failure(
                ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy provider returned null result"));
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy withdraw failed: " + e);
        }
    }

    @Override
    public EconomyResult deposit(OfflinePlayer player, double amount) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        requireValidAmount(amount);
        EconomyProvider provider = currentEconomy();
        if (provider == null) {
            return EconomyResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-007", "economy provider is not available");
        }
        try {
            EconomyResult result = provider.deposit(player, amount);
            return Objects.requireNonNullElseGet(result, () -> EconomyResult.failure(
                ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy provider returned null result"));
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED, "ACELIB-EXT-008",
                "economy deposit failed: " + e);
        }
    }

    @Override
    public void setEconomyProvider(EconomyProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException(
                "external integration service has been shut down");
        }
        economyProvider = provider;
    }

    @Override
    public void clearEconomyProvider() {
        economyProvider = null;
    }

    @Override
    public PermissionResult getPermissionGroups(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        PermissionProvider provider = currentPermission();
        if (provider == null) {
            return PermissionResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-009", "permission provider is not available");
        }
        try {
            PermissionResult result = provider.getPermissionGroups(playerId);
            return Objects.requireNonNullElseGet(result, () -> PermissionResult.failure(
                ExternalResultState.FAILED, "ACELIB-EXT-010",
                "permission provider returned null result"));
        } catch (Exception e) {
            return PermissionResult.failure(ExternalResultState.FAILED, "ACELIB-EXT-010",
                "permission lookup failed: " + e);
        }
    }

    @Override
    public void setPermissionProvider(PermissionProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException(
                "external integration service has been shut down");
        }
        permissionProvider = provider;
    }

    @Override
    public void clearPermissionProvider() {
        permissionProvider = null;
    }

    @Override
    public ExternalOperationResult registerPlaceholder(String identifier,
            PlaceholderHandler handler) {
        IdentifierCheck.requireValid(identifier);
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        PlaceholderProvider provider = currentPlaceholder();
        if (provider == null) {
            return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-011", "placeholder provider is not available");
        }
        try {
            ExternalOperationResult result =
                provider.registerPlaceholder(identifier, handler);
            return Objects.requireNonNullElseGet(result, () -> ExternalOperationResult
                .failure(ExternalResultState.FAILED, "ACELIB-EXT-012",
                    "placeholder provider returned null result"));
        } catch (Exception e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                "ACELIB-EXT-012", "placeholder registration failed: " + e);
        }
    }

    @Override
    public ExternalOperationResult unregisterPlaceholder(String identifier) {
        IdentifierCheck.requireValid(identifier);
        PlaceholderProvider provider = currentPlaceholder();
        if (provider == null) {
            return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-011", "placeholder provider is not available");
        }
        try {
            ExternalOperationResult result = provider.unregisterPlaceholder(identifier);
            return Objects.requireNonNullElseGet(result, () -> ExternalOperationResult
                .failure(ExternalResultState.FAILED, "ACELIB-EXT-012",
                    "placeholder provider returned null result"));
        } catch (Exception e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                "ACELIB-EXT-012", "placeholder unregistration failed: " + e);
        }
    }

    @Override
    public void setPlaceholderProvider(PlaceholderProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException(
                "external integration service has been shut down");
        }
        placeholderProvider = provider;
    }

    @Override
    public void clearPlaceholderProvider() {
        placeholderProvider = null;
    }

    @Override
    public BuildCheckResult canBuild(UUID playerId, String worldName, int x, int y, int z) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be null or blank");
        }
        BuildCheckProvider provider = currentBuildCheck();
        if (provider == null) {
            return BuildCheckResult.failure(ExternalResultState.UNAVAILABLE,
                "ACELIB-EXT-013", "no build-check provider is registered");
        }
        try {
            BuildCheckResult result = provider.canBuild(playerId, worldName, x, y, z);
            return Objects.requireNonNullElseGet(result, () -> BuildCheckResult.failure(
                ExternalResultState.FAILED, "ACELIB-EXT-014",
                "build-check provider returned null result"));
        } catch (Exception e) {
            return BuildCheckResult.failure(ExternalResultState.FAILED, "ACELIB-EXT-014",
                "build check failed: " + e);
        }
    }

    @Override
    public void setBuildCheckProvider(BuildCheckProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException(
                "external integration service has been shut down");
        }
        buildCheckProvider = provider;
    }

    @Override
    public void clearBuildCheckProvider() {
        buildCheckProvider = null;
    }

    private EconomyProvider currentEconomy() {
        return shutDown ? null : economyProvider;
    }

    private PermissionProvider currentPermission() {
        return shutDown ? null : permissionProvider;
    }

    private PlaceholderProvider currentPlaceholder() {
        return shutDown ? null : placeholderProvider;
    }

    private BuildCheckProvider currentBuildCheck() {
        return shutDown ? null : buildCheckProvider;
    }

    private static void requireValidAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException(
                "amount must be a finite non-negative number, got: " + amount);
        }
    }

    /** 佔位符識別檢查（與 production 門面同規則的輕量版）。 */
    private static final class IdentifierCheck {
        private IdentifierCheck() {
        }

        static void requireValid(String identifier) {
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("identifier must not be null or blank");
            }
        }
    }
}
