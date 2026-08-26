package com.smile.acelib.bedrock;

import com.smile.acelib.form.FormService;
import java.util.Optional;
import java.util.UUID;

/**
 * 基岩版玩家服務對外 facade（Supported API）— 骨架。
 */
public interface BedrockService {

    /** 服務尚未啟用（uninitialized / bind 前）的 facade code。 */
    String NOT_READY = BedrockErrorCodes.ACELIB_BED_SERVICE_NOT_READY;

    /** 服務已停用（onDisable / reload 失敗）的 facade code。 */
    String SHUTDOWN = BedrockErrorCodes.ACELIB_BED_SERVICE_SHUTDOWN;

    /**
     * Unavailable factory（骨架）。
     */
    static BedrockService forUnavailable(String code) {
        return new BedrockServiceUnavailableImpl(code);
    }

    /**
     * Production factory（骨架）：表單服務以 absent 發送 seam 建立
     * （Floodgate 缺席語意；發送以 {@code ACELIB-FORM-001} 拒絕）。
     */
    static BedrockService forProduction(PlayerLookup lookup) {
        return new BedrockServiceImpl(lookup,
            com.smile.acelib.form.FormService.forProduction(
                com.smile.acelib.form.FormService.FormSender.absent()));
    }

    /**
     * Production factory：注入明確的表單服務實例（plugin 接線在 Floodgate
     * 啟用時攜帶 typed 發送 seam 的實例）。
     *
     * @param lookup      基岩玩家查詢 seam；不可為 null
     * @param formService 表單服務；不可為 null
     * @return production 實作實例；never null
     * @since 1.0.0
     */
    static BedrockService forProduction(PlayerLookup lookup, FormService formService) {
        return new BedrockServiceImpl(lookup, formService);
    }

    /**
     * 查詢指定玩家是否為基岩版玩家（骨架）。
     */
    boolean isBedrockPlayer(UUID playerId);

    /**
     * 查詢指定玩家的基岩資訊（骨架）。
     */
    Optional<BedrockPlayerInfo> getPlayerInfo(UUID playerId);

    /**
     * 取得表單服務（骨架）。
     */
    FormService forms();

    /**
     * 取得當前模組狀態。
     */
    String getModuleStatus();

    /**
     * 停用服務（冪等）。
     */
    void shutdown();

    /**
     * 基岩玩家查詢 seam — 隔離 Floodgate 型別的 package 邊界（骨架）。
     */
    interface PlayerLookup {

        /**
         * 查詢是否為基岩玩家。
         */
        boolean isBedrockPlayer(UUID playerId);

        /**
         * 查詢基岩玩家資訊；非基岩或離線回 empty。
         */
        Optional<BedrockPlayerInfo> lookup(UUID playerId);

        /**
         * Floodgate 缺席時的 fallback lookup（骨架）。
         */
        static PlayerLookup absent() {
            return new PlayerLookup() {
                @Override
                public boolean isBedrockPlayer(UUID playerId) {
                    return false;
                }

                @Override
                public Optional<BedrockPlayerInfo> lookup(UUID playerId) {
                    return Optional.empty();
                }
            };
        }
    }
}
