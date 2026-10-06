package com.smile.acelib.gui;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * GUI 服務的 Bukkit listener（Internal）。
 *
 * <p>五個事件：</p>
 * <ul>
 *   <li>{@link InventoryClickEvent} — 透過 {@link GuiServiceImpl#handleClick} 驗證並取消</li>
 *   <li>{@link InventoryDragEvent} — 透過 {@link GuiServiceImpl#handleDrag} 驗證並取消</li>
 *   <li>{@link InventoryCloseEvent} — 透過 {@link GuiServiceImpl#handleClose} 移除 session</li>
 *   <li>{@link AsyncPlayerChatEvent} — 命中待處理聊天輸入提示時消耗並取消，
 *       未命中放行（不影響正常聊天）</li>
 *   <li>{@link PlayerQuitEvent} — 透過 {@link GuiServiceImpl#handleQuit}
 *       清除該玩家 session、票券與輸入提示</li>
 * </ul>
 *
 * <p>listener 不持有 {@link org.bukkit.entity.Player} reference；事件觸發時
 * 透過事件自帶的玩家資訊取得當下 Player，操作完成後立即釋放 reference。
 * 聊天事件在非同步執行緒觸發 — 本 listener 只做路由判斷，
 * 輸入 consumer 仍經服務派送到玩家 region context 才執行。</p>
 */
final class GuiListener implements Listener {

    private final GuiServiceImpl service;

    GuiListener(GuiServiceImpl service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onClick(InventoryClickEvent event) {
        service.handleClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onDrag(InventoryDragEvent event) {
        service.handleDrag(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onClose(InventoryCloseEvent event) {
        service.handleClose(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        GuiResult routed = service.handleChatInput(player.getUniqueId(),
            event.getMessage());
        if (routed.isSuccess() || routed.isAccepted()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        service.handleQuit(player.getUniqueId());
    }
}
