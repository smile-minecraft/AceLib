package com.smile.acelib.external;

import org.bukkit.OfflinePlayer;

/**
 * 經濟提供者（AceLib 自有 SPI）。
 *
 * <p>內建實作為 Vault legacy 經濟的純反射包裝（零外部 import）；下游可以
 * {@link ExternalIntegrationService#setEconomyProvider(EconomyProvider)} 替換為
 * 自有實作，{@code clearEconomyProvider()} 恢復內建。提供者缺席／停用時門面回
 * 不可用結果，不做任何本地記帳。</p>
 *
 * <p>執行緒：實作只轉交外部經濟服務；提供者端可能有 I/O，呼叫端可在非同步
 * 執行緒呼叫，本門面不觸碰世界／實體狀態。</p>
 *
 * <p>AceLib 只包裝提供者：不自製經濟系統、不做領域授權判斷、不做扣款去重或
 * 持久操作紀錄；重複呼叫的後果由提供者語意決定。</p>
 *
 * @see EconomyResult
 * @see ExternalIntegrationService
 * @since 1.4.0
 */
public interface EconomyProvider {

    /**
     * 查詢餘額。
     *
     * @param player 查詢對象；不可為 null
     * @return 永不為 null 的 {@link EconomyResult}
     */
    EconomyResult getBalance(OfflinePlayer player);

    /**
     * 扣款。
     *
     * @param player 扣款對象；不可為 null
     * @param amount 金額；必須為有限非負數
     * @return 永不為 null 的 {@link EconomyResult}（成功時餘額為扣款後餘額）
     */
    EconomyResult withdraw(OfflinePlayer player, double amount);

    /**
     * 入帳。
     *
     * @param player 入帳對象；不可為 null
     * @param amount 金額；必須為有限非負數
     * @return 永不為 null 的 {@link EconomyResult}（成功時餘額為入帳後餘額）
     */
    EconomyResult deposit(OfflinePlayer player, double amount);
}
