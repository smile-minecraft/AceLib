package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 指令註冊中心實作。
 *
 * <h2>dispatch 流程</h2>
 * <ol>
 *   <li>disabled 檢查 → {@link CommandErrorKind#REGISTRY_DISABLED}</li>
 *   <li>主指令查找 → 失敗回覆 {@link CommandErrorKind#UNKNOWN_SUBCOMMAND}</li>
 *   <li>主指令權限檢查 → {@link CommandErrorKind#NO_PERMISSION}</li>
 *   <li>args 為空 → 回覆主指令 help（依權限過濾子指令）</li>
 *   <li>子指令查找 → 失敗回覆 {@link CommandErrorKind#UNKNOWN_SUBCOMMAND}</li>
 *   <li>子指令權限檢查 → {@link CommandErrorKind#NO_PERMISSION}</li>
 *   <li>player-only / console-only 檢查 →
 *       {@link CommandErrorKind#CONSOLE_NOT_ALLOWED} /
 *       {@link CommandErrorKind#PLAYER_NOT_ALLOWED}</li>
 *   <li>參數數量檢查 → {@link CommandErrorKind#MISSING_ARGUMENTS}</li>
 *   <li>冷卻檢查 → {@link CommandErrorKind#COOLDOWN_ACTIVE}</li>
 *   <li>呼叫 handler；handler 拋 {@link CommandException} → 自動呼叫
 *       {@link ReplySink#sendError}；拋其他 {@link RuntimeException} → 包裝成
 *       {@link CommandErrorKind#ASYNC_EXECUTION_FAILED}</li>
 * </ol>
 *
 * <h2>tab complete 流程</h2>
 * <ul>
 *   <li>args 為空 → 列出主指令名 + 該 sender 可見的子指令名（過濾無權限）</li>
 *   <li>args[0] 已知子指令 → 委派給 {@link SubCommandCompleter}；
 *       若 completer 為 null 或拋例外 → 回傳空 list</li>
 *   <li>args[0] 未知字串 → 回傳空 list（不暴露其他子指令）</li>
 * </ul>
 *
 * <h2>Reload 行為</h2>
 * <p>disabled 標記後指令 map 與 {@link CooldownTracker} 狀態保留 —
 * {@link #onPluginDisable()} 採 idempotent 設計；reload 流程中（plugin 先
 * disable 後 register 一次）若 caller 希望完全重置可改呼叫
 * {@link CooldownTracker#clearAll()}。</p>
 *
 * @see CommandRegistry
 * @since 1.0.0
 */
public final class CommandRegistryImpl implements CommandRegistry {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final ReplySink replySink;
    private final CooldownTracker cooldowns;

    /** key = lower-case name or alias → spec。 */
    private final ConcurrentHashMap<String, CommandSpec> byName = new ConcurrentHashMap<>();
    /** key = lower-case primary name → spec（避免 alias 重複註冊衝突）。 */
    private final ConcurrentHashMap<String, CommandSpec> byPrimary = new ConcurrentHashMap<>();

    private volatile boolean disabled;

    /**
     * 主要建構子（production code）。
     *
     * @param replySink 回覆出口；不可為 null
     * @throws NullPointerException 當 {@code replySink} 為 null
     */
    public CommandRegistryImpl(ReplySink replySink) {
        this(replySink, new CooldownTracker());
    }

    /**
     * 注入式建構子（測試 seam：注入 deterministic cooldown tracker）。
     *
     * @param replySink 回覆出口；不可為 null
     * @param cooldowns 冷卻追蹤器；不可為 null
     * @throws NullPointerException 任一參數為 null
     */
    CommandRegistryImpl(ReplySink replySink, CooldownTracker cooldowns) {
        this.replySink = Objects.requireNonNull(replySink, "replySink");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
    }

    // ---------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------

    @Override
    public void register(CommandSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (disabled) {
            throw new CommandException(CommandErrorKind.REGISTRY_DISABLED,
                "registry has been disabled; cannot register new commands");
        }
        String primary = spec.name();
        if (byPrimary.containsKey(primary)) {
            throw new IllegalArgumentException(
                "command already registered with primary name: " + primary);
        }
        // 別名衝突檢查（不分 primary 與 alias）
        for (String existing : byName.keySet()) {
            if (existing.equalsIgnoreCase(primary)) {
                throw new IllegalArgumentException(
                    "name conflicts with existing alias: " + primary);
            }
        }
        for (String alias : spec.aliases()) {
            if (byName.containsKey(alias.toLowerCase())) {
                throw new IllegalArgumentException(
                    "alias conflicts with existing command: " + alias);
            }
        }
        byPrimary.put(primary, spec);
        byName.put(primary, spec);
        for (String alias : spec.aliases()) {
            byName.put(alias.toLowerCase(), spec);
        }
    }

    @Override
    public void unregister(String name) {
        Objects.requireNonNull(name, "name");
        CommandSpec spec = byName.remove(name.toLowerCase());
        if (spec == null) {
            return;
        }
        // 若依 primary name 解除，連帶移除別名與 primary entry
        if (name.equalsIgnoreCase(spec.name())) {
            byPrimary.remove(spec.name());
            for (String alias : spec.aliases()) {
                byName.remove(alias.toLowerCase());
            }
        }
        // 若是別名解除，僅移除該別名 entry（保留 primary 與其他別名）
    }

    @Override
    public Collection<CommandSpec> getRegisteredCommands() {
        return List.copyOf(byPrimary.values());
    }

    @Override
    public CommandSpec findCommand(String name) {
        if (name == null) return null;
        return byName.get(name.toLowerCase());
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public void onPluginDisable() {
        disabled = true;
        // 不清除 byName / byPrimary / cooldowns —
        // reload 流程通常 register 一次後可能想重用既有 CooldownTracker 狀態
        // （冷卻 / 防重複觸發在 reload 過程中也不破壞狀態）。
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    @Override
    public void dispatch(Sender sender, String commandLabel, List<String> args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(commandLabel, "commandLabel");
        Objects.requireNonNull(args, "args");
        List<String> safeArgs = args.isEmpty() ? List.of() : List.copyOf(args);

        // 1. disabled 檢查
        if (disabled) {
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.REGISTRY_DISABLED,
                "registry has been disabled"));
            return;
        }

        // 2. 主指令查找
        CommandSpec spec = byName.get(commandLabel.toLowerCase());
        if (spec == null) {
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.UNKNOWN_SUBCOMMAND,
                "unknown command: " + commandLabel,
                Map.of("command", commandLabel)));
            return;
        }

        // 3. 主指令權限檢查
        if (!sender.hasPermission(spec.permission())) {
            replySink.sendError(sender, noPermissionException(
                spec.permission(), null, spec.name()));
            return;
        }

        // 4. args 為空 → 主指令 help（依 sender 權限過濾）
        if (safeArgs.isEmpty()) {
            replySink.send(sender, formatHelp(commandLabel, sender));
            return;
        }

        // 5. 子指令查找
        String subName = safeArgs.get(0);
        SubCommandSpec sub = spec.findSubCommand(subName);
        if (sub == null) {
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.UNKNOWN_SUBCOMMAND,
                "unknown subcommand: " + subName,
                Map.of("sub", subName, "command", spec.name())));
            return;
        }

        // 6. 子指令權限檢查
        if (!sender.hasPermission(sub.permission())) {
            replySink.sendError(sender, noPermissionException(
                sub.permission(), sub.name(), spec.name()));
            return;
        }

        // 7. player-only / console-only 檢查
        if (sub.playerOnly() && !sender.isPlayer()) {
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.CONSOLE_NOT_ALLOWED,
                "subcommand is player-only: " + sub.name(),
                Map.of("sub", sub.name(), "command", spec.name())));
            return;
        }
        if (sub.consoleOnly() && sender.isPlayer()) {
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.PLAYER_NOT_ALLOWED,
                "subcommand is console-only: " + sub.name(),
                Map.of("sub", sub.name(), "command", spec.name())));
            return;
        }

        // 8. 參數數量檢查
        int provided = safeArgs.size() - 1;  // 扣除子指令名
        if (provided < sub.minArgs()) {
            replySink.sendError(sender, missingArgumentsException(
                sub, provided, "missing arguments: need " + sub.minArgs()
                    + " but got " + provided));
            return;
        }
        if (sub.maxArgs() >= 0 && provided > sub.maxArgs()) {
            replySink.sendError(sender, missingArgumentsException(
                sub, provided, "too many arguments: max " + sub.maxArgs()
                    + " but got " + provided));
            return;
        }

        // 9. 冷卻檢查（僅玩家）
        if (sub.cooldownMillis() > 0 && sender.isPlayer()) {
            UUID playerId = sender.asPlayer().getUniqueId();
            String subKey = spec.name() + ":" + sub.name();
            if (!cooldowns.tryAcquire(playerId, subKey, sub.cooldownMillis())) {
                long remaining = cooldowns.remainingMillis(playerId, subKey);
                replySink.sendError(sender, new CommandException(
                    CommandErrorKind.COOLDOWN_ACTIVE,
                    "cooldown active for " + sub.name() + ": " + remaining + "ms remaining",
                    Map.of("sub", sub.name(), "remaining", remaining)));
                return;
            }
        }

        // 10. 執行 handler
        CommandContext ctx = new CommandContext(sender, commandLabel, safeArgs, spec, sub, replySink);
        try {
            sub.handler().execute(ctx);
        } catch (CommandException ex) {
            replySink.sendError(sender, ex);
        } catch (RuntimeException ex) {
            // 不要靜默吞掉；包裝成 ACELIB-CMD-008 async execution failed
            logUnexpected("subcommand handler threw", ex);
            replySink.sendError(sender, new CommandException(
                CommandErrorKind.ASYNC_EXECUTION_FAILED,
                "execution failed for " + sub.name() + ": " + safeMessage(ex),
                Map.of("sub", sub.name(), "cause", safeMessage(ex))));
        }
    }

    // ---------------------------------------------------------------------
    // Tab Complete
    // ---------------------------------------------------------------------

    @Override
    public List<String> tabComplete(Sender sender, String commandLabel, List<String> args) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(commandLabel, "commandLabel");
        Objects.requireNonNull(args, "args");
        if (disabled) {
            return List.of();
        }
        CommandSpec spec = byName.get(commandLabel.toLowerCase());
        if (spec == null) {
            return List.of();
        }
        if (!sender.hasPermission(spec.permission())) {
            return List.of();
        }
        // args 為空 → 列出主指令名 + 可見子指令名
        if (args.isEmpty()) {
            List<String> result = new ArrayList<>();
            result.add(spec.name());
            for (SubCommandSpec sub : spec.subCommands().values()) {
                if (sender.hasPermission(sub.permission())) {
                    result.add(sub.name());
                }
            }
            return Collections.unmodifiableList(result);
        }
        // args[0] = 子指令名（已部分輸入或完整）
        String subName = args.get(0);
        SubCommandSpec sub = spec.findSubCommand(subName);
        if (sub == null) {
            // 未知子指令 → 列出「以 subName 為前綴」的可見子指令（過濾無權限）
            List<String> result = new ArrayList<>();
            String prefix = subName.toLowerCase();
            for (SubCommandSpec s : spec.subCommands().values()) {
                if (sender.hasPermission(s.permission()) && s.name().startsWith(prefix)) {
                    result.add(s.name());
                }
            }
            return Collections.unmodifiableList(result);
        }
        if (!sender.hasPermission(sub.permission())) {
            return List.of();
        }
        // 已知子指令 → 委派給 completer
        if (sub.completer() == null) {
            return List.of();
        }
        CommandContext ctx = new CommandContext(sender, commandLabel, args, spec, sub, replySink);
        try {
            List<String> r = sub.completer().complete(ctx, args);
            return r == null ? List.of() : List.copyOf(r);
        } catch (RuntimeException ex) {
            logUnexpected("tab completer threw", ex);
            return List.of();
        }
    }

    // ---------------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------------

    @Override
    public String formatHelp(String commandLabel, Sender sender) {
        Objects.requireNonNull(commandLabel, "commandLabel");
        Objects.requireNonNull(sender, "sender");
        CommandSpec spec = byName.get(commandLabel.toLowerCase());
        if (spec == null) {
            return "";
        }
        if (!sender.hasPermission(spec.permission())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(spec.name()).append(" ===");
        if (!spec.description().isEmpty()) {
            sb.append("\n").append(spec.description());
        }
        sb.append("\n---");
        for (SubCommandSpec sub : spec.subCommands().values()) {
            if (!sender.hasPermission(sub.permission())) {
                continue;
            }
            sb.append("\n  ").append(spec.name()).append(' ').append(sub.name());
            if (sub.playerOnly()) sb.append(" (player)");
            if (sub.consoleOnly()) sb.append(" (console)");
            if (!sub.usage().isEmpty()) sb.append(' ').append(sub.usage());
            if (!sub.description().isEmpty()) sb.append(" — ").append(sub.description());
        }
        sb.append("\n---");
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // 內部輔助
    // ---------------------------------------------------------------------

    private static CommandException noPermissionException(String permission,
                                                           String subName,
                                                           String commandName) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("permission", permission == null ? "" : permission);
        if (subName != null) vars.put("sub", subName);
        vars.put("command", commandName);
        return new CommandException(CommandErrorKind.NO_PERMISSION,
            "no permission" + (subName != null ? " for " + subName : "")
                + (permission != null ? ": " + permission : ""),
            vars);
    }

    private static CommandException missingArgumentsException(SubCommandSpec sub,
                                                              int provided,
                                                              String message) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("sub", sub.name());
        vars.put("usage", sub.usage());
        vars.put("provided", provided);
        vars.put("minArgs", sub.minArgs());
        vars.put("maxArgs", sub.maxArgs());
        return new CommandException(CommandErrorKind.MISSING_ARGUMENTS, message, vars);
    }

    private static void logUnexpected(String context, Throwable t) {
        try {
            LOGGER.log(Level.WARNING,
                "[" + CommandErrorKind.ASYNC_EXECUTION_FAILED.defaultCode() + "] "
                    + context + ": " + t.getMessage(), t);
        } catch (Throwable ignore) {
            // 日誌失敗不應中斷 dispatch
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}