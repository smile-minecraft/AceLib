package com.smile.acelib.command;

import com.smile.acelib.message.MessageService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 經 message 模組查 key 的 {@link CommandMessages} 轉接。
 *
 * <p>key 規則：{@code keyPrefix + suffix}，suffix 固定如下：</p>
 * <ul>
 *   <li>{@code unknown-command}、{@code unknown-subcommand}</li>
 *   <li>{@code no-permission}、{@code console-not-allowed}、
 *       {@code player-not-allowed}</li>
 *   <li>{@code missing-arguments}、{@code too-many-arguments}</li>
 *   <li>{@code cooldown-active}、{@code player-offline}</li>
 *   <li>{@code invalid-argument}、{@code registry-disabled}、
 *       {@code execution-failed}</li>
 * </ul>
 *
 * <p>變數沿用 {@link CommandException#getVars()} 命名
 * （{@code command}／{@code sub}／{@code permission}／{@code usage}／
 * {@code provided}／{@code min}／{@code max}／{@code remaining}／
 * {@code player}／{@code arg}／{@code value}／{@code reason}），
 * 語言檔模板以 {@code {var}} 引用（見模組頁範例）。key 缺失
 * （格式化結果為空）時退回 {@code fallback}（預設英文）。</p>
 *
 * @see MessageService#format(String, Map)
 * @since 1.4.0
 */
public final class MessageServiceCommandMessages implements CommandMessages {

    /** Key 後綴（key = prefix + suffix）。 */
    public static final String KEY_UNKNOWN_COMMAND = "unknown-command";
    /** Key 後綴。 */
    public static final String KEY_UNKNOWN_SUBCOMMAND = "unknown-subcommand";
    /** Key 後綴。 */
    public static final String KEY_NO_PERMISSION = "no-permission";
    /** Key 後綴。 */
    public static final String KEY_CONSOLE_NOT_ALLOWED = "console-not-allowed";
    /** Key 後綴。 */
    public static final String KEY_PLAYER_NOT_ALLOWED = "player-not-allowed";
    /** Key 後綴。 */
    public static final String KEY_MISSING_ARGUMENTS = "missing-arguments";
    /** Key 後綴。 */
    public static final String KEY_TOO_MANY_ARGUMENTS = "too-many-arguments";
    /** Key 後綴。 */
    public static final String KEY_COOLDOWN_ACTIVE = "cooldown-active";
    /** Key 後綴。 */
    public static final String KEY_PLAYER_OFFLINE = "player-offline";
    /** Key 後綴。 */
    public static final String KEY_INVALID_ARGUMENT = "invalid-argument";
    /** Key 後綴。 */
    public static final String KEY_REGISTRY_DISABLED = "registry-disabled";
    /** Key 後綴。 */
    public static final String KEY_EXECUTION_FAILED = "execution-failed";

    private final MessageService service;
    private final String keyPrefix;
    private final CommandMessages fallback;

    /**
     * @param service   訊息服務；不可為 null
     * @param keyPrefix key 前綴（例如 {@code "command.error."}）；不可為 null
     * @param fallback  缺 key 時的退回訊息表；不可為 null
     */
    public MessageServiceCommandMessages(MessageService service, String keyPrefix,
                                          CommandMessages fallback) {
        this.service = Objects.requireNonNull(service, "service");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public String unknownCommand(String command) {
        return lookup(KEY_UNKNOWN_COMMAND, Map.of("command", orEmpty(command)),
            () -> fallback.unknownCommand(command));
    }

    @Override
    public String unknownSubcommand(String command, String sub) {
        return lookup(KEY_UNKNOWN_SUBCOMMAND,
            Map.of("command", orEmpty(command), "sub", orEmpty(sub)),
            () -> fallback.unknownSubcommand(command, sub));
    }

    @Override
    public String noPermission(String permission, String command, String sub) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("permission", orEmpty(permission));
        vars.put("command", orEmpty(command));
        vars.put("sub", orEmpty(sub));
        return lookup(KEY_NO_PERMISSION, vars,
            () -> fallback.noPermission(permission, command, sub));
    }

    @Override
    public String consoleNotAllowed(String sub) {
        return lookup(KEY_CONSOLE_NOT_ALLOWED, Map.of("sub", orEmpty(sub)),
            () -> fallback.consoleNotAllowed(sub));
    }

    @Override
    public String playerNotAllowed(String sub) {
        return lookup(KEY_PLAYER_NOT_ALLOWED, Map.of("sub", orEmpty(sub)),
            () -> fallback.playerNotAllowed(sub));
    }

    @Override
    public String missingArguments(String sub, String usage, int provided, int min) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("sub", orEmpty(sub));
        vars.put("usage", orEmpty(usage));
        vars.put("provided", provided);
        vars.put("min", min);
        return lookup(KEY_MISSING_ARGUMENTS, vars,
            () -> fallback.missingArguments(sub, usage, provided, min));
    }

    @Override
    public String tooManyArguments(String sub, String usage, int provided, int max) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("sub", orEmpty(sub));
        vars.put("usage", orEmpty(usage));
        vars.put("provided", provided);
        vars.put("max", max);
        return lookup(KEY_TOO_MANY_ARGUMENTS, vars,
            () -> fallback.tooManyArguments(sub, usage, provided, max));
    }

    @Override
    public String cooldownActive(String sub, long remainingMillis) {
        return lookup(KEY_COOLDOWN_ACTIVE,
            Map.of("sub", orEmpty(sub), "remaining", remainingMillis),
            () -> fallback.cooldownActive(sub, remainingMillis));
    }

    @Override
    public String playerOffline(String player) {
        return lookup(KEY_PLAYER_OFFLINE, Map.of("player", orEmpty(player)),
            () -> fallback.playerOffline(player));
    }

    @Override
    public String invalidArgument(String arg, String value, String reason) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("arg", orEmpty(arg));
        vars.put("value", orEmpty(value));
        vars.put("reason", orEmpty(reason));
        return lookup(KEY_INVALID_ARGUMENT, vars,
            () -> fallback.invalidArgument(arg, value, reason));
    }

    @Override
    public String registryDisabled() {
        return lookup(KEY_REGISTRY_DISABLED, Map.of(),
            fallback::registryDisabled);
    }

    @Override
    public String executionFailed(String sub) {
        return lookup(KEY_EXECUTION_FAILED, Map.of("sub", orEmpty(sub)),
            () -> fallback.executionFailed(sub));
    }

    private String lookup(String suffix, Map<String, Object> vars,
                          java.util.function.Supplier<String> fallbackValue) {
        String formatted;
        try {
            formatted = service.format(keyPrefix + suffix, vars);
        } catch (Throwable t) {
            return fallbackValue.get();
        }
        if (formatted == null || formatted.isEmpty()) {
            return fallbackValue.get();
        }
        return formatted;
    }

    private static String orEmpty(String text) {
        return Objects.toString(text, "");
    }
}
