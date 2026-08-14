package com.smile.acelib.external;

import java.util.Objects;

/**
 * 外部插件探測結果。
 *
 * <p>承載 {@link IntegrationStatus} 與管理員可理解的 reason 字串。
 * {@link IntegrationStatus} 為 enum 常數（無法在 runtime 建立帶個別 reason 的
 * 實例），因此動態 reason（例如版本比較的目前 / 需求版本）由此結果型別承載。</p>
 *
 * <p>reason 為 null 或空白時由 factory 給定 {@link IntegrationStatus#getDefaultReason()}
 * 預設值；status 為 null 時拋 {@link NullPointerException}（不吞錯）。</p>
 *
 * @param status 整合狀態；不可為 null
 * @param reason 管理員可理解的說明；null / 空白時採用狀態預設
 * @see IntegrationStatus
 * @since 1.0.0
 */
public record IntegrationProbeResult(IntegrationStatus status, String reason) {

    /**
     * Compact constructor：驗證 status 非 null，並以狀態預設 reason 取代
     * null / 空白 reason。
     */
    public IntegrationProbeResult {
        Objects.requireNonNull(status, "status");
        if (reason == null || reason.isBlank()) {
            reason = status.getDefaultReason();
        }
    }

    /**
     * Factory：建立探測結果；{@code reason} 為 null / 空白時採用
     * {@code status} 的預設 reason。
     *
     * @param status 整合狀態；不可為 null
     * @param reason 管理員可理解的說明；可為 null（採用預設）
     * @return 不可變的 {@link IntegrationProbeResult}
     * @throws NullPointerException 當 {@code status} 為 null
     */
    public static IntegrationProbeResult of(IntegrationStatus status, String reason) {
        return new IntegrationProbeResult(status, reason);
    }
}