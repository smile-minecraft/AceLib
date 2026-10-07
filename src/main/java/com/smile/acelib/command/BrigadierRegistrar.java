package com.smile.acelib.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 型別化指令註冊器（下游正式組裝入口）。
 *
 * <p>一次 {@link #register(TypedCommand)} 同時完成：</p>
 * <ol>
 *   <li>內部 {@link CommandRegistry} 寫入（傳統 dispatch、help、
 *       tab complete、冷卻沿用 {@link CooldownTracker}）</li>
 *   <li>Brigadier 根節點經平台生命週期註冊（不再需要 {@code plugin.yml}
 *       的 {@code commands} 宣告；固定選項以 literal 結構送給客戶端，
 *       基岩版（Geyser）看得見補全）</li>
 * </ol>
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>在 {@code onEnable} 期間建立並註冊；reload <strong>不</strong>重複
 *       註冊（節點由平台持有，handler 讀取的狀態應經 supplier 取得最新，
 *       比照 {@code AceLibStatusHandler} 模式）</li>
 *   <li>重複註冊同名（或平台註冊失敗）時內部 registry 回滾，不殘留半註冊</li>
 *   <li>{@link #shutdown()} 標記內部 registry disabled 並清空本地簿記；
 *       平台側節點由平台在 plugin disable 時移除</li>
 * </ul>
 *
 * <h2>執行緒</h2>
 * <p>Brigadier 執行委派把來源包為 {@link BukkitSender} 後走
 * {@link CommandRegistry#dispatch}：指令本體仍在平台派送的執行緒執行
 * （Paper 主執行緒／Folia region 執行緒），handler 不得阻塞該執行緒，
 * 跨執行緒回覆走 {@link TypedContext#replyPlayerAsync}。</p>
 *
 * @see TypedCommand
 * @see Arguments
 * @since 1.4.0
 */
public final class BrigadierRegistrar {

    private final CommandRegistry registry;
    private final NodeRegistrar nodes;
    private final ArgumentTypeFactory types;

    /** 已註冊的根指令名（小寫去重，本地簿記）。 */
    private final Map<String, TypedCommand> registered = new ConcurrentHashMap<>();

    /**
     * 生產建構子（下游組裝入口）。
     *
     * <p>內部自建 {@link CommandRegistryImpl}（攜帶
     * {@link BukkitReplySink}）與平台生命週期註冊器。注意
     * {@link BukkitReplySink} 的 backend 語意：owner 非 AceLib 時
     * 跨執行緒玩家回覆走 {@code ACELIB-CMD-011} 路徑（見其文件）。</p>
     *
     * @param plugin owner plugin；不可為 null
     * @param replySink 回覆出口；不可為 null
     */
    public BrigadierRegistrar(JavaPlugin plugin, ReplySink replySink) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(replySink, "replySink");
        this.registry = new CommandRegistryImpl(replySink);
        this.nodes = new LifecycleNodeRegistrar(plugin);
        this.types = PaperArgumentTypes.instance();
    }

    /**
     * 生產建構子（預設回覆出口）。
     *
     * @param plugin owner plugin；不可為 null
     */
    public BrigadierRegistrar(JavaPlugin plugin) {
        this(plugin, new BukkitReplySink(plugin));
    }

    BrigadierRegistrar(CommandRegistry registry, NodeRegistrar nodes,
                       ArgumentTypeFactory types) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.types = Objects.requireNonNull(types, "types");
    }

    /**
     * 註冊型別化根指令（雙寫入）。
     *
     * <p>順序保證原子性：先寫內部 registry（名稱衝突即
     * {@link IllegalArgumentException}），再建 Brigadier 節點，
     * 最後掛平台註冊；任一步失敗都回滾前面的本地寫入。</p>
     *
     * @param command 型別化根指令；不可為 null
     * @throws NullPointerException 當 {@code command} 為 null
     * @throws IllegalArgumentException 當同名（或別名衝突）已註冊
     * @throws CommandException {@code ACELIB-CMD-009} 當已 shutdown
     */
    public void register(TypedCommand command) {
        Objects.requireNonNull(command, "command");
        if (registry.isDisabled()) {
            throw new CommandException(CommandErrorKind.REGISTRY_DISABLED,
                "registrar has been shut down; cannot register new commands");
        }
        CommandSpec spec = command.toCommandSpec();
        registry.register(spec);
        try {
            // 節點建構延後到平台觸發 COMMANDS 事件（vanilla 型別需伺服器
            // runtime；MockBukkit 等無事件環境只掛 handler、不求值）。
            nodes.register(command.name(), command.description(),
                command.aliases(),
                () -> command.toBrigadierNode(types, this::dispatchToRegistry));
        } catch (RuntimeException ex) {
            registry.unregister(spec.name());
            throw ex;
        }
        registered.put(command.name(), command);
    }

    /**
     * 解除註冊（本地簿記＋內部 registry；平台側節點由平台在
     * plugin disable 時移除，見 {@link NodeRegistrar#unregister}）。
     *
     * <p>未知名稱為 no-op。</p>
     *
     * @param name 根指令名或別名；不可為 null
     */
    public void unregister(String name) {
        Objects.requireNonNull(name, "name");
        registry.unregister(name);
        nodes.unregister(name.toLowerCase(java.util.Locale.ROOT));
        registered.remove(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 已註冊的根指令（不可變快照，本地簿記）。 */
    public List<TypedCommand> getRegisteredCommands() {
        return List.copyOf(registered.values());
    }

    /** 內部 registry（dispatch／help／tab complete 共用；測試亦可用）。 */
    public CommandRegistry getRegistry() {
        return registry;
    }

    /**
     * 停用：內部 registry 標記 disabled 並清空本地簿記（冪等）。
     *
     * <p>平台側節點由平台在 plugin disable 時移除；此處不假設
     * 即時移除語意。</p>
     */
    public void shutdown() {
        List<String> names = new ArrayList<>(registered.keySet());
        Collections.sort(names);
        try {
            registry.onPluginDisable();
        } catch (Throwable ignored) {
            // 停用流程不因單步失敗中斷。
        }
        for (String name : names) {
            try {
                registry.unregister(name);
            } catch (Throwable ignored) {
                // 同上。
            }
            try {
                nodes.unregister(name);
            } catch (Throwable ignored) {
                // 同上。
            }
        }
        registered.clear();
    }

    private void dispatchToRegistry(CommandSourceStack stack, String commandLabel,
                                    List<String> args) {
        BukkitSender sender;
        try {
            sender = new BukkitSender(stack.getSender());
        } catch (Throwable ex) {
            return;
        }
        registry.dispatch(sender, commandLabel, args);
    }
}
