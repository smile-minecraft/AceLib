package com.smile.acelib.gui;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import org.bukkit.inventory.Inventory;

/**
 * Inventory ↔ session generation 綁定表（Internal）。
 *
 * <p>用 {@link WeakHashMap} 保存，避免 listener 持有 strong reference 阻止
 * inventory 被 GC 回收。Listener 透過 {@link #generationOf(Inventory)} 查詢
 * inventory 對應的 generation；對未綁定的 inventory 回傳 null（表示不屬於
 * 本服務管理）。</p>
 *
 * <p>此類別為內部耦合 helper，僅 {@link GuiServiceImpl} / {@link GuiListener}
 * 使用；不對外暴露。</p>
 *
 * @see GuiServiceImpl
 * @since 1.0.0
 */
final class GuiInventoryLink {

    private static final Map<Inventory, Long> LINKS = new WeakHashMap<>();

    private GuiInventoryLink() {
        // utility
    }

    /**
     * 將 inventory 與 generation 綁定。
     */
    static synchronized void link(Inventory inventory, long generation) {
        LINKS.put(inventory, generation);
    }

    /**
     * 查詢 inventory 對應的 generation；未綁定回傳 null。
     */
    static synchronized Long generationOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        // WeakHashMap 的 key 必須以 identity 比較；get 取不到時嘗試清理
        Long gen = LINKS.get(inventory);
        if (gen == null) {
            // 觸發 expungeStaleEntries 清理已完成 GC 的 inventory
            Iterator<Map.Entry<Inventory, Long>> it = LINKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Inventory, Long> e = it.next();
                if (e.getKey() == null) {
                    it.remove();
                }
            }
        }
        return gen;
    }

    /**
     * 解除綁定（InventoryCloseEvent 觸發）。
     */
    static synchronized void unlink(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        LINKS.remove(inventory);
    }

    /**
     * 清空所有綁定（reload / disable 用）。
     */
    static synchronized void clear() {
        LINKS.clear();
    }
}
