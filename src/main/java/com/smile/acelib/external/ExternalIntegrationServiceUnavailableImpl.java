package com.smile.acelib.external;

import java.util.Objects;

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
}