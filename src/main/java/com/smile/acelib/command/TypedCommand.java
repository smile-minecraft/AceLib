package com.smile.acelib.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.CommandSender;

/**
 * 型別化根指令規格（下游組裝入口）。
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * TypedCommand shop = TypedCommand.builder("shop")
 *     .description("商店指令")
 *     .permission("shop.use")
 *     .aliases("s")
 *     .subcommand(TypedSubCommand.builder("buy")
 *         .description("購買")
 *         .argument(Arguments.material("item"))
 *         .argument(Arguments.intArg("amount", 1, 64))
 *         .executes(ctx -> {
 *             Material item = ctx.get(itemArg);
 *             int amount = ctx.get(amountArg);
 *             // ... 業務邏輯（不再碰原始字串）
 *         })
 *         .build())
 *     .build();
 * registrar.register(shop);  // 同時寫入內部 registry 與 Brigadier
 * }</pre>
 *
 * <p>註冊後不再需要 {@code plugin.yml} 的 {@code commands} 宣告：
 * Brigadier 節點經 {@code LifecycleEvents.COMMANDS} 由平台持有，
 * plugin disable 時由平台移除。</p>
 *
 * @see TypedSubCommand
 * @see BrigadierRegistrar
 * @since 1.4.0
 */
public final class TypedCommand {

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final String permission;
    private final List<TypedSubCommand> subcommands;

    private TypedCommand(Builder builder) {
        Objects.requireNonNull(builder.name, "name");
        if (builder.name.isEmpty()) {
            throw new IllegalArgumentException("command name cannot be empty");
        }
        this.name = builder.name.toLowerCase(java.util.Locale.ROOT);
        this.aliases = builder.aliases == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(builder.aliases));
        this.description = builder.description == null ? "" : builder.description;
        this.usage = builder.usage == null ? "" : builder.usage;
        this.permission = builder.permission;
        if (builder.subcommands.isEmpty()) {
            throw new IllegalArgumentException(
                "command must declare at least one subcommand: " + builder.name);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TypedSubCommand sub : builder.subcommands) {
            Objects.requireNonNull(sub, "subcommand");
            if (!seen.add(sub.name())) {
                throw new IllegalArgumentException(
                    "duplicate subcommand name: " + sub.name());
            }
        }
        this.subcommands = Collections.unmodifiableList(
            new ArrayList<>(builder.subcommands));
    }

    /** 根指令名稱（小寫）。 */
    public String name() {
        return name;
    }

    /** 別名（不可變）。 */
    public List<String> aliases() {
        return aliases;
    }

    /** 描述。 */
    public String description() {
        return description;
    }

    /** 用法字串。 */
    public String usage() {
        return usage;
    }

    /** 根權限節點；null 表示無需求。 */
    public String permission() {
        return permission;
    }

    /** 子指令清單（不可變，保留宣告順序）。 */
    public List<TypedSubCommand> subcommands() {
        return subcommands;
    }

    /**
     * 轉為 {@link CommandSpec}（傳統路徑／目錄投影用）。
     *
     * @return 對應的 {@link CommandSpec}；永不為 null
     */
    public CommandSpec toCommandSpec() {
        CommandSpec.Builder builder = CommandSpec.builder(name)
            .description(description)
            .usage(usage);
        if (!aliases.isEmpty()) {
            builder.aliases(aliases.toArray(new String[0]));
        }
        if (permission != null) {
            builder.permission(permission);
        }
        for (TypedSubCommand sub : subcommands) {
            builder.subCommand(sub.toSubCommandSpec());
        }
        return builder.build();
    }

    /**
     * 建構 Brigadier 根節點（生產環境用，vanilla 引數型別）。
     *
     * <p>需伺服器 runtime（vanilla provider）；單元測試改用
     * {@link #toBrigadierNode(ArgumentTypeFactory, BrigadierDispatch)}。</p>
     *
     * @param dispatch 執行委派；不可為 null
     * @return 根 literal 節點；永不為 null
     */
    public LiteralCommandNode<CommandSourceStack> toBrigadierNode(
            BrigadierDispatch dispatch) {
        return toBrigadierNode(PaperArgumentTypes.instance(), dispatch);
    }

    /**
     * 建構 Brigadier 根節點（引數型別工廠可注入）。
     *
     * <p>根字面本身掛 {@code requires}（根權限）與 {@code executes}
     * （無子指令輸入時委派，傳統路徑回主 help）。固定選項子樹為
     * literal 分支（基岩可見）；開放式引數為 argument 節點。</p>
     *
     * @param factory  引數型別工廠；不可為 null
     * @param dispatch 執行委派；不可為 null
     * @return 根 literal 節點；永不為 null
     */
    public LiteralCommandNode<CommandSourceStack> toBrigadierNode(
            ArgumentTypeFactory factory, BrigadierDispatch dispatch) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(dispatch, "dispatch");
        LiteralArgumentBuilder<CommandSourceStack> root =
            Commands.<CommandSourceStack>literal(name)
                .requires(stack -> {
                    try {
                        if (permission == null || permission.isEmpty()) {
                            return true;
                        }
                        return stack.getSender().hasPermission(permission);
                    } catch (Throwable ignored) {
                        return false;
                    }
                })
                .executes(ctx -> {
                    dispatch.dispatch(ctx.getSource(), name,
                        TypedSubCommand.splitInput(ctx.getInput(), name));
                    return Command.SINGLE_SUCCESS;
                });
        for (TypedSubCommand sub : subcommands) {
            root.then(sub.buildBranch(factory, dispatch, name));
        }
        return root.build();
    }

    /**
     * 建立 builder。
     *
     * @param name 根指令名稱；不可為 null 或空字串
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 型別化根指令 builder。 */
    public static final class Builder {
        private final String name;
        private List<String> aliases;
        private String description;
        private String usage;
        private String permission;
        private final List<TypedSubCommand> subcommands = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder aliases(String... aliases) {
            this.aliases = aliases == null ? null : Arrays.asList(aliases);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder subcommand(TypedSubCommand subcommand) {
            Objects.requireNonNull(subcommand, "subcommand");
            this.subcommands.add(subcommand);
            return this;
        }

        public TypedCommand build() {
            return new TypedCommand(this);
        }
    }
}
