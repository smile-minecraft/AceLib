package com.smile.acelib.external;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.LinkedPlayer;

/**
 * Floodgate typed 查詢 seam 實作（package-private，Internal）。
 *
 * <p>所有 {@code org.geysermc.*} 參考都被隔離在 external 套件的 package-private
 * seam 類別內；目前允許 import 的類別僅三個：本類別（玩家查詢）、
 * {@link FloodgateFormSender}（表單發送）與 {@link CumulusFormTranslator}
 * （Cumulus 翻譯層）。對外交付的 {@link BedrockService.PlayerLookup} 只含
 * {@link UUID} 與 {@link Optional}&lt;{@link BedrockPlayerInfo}&gt;。本類別僅在
 * adapter 探測確認 marker 存在後才會被載入，Floodgate 缺席時不進入 classpath。</p>
 *
 * <h2>列舉映射策略</h2>
 * <p>上游列舉以「名稱字串」鏡射到 AceLib 自有列舉（名稱逐一對應）；上游新增未知
 * 常數、或名稱大小寫不符時，一律映射為 {@code UNKNOWN}，不拋例外。名稱式映射讓
 * 「未知值」路徑可直接以字串測試驅動，不需 mock 上游 enum（JVM 不允許 enum 子類化）。</p>
 *
 * @since 1.0.0
 */
final class FloodgateBedrockPlayerLookup implements BedrockService.PlayerLookup {

    /**
     * 延遲綁定的 api 供應器：每次查詢重新取得 instance。
     *
     * <p>不於 adapter 初始化時呼叫 {@code FloodgateApi.getInstance()}——該 static
     * 存取子在 floodgate plugin 完成自身啟動前會失敗；探測確認 plugin 啟用後，
     * 查詢時才綁定是安全的，且 reload 後自動取到新 instance。</p>
     */
    private final Supplier<FloodgateApi> apiSupplier;

    FloodgateBedrockPlayerLookup(Supplier<FloodgateApi> apiSupplier) {
        this.apiSupplier = Objects.requireNonNull(apiSupplier, "apiSupplier");
    }

    /**
     * 將上游 DeviceOs 名稱映射為 AceLib 列舉；未知 / null 名稱回 UNKNOWN。
     *
     * @param name 上游 {@code DeviceOs} 常數名稱；可為 null
     * @return 對應的 AceLib 列舉常數；永不為 null
     */
    static BedrockPlayerInfo.DeviceOs mapDeviceOsName(String name) {
        if (name == null) {
            return BedrockPlayerInfo.DeviceOs.UNKNOWN;
        }
        try {
            return BedrockPlayerInfo.DeviceOs.valueOf(name);
        } catch (IllegalArgumentException e) {
            // 上游新增未知裝置常數：防禦為 UNKNOWN，不拋例外
            return BedrockPlayerInfo.DeviceOs.UNKNOWN;
        }
    }

    /**
     * 將上游 InputMode 名稱映射為 AceLib 列舉；未知 / null 名稱回 UNKNOWN。
     *
     * @param name 上游 {@code InputMode} 常數名稱；可為 null
     * @return 對應的 AceLib 列舉常數；永不為 null
     */
    static BedrockPlayerInfo.InputMode mapInputModeName(String name) {
        if (name == null) {
            return BedrockPlayerInfo.InputMode.UNKNOWN;
        }
        try {
            return BedrockPlayerInfo.InputMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            // 上游新增未知輸入模式常數：防禦為 UNKNOWN，不拋例外
            return BedrockPlayerInfo.InputMode.UNKNOWN;
        }
    }

    @Override
    public boolean isBedrockPlayer(UUID playerId) {
        requireNonNullId(playerId);
        return apiSupplier.get().isFloodgatePlayer(playerId);
    }

    @Override
    public Optional<BedrockPlayerInfo> lookup(UUID playerId) {
        requireNonNullId(playerId);
        FloodgateApi api = apiSupplier.get();
        if (!api.isFloodgatePlayer(playerId)) {
            return Optional.empty();
        }
        FloodgatePlayer player = api.getPlayer(playerId);
        if (player == null) {
            // isFloodgatePlayer 與 getPlayer 之間玩家離線的競態：視為查無資料
            return Optional.empty();
        }
        LinkedPlayer linked = player.getLinkedPlayer();
        return Optional.of(new BedrockPlayerInfo(
            playerId,
            player.getUsername(),
            mapDeviceOsName(player.getDeviceOs() == null ? null : player.getDeviceOs().name()),
            mapInputModeName(player.getInputMode() == null ? null : player.getInputMode().name()),
            player.getLanguageCode() == null ? "" : player.getLanguageCode(),
            linked == null ? BedrockPlayerInfo.LinkState.UNLINKED : BedrockPlayerInfo.LinkState.LINKED,
            linked == null ? null : linked.getJavaUsername()));
    }

    private static void requireNonNullId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException(
                "[" + com.smile.acelib.bedrock.BedrockErrorCodes.ACELIB_BED_INVALID_INPUT
                    + "] playerId must not be null");
        }
    }
}
