package com.example.acelibcmdprobe;

import com.smile.acelib.command.Arguments;
import com.smile.acelib.command.BrigadierRegistrar;
import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;
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
                + "parse-duration|parse-world|parse-mode|parse-fixed|parse-material|lifecycle>")
            .permission("acelibcmdprobe.use")
            .aliases("cp");
        root.subcommand(parseSub("parse", player));
        root.subcommand(parseSub("parse-offline", offline));
        root.subcommand(parseSub("parse-int", intArg));
        root.subcommand(parseSub("parse-double", doubleArg));
        root.subcommand(parseSub("parse-duration", duration));
        root.subcommand(parseSub("parse-world", worldArg));
        root.subcommand(parseSub("parse-mode", mode));
        root.subcommand(parseSub("parse-fixed", fixed));
        root.subcommand(parseSub("parse-material", materialArg));
        root.subcommand(lifecycleSub());
        return root.build();
    }

    private TypedSubCommand parseSub(String name, CommandArgument<?> argument) {
        return TypedSubCommand.builder(name)
            .description("解析 " + argument.usageToken() + " 並回報型別值與執行緒")
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