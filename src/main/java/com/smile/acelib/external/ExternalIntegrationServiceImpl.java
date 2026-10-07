package com.smile.acelib.external;

import com.smile.acelib.diagnostics.ModuleState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;

/**
 * 實際 {@link ExternalIntegrationService} 實作（Internal）：包裝 {@link IntegrationRegistry}，
 * 提供整合狀態查詢、模組狀態聚合、四類業務門面（經濟／權限／佔位符／建造查詢）
 * 與提供者替換。
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
 * <h2>提供者解析（每次呼叫重新解析，不跨停用快取）</h2>
 * <p>業務呼叫的提供者解析順序：已停用 → 一律 {@code UNAVAILABLE}；下游覆寫存在 →
 * 使用覆寫；否則使用建構時注入的內建提供者；內建為 null（對應 adapter 未啟用）→
 * {@code UNAVAILABLE}。{@link #shutdown()} 會丟棄覆寫與內建引用並清理佔位符註冊，
 * 之後任何業務呼叫皆為 {@code UNAVAILABLE}，{@code set*} 則拋
 * {@link IllegalStateException}（fail-closed）。</p>
 *
 * <p>{@link #toModuleState()} 將上述聚合結果轉換為 {@code DiagnosticsService} 的
 * {@link ModuleState}，供 {@code AceLibPlugin} 後續呼叫
 * {@code DiagnosticsService.registerModuleState(String, ModuleState)} 使用。</p>
 *
 * @see IntegrationRegistry
 * @see ExternalIntegrationErrorCodes
 * @since 1.0.0
 */
public final class ExternalIntegrationServiceImpl implements ExternalIntegrationService {

    /** 診斷模組名稱（對應 DiagnosticsService.MODULE_INTEGRATION）。 */
    static final String MODULE_NAME = "integration";

    /** 清理路徑日誌（沿用 {@code Logger.getLogger("AceLib")} 慣例）。 */
    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final IntegrationRegistry registry;
    private volatile boolean shutDown = false;

    private volatile EconomyProvider builtinEconomy;
    private volatile PermissionProvider builtinPermission;
    private volatile PlaceholderProvider builtinPlaceholder;
    private volatile BuildCheckProvider builtinBuildCheck;

    private volatile EconomyProvider economyOverride;
    private volatile PermissionProvider permissionOverride;
    private volatile PlaceholderProvider placeholderOverride;
    private volatile BuildCheckProvider buildCheckOverride;

    /**
     * 建構子（內建提供者全缺席：任何業務呼叫皆回 {@code UNAVAILABLE}）。
     *
     * @param registry 被包裝的整合 registry；不可為 null
     * @throws NullPointerException 當 {@code registry} 為 null
     */
    public ExternalIntegrationServiceImpl(IntegrationRegistry registry) {
        this(registry, null, null, null, null);
    }

