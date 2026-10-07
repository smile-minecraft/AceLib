package com.smile.acelib.external;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * 未啟用 / 已停用狀態下的可診斷 facade（共同契約）。
 *
 * <p>任何狀態下呼叫本類別的查詢，都會回傳 {@link IntegrationStatus#INIT_FAILED}
 * 結果並附帶對應的 {@code NOT_READY} / {@code SHUTDOWN} 說明 —
 * <strong>永不為 null，絕不丟例外（除了 null inputs 的契約例外）</strong>。
 * 後續插件於 onEnable 之前或 plugin disable 之後呼叫
 * {@code AceLibApi.getExternalIntegrationService()} 即取得此 instance。</p>
 *
 * <p>code 為 {@link ExternalIntegrationService#NOT_READY} /
 * {@link ExternalIntegrationService#SHUTDOWN}（皆為 {@code ACELIB-EXT-*} 常數）；
 * 本類別簽章與語意不變。</p>
 *
 * @see ExternalIntegrationService
 */
final class ExternalIntegrationServiceUnavailableImpl implements ExternalIntegrationService {

    /** 標記本 facade 為「未啟用」或「已停用」。 */
    private final String code;

    ExternalIntegrationServiceUnavailableImpl(String code) {
        if (!ExternalIntegrationService.NOT_READY.equals(code)
                && !ExternalIntegrationService.SHUTDOWN.equals(code)) {
            throw new IllegalArgumentException(
                "ExternalIntegrationServiceUnavailableImpl.code 必須為 NOT_READY 或 SHUTDOWN，實際: "
                    + code);
        }
        this.code = code;
    }

    // ----- contract: null inputs throw IllegalArgumentException -----

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + name + "] must not be null");
        }
    }

    @Override
    public IntegrationProbeResult getStatus(String integrationId) {
        requireNonNull(integrationId, "integrationId");
        return IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
            "external integration service is unavailable: " + code);
    }

    @Override
    public String getModuleStatus() {
        return Objects.equals(code, ExternalIntegrationService.SHUTDOWN)
            ? "FAILED" : "NOT_INITIALIZED";
    }

    @Override
    public void shutdown() {
        // no-op for unavailable facade: idempotent + 留 audit trail 只留於 status 字串
    }

    // ----- 業務門面：未啟用／已停用一律回 UNAVAILABLE（錯誤代碼為服務層級代碼） -----

    @Override
    public EconomyResult getBalance(OfflinePlayer player) {
        requireNonNull(player, "player");
        return EconomyResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public EconomyResult withdraw(OfflinePlayer player, double amount) {
        requireNonNull(player, "player");
        requireValidAmount(amount);
        return EconomyResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public EconomyResult deposit(OfflinePlayer player, double amount) {
        requireNonNull(player, "player");
        requireValidAmount(amount);
        return EconomyResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public void setEconomyProvider(EconomyProvider provider) {
        requireNonNull(provider, "provider");
        // 未啟用 facade 不接受替換：直接拒絕，避免呼叫端誤以為已生效。
        throw new IllegalStateException(
            "external integration service is unavailable: " + code);
    }

    @Override
    public void clearEconomyProvider() {
        // no-op：本來就沒有任何提供者。
    }

    @Override
    public PermissionResult getPermissionGroups(UUID playerId) {
        requireNonNull(playerId, "playerId");
        return PermissionResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public void setPermissionProvider(PermissionProvider provider) {
        requireNonNull(provider, "provider");
        throw new IllegalStateException(
            "external integration service is unavailable: " + code);
    }

    @Override
    public void clearPermissionProvider() {
        // no-op：本來就沒有任何提供者。
    }

    @Override
    public ExternalOperationResult registerPlaceholder(String identifier,
            PlaceholderHandler handler) {
        requireValidIdentifier(identifier);
        requireNonNull(handler, "handler");
        return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public ExternalOperationResult unregisterPlaceholder(String identifier) {
        requireValidIdentifier(identifier);
        return ExternalOperationResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public void setPlaceholderProvider(PlaceholderProvider provider) {
        requireNonNull(provider, "provider");
        throw new IllegalStateException(
            "external integration service is unavailable: " + code);
    }

    @Override
    public void clearPlaceholderProvider() {
        // no-op：本來就沒有任何提供者。
    }

    @Override
    public BuildCheckResult canBuild(UUID playerId, String worldName, int x, int y, int z) {
        requireNonNull(playerId, "playerId");
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("[worldName] must not be null or blank");
        }
        return BuildCheckResult.failure(ExternalResultState.UNAVAILABLE, code,
            "external integration service is unavailable: " + code);
    }

    @Override
    public void setBuildCheckProvider(BuildCheckProvider provider) {
        requireNonNull(provider, "provider");
        throw new IllegalStateException(
            "external integration service is unavailable: " + code);
    }

    @Override
    public void clearBuildCheckProvider() {
        // no-op：本來就沒有任何提供者。
    }

    private static void requireValidAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException(
                "amount must be a finite non-negative number, got: " + amount);
        }
    }

    private static void requireValidIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(
                "[identifier] must not be null or blank");
        }
    }
}