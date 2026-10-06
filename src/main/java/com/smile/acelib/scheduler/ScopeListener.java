package com.smile.acelib.scheduler;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 作用域關閉事件監聽（Internal）。
 *
 * <p>由 {@link SafeSchedulerImpl} 在第一個玩家／實體作用域建立時註冊、
 * plugin 停用時解除註冊。收到退服或實體移除事件後，把擁有者相符的作用域
 * 關閉（群組任務取消、呼叫端收到終態）。只做分派，不執行使用者程式；
 * 事件非同步送達的空窗由派送前與執行前的可用性檢查補上。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 */
final class ScopeListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final SafeSchedulerImpl scheduler;

    ScopeListener(SafeSchedulerImpl scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 玩家退服時關閉其作用域。
     *
     * @param event 退服事件；不可為 null
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            UUID id = event.getPlayer().getUniqueId();
            scheduler.onPlayerQuit(id);
        } catch (Throwable failure) {
            // 監聽邊界：識別碼解析失敗時無法分派，只能放棄本次通知；
            // 可用性檢查仍會在後續派送與執行前擋下失效擁有者。
            LOGGER.log(Level.FINE, "scope listener: quit dispatch failed", failure);
        }
    }

    /**
     * 實體移出世界時關閉其作用域（含 Folia 退休與死亡）。
     *
     * @param event 實體移除事件；不可為 null
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        try {
            UUID id = event.getEntity().getUniqueId();
            scheduler.onEntityRetired(id);
        } catch (Throwable failure) {
            // 同退服路徑：無法分派時放棄本次通知，可用性檢查補上。
            LOGGER.log(Level.FINE, "scope listener: retire dispatch failed", failure);
        }
    }
}