    /**
     * 完整建構子。
     *
     * @param registry 被包裝的整合 registry；不可為 null
     * @param economy 內建經濟提供者；可為 null（缺席）
     * @param permission 內建權限提供者；可為 null（缺席）
     * @param placeholder 內建佔位符提供者；可為 null（缺席）
     * @param buildCheck 內建建造查詢提供者；可為 null（本期恆為 null）
     * @throws NullPointerException 當 {@code registry} 為 null
     */
    public ExternalIntegrationServiceImpl(IntegrationRegistry registry,
            EconomyProvider economy, PermissionProvider permission,
            PlaceholderProvider placeholder, BuildCheckProvider buildCheck) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.builtinEconomy = economy;
        this.builtinPermission = permission;
        this.builtinPlaceholder = placeholder;
        this.builtinBuildCheck = buildCheck;
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
        PlaceholderProvider placeholder = placeholderOverride != null
            ? placeholderOverride : builtinPlaceholder;
        if (placeholder != null) {
            try {
                placeholder.close();
            } catch (Exception | LinkageError e) {
                // 清理應盡力而為；失敗記一行日誌但不中斷 adapter 停用與引用丟棄。
                LOGGER.warning("["
                    + ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED
                    + "] placeholder cleanup failed on shutdown: " + e);
            }
        }
        economyOverride = null;
        permissionOverride = null;
        placeholderOverride = null;
        buildCheckOverride = null;
        builtinEconomy = null;
        builtinPermission = null;
        builtinPlaceholder = null;
        builtinBuildCheck = null;
        registry.shutdownAll();
    }

    // ----- 經濟 -----

    @Override
    public EconomyResult getBalance(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        EconomyProvider provider = currentEconomy();
        if (provider == null) {
            return EconomyResult.failure(ExternalResultState.UNAVAILABLE,
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
                "economy provider is not available");
        }
        return callEconomyBalance(provider, player);
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
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
                "economy provider is not available");
        }
        return callEconomyWithdraw(provider, player, amount);
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
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_UNAVAILABLE,
                "economy provider is not available");
        }
        return callEconomyDeposit(provider, player, amount);
    }

    @Override
    public void setEconomyProvider(EconomyProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException("external integration service has been shut down");
        }
        economyOverride = provider;
    }

    @Override
    public void clearEconomyProvider() {
        economyOverride = null;
    }

    // ----- 權限 -----

    @Override
    public PermissionResult getPermissionGroups(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        PermissionProvider provider = currentPermission();
        if (provider == null) {
            return PermissionResult.failure(ExternalResultState.UNAVAILABLE,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_UNAVAILABLE,
                "permission provider is not available");
        }
        return callPermissionGroups(provider, playerId);
    }

    @Override
    public void setPermissionProvider(PermissionProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException("external integration service has been shut down");
        }
        permissionOverride = provider;
    }

    @Override
    public void clearPermissionProvider() {
        permissionOverride = null;
    }

    // ----- 佔位符 -----

    @Override
    public ExternalOperationResult registerPlaceholder(String identifier,
            PlaceholderHandler handler) {
        requireValidIdentifier(identifier);
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        PlaceholderProvider provider = currentPlaceholder();
        if (provider == null) {
            return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_UNAVAILABLE,
                "placeholder provider is not available");
        }
        return callPlaceholderRegister(provider, identifier, handler);
    }

    @Override
    public ExternalOperationResult unregisterPlaceholder(String identifier) {
        requireValidIdentifier(identifier);
        PlaceholderProvider provider = currentPlaceholder();
        if (provider == null) {
            return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_UNAVAILABLE,
                "placeholder provider is not available");
        }
        return callPlaceholderUnregister(provider, identifier);
    }

    @Override
    public void setPlaceholderProvider(PlaceholderProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException("external integration service has been shut down");
        }
        PlaceholderProvider replaced = placeholderOverride != null
            ? placeholderOverride : builtinPlaceholder;
        placeholderOverride = provider;
        if (replaced != null && replaced != provider) {
            try {
                replaced.close();
            } catch (Exception | LinkageError e) {
                // 被替換提供者的清理應盡力而為；失敗記一行日誌但不中斷替換。
                LOGGER.warning("["
                    + ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED
                    + "] replaced placeholder provider cleanup failed: " + e);
            }
        }
    }

    @Override
    public void clearPlaceholderProvider() {
        PlaceholderProvider replaced = placeholderOverride;
        placeholderOverride = null;
        if (replaced != null) {
            try {
                replaced.close();
            } catch (Exception | LinkageError e) {
                // 被替換提供者的清理應盡力而為；失敗記一行日誌但不中斷恢復內建。
                LOGGER.warning("["
                    + ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED
                    + "] replaced placeholder provider cleanup failed: " + e);
            }
        }
    }

    // ----- 建造查詢 -----

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
                ExternalIntegrationErrorCodes.ACELIB_EXT_BUILD_UNAVAILABLE,
                "no build-check provider is registered");
        }
        return callBuildCheck(provider, playerId, worldName, x, y, z);
    }

    @Override
    public void setBuildCheckProvider(BuildCheckProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (shutDown) {
            throw new IllegalStateException("external integration service has been shut down");
        }
        buildCheckOverride = provider;
    }

    @Override
    public void clearBuildCheckProvider() {
        buildCheckOverride = null;
    }

    // ----- 內部：提供者解析（每次呼叫重新讀取 volatile，不快取實例） -----

    private EconomyProvider currentEconomy() {
        if (shutDown) {
            return null;
        }
        EconomyProvider override = economyOverride;
        return override != null ? override : builtinEconomy;
    }

    private PermissionProvider currentPermission() {
        if (shutDown) {
            return null;
        }
        PermissionProvider override = permissionOverride;
        return override != null ? override : builtinPermission;
    }

    private PlaceholderProvider currentPlaceholder() {
        if (shutDown) {
            return null;
        }
        PlaceholderProvider override = placeholderOverride;
        return override != null ? override : builtinPlaceholder;
    }

    private BuildCheckProvider currentBuildCheck() {
        if (shutDown) {
            return null;
        }
        BuildCheckProvider override = buildCheckOverride;
        return override != null ? override : builtinBuildCheck;
    }

    private static void requireValidAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException(
                "amount must be a finite non-negative number, got: " + amount);
        }
    }

    private static void requireValidIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be null or blank");
        }
    }

    // ----- 內部：提供者呼叫（例外轉明確失敗，不逃逸） -----

    private static EconomyResult callEconomyBalance(EconomyProvider provider,
            OfflinePlayer player) {
        try {
            EconomyResult result = provider.getBalance(player);
            return requireEconomyResult(result);
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
                "economy balance lookup failed: " + e);
        }
    }

    private static EconomyResult callEconomyWithdraw(EconomyProvider provider,
            OfflinePlayer player, double amount) {
        try {
            EconomyResult result = provider.withdraw(player, amount);
            return requireEconomyResult(result);
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
                "economy withdraw failed: " + e);
        }
    }

    private static EconomyResult callEconomyDeposit(EconomyProvider provider,
            OfflinePlayer player, double amount) {
        try {
            EconomyResult result = provider.deposit(player, amount);
            return requireEconomyResult(result);
        } catch (Exception e) {
            return EconomyResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
                "economy deposit failed: " + e);
        }
    }

    private static EconomyResult requireEconomyResult(EconomyResult result) {
        if (result == null) {
            return EconomyResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_ECONOMY_FAILED,
                "economy provider returned null result");
        }
        return result;
    }

    private static PermissionResult callPermissionGroups(PermissionProvider provider,
            UUID playerId) {
        try {
            PermissionResult result = provider.getPermissionGroups(playerId);
            if (result == null) {
                return PermissionResult.failure(ExternalResultState.FAILED,
                    ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED,
                    "permission provider returned null result");
            }
            return result;
        } catch (Exception e) {
            return PermissionResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED,
                "permission lookup failed: " + e);
        }
    }

    private static ExternalOperationResult callPlaceholderRegister(PlaceholderProvider provider,
            String identifier, PlaceholderHandler handler) {
        try {
            ExternalOperationResult result = provider.registerPlaceholder(identifier, handler);
            if (result == null) {
                return ExternalOperationResult.failure(ExternalResultState.FAILED,
                    ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                    "placeholder provider returned null result");
            }
            return result;
        } catch (Exception | LinkageError e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder registration failed: " + e);
        }
    }

    private static ExternalOperationResult callPlaceholderUnregister(
            PlaceholderProvider provider, String identifier) {
        try {
            ExternalOperationResult result = provider.unregisterPlaceholder(identifier);
            if (result == null) {
                return ExternalOperationResult.failure(ExternalResultState.FAILED,
                    ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                    "placeholder provider returned null result");
            }
            return result;
        } catch (Exception | LinkageError e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder unregistration failed: " + e);
        }
    }

    private static BuildCheckResult callBuildCheck(BuildCheckProvider provider, UUID playerId,
            String worldName, int x, int y, int z) {
        try {
            BuildCheckResult result = provider.canBuild(playerId, worldName, x, y, z);
            if (result == null) {
                return BuildCheckResult.failure(ExternalResultState.FAILED,
                    ExternalIntegrationErrorCodes.ACELIB_EXT_BUILD_FAILED,
                    "build-check provider returned null result");
            }
            return result;
        } catch (Exception e) {
            return BuildCheckResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_BUILD_FAILED,
                "build check failed: " + e);
        }
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
