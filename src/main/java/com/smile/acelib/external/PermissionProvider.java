package com.smile.acelib.external;

import java.util.UUID;

/**
 * 權限提供者（AceLib 自有 SPI）。
 *
 * <p>內建實作為 LuckPerms 的 typed 包裝（外部型別集中於僅在 AVAILABLE 後載入的
 * 內部持有者）；下游可以
 * {@link ExternalIntegrationService#setPermissionProvider(PermissionProvider)} 替換為
 * 自有實作，{@code clearPermissionProvider()} 恢復內建。提供者缺席／停用時門面回
 * 不可用結果；查無玩家回失敗結果，不得以空群組做授權判斷。</p>
 *
 * <p>執行緒：實作只讀取外部權限服務的快照；提供者端可能有 I/O，呼叫端可在
 * 非同步執行緒呼叫，本門面不觸碰世界／實體狀態。</p>
 *
 * <p>AceLib 只包裝提供者：不自製權限系統、不代做領域授權判斷。</p>
 *
 * @see PermissionResult
 * @see ExternalIntegrationService
 * @since 1.4.0
 */
public interface PermissionProvider {

    /**
     * 查詢玩家主要群組、所屬群組與當前情境。
     *
     * @param playerId 玩家識別；不可為 null
     * @return 永不為 null 的 {@link PermissionResult}
     */
    PermissionResult getPermissionGroups(UUID playerId);
}
