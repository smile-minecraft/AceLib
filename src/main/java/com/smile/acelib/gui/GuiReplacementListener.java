package com.smile.acelib.gui;

import java.util.UUID;

/**
 * GUI 被取代通知（Supported API）。
 *
 * <p>每位玩家同時只能看到一個 GUI：當別的 plugin 開啟新 GUI 取代本作用域的
 * GUI 時，原擁有者的作用域會收到本通知（經
 * {@link GuiScope#onReplaced} 註冊）。通知在取代呼叫端執行緒內同步觸發，
 * 不持有內部鎖；listener 不得做長時間工作或直接操作 Bukkit／跨 region 狀態，
 * 需要更新遊戲狀態時請經安全排程派送。</p>
 *
 * @see GuiScope#onReplaced(GuiReplacementListener)
 * @since 1.4.0
 */
@FunctionalInterface
public interface GuiReplacementListener {

    /**
     * 本作用域的 GUI 被取代時呼叫。
     *
     * @param playerUuid 玩家 UUID；永不為 null
     * @param oldSession 被取代的 session（本作用域擁有）；永不為 null
     * @param newSession 取代的新 session（他 plugin 擁有）；永不為 null
     */
    void onReplaced(UUID playerUuid, GuiSession oldSession, GuiSession newSession);
}
