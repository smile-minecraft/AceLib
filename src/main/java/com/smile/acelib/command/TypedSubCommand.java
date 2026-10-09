package com.smile.acelib.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * <p>引數分三種形狀（宣告順序即位置順序）：</p>
 * <ul>
 *   <li>必要引數（{@link Builder#argument}）— 必須提供；全必要宣告時
 *       {@code minArgs == maxArgs == 引數數}</li>
 *   <li>省略引數（{@link Builder#optional}）— 必須是連續尾段；省略時
 *       {@link ArgumentDefault} 依本次執行的 sender 計算一次型別化預設值
 *       交給 handler（仍以 {@link TypedContext#get} 取值）</li>
 *   <li>重複引數（{@link Builder#repeatable}）— 只能是最後一個引數；
 *       零個合法，handler 以 {@link TypedContext#getList} 取得不可變
 *       {@link List}</li>
 * </ul>
 *
 * <p>位置語法無法跳過中間引數：已提供的值先填滿省略引數，剩下的才歸重複引數。
 * 互相矛盾的宣告（必要引數接在省略引數後、重複引數後面還有引數、
 * 兩個重複引數）在 {@code build()} 以 {@link IllegalArgumentException}
 * 拒絕並說明，不猜使用者意圖。引數解析錯誤訊息經建構時指定的
 * {@link CommandMessages} 產生（預設英文）。零引數子指令允許
 * （handler 直接執行）。</p>
 *
 * @see TypedCommand
 * @see Arguments
 * @since 1.4.0
 */
public final class TypedSubCommand {

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final String permission;
    private final boolean playerOnly;
    private final boolean consoleOnly;
    private final long cooldownMillis;
    private final List<CommandArgument<?>> arguments;
    private final TypedHandler handler;
    private final CommandMessages messages;
    /** 必要引數個數（前段連續必要引數；相容層 minArgs 用）。 */
    private final int requiredCount;
    /** 省略引數的預設值提供者（引數實例 key；非省略引數不在內）。 */
    private final Map<CommandArgument<?>, ArgumentDefault<?>> defaults;
    /** 重複引數（null 表示無；必為 arguments 最後一個）。 */
    private final CommandArgument<?> repeatable;

    private TypedSubCommand(Builder builder) {
        Objects.requireNonNull(builder.name, "name");
        if (builder.name.isEmpty()) {
            throw new IllegalArgumentException("subcommand name cannot be empty");
        }
        this.name = builder.name.toLowerCase(java.util.Locale.ROOT);
        this.aliases = SubCommandSpec.checkAliases(builder.name, builder.aliases);
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
        validateShape(builder.name, args, builder.defaults, builder.repeatables);
        this.arguments = Collections.unmodifiableList(args);
        Map<CommandArgument<?>, ArgumentDefault<?>> defaultCopy = new IdentityHashMap<>();
        defaultCopy.putAll(builder.defaults);
        this.defaults = Collections.unmodifiableMap(defaultCopy);
        this.repeatable = builder.repeatables.isEmpty()
            ? null
            : builder.repeatables.iterator().next();
        int required = 0;
        for (CommandArgument<?> arg : args) {
            if (isOptional(arg) || arg == this.repeatable) {
                break;
            }
            required++;
        }
        this.requiredCount = required;
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
                generated.append(usageToken(arg));
            }
            this.usage = generated.toString();
        }
    }

    /**
     * 驗證宣告形狀：省略引數必須是連續尾段、重複引數唯一且居末。
     *
     * @param subName    子指令名（錯誤訊息用）
     * @param args       宣告順序的引數
     * @param defaults   省略引數預設值（引數實例 key）
     * @param repeatables 重複引數（引數實例集合；至多一個）
     * @throws IllegalArgumentException 形狀矛盾時說明原因
     */
    private static void validateShape(String subName, List<CommandArgument<?>> args,
                                      Map<CommandArgument<?>, ArgumentDefault<?>> defaults,
                                      java.util.Set<CommandArgument<?>> repeatables) {
        if (repeatables.size() > 1) {
            List<String> names = new ArrayList<>();
            for (CommandArgument<?> arg : args) {
                if (repeatables.contains(arg)) {
                    names.add(arg.name());
                }
            }
            throw new IllegalArgumentException(
                "subcommand '" + subName + "' allows only one repeatable argument: "
                    + String.join(", ", names));
        }
        CommandArgument<?> repeated = repeatables.isEmpty()
            ? null
            : repeatables.iterator().next();
        int repeatIndex = -1;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) == repeated) {
                repeatIndex = i;
                break;
            }
        }
        String seenOptional = null;
        for (int i = 0; i < args.size(); i++) {
            CommandArgument<?> arg = args.get(i);
            boolean optional = defaults.containsKey(arg);
            boolean repeatedHere = i == repeatIndex;
            if (repeatIndex >= 0 && i > repeatIndex) {
                throw new IllegalArgumentException(
                    "subcommand '" + subName + "': argument '" + arg.name()
                        + "' cannot follow repeatable argument '" + repeated.name()
                        + "': repeatable must be the last argument");
            }
            if (repeatedHere && i != args.size() - 1) {
                throw new IllegalArgumentException(
                    "subcommand '" + subName + "': repeatable argument '" + arg.name()
                        + "' must be the last argument");
            }
            if (seenOptional != null && !optional && !repeatedHere) {
                throw new IllegalArgumentException(
                    "subcommand '" + subName + "': required argument '" + arg.name()
                        + "' cannot follow optional argument '" + seenOptional
                        + "': optional arguments must form a trailing segment");
            }
            if (optional && seenOptional == null) {
                seenOptional = arg.name();
            }
        }
    }

    /** 用法 token：省略加中括號、重複加省略號（既有全必要形狀維持原樣）。 */
    private String usageToken(CommandArgument<?> arg) {
        String token = arg.usageToken();
        if (arg == repeatable) {
            return token + "...";
        }
        if (isOptional(arg)) {
            return "[" + token + "]";
        }
        return token;
    }

    /** 是否為省略引數。 */
    private boolean isOptional(CommandArgument<?> arg) {
        return defaults.containsKey(arg);
    }

    /** 子指令名稱（小寫）。 */
    public String name() {
        return name;
    }

    /**
     * 子指令別名（以呼叫端提供的形式保留，不轉小寫；不可變）。
     *
     * <p>比較一律小寫；傳統路徑與 Brigadier 路徑都把別名視為主名
     * （同一 handler、同一引數、同一冷卻 key）。help 只列主名。</p>
     */
    public List<String> aliases() {
        return aliases;
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

    /**
     * 引數清單（不可變，保留宣告順序）。
     *
     * <p>含省略引數與重複引數（如有）：省略引數取值仍用
     * {@link TypedContext#get}（省略時為預設值），重複引數改用
     * {@link TypedContext#getList}。</p>
     */
    public List<CommandArgument<?>> arguments() {
        return arguments;
    }

    /**
     * 轉為 {@link SubCommandSpec} 相容層。
     *
     * <p>handler 內部依序解析（失敗即拋 {@link CommandException}，
     * dispatcher 經 {@link ReplySink} 回覆）：已提供的值先填滿省略引數，
     * 剩下的歸重複引數；被省略的省略引數由登記的
     * {@link ArgumentDefault} 依本次 sender 計算一次預設值
     * （提供者拋 {@link CommandException} 時直接向外傳遞，不靜默 fallback）。
     * completer 依目前位置委派給對應引數的 {@code suggest}。</p>
     *
     * <p>參數數量：{@code minArgs} 為必要引數數；有重複引數時
     * {@code maxArgs} 為無上限（-1），否則等於引數總數。</p>
     *
     * @return 對應的 {@link SubCommandSpec}；永不為 null
     */
    public SubCommandSpec toSubCommandSpec() {
        SubCommandSpec.Builder builder = SubCommandSpec.builder(name)
            .description(description)
            .usage(usage)
            .aliases(aliases.toArray(new String[0]))
            .cooldownMillis(cooldownMillis)
            .handler(ctx -> {
                Map<CommandArgument<?>, Object> values = new IdentityHashMap<>();
                List<String> provided = ctx.commandArgs();
                Sender sender = ctx.sender();
                int cursor = 0;
                int fixedSlots = fixedSlotCount();
                for (int i = 0; i < fixedSlots; i++) {
                    CommandArgument<?> arg = arguments.get(i);
                    if (cursor < provided.size()) {
                        values.put(arg, parseOne(arg, provided.get(cursor)));
                        cursor++;
                    } else {
                        values.put(arg, defaultFor(arg, sender));
                    }
                }
                if (repeatable != null) {
                    List<Object> items = new ArrayList<>();
                    while (cursor < provided.size()) {
                        items.add(parseOne(repeatable, provided.get(cursor)));
                        cursor++;
                    }
                    values.put(repeatable, new TypedContext.Repeated(items));
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
        builder.minArgs(requiredCount)
            .maxArgs(repeatable == null ? arguments.size() : -1);
        List<String> names = new ArrayList<>();
        for (CommandArgument<?> arg : arguments) {
            names.add(arg.name());
        }
        if (!names.isEmpty()) {
            builder.args(names.toArray(new String[0]));
        }
        return builder.build();
    }

    /** 非重複槽位數（必要＋省略；重複引數不佔固定槽）。 */
    private int fixedSlotCount() {
        return repeatable == null ? arguments.size() : arguments.size() - 1;
    }

    /**
     * 計算被省略的省略引數的預設值（本次執行算一次）。
     *
     * @param arg    省略引數實例
     * @param sender 本次執行的 sender
     * @return 型別化預設值；永不為 null
     * @throws CommandException 提供者計算失敗（直接向外傳遞，經回覆出口在地化）
     * @throws NullPointerException 提供者回傳 null（契約違規；上層包裝為執行失敗）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object defaultFor(CommandArgument<?> arg, Sender sender) {
        ArgumentDefault<?> provider = defaults.get(arg);
        Object resolved = ((ArgumentDefault) provider).resolve(sender);
        return Objects.requireNonNull(resolved,
            "default value for argument '" + arg.name() + "' must not be null");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object parseOne(CommandArgument<?> arg, String raw) {
        return ((CommandArgument) arg).parse(raw, messages);
    }

    private List<String> completePositional(CommandContext ctx, List<String> args) {
        // args[0] 為子指令名；其後為位置引數。補全永遠針對最後一個已輸入 token。
        // 固定槽位（必要＋省略）按位置對應；超出固定槽位時（只能是有重複引數
        // 才到得了的位置）一律歸重複引數。
        int positional = args.size() - 2;
        if (positional < 0) {
            return List.of();
        }
        if (!ctx.sender().hasPermission(permission)) {
            return List.of();
        }
        CommandArgument<?> arg;
        int fixedSlots = fixedSlotCount();
        if (positional < fixedSlots) {
            arg = arguments.get(positional);
        } else if (repeatable != null) {
            arg = repeatable;
        } else {
            return List.of();
        }
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
     * <p>固定選項展開為 literal 分支（合法值走字面，成功與補全不變），
     * 另掛一個錯誤後備節點（單 token 字串、不提供補全）：集合外的值由此
     * 承接，切分重建後走相容層解析，回在地化 {@code ACELIB-CMD-015}
     * 而非 Brigadier 通用錯誤。後備型別拒絕字面完全一致的輸入，
     * 合法 literal 不被搶走；後續引數的展開與 literal 分支共用同一結構。
     * 開放式引數為 argument 節點
     * （附伺服器端 suggests，依子指令權限過濾）。每一層（含子指令字面
     * 本身）皆掛 executes，把原始輸入切分後委派給
     * {@code dispatch}（單一真相來源；部分輸入同樣得到在地化的
     * MISSING_ARGUMENTS 而非 Brigadier 通用錯誤）。</p>
     *
     * <p>可變形狀的編譯：省略引數不需額外結構（缺席時父層 executes 承接，
     * 相容層填預設值），且一律編譯為 stringWord 開放節點（即使是固定選項：
     * 失去 literal 結構，選項驗證與錯誤全由伺服器端判定，兩條路徑同回
     * 在地化 {@code ACELIB-CMD-015}）；重複引數編譯為單一 greedy 尾節點（無上限吞入剩餘
     * token，執行時同樣切分重建、相容層逐個解析——客戶端不做逐元素驗證，
     * 非法值由伺服器端回在地化 {@code ACELIB-CMD-015}，取捨比照
     * {@code bigDecimal} 的字串單詞節點）；有省略無重複時，末槽再掛一個
     * 不建議的 greedy 溢位節點，把多餘輸入導回相容層以產生在地化的
     * 過多引數錯誤，而非 Brigadier 通用錯誤。</p>
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
        return buildBranchAs(name, factory, dispatch, rootLabel);
    }

    /**
     * 主名＋別名的全部分支（根指令組裝 Brigadier 樹用；package 內可見）。
     *
     * <p>每個分支掛同一套引數子樹與執行委派（單一真相來源：執行時切分重建
     * 後走相容層 dispatch，別名同樣解析為主規格）。字面一律小寫
     * （主名本就小寫；別名折小寫與傳統路徑的大小寫不敏感一致）。</p>
     *
     * @param factory  引數型別工廠；不可為 null
     * @param dispatch 執行委派；不可為 null
     * @param rootLabel 根指令標籤（委派時回填）；不可為 null
     * @return 主名分支在首、其後為別名分支的不可變清單；永不為 null
     */
    List<LiteralArgumentBuilder<CommandSourceStack>> buildBranches(
            ArgumentTypeFactory factory,
            BrigadierDispatch dispatch,
            String rootLabel) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(rootLabel, "rootLabel");
        List<LiteralArgumentBuilder<CommandSourceStack>> branches =
            new ArrayList<>(1 + aliases.size());
        branches.add(buildBranchAs(name, factory, dispatch, rootLabel));
        for (String alias : aliases) {
            branches.add(buildBranchAs(
                alias.toLowerCase(java.util.Locale.ROOT),
                factory, dispatch, rootLabel));
        }
        return List.copyOf(branches);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildBranchAs(
            String literalName,
            ArgumentTypeFactory factory,
            BrigadierDispatch dispatch,
            String rootLabel) {
        LiteralArgumentBuilder<CommandSourceStack> literal =
            Commands.<CommandSourceStack>literal(literalName).requires(requirement());
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
        if (arg == repeatable) {
            // 重複引數必為最後一個：單一 greedy 尾節點無上限承接（固定選項
            // 的重複同樣走此開放節點，選項驗證由相容層解析負責）。
            attachRepeatableTail(parent, arg, dispatch, rootLabel);
            return;
        }
        boolean lastFixedSlot = repeatable == null && index == arguments.size() - 1;
        boolean optional = isOptional(arg);
        if (arg.isFixedOptions() && !optional) {
            for (String option : arg.fixedOptions()) {
                LiteralArgumentBuilder<CommandSourceStack> branch =
                    Commands.<CommandSourceStack>literal(option);
                attachExecutes(branch, dispatch, rootLabel);
                continueAfterFixedSlot(branch, index, factory, dispatch,
                    rootLabel, lastFixedSlot, arg);
                parent.then(branch);
            }
            // 錯誤後備節點：集合外的值由此承接，切分重建後走相容層解析，
            // 產在地化 ACELIB-CMD-015。型別拒絕與選項字面完全一致的輸入
            // （大小寫敏感，與 literal 比對語意相同），合法值永遠走上面的
            // literal 分支；後備不提供補全，literal 的建議內容不變。
            RequiredArgumentBuilder<CommandSourceStack, String> fallback =
                Commands.argument(fallbackNodeName(arg),
                    new FixedFallbackType(arg.fixedOptions()));
            fallback.suggests((ctx, builder) -> builder.buildFuture());
            attachExecutes(fallback, dispatch, rootLabel);
            continueAfterFixedSlot(fallback, index, factory, dispatch,
                rootLabel, lastFixedSlot, arg);
            parent.then(fallback);
            return;
        }
        // 開放節點：必要引數用各自的 vanilla 型別（客戶端先行驗證，既有契約
        // 不動）；省略引數（含省略的固定選項）一律用 stringWord 承接單 token，
        // 合法性與錯誤全由伺服器端同一個解析器判定，兩條路徑同回
        // ACELIB-CMD-015（取捨比照 bigDecimal 的字串單詞節點）。
        ArgumentType<?> type = optional
            ? factory.stringWord()
            : arg.brigadierType(factory);
        RequiredArgumentBuilder<CommandSourceStack, ?> node = typedNode(arg.name(), type);
        node.suggests((ctx, builder) -> suggestFor(ctx, builder, arg));
        attachExecutes(node, dispatch, rootLabel);
        if (lastFixedSlot && hasOptional()) {
            attachOverflow(node, dispatch, rootLabel, arg);
        } else {
            appendArguments(node, index + 1, factory, dispatch, rootLabel);
        }
        parent.then(node);
    }

    /** 是否含省略引數。 */
    private boolean hasOptional() {
        return !defaults.isEmpty();
    }

    /**
     * 固定選項層的後續展開（literal 分支與錯誤後備共用，結構一致）。
     */
    private void continueAfterFixedSlot(ArgumentBuilder<CommandSourceStack, ?> node,
                                        int index,
                                        ArgumentTypeFactory factory,
                                        BrigadierDispatch dispatch,
                                        String rootLabel,
                                        boolean lastFixedSlot,
                                        CommandArgument<?> arg) {
        if (lastFixedSlot && hasOptional()) {
            attachOverflow(node, dispatch, rootLabel, arg);
        } else {
            appendArguments(node, index + 1, factory, dispatch, rootLabel);
        }
    }

    /**
     * 錯誤後備節點名（避開已宣告引數名與選項字面，免得 Brigadier
     * 同層子節點衝突）。
     */
    private String fallbackNodeName(CommandArgument<?> arg) {
        String base = "_" + arg.name() + "_fallback";
        String candidate = base;
        while (hasArgumentNamed(candidate)
            || arg.fixedOptions().contains(candidate)) {
            candidate = "_" + candidate;
        }
        return candidate;
    }

    /**
     * 固定選項的錯誤後備型別（單 token 字串；Paper 平台可註冊）。
     *
     * <p>實作 Paper {@code CustomArgumentType}，原生型別為
     * {@code stringWord}：平台註冊轉換只接受此類包裝（普通
     * {@code ArgumentType} 會被轉換拒絕），送客戶端的節點仍是字串單詞。
     * 行為同 {@code stringWord}，唯獨拒絕與選項字面完全一致
     * （大小寫敏感，與 literal 比對語意相同）的輸入：合法值永遠走
     * literal 分支，後備只承接集合外的值。拒絕訊息不進使用者可見流程
     * （後備成功即進 dispatch；字面一致即有 literal 承接），僅為結構正確。</p>
     */
    private static final class FixedFallbackType
            implements CustomArgumentType<String, String> {
        private final Set<String> options;

        FixedFallbackType(Collection<String> options) {
            this.options = Set.copyOf(
                Objects.requireNonNull(options, "options"));
        }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            String word = StringArgumentType.word().parse(reader);
            if (options.contains(word)) {
                throw new SimpleCommandExceptionType(
                    new LiteralMessage(
                        "use the literal branch: " + word)).create();
            }
            return word;
        }

        @Override
        public ArgumentType<String> getNativeType() {
            return StringArgumentType.word();
        }
    }

    /**
     * 掛載重複引數的 greedy 尾節點：無上限吞入剩餘 token，執行時同樣切分
     * 重建、相容層逐個解析；補全取末 token 對應選項。
     */
    private void attachRepeatableTail(ArgumentBuilder<CommandSourceStack, ?> parent,
                                      CommandArgument<?> arg,
                                      BrigadierDispatch dispatch,
                                      String rootLabel) {
        RequiredArgumentBuilder<CommandSourceStack, String> node =
            greedyTailNode(arg.name());
        node.suggests((ctx, builder) -> suggestRepeatable(ctx, builder, arg));
        attachExecutes(node, dispatch, rootLabel);
        parent.then(node);
    }

    /**
     * 掛載省略形的溢位節點（有省略無重複時）：多餘輸入經此節點委派回
     * 相容層，由既有的數量檢查產生在地化過多引數錯誤；不提供補全。
     */
    private void attachOverflow(ArgumentBuilder<CommandSourceStack, ?> parent,
                                BrigadierDispatch dispatch,
                                String rootLabel,
                                CommandArgument<?> lastArg) {
        RequiredArgumentBuilder<CommandSourceStack, String> node =
            greedyTailNode(overflowNodeName(lastArg));
        node.suggests((ctx, builder) -> builder.buildFuture());
        attachExecutes(node, dispatch, rootLabel);
        parent.then(node);
    }

    /** greedy 尾節點工廠（重複本體與溢位槽共用形狀）。 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> greedyTailNode(
            String nodeName) {
        return Commands.argument(nodeName, StringArgumentType.greedyString());
    }

    /**
     * 溢位節點名（避開已宣告引數名，避免 Brigadier 同層子節點衝突）。
     */
    private String overflowNodeName(CommandArgument<?> lastArg) {
        String base = "_" + lastArg.name() + "_overflow";
        String candidate = base;
        while (hasArgumentNamed(candidate)) {
            candidate = "_" + candidate;
        }
        return candidate;
    }

    private boolean hasArgumentNamed(String name) {
        for (CommandArgument<?> arg : arguments) {
            if (arg.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重複引數的伺服器端補全（greedy 尾節點用）。
     *
     * <p>greedy 剩餘字串含多個已輸入 token，只取最後一個 token 做前綴
     * 過濾，並以 {@code createOffset} 把建議範圍縮到該 token（與傳統路徑
     * 只看最後 token 一致；已輸入的前段 token 不受建議取代影響）。</p>
     */
    private CompletableFuture<Suggestions> suggestRepeatable(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder,
            CommandArgument<?> arg) {
        // greedy 剩餘字串含多個已輸入 token（Brigadier 保證非 null）。
        String remaining = builder.getRemaining();
        int cut = remaining.lastIndexOf(' ');
        int tokenStart = cut < 0 ? builder.getStart() : builder.getStart() + cut + 1;
        String lastToken = cut < 0 ? remaining : remaining.substring(cut + 1);
        SuggestionsBuilder offset;
        try {
            offset = builder.createOffset(tokenStart);
        } catch (Throwable ex) {
            return builder.buildFuture();
        }
        try {
            CommandSender sender = ctx.getSource().getSender();
            if (permission != null && !permission.isEmpty()
                && !sender.hasPermission(permission)) {
                return offset.buildFuture();
            }
            for (String candidate : arg.suggest(lastToken)) {
                offset.suggest(candidate);
            }
        } catch (Throwable ignored) {
            // 補全失敗不中斷輸入；回傳目前已累積結果。
        }
        return offset.buildFuture();
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

    /**
     * 省略引數的預設值提供者（函數式介面）。
     *
     * <p>只在引數被省略時呼叫，每次執行至多一次；有提供值時不呼叫。
     * 實作依本次執行的 {@code sender} 計算型別值（例如線上玩家預設為
     * 自己）。無法產出預設值時（例如 console 無法預設為自己）應拋
     * {@link CommandException}（例如 {@code ACELIB-CMD-007}），由回覆出口
     * 在地化，不靜默 fallback。回傳 null 視為契約違規（上層包裝為執行失敗）。
     * 計算結果不跨執行快取。</p>
     *
     * @param <T> 引數值型別
     * @see Builder#optional(CommandArgument, ArgumentDefault)
     * @since 1.5.0
     */
    @FunctionalInterface
    public interface ArgumentDefault<T> {

        /**
         * 依本次執行的 sender 計算預設值。
         *
         * @param sender 本次執行的 sender；永不為 null
         * @return 型別化預設值；永不為 null
         * @throws CommandException 無法產出預設值（直接向外傳遞，經回覆出口在地化）
         */
        T resolve(Sender sender) throws CommandException;
    }

    /** 型別化子指令 builder。 */
    public static final class Builder {
        private final String name;
        private List<String> aliases;
        private String description;
        private String usage;
        private String permission;
        private boolean playerOnly;
        private boolean consoleOnly;
        private long cooldownMillis;
        private final List<CommandArgument<?>> arguments = new ArrayList<>();
        private final Map<CommandArgument<?>, ArgumentDefault<?>> defaults =
            new IdentityHashMap<>();
        private final java.util.Set<CommandArgument<?>> repeatables =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private TypedHandler handler;
        private CommandMessages messages;

        private Builder(String name) {
            this.name = name;
        }

        /**
         * 設定子指令別名（可選；與 {@link TypedCommand.Builder#aliases} 同形）。
         *
         * <p>別名儲存保留原形式、比較一律小寫；別名與主名或彼此衝突
         * （大小寫不敏感）在 {@link #build()} 以
         * {@link IllegalArgumentException} 拒絕並說明。跨子指令的衝突
         * （別名對其他子指令主名／別名）由 {@link TypedCommand} 建構時拒絕。</p>
         *
         * @param aliases 別名；不可含 null／空字串
         * @return this
         */
        public Builder aliases(String... aliases) {
            this.aliases = aliases == null ? null
                : Collections.unmodifiableList(new ArrayList<>(Arrays.asList(aliases)));
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

        /**
         * 宣告省略引數（必須是連續尾段）。
         *
         * <p>省略時 {@code defaultValue} 依本次執行的 sender 計算一次型別化
         * 預設值交給 handler（仍以 {@link TypedContext#get} 取值）。必要引數
         * 不得接在省略引數之後，重複引數之後不得再宣告引數；違反時
         * {@link #build()} 拋 {@link IllegalArgumentException}。</p>
         *
         * @param argument     引數；不可為 null
         * @param defaultValue 預設值提供者；不可為 null
         * @param <T>          引數值型別
         * @return this
         * @throws NullPointerException 任一參數為 null
         * @since 1.5.0
         */
        public <T> Builder optional(CommandArgument<T> argument,
                                   ArgumentDefault<T> defaultValue) {
            Objects.requireNonNull(argument, "argument");
            Objects.requireNonNull(defaultValue, "defaultValue");
            this.arguments.add(argument);
            this.defaults.put(argument, defaultValue);
            return this;
        }

        /**
         * 宣告重複引數（只能是最後一個引數，零個合法）。
         *
         * <p>handler 以 {@link TypedContext#getList} 取得不可變
         * {@link List}。此引數之後不得再宣告引數，最多一個重複引數；
         * 違反時 {@link #build()} 拋 {@link IllegalArgumentException}。</p>
         *
         * @param argument 引數；不可為 null
         * @param <T>      引數元素型別
         * @return this
         * @throws NullPointerException 當 {@code argument} 為 null
         * @since 1.5.0
         */
        public <T> Builder repeatable(CommandArgument<T> argument) {
            Objects.requireNonNull(argument, "argument");
            this.arguments.add(argument);
            this.repeatables.add(argument);
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
