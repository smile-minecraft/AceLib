package com.example.acelibcmdprobe;

import com.smile.acelib.command.Arguments;
import com.smile.acelib.command.BrigadierRegistrar;
import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandErrorKind;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandMessages;
import com.smile.acelib.command.DefaultCommandMessages;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 指令相容性探針 plugin。
 *
 * <p>以 AceLib v1.4.0 的 {@link BrigadierRegistrar} 註冊三個根指令，
 * 讓維護者用 Java 版與基岩版（Geyser）客戶端觀察每種型別化引數的解析、
 * 錯誤提示與補全：</p>
 * <ul>
 *   <li>{@code /cprobe parse <case>} — 解析成功路徑，記錄執行緒與型別值</li>
 *   <li>{@code /cprobe give <player> [amount] [extra...]} — 省略引數
 *       （amount 預設 1）與重複引數（extra 零個合法）的組合形狀</li>
 *   <li>{@code /cprobe parse-bigdecimal / parse-dyn / parse-percent} —
 *       第三階段引數（精確數值／動態選項／自訂引數）的成功與錯誤路徑</li>
 *   <li>{@code /cprobe dyn-add / dyn-remove} — 動態選項執行期增刪</li>
 *   <li>{@code /cprobe-args <case>} — 錯誤路徑與補全（無權限限制）</li>
 *   <li>{@code /cprobe lifecycle <case>} — reload／重複註冊／disable 殘留</li>
 * </ul>
 *
 * <p><strong>執行緒語意</strong>：handler 在平台派送的執行緒執行（Paper 主
 * 執行緒／Folia 的 region 執行緒）。本探針只記錄執行緒名稱，不在該執行緒
 * 阻塞，也不跨執行緒直接操作實體，因此不會違反 Folia 規則。</p>
 *
 * <p><strong>無殘留</strong>：本 plugin 的 plugin.yml 不宣告 commands 區塊，
 * 指令完全由平台生命週期註冊；{@code onDisable} 呼叫
 * {@link BrigadierRegistrar#shutdown()}，使殘留 dispatch 一律回
 * {@code ACELIB-CMD-009}。</p>
 */
public class CommandCompatibilityProbePlugin extends JavaPlugin {

    /** 根指令名。 */
    public static final String ROOT = "cprobe";
    /** 開放式引數（錯誤與補全觀察用）。 */
    public static final String ROOT_ARGS = "cprobe-args";

    private volatile BrigadierRegistrar registrar;
    /** 最近一次 handler 觀察到的執行緒名稱（供 lifecycle status 回報）。 */
    private final AtomicReference<String> lastHandlerThread = new AtomicReference<>();

    /** 探針使用的引數實例（handler 以實例取值）。 */
    private final CommandArgument<World> worldArg = Arguments.world("world");
    private final CommandArgument<Material> materialArg = Arguments.material("item");
    private final CommandArgument<OfflinePlayer> offlineArg =
        Arguments.offlinePlayer("target");

    /**
     * 動態選項的執行期集合（parse-dyn 的供應函式每次解析與補全重新快照；
     * dyn-add／dyn-remove 在執行期增刪）。
     *
     * <p>reload 會重建 plugin 實例，集合回到初始值（alpha／beta）；
     * 這是探針本體的狀態，不是框架行為。</p>
     */
    private final List<String> dynOptions =
        new CopyOnWriteArrayList<>(List.of("alpha", "beta"));

    @Override
    public void onEnable() {
        BrigadierRegistrar created = new BrigadierRegistrar(this);
        try {
            // /cprobe 含 lifecycle 子指令（由 parseCommand 一併帶出）。
            created.register(parseCommand());
            created.register(argsCommand());
        } catch (Throwable t) {
            getLogger().severe("probe registration failed: " + t);
            created.shutdown();
            return;
        }
        this.registrar = created;
        getLogger().info("registered " + created.getRegisteredCommands().size()
            + " probe commands (no plugin.yml commands block)");
        getLogger().info("probe cases: " + CommandProbeCases.all().size());
    }

    @Override
    public void onDisable() {
        BrigadierRegistrar current = this.registrar;
        if (current != null) {
            current.shutdown();
            this.registrar = null;
        }
        getLogger().info("probe commands shut down; residual dispatch -> ACELIB-CMD-009");
    }

    /** {@code /cprobe parse <...>}：每種型別化引數的成功解析路徑。 */
    private TypedCommand parseCommand() {
        CommandArgument<?> player = Arguments.player("player");
        CommandArgument<?> offline = Arguments.offlinePlayer("offline");
        CommandArgument<Integer> intArg = Arguments.intArg("value", 1, 64);
        CommandArgument<Double> doubleArg = Arguments.doubleArg("value", 0, 10);
        CommandArgument<Long> duration = Arguments.duration("value");
        CommandArgument<Mode> mode = Arguments.enumArg("value", Mode.class);
        CommandArgument<String> fixed = Arguments.fixed("value", "buy", "sell");

        TypedCommand.Builder root = TypedCommand.builder(ROOT)
            .description("指令相容性探針（解析／錯誤／補全／生命週期）")
            .usage("/" + ROOT + " <parse|parse-offline|parse-int|parse-double|"
                + "parse-duration|parse-world|parse-mode|parse-fixed|parse-material|"
                + "give|parse-bigdecimal|parse-dyn|dyn-add|dyn-remove|parse-percent|"
                + "lifecycle>")
            .permission("acelibcmdprobe.use")
            .aliases("cp");
        root.subcommand(parseSub("parse", player));
        root.subcommand(parseSub("parse-offline", offline));
        root.subcommand(parseSub("parse-int", intArg, "pi"));
        root.subcommand(parseSub("parse-double", doubleArg));
        root.subcommand(parseSub("parse-duration", duration));
        root.subcommand(parseSub("parse-world", worldArg));
        root.subcommand(parseSub("parse-mode", mode));
        root.subcommand(parseSub("parse-fixed", fixed));
        root.subcommand(parseSub("parse-material", materialArg));
        root.subcommand(giveSub());
        root.subcommand(bigDecimalSub());
        root.subcommand(dynParseSub());
        root.subcommand(dynAddSub());
        root.subcommand(dynRemoveSub());
        root.subcommand(percentSub());
        root.subcommand(lifecycleSub());
        return root.build();
    }

    private TypedSubCommand parseSub(String name, CommandArgument<?> argument) {
        return parseSub(name, argument, new String[0]);
    }

    private TypedSubCommand parseSub(String name, CommandArgument<?> argument,
                                     String... aliases) {
        return TypedSubCommand.builder(name)
            .description("解析 " + argument.usageToken() + " 並回報型別值與執行緒")
            .aliases(aliases)
            .argument(argument)
            .executes(ctx -> {
                String thread = Thread.currentThread().getName();
                lastHandlerThread.set(thread);
                // 以引數實例取值：解析失敗不會進到這裡（已由 dispatcher
                // 轉為 ACELIB-CMD-015 回覆）。
                Object resolved = ctx.get(argument);
                String value = describe(resolved);
                ctx.reply("[" + name + "] ok value=" + value
                    + " type=" + valueType(resolved)
                    + " thread=" + thread);
            })
            .build();
    }

    /**
     * {@code /cprobe give <player> [amount] [extra...]}：
     * 省略引數（amount 預設 1）與重複引數（extra 零個合法）的組合形狀。
     *
     * <p>位置語法無法跳過中間引數：已提供的值先填滿省略引數，
     * 剩下的才歸重複引數（例如 {@code give Steve stone} 的 {@code stone}
     * 會先填 amount 槽而得到 ACELIB-CMD-015，不會落到 extra）。</p>
     */
    private TypedSubCommand giveSub() {
        CommandArgument<PlayerHandle> player = Arguments.player("player");
        CommandArgument<Integer> amount = Arguments.intArg("amount", 1, 64);
        CommandArgument<Material> extra = Arguments.material("extra");
        return TypedSubCommand.builder("give")
            .description("省略引數＋重複引數：[player] [amount 預設1] [extra...]")
            .argument(player)
            .optional(amount, sender -> 1)
            .repeatable(extra)
            .executes(ctx -> {
                PlayerHandle handle = ctx.get(player);
                int count = ctx.get(amount);
                List<Material> extras = ctx.getList(extra);
                // 子指令之後只有玩家名 ⟺ amount 被省略（位置語法保證）。
                String source = ctx.commandArgs().size() <= 1 ? "default" : "provided";
                List<String> notes = new ArrayList<>(extras.size());
                for (Material item : extras) {
                    notes.add(String.valueOf(item));
                }
                ctx.reply("[give] ok player=" + describe(handle)
                    + " amount=" + count + " source=" + source
                    + " notes=" + notes
                    + " thread=" + Thread.currentThread().getName());
            })
            .build();
    }

    /** {@code /cprobe parse-bigdecimal <value>}：精確數值（範圍 0-1000，小數位上限 2）。 */
    private TypedSubCommand bigDecimalSub() {
        CommandArgument<BigDecimal> value = Arguments.bigDecimal("value",
            new BigDecimal("0"), new BigDecimal("1000"), 2);
        return TypedSubCommand.builder("parse-bigdecimal")
            .description("精確數值（範圍 0-1000 含端點，小數位上限 2，科學記號拒絕）")
            .argument(value)
            .executes(ctx -> {
                BigDecimal parsed = ctx.get(value);
                ctx.reply("[parse-bigdecimal] ok value=" + parsed.toPlainString()
                    + " scale=" + parsed.scale()
                    + " thread=" + Thread.currentThread().getName());
            })
            .build();
    }

    /**
     * {@code /cprobe parse-dyn <value>}：動態選項解析。
     *
     * <p>供應函式每次解析與補全重新快照執行期集合，因此 dyn-add／dyn-remove
     * 的增刪立刻反映，不需要重新註冊。供應函式回傳複本（框架文件要求
     * 回傳穩定快照，不回傳仍被別處修改中的集合）。</p>
     */
    private TypedSubCommand dynParseSub() {
        CommandArgument<String> dyn = Arguments.dynamic("value",
            () -> List.copyOf(dynOptions));
        return TypedSubCommand.builder("parse-dyn")
            .description("動態選項解析（執行期增刪即時反映）")
            .argument(dyn)
            .executes(ctx -> ctx.reply("[parse-dyn] ok value=" + ctx.get(dyn)
                + " options=" + List.copyOf(dynOptions)
                + " thread=" + Thread.currentThread().getName()))
            .build();
    }

    /** {@code /cprobe dyn-add <name>}：新增動態選項（大小寫保留原形）。 */
    private TypedSubCommand dynAddSub() {
        CommandArgument<String> name = optionNameArg("name");
        return TypedSubCommand.builder("dyn-add")
            .description("新增動態選項（已存在則不重複加入）")
            .argument(name)
            .executes(ctx -> {
                String raw = ctx.get(name);
                boolean exists = dynOptions.stream()
                    .anyMatch(option -> option.equalsIgnoreCase(raw));
                if (!exists) {
                    dynOptions.add(raw);
                }
                ctx.reply("[dyn-add] ok added=" + raw + " exists=" + exists
                    + " options=" + List.copyOf(dynOptions));
            })
            .build();
    }

    /** {@code /cprobe dyn-remove <name>}：移除動態選項（大小寫不敏感比對）。 */
    private TypedSubCommand dynRemoveSub() {
        CommandArgument<String> name = optionNameArg("name");
        return TypedSubCommand.builder("dyn-remove")
            .description("移除動態選項（不存在則回報移除失敗，不拋錯）")
            .argument(name)
            .executes(ctx -> {
                String raw = ctx.get(name);
                boolean removed = dynOptions.removeIf(
                    option -> option.equalsIgnoreCase(raw));
                ctx.reply("[dyn-remove] ok removed=" + raw + " success=" + removed
                    + " options=" + List.copyOf(dynOptions));
            })
            .build();
    }

    /**
     * dyn-add／dyn-remove 用的選項名引數（自訂引數示範之二）。
     *
     * <p>接受英數字、連字號與底線的單 token（Brigadier {@code word}
     * 字元集內，兩條路徑皆可解析）；補全列出當前動態集合的前綴符合項。</p>
     */
    private CommandArgument<String> optionNameArg(String name) {
        return CommandArgument.custom(name, "<" + name + ":word>",
            (raw, messages) -> {
                CommandMessages effective = messages == null
                    ? DefaultCommandMessages.instance()
                    : messages;
                if (raw.matches("[A-Za-z0-9_-]+")) {
                    return raw;
                }
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    effective.invalidArgument(name, raw,
                        "expected <word> of letters, digits, - or _"),
                    Map.of("arg", name, "value", raw));
            },
            prefix -> {
                String lower = prefix.toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (String option : dynOptions) {
                    if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        out.add(option);
                    }
                }
                return List.copyOf(out);
            });
    }

    /** {@code /cprobe parse-percent <rate>}：自訂百分比引數（0-100 裸數字 → 0.0-1.0）。 */
    private TypedSubCommand percentSub() {
        CommandArgument<Double> rate = percentArg("rate");
        return TypedSubCommand.builder("parse-percent")
            .description("自訂引數（0-100 裸數字 → 0.0-1.0，字元集內單 token）")
            .argument(rate)
            .executes(ctx -> ctx.reply("[parse-percent] ok value=" + ctx.get(rate)
                + " thread=" + Thread.currentThread().getName()))
            .build();
    }

    /**
     * 百分比自訂引數（外部 consumer 寫法：解析函式＋補全函式即可，
     * 與主倉測試的同格式引數行為一致）。
     *
     * <p>裸數字 {@code 75} 解析為 {@code 0.75}；範圍外與非數字走
     * 在地化 ACELIB-CMD-015。數字全在 Brigadier {@code word}
     * 字元集內，因此兩條路徑皆可解析。</p>
     */
    private static CommandArgument<Double> percentArg(String name) {
        return CommandArgument.custom(name, "<" + name + ":percent>",
            (raw, messages) -> {
                CommandMessages effective = messages == null
                    ? DefaultCommandMessages.instance()
                    : messages;
                try {
                    double value = Double.parseDouble(raw);
                    if (!Double.isNaN(value) && !Double.isInfinite(value)
                        && value >= 0 && value <= 100) {
                        return value / 100.0;
                    }
                } catch (NumberFormatException ignored) {
                    // 落到下方的統一錯誤。
                }
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    effective.invalidArgument(name, raw,
                        "expected <number> in 0-100"),
                    Map.of("arg", name, "value", raw));
            },
            prefix -> {
                List<String> out = new ArrayList<>();
                for (String candidate : List.of("25", "50", "75", "100")) {
                    if (candidate.toLowerCase(Locale.ROOT)
                        .startsWith(prefix.toLowerCase(Locale.ROOT))) {
                        out.add(candidate);
                    }
                }
                return List.copyOf(out);
            });
    }

    /**
     * {@code /cprobe-args <...>}：無權限限制的錯誤路徑與補全觀察面。
     *
     * <p>固定選項（mode）以 literal 分支呈現；原本預期基岩可見，但 2026-10-08
     * 真人基岩客戶端實測顯示基岩端建議列並未出現（Geyser Current Limitations，
     * Unfixable），見 {@code docs/modules/command.md} 補全支援矩陣；
     * 開放式引數的伺服器建議送不到基岩版，供對照觀察。</p>
     */
    private TypedCommand argsCommand() {
        CommandArgument<Integer> amount = Arguments.intArg("amount", 1, 64);
        CommandArgument<String> mode = Arguments.fixed("mode", "buy", "sell");

        TypedCommand.Builder root = TypedCommand.builder(ROOT_ARGS)
            .description("指令相容性探針（開放式引數，無權限限制，供補全與錯誤觀察）")
            .usage("/" + ROOT_ARGS + " <mode:buy|sell> [amount:1-64]");
        root.subcommand(TypedSubCommand.builder("trade")
            .description("固定選項 + 有界整數")
            .argument(mode)
            .argument(amount)
            .executes(ctx -> ctx.reply("[" + ROOT_ARGS + "] ok mode="
                + ctx.get(mode) + " amount=" + ctx.get(amount)
                + " thread=" + Thread.currentThread().getName()))
            .build());
        return root.build();
    }

    /** {@code /cprobe lifecycle <...>}：殘留檢查。 */
    private TypedSubCommand lifecycleSub() {
        // 保留同一個實例：TypedContext 以引數實例為 key，傳入另一個
        // 內容相同但身分不同的實例會被視為未參與解析。
        CommandArgument<String> action =
            Arguments.fixed("action", "re-register", "shutdown", "status");
        return TypedSubCommand.builder("lifecycle")
            .description("生命週期殘留檢查（re-register / shutdown / status）")
            .permission("acelibcmdprobe.admin")
            .consoleOnly()
            .argument(action)
            .executes(ctx -> handleLifecycle(ctx.get(action)))
            .build();
    }

    private void handleLifecycle(String action) {
        BrigadierRegistrar current = this.registrar;
        if (current == null) {
            getLogger().warning("registrar already shut down; action=" + action);
            return;
        }
        switch (action) {
            case "re-register" -> {
                try {
                    // 同名重複註冊必須原子拒絕，且平台側不掛第二個節點。
                    current.register(parseCommand());
                    getLogger().warning("RE-REGISTER UNEXPECTEDLY SUCCEEDED "
                        + "(duplicate registration should be rejected)");
                } catch (IllegalArgumentException ex) {
                    getLogger().info("re-register rejected as expected: "
                        + ex.getMessage()
                        + "; registered=" + current.getRegisteredCommands().size());
                }
            }
            case "shutdown" -> {
                current.shutdown();
                getLogger().info("shutdown done; residual dispatch -> ACELIB-CMD-009"
                    + "; last handler thread=" + lastHandlerThread.get());
            }
            default -> getLogger().info("registered=" + current.getRegisteredCommands().size()
                + " lastHandlerThread=" + lastHandlerThread.get()
                + " platform=(" + getServer().getName() + " "
                + getServer().getVersion() + ")");
        }
    }

    /**
     * 把已解析的型別值轉為可觀察描述。
     *
     * <p>線上玩家引數解析出的是 {@link PlayerHandle}（AceLib 抽象），不是
     * {@code org.bukkit.entity.Player}，因此必須先比對 handle，否則會落到
     * {@code String.valueOf} 印出內部類別名與雜湊碼。</p>
     */
    private String describe(Object value) {
        if (value instanceof PlayerHandle handle) {
            return handle.getName() + " (online=" + handle.isOnline() + ")";
        }
        if (value instanceof org.bukkit.entity.Player player) {
            return player.getName() + " (online=" + player.isOnline() + ")";
        }
        if (value instanceof OfflinePlayer offline) {
            return offline.getName() + " (playedBefore="
                + offline.hasPlayedBefore() + ")";
        }
        if (value instanceof World world) {
            return world.getName();
        }
        return String.valueOf(value);
    }

    /**
     * 已解析值的型別名。
     *
     * <p>直接取自已解析值的實型別，不從引數名稱或 usage token 推測 ——
     * 後者對沒有冒號的 token（例如 {@code <player>}）會推不出型別而印出
     * {@code ?}，且每新增一種引數都要補一個對應規則。</p>
     */
    private String valueType(Object value) {
        if (value instanceof PlayerHandle) {
            return "PlayerHandle";
        }
        return value.getClass().getSimpleName();
    }

    /** 探針用的示範列舉（固定選項 literal 分支）。 */
    private enum Mode {
        BUY,
        SELL
    }
}