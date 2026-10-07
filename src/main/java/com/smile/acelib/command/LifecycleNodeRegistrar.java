package com.smile.acelib.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 生產環境 {@link NodeRegistrar}（package 內）：經 Paper
 * {@code LifecycleEvents.COMMANDS} 註冊 Brigadier 節點。
 *
 * <p>註冊只在 {@code onEnable} 期間呼叫（平台要求）；實際的
 * {@code registrar().register(...)} 由平台在命令同步時機執行。
 * 同一 plugin 重複呼叫本方法掛上多個 handler 時，平台會重複註冊 —
 * 去重由 {@link BrigadierRegistrar} 在呼叫前保證。</p>
 */
final class LifecycleNodeRegistrar implements NodeRegistrar {

    private final JavaPlugin plugin;

    LifecycleNodeRegistrar(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void register(String name, String description, Collection<String> aliases,
                         java.util.function.Supplier<LiteralCommandNode<CommandSourceStack>> nodeSupplier) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(nodeSupplier, "nodeSupplier");
        List<String> aliasCopy = List.copyOf(aliases);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
            event -> event.registrar().register(
                nodeSupplier.get(), description, aliasCopy));
    }

    @Override
    public void unregister(String name) {
        // 平台未提供取消註冊 API：plugin disable 時平台自動移除。
        // 本地簿記的移除由 BrigadierRegistrar 負責。
    }
}
