package com.smile.acelib.bedrock;

import com.smile.acelib.form.FormService;
import java.util.UUID;

/**
 * 未啟用 / 已停用狀態下的可診斷 facade（Internal）。
 *
 * <p>任何狀態下呼叫本類別的操作（含 {@code forms()}），都會拋出攜帶
 * {@link BedrockErrorCodes#ACELIB_BED_SERVICE_NOT_READY} 或
 * {@link BedrockErrorCodes#ACELIB_BED_SERVICE_SHUTDOWN} 的
 * {@link IllegalStateException} — 拒絕語意讓呼叫端無法把「服務不可用」誤讀為
 * 「此玩家不是基岩玩家」。下游於 onEnable 之前或 plugin disable 之後呼叫
 * {@code AceLibApi.getBedrockService()} 即取得此 instance。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link BedrockService#forUnavailable(String)} 取得介面。</p>
 *
 * @since 1.0.0
 */
final class BedrockServiceUnavailableImpl implements BedrockService {

    /** 標記本 facade 為「未啟用」或「已停用」；僅接受 NOT_READY / SHUTDOWN。 */
    private final String code;

    /** 是否已呼叫過 {@link #shutdown()}（冪等；影響模組狀態呈現）。 */
    private volatile boolean shutDown = false;

    BedrockServiceUnavailableImpl(String code) {
        if (!BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY.equals(code)
                && !BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN.equals(code)) {
            throw new IllegalArgumentException(
                "BedrockServiceUnavailableImpl.code must be NOT_READY or SHUTDOWN, got: " + code);
        }
        this.code = code;
    }

    private IllegalStateException rejection() {
        return new IllegalStateException(
            "[" + code + "] bedrock service is unavailable: " + code);
    }

    private static void requireNonNull(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException(
                "[" + BedrockErrorCodes.ACELIB_BED_INVALID_INPUT + "] playerId must not be null");
        }
    }

    @Override
    public boolean isBedrockPlayer(UUID playerId) {
        requireNonNull(playerId);
        throw rejection();
    }

    @Override
    public java.util.Optional<BedrockPlayerInfo> getPlayerInfo(UUID playerId) {
        requireNonNull(playerId);
        throw rejection();
    }

    @Override
    public FormService forms() {
        throw rejection();
    }

    @Override
    public String getModuleStatus() {
        return shutDown || BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN.equals(code)
            ? "FAILED" : "NOT_INITIALIZED";
    }

    @Override
    public void shutdown() {
        // unavailable facade 為冪等 no-op；shutdown 後模組狀態呈現 FAILED
        shutDown = true;
    }
}
