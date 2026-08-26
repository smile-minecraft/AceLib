package com.smile.acelib.form;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Production {@link FormResponseDispatcher} 實作：透過既有
 * {@link SafeScheduler#runForPlayer(org.bukkit.entity.Player, Runnable)}
 * 把表單回應 callback 派送到玩家所屬 region（Folia entity scheduler、
 * Paper main thread）。
 *
 * <p>此類別位於 form 套件內（package-private），避免外部 caller 直接持有
 * SafeScheduler reference；比照 {@code FloodgateFormSender} 的延遲綁定先例，
 * scheduler 以 supplier 包裝——每次派送才讀取，reload 提交新 scheduler 後自動
 * 取到新實例，不捕獲已 disabled 的舊 scheduler。</p>
 *
 * <h2>派送成功 / 拒絕語意</h2>
 * <p>回傳 {@code true} 表示底層 {@link SafeScheduler} 已接受任務；回傳
 * {@code false} 表示玩家解析失敗（離線）或 SafeScheduler 回傳 cancelled no-op
 * task（scheduler disabled、平台不支援等）。呼叫端必須把 {@code false} 視為
 * 「未派送」並清理 pending 狀態。</p>
 *
 * @since 1.0.0
 */
final class SafeSchedulerFormResponseDispatcher implements FormResponseDispatcher {

    /** 延遲綁定的 scheduler 供應器：每次派送重新讀取（reload 安全）。 */
    private final Supplier<SafeScheduler> schedulerSupplier;

    private SafeSchedulerFormResponseDispatcher(Supplier<SafeScheduler> schedulerSupplier) {
        this.schedulerSupplier = Objects.requireNonNull(schedulerSupplier, "schedulerSupplier");
    }

    /**
     * 建立 production dispatcher。
     *
     * @param schedulerSupplier scheduler 供應器；不可為 null（建議包裝 plugin 的
     *                          volatile scheduler 欄位，reload 後自動取新實例）
     * @return 不可為 null 的 dispatcher
     * @throws NullPointerException schedulerSupplier 為 null
     */
    static SafeSchedulerFormResponseDispatcher viaSafeScheduler(
            Supplier<SafeScheduler> schedulerSupplier) {
        return new SafeSchedulerFormResponseDispatcher(schedulerSupplier);
    }

    @Override
    public boolean dispatch(UUID playerId, Runnable task) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(task, "task");
        Player player = resolvePlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        SafeScheduler scheduler = schedulerSupplier.get();
        if (scheduler == null) {
            return false;
        }
        // SafeScheduler 內部已處理 player offline / 平台不支援等邊界；
        // isCancelled() == true 表示回傳的是 NoOpScheduledTask（拒絕派送）
        ScheduledTask scheduled = scheduler.runForPlayer(player, task);
        return !scheduled.isCancelled();
    }

    @Override
    public boolean isPlayerOnline(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Player player = resolvePlayer(playerId);
        return player != null && player.isOnline();
    }

    private static Player resolvePlayer(UUID playerId) {
        return Bukkit.getPlayer(playerId);
    }
}
