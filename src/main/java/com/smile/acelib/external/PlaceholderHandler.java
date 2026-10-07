package com.smile.acelib.external;

import java.util.UUID;

/**
 * 下游自有佔位符處理器（AceLib 自有 SPI）。
 *
 * <p>下游經 {@link ExternalIntegrationService#registerPlaceholder(String, PlaceholderHandler)}
 * 註冊自有佔位符時提供本處理器；內建 PlaceholderAPI adapter 會為每個 identifier
 * 建立對應的 {@code PlaceholderExpansion} 子類別並轉交呼叫。</p>
 *
 * @see PlaceholderProvider
 * @since 1.4.0
 */
@FunctionalInterface
public interface PlaceholderHandler {

    /**
     * 解析一次佔位符請求。
     *
     * @param playerId 請求玩家識別；主控台或未知玩家時為 null
     * @param params 佔位符參數（identifier 之後的部分）；永不為 null（可為空字串）
     * @return 解析後文字；回 null 表示「無值」（PlaceholderAPI 將保留佔位符原文）
     */
    String onRequest(UUID playerId, String params);
}
