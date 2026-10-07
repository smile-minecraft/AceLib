package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.LiteralMessage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * 離線玩家引數（package 內實作；經 {@link Arguments#offlinePlayer(String)} 建立）。
 *
 * <p>目標可以離線：已上線過的玩家（含當前在線）皆可解析。從未上線的名稱
 * 拋 {@code ACELIB-CMD-015}（不存在的目標，非「離線」語意）。
 * Brigadier 路徑用 vanilla profile 選擇器（含離線 profile）。</p>
 */
final class OfflinePlayerArgument extends BaseArgument<OfflinePlayer> {

    OfflinePlayerArgument(String name) {
        super(name);
    }

    @Override
    public String usageToken() {
        return "<" + name + ">";
    }

    @Override
    @SuppressWarnings("deprecation")
    public OfflinePlayer parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(raw);
        if (!offline.hasPlayedBefore() && !offline.isOnline()) {
            throw invalid(raw, "unknown player");
        }
        return offline;
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        // 離線名單無法低成本枚舉：以在線名單作 best-effort 補全（見模組頁限制）。
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return filterPrefix(names, prefix);
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.offlinePlayer();
    }

    @Override
    public OfflinePlayer resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        Collection<com.destroystokyo.paper.profile.PlayerProfile> profiles =
            ctx.getArgument(name, PlayerProfileListResolver.class).resolve(ctx.getSource());
        if (profiles.isEmpty()) {
            throw new SimpleCommandExceptionType(
                new LiteralMessage("no player matched: " + name)).create();
        }
        com.destroystokyo.paper.profile.PlayerProfile first =
            profiles.iterator().next();
        if (first.getUniqueId() == null) {
            throw new SimpleCommandExceptionType(
                new LiteralMessage("player profile has no id: " + name)).create();
        }
        return Bukkit.getOfflinePlayer(first.getUniqueId());
    }
}
