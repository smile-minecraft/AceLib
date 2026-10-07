package com.smile.acelib.external;

import java.util.UUID;

/**
 * 建造查詢提供者（AceLib 自有 SPI）。
 *
 * <p>判斷某玩家能否在某位置建造。本期 AceLib 不內建任何外部區域保護 adapter；
 * 無提供者時門面一律回不可用（且不得解讀為允許）。下游可實作本介面
 *（包裝自選的區域保護 plugin）並以
 * {@link ExternalIntegrationService#setBuildCheckProvider(BuildCheckProvider)} 註冊。</p>
 *
 * <p>執行緒：查詢資料（玩家識別＋世界名稱＋座標）由呼叫端提供，本介面不觸碰
 * 世界／實體狀態；提供者實作自負其查詢的執行緒責任（例如必須在 region 執行緒
 * 讀取的保護資料，應由實作自行排程或記錄限制）。</p>
 *
 * @see BuildCheckResult
 * @see ExternalIntegrationService
 * @since 1.4.0
 */
public interface BuildCheckProvider {

    /**
     * 查詢玩家能否在指定位置建造。
     *
     * @param playerId 玩家識別；不可為 null
     * @param worldName 世界名稱；不可為 null／空白
     * @param x 方塊 X 座標
     * @param y 方塊 Y 座標
     * @param z 方塊 Z 座標
     * @return 永不為 null 的 {@link BuildCheckResult}
     */
    BuildCheckResult canBuild(UUID playerId, String worldName, int x, int y, int z);
}
