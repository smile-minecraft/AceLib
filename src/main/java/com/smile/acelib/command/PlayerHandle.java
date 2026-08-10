package com.smile.acelib.command;

import java.util.Locale;
import java.util.UUID;

/**
 * 玩家 sender 的語意化 handle（Plan §十一 Phase 6）。
 *
 * <p>純抽象，不暴露 Bukkit Player；Bukkit adapter 透過實作本介面把
 * {@code org.bukkit.entity.Player} 包起來。</p>
 *
 * <p>注意：{@link #isOnline()} 與 {@link Sender#isPlayer()} 語意不同 —
 * {@code Sender.isPlayer()} 表示「sender 本身是玩家物件」；
 * {@code PlayerHandle.isOnline()} 表示「該玩家是否仍在線上」。
 * 玩家離線後 sender 仍可能是玩家物件，但 {@code asPlayer().isOnline() = false}。</p>
 *
 * @see Sender
 * @since Phase 6 (Plan §十一)
 */
public interface PlayerHandle {

    /**
     * 取得玩家 UUID（穩定識別）。
     *
     * @return 永不為 null 的玩家 UUID
     */
    UUID getUniqueId();

    /**
     * 判斷玩家是否仍連線。
     *
     * @return true 表示在線上
     */
    boolean isOnline();

    /**
     * 取得玩家顯示名稱。
     *
     * @return 永不為 null 的玩家名稱
     */
    String getName();

    /**
     * 取得玩家偏好的 locale。
     *
     * <p>不支援 locale 的實作回傳 {@link Locale#ROOT}。</p>
     *
     * @return 永不為 null 的 {@link Locale}
     */
    Locale getLocale();
}