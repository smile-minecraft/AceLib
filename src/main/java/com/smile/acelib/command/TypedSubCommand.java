package com.smile.acelib.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 型別化子指令規格（下游組裝入口）。
 *
 * <p>一個 builder 同時產出兩種註冊形式（語意一致）：</p>
 * <ul>
 *   <li>{@link #toSubCommandSpec()} — {@link SubCommandSpec} 相容層：
 *       傳統 Bukkit 路徑（plugin.yml＋bridge）沿用既有的權限／玩家限定／
 *       參數數量／冷卻／help／補全流程，handler 內部先解析再呼叫
 *       {@link TypedHandler}</li>
 *   <li>{@link #buildBranch} — Brigadier 子樹：固定選項編譯為 literal
 *       分支（原本預期基岩可見，但 2026-10-08 真人基岩客戶端實測顯示
 *       基岩端建議列並未出現，Geyser Current Limitations，Unfixable，見模組頁
 *       補全支援矩陣），開放式引數為 argument 節點；執行時把原始輸入
 *       切分後委派給同一套相容層（單一真相來源：權限、冷卻、錯誤全走
 *       {@link CommandRegistry#dispatch}）</li>
 * </ul>
 *
 * <p>引數一律必填（{@code minArgs == maxArgs == 引數數}）；零引數子指令
 * 允許（handler 直接執行）。引數解析錯誤訊息經建構時指定的
 * {@link CommandMessages} 產生（預設英文）。</p>
 *
 * @see TypedCommand
 * @see Arguments
 * @since 1.4.0
 */
public final class TypedSubCommand {

    private final String name;
    private final String description;
    private final String usage;
    private final String permission;
    private final boolean playerOnly;
    private final boolean consoleOnly;
    private final long cooldownMillis;
    private final List<CommandArgument<?>> arguments;
    private final TypedHandler handler;
    private final CommandMessages messages;

    private TypedSubCommand(Builder builder) {
        Objects.requireNonNull(builder.name, "name");
        if (builder.name.isEmpty()) {
            throw new IllegalArgumentException("subcommand name cannot be empty");
        }
        this.name = builder.name.toLowerCase(java.util.Locale.ROOT);
        this.description = builder.description == null ? "" : builder.description;
        this.permission = builder.permission;
        this.playerOnly = builder.playerOnly;
        this.consoleOnly = builder.consoleOnly;
        if (playerOnly && consoleOnly) {
            throw new IllegalArgumentException(
                "subcommand cannot be both playerOnly and consoleOnly: " + builder.name);
        }
        this.cooldownMillis = builder.cooldownMillis < 0 ? 0 : builder.cooldownMillis;
        List<CommandArgument<?>> args = new ArrayList<>(builder.arguments);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CommandArgument<?> arg : args) {
            Objects.requireNonNull(arg, "argument");
            String key = arg.name().toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("duplicate argument name: " + arg.name());
            }
        }
        this.arguments = Collections.unmodifiableList(args);
        this.handler = Objects.requireNonNull(builder.handler, "handler");
        this.messages = builder.messages == null
            ? DefaultCommandMessages.instance()
            : builder.messages;
        if (builder.usage != null) {
            this.usage = builder.usage;
        } else {
            StringBuilder generated = new StringBuilder();
            for (CommandArgument<?> arg : this.arguments) {
                if (generated.length() > 0) {
                    generated.append(' ');
                }
                generated.append(arg.usageToken());
            }
            this.usage = generated.toString();
        }
    }

    /** 子指令名稱（小寫）。 */
    public String name() {
        return name;
    }

    /** 描述。 */
    public String description() {
        return description;
    }

    /** 用法字串（未指定時由引數 token 自動產生）。 */
    public String usage() {
        return usage;
    }

    /** 權限節點；null 表示無需求。 */
    public String permission() {
        return permission;
    }

    /** 引數清單（不可變，保留宣告順序）。 */
    public List<CommandArgument<?>> arguments() {
        return arguments;
    }

    /**
     * 轉為 {@link SubCommandSpec} 相容層。
     *
     * <p>handler 內部依序解析（失敗即拋 {@link CommandException}，
     * dispatcher 經 {@link ReplySink} 回覆）；completer 依目前位置
     * 委派給對應引數的 {@code suggest}。</p>
     *
     * @return 對應的 {@link SubCommandSpec}；永不為 null
     */
    public SubCommandSpec toSubCommandSpec() {
        SubCommandSpec.Builder builder = SubCommandSpec.builder(name)
            .description(description)
            .usage(usage)
            .cooldownMillis(cooldownMillis)
            .handler(ctx -> {
                Map<CommandArgument<?>, Object> values = new IdentityHashMap<>();
                List<String> provided = ctx.commandArgs();
                for (int i = 0; i < arguments.size(); i++) {
                    CommandArgument<?> arg = arguments.get(i);
                    Object value = parseOne(arg, provided.get(i));
                    values.put(arg, value);
                }
                handler.execute(new TypedContext(ctx, values));
            })
            .completer((ctx, args) -> completePositional(ctx, args));
        if (permission != null) {
            builder.permission(permission);
        }
        if (playerOnly) {
            builder.playerOnly();
        }
        if (consoleOnly) {
            builder.consoleOnly();
        }
        builder.minArgs(arguments.size()).maxArgs(arguments.size());
        List<String> names = new ArrayList<>();
        for (CommandArgument<?> arg : arguments) {
            names.add(arg.name());
        }
        if (!names.isEmpty()) {
            builder.args(names.toArray(new String[0]));
        }
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object parseOne(CommandArgument<?> arg, String raw) {
        return ((CommandArgument) arg).parse(raw, messages);
    }

    private List<String> completePositional(CommandContext ctx, List<String> args) {
        // args[0] 為子指令名；其後為位置引數。補全永遠針對最後一個已輸入 token。
        int positional = args.size() - 2;
        if (positional < 0 || positional >= arguments.size()) {
            return List.of();
        }
        if (!ctx.sender().hasPermission(permission)) {
            return List.of();
        }
        CommandArgument<?> arg = arguments.get(positional);
        String prefix = args.get(args.size() - 1);
        try {
            List<String> result = arg.suggest(prefix);
            return result == null ? List.of() : List.copyOf(result);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * 建構 Brigadier 子樹（根指令組裝用）。
     *
     * <p>固定選項展開為 literal 分支；開放式引數為 argument 節點
     * （附伺服器端 suggests，依子指令權限過濾）。每一層（含子指令字面
     * 本身）皆掛 executes，把原始輸入切分後委派給
     * {@code dispatch}（單一真相來源；部分輸入同樣得到在地化的
     * MISSING_ARGUMENTS 而非 Brigadier 通用錯誤）。</p>
     *
     * @param factory  引數型別工廠；不可為 null
     * @param dispatch 執行委派；不可為 null
     * @param rootLabel 根指令標籤（委派時回填）；不可為 null
     * @return 子指令字面 builder；永不為 null
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildBranch(
            ArgumentTypeFactory factory,
            BrigadierDispatch dispatch,
            String rootLabel) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(rootLabel, "rootLabel");
        LiteralArgumentBuilder<CommandSourceStack> literal =
            Commands.<CommandSourceStack>literal(name).requires(requirement());
        attachExecutes(literal, dispatch, rootLabel);
        appendArguments(literal, 0, factory, dispatch, rootLabel);
        return literal;
    }

    private void appendArguments(ArgumentBuilder<CommandSourceStack, ?> parent,
                                 int index,
                                 ArgumentTypeFactory factory,
                                 BrigadierDispatch dispatch,
                                 String rootLabel) {
        if (index >= arguments.size()) {
            return;
        }
        CommandArgument<?> arg = arguments.get(index);
        if (arg.isFixedOptions()) {
            for (String option : arg.fixedOptions()) {
                LiteralArgumentBuilder<CommandSourceStack> branch =
                    Commands.<CommandSourceStack>literal(option);
                attachExecutes(branch, dispatch, rootLabel);
                appendArguments(branch, index + 1, factory, dispatch, rootLabel);
                parent.then(branch);
            }
            return;
        }
        ArgumentType<?> type = arg.brigadierType(factory);
        RequiredArgumentBuilder<CommandSourceStack, ?> node = typedNode(arg.name(), type);
        node.suggests((ctx, builder) -> suggestFor(ctx, builder, arg));
        attachExecutes(node, dispatch, rootLabel);
        appendArguments(node, index + 1, factory, dispatch, rootLabel);
        parent.then(node);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RequiredArgumentBuilder<CommandSourceStack, ?> typedNode(
            String argName, ArgumentType<?> type) {
        return Commands.argument(argName, (ArgumentType) type);
    }

    private void attachExecutes(ArgumentBuilder<CommandSourceStack, ?> builder,
                                BrigadierDispatch dispatch,
                                String rootLabel) {
        builder.executes(ctx -> {
            dispatch.dispatch(ctx.getSource(), rootLabel,
                splitInput(ctx.getInput(), rootLabel));
            return Command.SINGLE_SUCCESS;
        });
    }

    /**
     * 依原始輸入空白切分重建 args（去掉根標籤）。
     *
     * <p>所有開放式引數拒絕空白（單 token 不變條件），故切分無損；
     * 固定選項字面本身不含空白。</p>
     */
    static List<String> splitInput(String input, String rootLabel) {
        if (input == null) {
            return List.of();
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= 1) {
            return List.of();
        }
        // tokens[0] 為根標籤（可能為別名）；其餘為子指令＋引數。
        List<String> rest = new ArrayList<>(tokens.length - 1);
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].isEmpty()) {
                rest.add(tokens[i]);
            }
        }
        return List.copyOf(rest);
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
        suggestFor(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                   com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
                   CommandArgument<?> arg) {
        String prefix = builder.getRemainingLowerCase();
        try {
            CommandSender sender = ctx.getSource().getSender();
            if (permission != null && !permission.isEmpty()
                && !sender.hasPermission(permission)) {
                return builder.buildFuture();
            }
            for (String candidate : arg.suggest(prefix)) {
                builder.suggest(candidate);
            }
        } catch (Throwable ignored) {
            // 補全失敗不中斷輸入；回傳目前已累積結果。
        }
        return builder.buildFuture();
    }

    private Predicate<CommandSourceStack> requirement() {
        return stack -> {
            try {
                CommandSender sender = stack.getSender();
                if (permission != null && !permission.isEmpty()
                    && !sender.hasPermission(permission)) {
                    return false;
                }
                if (playerOnly && !(sender instanceof Player)) {
                    return false;
                }
                return !consoleOnly || !(sender instanceof Player);
            } catch (Throwable ignored) {
                return false;
            }
        };
    }

    /**
     * 建立 builder。
     *
     * @param name 子指令名稱；不可為 null 或空字串
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 型別化子指令 builder。 */
    public static final class Builder {
        private final String name;
        private String description;
        private String usage;
        private String permission;
        private boolean playerOnly;
        private boolean consoleOnly;
        private long cooldownMillis;
        private final List<CommandArgument<?>> arguments = new ArrayList<>();
        private TypedHandler handler;
        private CommandMessages messages;

        private Builder(String name) {
            this.name = name;
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

        public Builder playerOnly() {
            this.playerOnly = true;
            this.consoleOnly = false;
            return this;
        }

        public Builder consoleOnly() {
            this.consoleOnly = true;
            this.playerOnly = false;
            return this;
        }

        public Builder cooldownMillis(long cooldownMillis) {
            this.cooldownMillis = cooldownMillis;
            return this;
        }

        public Builder argument(CommandArgument<?> argument) {
            Objects.requireNonNull(argument, "argument");
            this.arguments.add(argument);
            return this;
        }

        public Builder executes(TypedHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Builder messages(CommandMessages messages) {
            this.messages = messages;
            return this;
        }

        public TypedSubCommand build() {
            return new TypedSubCommand(this);
        }
    }
}
