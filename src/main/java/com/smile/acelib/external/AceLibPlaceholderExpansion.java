package com.smile.acelib.external;

import com.smile.acelib.AceLibVersion;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * 承載下游自有佔位符的 PlaceholderAPI {@code PlaceholderExpansion} 子類別
 *（Internal）。
 *
 * <p>PlaceholderAPI 的 {@code PlaceholderExpansion} 為抽象類別，必須以子類別
 * 註冊，無法純反射；本類別即該子類別（外部型別集中於此）。{@link #persist()}
 * 回 true（PAPI 自身 reload 時保留註冊）；AceLib 服務停用／reload／提供者被
 * 替換時由 {@link PlaceholderApiPlaceholderProvider#close()} 顯式取消註冊，
 * 不殘留。</p>
 *
 * <p>{@link #onRequest(OfflinePlayer, String)} 只轉交下游
 * {@link PlaceholderHandler}；處理器拋例外時回 null（PlaceholderAPI 語意為
 * 「無值」，保留佔位符原文），不讓例外逃逸進 PAPI 執行緒。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴。</p>
 *
 * @since 1.4.0
 */
public final class AceLibPlaceholderExpansion
        extends me.clip.placeholderapi.expansion.PlaceholderExpansion {

    private final String identifier;
    private final PlaceholderHandler handler;

    /**
     * 建構子。
     *
     * @param identifier 佔位符識別；不可為 null／空白
     * @param handler 解析處理器；不可為 null
     */
    public AceLibPlaceholderExpansion(String identifier, PlaceholderHandler handler) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be null or blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.identifier = identifier;
        this.handler = handler;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "AceLib";
    }

    @Override
    public String getVersion() {
        return AceLibVersion.VERSION;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        UUID playerId = (player == null) ? null : player.getUniqueId();
        String effectiveParams = (params == null) ? "" : params;
        try {
            return handler.onRequest(playerId, effectiveParams);
        } catch (Throwable t) {
            // 處理器例外不得逃逸進 PAPI 執行緒；回 null 讓 PAPI 保留佔位符原文。
            return null;
        }
    }
}
