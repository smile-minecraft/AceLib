package com.smile.acelib.command;

import java.util.Map;

/**
 * 指令錯誤訊息在地化契約。
 *
 * <p>dispatcher 與型別化引數產生的 {@link CommandException} 預設攜帶英文
 * 訊息；要依 sender 語系呈現時，presentation 層（{@link LocalizingReplySink}）
 * 透過本介面把 kind + vars 轉為在地化字串。</p>
 *
 * <h2>實作方式</h2>
 * <ul>
 *   <li>{@link DefaultCommandMessages} — 內建英文預設（AceLib 自身管理指令使用）</li>
 *   <li>{@link MessageServiceCommandMessages} — 經 message 模組查 key
 *       （下游 plugin 自備語言檔）</li>
 *   <li>部分實作允許：未覆寫的方法回傳空字串，視為「缺 key」，
 *       {@link LocalizingReplySink} 會退回例外原文</li>
 * </ul>
 *
 * <p>所有方法永不回傳 null（缺 key 回傳空字串）；參數為 null 時的行為由
 * 各實作定義（預設實作容忍 null，以空字串或通用文字代替）。</p>
 *
 * @see LocalizingReplySink
 * @see DefaultCommandMessages
 * @since 1.4.0
 */
public interface CommandMessages {

    /** 未知主指令。 */
    default String unknownCommand(String command) { return ""; }

    /** 未知子指令。 */
    default String unknownSubcommand(String command, String sub) { return ""; }

    /**
     * 無權限。
     *
     * @param permission 權限節點；null 表示呼叫端未提供（視為通用無權限訊息）
     */
    default String noPermission(String permission, String command, String sub) { return ""; }

    /** 僅限玩家（console 觸發）。 */
    default String consoleNotAllowed(String sub) { return ""; }

    /** 僅限 console（玩家觸發）。 */
    default String playerNotAllowed(String sub) { return ""; }

    /** 參數不足。 */
    default String missingArguments(String sub, String usage, int provided, int min) { return ""; }

    /** 參數過多。 */
    default String tooManyArguments(String sub, String usage, int provided, int max) { return ""; }

    /** 冷卻中。 */
    default String cooldownActive(String sub, long remainingMillis) { return ""; }

    /** 目標玩家離線。 */
    default String playerOffline(String player) { return ""; }

    /**
     * 引數值非法（型別化引數解析失敗）。
     *
     * @param arg    引數名
     * @param value  玩家輸入的原始字串
     * @param reason 機器可讀原因（例如 {@code "out-of-range"}、
     *               {@code "unknown-player"}），給轉接實作選 key 用
     */
    default String invalidArgument(String arg, String value, String reason) { return ""; }

    /** registry 已停用。 */
    default String registryDisabled() { return ""; }

    /** 執行失敗。 */
    default String executionFailed(String sub) { return ""; }

    /**
     * 依例外 kind + vars 選出對應訊息（presentation 層入口）。
     *
     * <p>vars 缺鍵時以空字串／零值代替，不拋例外；未知 kind 回傳空字串
     * （呼叫端退回例外原文）。</p>
     *
     * @param ex 指令例外；不可為 null
     * @return 在地化字串；缺 key 時為空字串
     */
    default String localize(CommandException ex) {
        if (ex == null) {
            throw new NullPointerException("ex");
        }
        Map<String, Object> vars = ex.getVars();
        return switch (ex.getKind()) {
            case UNKNOWN_SUBCOMMAND -> vars.containsKey("sub")
                ? unknownSubcommand(str(vars, "command"), str(vars, "sub"))
                : unknownCommand(str(vars, "command"));
            case NO_PERMISSION -> noPermission(strOrNull(vars, "permission"),
                str(vars, "command"), strOrNull(vars, "sub"));
            case CONSOLE_NOT_ALLOWED -> consoleNotAllowed(str(vars, "sub"));
            case PLAYER_NOT_ALLOWED -> playerNotAllowed(str(vars, "sub"));
            case MISSING_ARGUMENTS -> {
                int provided = num(vars, "provided");
                int min = num(vars, "minArgs");
                int max = num(vars, "maxArgs");
                yield max >= 0 && provided > max
                    ? tooManyArguments(str(vars, "sub"), str(vars, "usage"), provided, max)
                    : missingArguments(str(vars, "sub"), str(vars, "usage"), provided, min);
            }
            case COOLDOWN_ACTIVE -> cooldownActive(str(vars, "sub"),
                num(vars, "remaining"));
            case PLAYER_OFFLINE -> playerOffline(str(vars, "player"));
            case INVALID_ARGUMENT -> invalidArgument(str(vars, "arg"),
                str(vars, "value"), invalidReason(vars));
            case REGISTRY_DISABLED -> registryDisabled();
            case EXECUTION_FAILED -> executionFailed(str(vars, "sub"));
            default -> "";
        };
    }

    private static String str(Map<String, Object> vars, String key) {
        Object value = vars.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String strOrNull(Map<String, Object> vars, String key) {
        Object value = vars.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static int num(Map<String, Object> vars, String key) {
        Object value = vars.get(key);
        if (value instanceof Number number) {
            long asLong = number.longValue();
            if (asLong > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (asLong < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return (int) asLong;
        }
        return 0;
    }

    /**
     * 取 INVALID_ARGUMENT 的 reason：有 {@code reason} 鍵直接用；只有舊式
     * {@code options} 鍵（逗號分隔）時重建成 {@code expected one of a|b}；
     * 兩者皆無回空字串（由 {@code invalidArgument} 實作決定是否省略括號）。
     */
    private static String invalidReason(Map<String, Object> vars) {
        Object reason = vars.get("reason");
        if (reason != null) {
            String text = String.valueOf(reason);
            if (!text.isEmpty()) {
                return text;
            }
        }
        Object options = vars.get("options");
        if (options != null) {
            String text = String.valueOf(options);
            if (!text.isEmpty()) {
                return "expected one of " + text.replace(',', '|');
            }
        }
        return "";
    }
}
