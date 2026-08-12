package com.smile.acelib.gui;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * GUI 服務的 Bukkit listener（Plan §十六 Phase 11 內部使用）。
 *
 * <p>三個事件：</p>
 * <ul>
 *   <li>{@link InventoryClickEvent} — 透過 {@link GuiServiceImpl#handleClick} 驗證並取消</li>
 *   <li>{@link InventoryDragEvent} — 透過 {@link GuiServiceImpl#handleDrag} 驗證並取消</li>
 *   <li>{@link InventoryCloseEvent} — 透過 {@link GuiServiceImpl#handleClose} 移除 session</li>
 * </ul>
 *
 * <p>listener 不持有 {@link org.bukkit.entity.Player} reference；事件觸發時
 * 透過 {@link org.bukkit.event.inventory.InventoryView#getPlayer()} 取得當下
 * Player，操作完成後立即釋放 reference。</p>
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
}
