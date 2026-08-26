package com.smile.acelib.bedrock;

import com.smile.acelib.form.FormService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link BedrockService} production 實作（Internal）。
 *
 * <p>所有查詢委派給建構時注入的 {@link PlayerLookup} seam；Floodgate 缺席時
 * plugin 端注入 {@link PlayerLookup#absent()}，查詢安全回覆「非基岩玩家」，
 * 對呼叫端零影響。shutdown 後查詢一律以 {@code ACELIB-BED-002} 拒絕。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link com.smile.acelib.AceLibApi#getBedrockService()} 取得介面。</p>
 *
 * @since 1.0.0
 */
final class BedrockServiceImpl implements BedrockService {

    private final PlayerLookup lookup;
    private final FormService formService;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    BedrockServiceImpl(PlayerLookup lookup, FormService formService) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.formService = Objects.requireNonNull(formService, "formService");
    }

    // ----- contract: null inputs throw IllegalArgumentException -----

    private void requireReadyAndNonNull(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException(
                "[" + BedrockErrorCodes.ACELIB_BED_INVALID_INPUT + "] playerId must not be null");
        }
        if (stopped.get()) {
            throw new IllegalStateException(rejectionMessage(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN));
        }
    }

    private static String rejectionMessage(String code) {
        return "[" + code + "] bedrock service is unavailable: " + code;
    }

    @Override
    public boolean isBedrockPlayer(UUID playerId) {
        requireReadyAndNonNull(playerId);
        return lookup.isBedrockPlayer(playerId);
    }

    @Override
    public Optional<BedrockPlayerInfo> getPlayerInfo(UUID playerId) {
        requireReadyAndNonNull(playerId);
        return lookup.lookup(playerId);
    }

    @Override
    public FormService forms() {
        if (stopped.get()) {
            throw new IllegalStateException(rejectionMessage(BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN));
        }
        return formService;
    }

    @Override
    public String getModuleStatus() {
        return stopped.get() ? "FAILED" : "READY";
    }

    @Override
    public void shutdown() {
        stopped.set(true);
    }
}
