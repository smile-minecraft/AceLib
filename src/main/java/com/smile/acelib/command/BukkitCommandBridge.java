package com.smile.acelib.command;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit {@link CommandExecutor} + {@link TabCompleter} 適配（Plan §十一 Phase 6）。
 *
 * <p>把 {@link CommandRegistry} 對接到 Bukkit 指令系統：</p>
 * <ul>
 *   <li>{@link #onCommand} → 包 {@link CommandSender} 為 {@link BukkitSender}，
 *       委派給 {@link CommandRegistry#dispatch}</li>
 *   <li>{@link #onTabComplete} → 同樣包 sender，委派給 {@link CommandRegistry#tabComplete}</li>
 * </ul>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 *   CommandRegistryImpl registry = new CommandRegistryImpl(new BukkitReplySink(plugin));
 *   registry.register(CommandSpec.builder("mycmd").subCommand(...).build());
 *   BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
 *   bridge.attach(plugin, "mycmd");
 * }</pre>
 *
 * <h2>plugin.yml 需求</h2>
 * <p>{@link #attach(JavaPlugin, String)} 透過 {@link PluginCommand#getCommand(String)}
 * 取得 Bukkit 端的 {@link PluginCommand} 物件，因此 plugin.yml 必須宣告對應指令。</p>
 *
 * @see CommandRegistry
 * @see BukkitSender
 * @see BukkitReplySink
 * @since Phase 6 (Plan §十一)
 */
public final class BukkitCommandBridge implements CommandExecutor, TabCompleter {

    private final CommandRegistry registry;

    /**
     * 主要建構子。
     *
     * @param registry 對應的指令 registry；不可為 null
     * @throws NullPointerException 當 {@code registry} 為 null
     */
    public BukkitCommandBridge(CommandRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(args, "args");
        BukkitSender wrapped = new BukkitSender(sender);
        List<String> argList = args.length == 0 ? List.of() : Arrays.asList(args);
        registry.dispatch(wrapped, label, argList);
        // 即使 dispatch 內部已輸出錯誤，回 true 讓 Bukkit 不顯示 "Unknown command"
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(args, "args");
        BukkitSender wrapped = new BukkitSender(sender);
        List<String> argList = args.length == 0 ? List.of() : Arrays.asList(args);
        return registry.tabComplete(wrapped, alias, argList);
    }

    /**
     * 將本 bridge 註冊到 Bukkit 端 {@link PluginCommand}。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>透過 {@code plugin.getCommand(name)} 取得 Bukkit 端指令物件</li>
     *   <li>設定 {@link PluginCommand#setExecutor(CommandExecutor)} 與
     *       {@link PluginCommand#setTabCompleter(TabCompleter)} 為本 bridge</li>
     * </ol>
     *
     * @param plugin  plugin owner；不可為 null
     * @param commandName  指令名稱（必須已於 plugin.yml 宣告）；不可為 null
     * @return 對應的 {@link PluginCommand}；null 表示找不到（plugin.yml 缺少宣告）
     * @throws NullPointerException 任一參數為 null
     */
    public PluginCommand attach(JavaPlugin plugin, String commandName) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(commandName, "commandName");
        PluginCommand cmd = plugin.getCommand(commandName);
        if (cmd == null) {
            return null;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        return cmd;
    }
}