package com.smile.acelib.command;

import java.util.Objects;

/**
 * 內建英文指令錯誤訊息（{@link CommandMessages} 預設實作）。
 *
 * <p>文字與既有 dispatcher 行為一致（同一英文訊息），下游要改語系時改用
 * {@link MessageServiceCommandMessages} 或自訂實作；本類別保證所有方法
 * 非空、無未替換 placeholder。</p>
 *
 * @see CommandMessages
 * @since 1.4.0
 */
public final class DefaultCommandMessages implements CommandMessages {

    private static final DefaultCommandMessages INSTANCE = new DefaultCommandMessages();

    private DefaultCommandMessages() {
    }

    /** 取得單例。 */
    public static DefaultCommandMessages instance() {
        return INSTANCE;
    }

    @Override
    public String unknownCommand(String command) {
        return "unknown command: " + orEmpty(command);
    }

    @Override
    public String unknownSubcommand(String command, String sub) {
        return "unknown subcommand: " + orEmpty(sub);
    }

    @Override
    public String noPermission(String permission, String command, String sub) {
        String target = sub == null || sub.isEmpty() ? orEmpty(command) : sub;
        String detail = permission == null || permission.isEmpty() ? "" : ": " + permission;
        return "no permission for " + target + detail;
    }

    @Override
    public String consoleNotAllowed(String sub) {
        return "subcommand is player-only: " + orEmpty(sub);
    }

    @Override
    public String playerNotAllowed(String sub) {
        return "subcommand is console-only: " + orEmpty(sub);
    }

    @Override
    public String missingArguments(String sub, String usage, int provided, int min) {
        String hint = usage == null || usage.isEmpty() ? "" : " Usage: " + usage;
        return "missing arguments for " + orEmpty(sub) + ": need " + min
            + " but got " + provided + "." + hint;
    }

    @Override
    public String tooManyArguments(String sub, String usage, int provided, int max) {
        String hint = usage == null || usage.isEmpty() ? "" : " Usage: " + usage;
        return "too many arguments for " + orEmpty(sub) + ": max " + max
            + " but got " + provided + "." + hint;
    }

    @Override
    public String cooldownActive(String sub, long remainingMillis) {
        if (remainingMillis < 1000) {
            return "cooldown active for " + orEmpty(sub) + ": " + remainingMillis
                + "ms remaining";
        }
        double seconds = remainingMillis / 1000.0;
        String text = String.format(java.util.Locale.ROOT, "%.1f", seconds);
        return "cooldown active for " + orEmpty(sub) + ": " + text + "s remaining";
    }

    @Override
    public String playerOffline(String player) {
        return "player is offline: " + orEmpty(player);
    }

    @Override
    public String invalidArgument(String arg, String value, String reason) {
        return "invalid value for <" + orEmpty(arg) + ">: '" + orEmpty(value) + "'"
            + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")");
    }

    @Override
    public String registryDisabled() {
        return "command registry has been disabled";
    }

    @Override
    public String executionFailed(String sub) {
        return "execution failed for " + orEmpty(sub);
    }

    private static String orEmpty(String text) {
        return Objects.toString(text, "");
    }
}
