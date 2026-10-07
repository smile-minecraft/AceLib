package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.LiteralMessage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 在線玩家引數（package 內實作；經 {@link Arguments#player(String)} 建立）。
 *
 * <p>傳統路徑以 {@code Bukkit.getPlayerExact}（大小寫不敏感 fallback 掃描
 * 在線名單）查找；不在線或不存在拋 {@code ACELIB-CMD-007}
 * （{@link CommandErrorKind#PLAYER_OFFLINE}，沿用
 * {@code requireOnlinePlayer} 語意）。Brigadier 路徑用 vanilla
 * 玩家選擇器（客戶端驗證＋補全）。</p>
 */
final class PlayerArgument extends BaseArgument<PlayerHandle> {

    PlayerArgument(String name) {
        super(name);
    }

    @Override
    public String usageToken() {
        return "<" + name + ">";
    }

    @Override
    public PlayerHandle parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        Player exact = Bukkit.getPlayerExact(raw);
        Player found = exact != null ? exact : findIgnoreCase(raw);
        if (found == null || !found.isOnline()) {
            CommandMessages effective = effective(messages);
            throw new CommandException(CommandErrorKind.PLAYER_OFFLINE,
                effective.playerOffline(raw), Map.of("player", raw));
        }
        return new BukkitSender.BukkitPlayerHandle(found);
    }

    private static Player findIgnoreCase(String raw) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(raw)) {
                return online;
            }
        }
        return null;
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
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
        return factory.player();
    }

    @Override
    public PlayerHandle resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        List<Player> players = ctx.getArgument(name, PlayerSelectorArgumentResolver.class)
            .resolve(ctx.getSource());
        if (players.isEmpty()) {
            throw new SimpleCommandExceptionType(
                new LiteralMessage("no player matched: " + name)).create();
        }
        return new BukkitSender.BukkitPlayerHandle(players.get(0));
    }
}
