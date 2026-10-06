package com.smile.acelib.message;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * 玩家語系解析器（可替換）。
 *
 * <p>決定某位玩家當下應該用哪個語系讀文案。AceLib 不規定偏好存在哪裡：
 * 下游可以用自己的資料庫、權限、客戶端語言或固定對照表實作此介面，
 * 透過 {@link MessageScope#setResolver(PlayerLocaleResolver)} 換上。</p>
 *
 * <p>解析器抛例外時，呼叫端退回作用域預設語系並留下可追蹤警告，不中斷發送。</p>
 *
 * @since 1.4.0
 */
@FunctionalInterface
public interface PlayerLocaleResolver {

    /**
     * 解析玩家的語系。
     *
     * @param player 目標玩家；可為 null（實作應回退回預設語系，不拋例外）
     * @return 解析到的語系；不可為 null（回傳 null 視為實作錯誤，呼叫端退回預設）
     */
    Locale resolve(Player player);

    /**
     * 預設解析器：跟隨 {@link Player#locale()}。
     *
     * <p>{@code player} 為 null、{@code locale()} 為 null／{@link Locale#ROOT}／
     * 拋例外時，一律回退到 {@code fallback}，不拋例外。</p>
     *
     * @param fallback 回退語系；不可為 null
     * @return 預設解析器；never null
     */
    static PlayerLocaleResolver playerLocale(Locale fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return player -> {
            if (player == null) {
                return fallback;
            }
            try {
                Locale locale = player.locale();
                if (locale == null || Locale.ROOT.equals(locale)) {
                    return fallback;
                }
                return locale;
            } catch (Throwable t) {
                return fallback;
            }
        };
    }
}
