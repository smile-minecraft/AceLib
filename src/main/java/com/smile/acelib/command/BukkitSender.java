package com.smile.acelib.command;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bukkit {@link CommandSender} 的 {@link Sender} 適配。
 *
 * <p>將 Bukkit sender 包裝為 core dispatcher 看得懂的 {@link Sender} 抽象；
 * 玩家 sender 額外包成 {@link BukkitPlayerHandle} 提供 UUID / locale / online
 * 等資訊。</p>
 *
 * <h2>Locale 來源</h2>
 * <ul>
 *   <li>玩家 sender：優先 {@link Player#locale()}（Paper API），
 *       失敗時 fallback 到 {@link Locale#ROOT}</li>
 *   <li>非玩家 sender（console / RCON / 自動化）：固定 {@link Locale#ROOT}</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class BukkitSender implements Sender {

    private final CommandSender commandSender;
    private final BukkitPlayerHandle playerHandle;
    private final Locale locale;

    /**
     * 包裝 Bukkit sender。
     *
     * @param commandSender Bukkit sender；不可為 null
     * @throws NullPointerException 當 {@code commandSender} 為 null
     */
    public BukkitSender(CommandSender commandSender) {
        this.commandSender = Objects.requireNonNull(commandSender, "commandSender");
        if (commandSender instanceof Player p) {
            this.playerHandle = new BukkitPlayerHandle(p);
        } else {
            this.playerHandle = null;
        }
        this.locale = resolveLocale(commandSender);
    }

    private static Locale resolveLocale(CommandSender cs) {
        if (cs instanceof Player p) {
            try {
                Locale l = p.locale();
                if (l != null) return l;
            } catch (Throwable ignored) {
                // Paper Player.locale() 在某些 Mock / 早期版本可能 NPE；不中斷
            }
        }
        return Locale.ROOT;
    }

    @Override
    public String getName() {
        try {
            String n = commandSender.getName();
            return n != null ? n : "<unknown>";
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    @Override
    public boolean isPlayer() {
        return playerHandle != null;
    }

    @Override
    public PlayerHandle asPlayer() {
        return playerHandle;
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return true;
        }
        try {
            return commandSender.hasPermission(permission);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    /**
     * 取得底層 Bukkit sender（測試或進階整合用；不應被 handler 直接使用）。
     *
     * @return 永不為 null 的 Bukkit {@link CommandSender}
     */
    public CommandSender bukkitSender() {
        return commandSender;
    }

    /**
     * 玩家 handle 實作。
     */
    static final class BukkitPlayerHandle implements PlayerHandle {
        private final Player player;

        BukkitPlayerHandle(Player player) {
            this.player = Objects.requireNonNull(player, "player");
        }

        @Override
        public UUID getUniqueId() {
            return player.getUniqueId();
        }

        @Override
        public boolean isOnline() {
            try {
                return player.isOnline();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String getName() {
            try {
                String n = player.getName();
                return n != null ? n : "<unknown>";
            } catch (Throwable t) {
                return "<unknown>";
            }
        }

        @Override
        public Locale getLocale() {
            try {
                Locale l = player.locale();
                return l != null ? l : Locale.ROOT;
            } catch (Throwable t) {
                return Locale.ROOT;
            }
        }

        /**
         * 取得底層 Bukkit Player（ReplySink 內部使用，繞過 region thread 限制
         * 由 {@link SafeExecutor#executeOnRegion} 保證執行緒安全）。
         */
        Player bukkitPlayer() {
            return player;
        }
    }
}