package com.smile.acelib.command;

import java.util.Locale;
import java.util.UUID;

/**
 * 指令執行者抽象介面。
 *
 * <p>核心 dispatcher 不直接依賴 Bukkit {@code CommandSender}，而是透過本介面
 * 抽象 sender；Bukkit adapter（{@code com.smile.acelib.command.bukkit}）負責
 * 把 Bukkit {@code CommandSender} 包成本介面。</p>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>{@link #isPlayer()} 與 {@link #asPlayer()} 一致：玩家時後者非 null，
 *       非玩家（console / RCON / 自動化測試 stub）時後者為 null</li>
 *   <li>{@link #hasPermission(String)} 對於 {@code null} permission 一律回傳 true
 *       （語意「無權限需求」）</li>
 *   <li>{@link #getLocale()} 為 best-effort；不支援 locale 的 sender（純文字
 *       console）回傳 {@link Locale#ROOT}</li>
 * </ul>
 *
 * @see PlayerHandle
 * @since 1.0.0
 */
public interface Sender {

    /**
     * 取得 sender 顯示名稱（玩家名 / "Console" / 測試 stub 名）。
     *
     * @return 永不為 null 的顯示名稱
     */
    String getName();

    /**
     * 判斷是否為線上玩家。
     *
     * @return true 表示是玩家（{@link #asPlayer()} 將回傳非 null）
     */
    boolean isPlayer();

    /**
     * 取得對應的 {@link PlayerHandle}；若非玩家回傳 null。
     *
     * @return 玩家 handle 或 null
     */
    PlayerHandle asPlayer();

    /**
     * 判斷 sender 是否擁有指定權限。
     *
     * <p>語意：</p>
     * <ul>
     *   <li>{@code permission == null} → 一律 true（語意「無權限需求」）</li>
     *   <li>{@code permission == ""} → 視為匿名權限，等同 {@code null} 處理
     *       （避免空字串誤判）</li>
     *   <li>非玩家 sender：{@code permission == null} → true；否則依 owner policy
     *       （console 通常視為擁有所有權限，由 adapter 決定）</li>
     * </ul>
     *
     * @param permission 權限節點或 null
     * @return 是否擁有該權限
     */
    boolean hasPermission(String permission);

    /**
     * 取得 sender 偏好的 locale（用於 i18n）。
     *
     * <p>不支援 locale 的 sender 回傳 {@link Locale#ROOT}。</p>
     *
     * @return 永不為 null 的 {@link Locale}
     */
    Locale getLocale();

    /**
     * 預設常數：root locale（用於不支援 locale 的 sender 與 fallback）。
     */
    static Locale defaultLocale() {
        return Locale.ROOT;
    }

    /**
     * 預設工具：把 Bukkit UUID 取得失敗的 sender 視為匿名；呼叫端應自行判斷。
     */
    static UUID requireUuid(PlayerHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("sender is not a player");
        }
        return handle.getUniqueId();
    }
}