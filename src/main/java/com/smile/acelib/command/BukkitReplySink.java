package com.smile.acelib.command;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 預設 Bukkit {@link ReplySink} 實作 — 指令系統的玩家回覆出口。
 *
 * <p>使用 plugin logger（玩家導向輸出到 {@code Player.sendMessage}，console
 * 與其他 sender 輸出到 plugin logger）。i18n 由 caller 在 handler 內透過
 * {@code MessageService.format(...)} 完成後再呼叫 {@link CommandContext#reply}
 * — 此 sink 不持有 lang key 解析責任。</p>
 *
 * <h2>玩家回覆一律走 backend（Folia region 安全）</h2>
 * <p>不論 {@code ctx.reply()} 同步或 {@code ctx.replyPlayerAsync()} 跨執行緒，
 * 對玩家 sender 的回覆都必須透過 {@link SafeExecutorBackend} 派送，由
 * {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 確保 mutate 發生在玩家 region thread
 * （Folia 安全）。本 sink 內部 <strong>禁止</strong>直接呼叫
 * {@code Player.sendMessage} 來繞過 region-bound 派送。</p>
 *
 * <p>當 owner plugin 不是 {@code AceLibPlugin}（沒有 canonical platform /
 * capability 快取）時，{@link SafeExecutorBackend#detect} 會回傳明確拒絕
 * inline 派送的實作 — 重新呼叫 {@link SafeExecutorBackend#runOnPlayerRegion}
 * 時拋 {@link IllegalStateException} 帶 {@code ACELIB-CMD-011} 錯誤代碼。
 * 本 sink 必須 catch 該例外並輸出 warning，<strong>不得</strong> 在 catch 區塊
 * 內 fallback 為直接 {@code player.sendMessage}（那會繞過 region 安全檢查，
 * Folia 環境下違規）。</p>
 *
 * <h2>錯誤處理</h2>
 * <ul>
 *   <li>{@link CommandException} → 對玩家只送 message；對 console 攜帶
 *       code + message 方便管理員診斷</li>
 *   <li>其他 {@link Throwable} → 對玩家送 generic 訊息；對 console 攜帶
 *       class 名 + message</li>
 *   <li>backend 拋例外（{@code ACELIB-CMD-011}）→ 對玩家不送、記錄 warning
 *       攜帶 code；對 console 仍記錄但附 code</li>
 * </ul>
 *
 * @see BukkitCommandBridge
 * @since 1.0.0
 */
public final class BukkitReplySink implements ReplySink {

    /** Backend 拒絕 inline 派送時的錯誤代碼（{@code ACELIB-CMD-011}）。 */
    static final String ERR_REPLY_BACKEND_UNAVAILABLE =
        CommandErrorKind.REPLY_BACKEND_UNAVAILABLE.defaultCode();

    private final JavaPlugin plugin;
    private final SafeExecutorBackend backend;

    /**
     * 主要建構子：自動偵測 backend（{@link com.smile.acelib.AceLibPlugin}
     * 透過 canonical cache；其他 plugin 走拒絕派送）。
     *
     * @param plugin 對外 owner plugin；不可為 null
     * @throws NullPointerException 當 {@code plugin} 為 null
     */
    public BukkitReplySink(JavaPlugin plugin) {
        this(plugin, SafeExecutorBackend.detect(plugin));
    }

    /**
     * 注入式建構子（測試 seam）。
     *
     * @param plugin  plugin owner；不可為 null
     * @param backend SafeExecutor backend；不可為 null
     */
    public BukkitReplySink(JavaPlugin plugin, SafeExecutorBackend backend) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void send(Sender sender, String message) {
        Objects.requireNonNull(message, "message");
        if (sender instanceof BukkitSender bs && bs.isPlayer()) {
            PlayerHandle handle = bs.asPlayer();
            if (handle instanceof BukkitSender.BukkitPlayerHandle bph) {
                dispatchToPlayerRegion(bph, message);
            } else {
                // 非 Bukkit player handle — best-effort warning，不直接 sendMessage
                plugin.getLogger().log(Level.WARNING,
                    "[{0}] send() called with non-Bukkit player handle: {1}",
                    new Object[] { ERR_REPLY_BACKEND_UNAVAILABLE, handle.getName() });
            }
            return;
        }
        // console / 其他 sender
        plugin.getLogger().log(Level.INFO, "[{0}] {1}",
            new Object[] { sender.getName(), message });
    }

    @Override
    public void sendError(Sender sender, Throwable error) {
        Objects.requireNonNull(error, "error");
        String body;
        String code = "ACELIB-CMD-008";
        if (error instanceof CommandException ex) {
            code = ex.getCode();
            body = ex.getMessage();
        } else {
            body = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        }
        String prefix = sender.isPlayer() ? "" : "[" + code + "] ";
        String out = prefix + body;
        send(sender, out);
    }

    @Override
    public void sendPlayerAsync(PlayerHandle player, String message) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        if (!(player instanceof BukkitSender.BukkitPlayerHandle bph)) {
            // 非 Bukkit player — best-effort warning
            plugin.getLogger().log(Level.WARNING,
                "[{0}] sendPlayerAsync called with non-Bukkit player handle: {1}",
                new Object[] { ERR_REPLY_BACKEND_UNAVAILABLE, player.getName() });
            return;
        }
        if (!bph.bukkitPlayer().isOnline()) {
            return;
        }
        dispatchToPlayerRegion(bph, message);
    }

    /**
     * 將玩家回覆派送到 backend；backend 拋例外時記錄 warning 並不直接
     * 呼叫 {@code player.sendMessage}（避免繞過 region-bound 派送）。
     *
     * <p>例外處理策略：</p>
     * <ul>
     *   <li>{@link IllegalStateException} → 紀錄 warning 攜帶
     *       {@code ACELIB-CMD-011}（backend 拒絕 inline 派送 — owner 不是
     *       已 ready 的 {@code AceLibPlugin}）；<strong>不</strong> fallback
     *       為直接 {@code player.sendMessage}。</li>
     *   <li>其他 {@link Throwable}（理論上只有 OOM 等系統錯誤）→ 記錄 warning
     *       攜帶 {@code ACELIB-CMD-011}，讓管理員可追蹤；同樣不 fallback。</li>
     * </ul>
     */
    private void dispatchToPlayerRegion(BukkitSender.BukkitPlayerHandle handle,
                                        String message) {
        Player player = handle.bukkitPlayer();
        try {
            backend.runOnPlayerRegion(plugin, player,
                () -> safeSendMessage(player, message));
        } catch (IllegalStateException ex) {
            // backend 拒絕 inline 派送（非 AceLib owner）。記錄 warning，但不
            // 退縮為直接 player.sendMessage — 那會繞過 region 安全檢查。
            plugin.getLogger().log(Level.WARNING,
                "[{0}] player reply backend unavailable; player={1}, reason={2}",
                new Object[] { ERR_REPLY_BACKEND_UNAVAILABLE,
                    safePlayerName(player), ex.getMessage() });
        } catch (Throwable t) {
            // 其他型別的例外：仍不繞過 backend
            plugin.getLogger().log(Level.WARNING,
                "[{0}] player reply dispatch failed; player={1}, reason={2}",
                new Object[] { ERR_REPLY_BACKEND_UNAVAILABLE,
                    safePlayerName(player), t.getMessage() });
        }
    }

    private static void safeSendMessage(Player player, String message) {
        try {
            player.sendMessage(message);
        } catch (Throwable t) {
            Logger.getLogger("AceLib").log(Level.WARNING,
                "player.sendMessage failed: " + t.getMessage(), t);
        }
    }

    private static String safePlayerName(Player player) {
        try {
            return player.getName() != null ? player.getName() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 透過 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 把 runnable 派送到玩家 region 的 backend 介面。
     *
     * <p>為避免對外暴露 {@code SafeExecutor} 的 platform / capability 參數（這些
     * 由 AceLibPlugin 統一管理），透過此介面包裝，caller 只需提供 player + runnable。</p>
     */
    @FunctionalInterface
    public interface SafeExecutorBackend {

        /**
         * 把 runnable 派送到玩家的 region thread（Folia 安全）。
         *
         * <p>當 owner 為 {@link com.smile.acelib.AceLibPlugin} 時，內部委派給
         * {@link com.smile.acelib.context.SafeExecutor#executeOnRegion}；當 owner 非 {@code AceLibPlugin}
         * 或尚未 ready 時，必須 <strong>拋 {@link IllegalStateException}</strong>
         * 帶 {@code ACELIB-CMD-011} 錯誤代碼，禁止 inline 執行 runnable
         * （否則會繞過 region-bound 派送、Folia 環境下違規）。</p>
         *
         * @param plugin    plugin owner
         * @param player    目標玩家
         * @param runnable  要執行的程式
         * @throws IllegalStateException 當 owner plugin 沒有可用的 region-safe backend
         */
        void runOnPlayerRegion(JavaPlugin plugin, Player player, Runnable runnable);

        /**
         * 自動偵測 backend：
         * <ul>
         *   <li>{@link com.smile.acelib.AceLibPlugin} 且 {@code isReady()} 為
         *       true → 透過 {@code getApi()} 取得 platform / capability，呼叫
         *       {@link com.smile.acelib.context.SafeExecutor#executeOnRegion}</li>
         *   <li>其他 plugin → 明確拒絕：回傳 backend 實作，重新呼叫
         *       {@link #runOnPlayerRegion} 時拋 {@link IllegalStateException}
         *       帶 {@code ACELIB-CMD-011} code。</li>
         * </ul>
         */
        static SafeExecutorBackend detect(JavaPlugin plugin) {
            if (plugin instanceof com.smile.acelib.AceLibPlugin aceLib
                && aceLib.isReady()) {
                return (p, player, runnable) -> {
                    var api = aceLib.getApi();
                    com.smile.acelib.context.SafeExecutor.executeOnRegion(
                        p,
                        api.getPlatform(),
                        api.getPlatformCapability(),
                        player,
                        runnable
                    );
                };
            }
            // 非 AceLib owner 或 AceLib 尚未 ready：拒絕 inline 派送。
            // 拋 IllegalStateException 帶 ACELIB-CMD-011 錯誤代碼；sink 必須
            // 在 catch 區塊處理並記錄 warning，不得退縮為直接 player.sendMessage。
            return (p, player, runnable) -> {
                throw new IllegalStateException(
                    "[" + ERR_REPLY_BACKEND_UNAVAILABLE + "] "
                        + "player reply backend unavailable; owner plugin '"
                        + p.getName() + "' is not AceLibPlugin (ready) and "
                        + "cannot perform region-safe player reply. "
                        + "Use AceLibPlugin as owner or reply via the dispatch thread.");
            };
        }
    }
}
