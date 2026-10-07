package com.smile.acelib.command;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 型別化指令執行 context。
 *
 * <p>包裝 {@link CommandContext} 與已解析的引數值：handler 以
 * {@link #get(CommandArgument)} 取得型別值，不再碰原始字串。
 * 值以引數<strong>實例</strong>為 key（同一 builder 產生的實例），
 * 傳入未註冊於本子指令的引數實例拋 {@link IllegalArgumentException}。</p>
 *
 * @see TypedHandler
 * @see TypedSubCommand
 * @since 1.4.0
 */
public final class TypedContext {

    private final CommandContext legacy;
    private final Map<CommandArgument<?>, Object> values;

    TypedContext(CommandContext legacy, Map<CommandArgument<?>, Object> values) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(values, "values");
        // 引數實例語意：不同實例即使同名也視為不同 key。
        IdentityHashMap<CommandArgument<?>, Object> copy = new IdentityHashMap<>();
        copy.putAll(values);
        this.values = Map.copyOf(copy);
    }

    /**
     * 取得已解析的引數值。
     *
     * @param arg 引數實例（須為本子指令 builder 傳入的同一實例）；不可為 null
     * @param <T> 引數值型別
     * @return 型別值；永不為 null
     * @throws NullPointerException 當 {@code arg} 為 null
     * @throws IllegalArgumentException 當 {@code arg} 未參與本次解析
     */
    @SuppressWarnings("unchecked")
    public <T> T get(CommandArgument<T> arg) {
        Objects.requireNonNull(arg, "arg");
        if (!values.containsKey(arg)) {
            throw new IllegalArgumentException(
                "argument is not part of this subcommand: " + arg.name());
        }
        return (T) values.get(arg);
    }

    /** 指令 sender。 */
    public Sender sender() {
        return legacy.sender();
    }

    /** dispatch 時傳入的指令標籤。 */
    public String commandLabel() {
        return legacy.commandLabel();
    }

    /** 子指令之後的原始 args（不可變；除錯／記錄用，業務請用 {@link #get}）。 */
    public List<String> commandArgs() {
        return legacy.commandArgs();
    }

    /** 取得玩家 handle；非玩家拋 {@code ACELIB-CMD-004}。 */
    public PlayerHandle requirePlayer() {
        return legacy.requirePlayer();
    }

    /** 取得在線玩家 handle；離線拋 {@code ACELIB-CMD-007}。 */
    public PlayerHandle requireOnlinePlayer() {
        return legacy.requireOnlinePlayer();
    }

    /** 同步回覆訊息。 */
    public void reply(String message) {
        legacy.reply(message);
    }

    /**
     * 回覆錯誤。
     *
     * @param error 錯誤；不可為 null
     */
    public void replyError(Throwable error) {
        legacy.replyError(error);
    }

    /** 跨執行緒回覆玩家（Folia 安全）。 */
    public void replyPlayerAsync(String message) {
        legacy.replyPlayerAsync(message);
    }

    /** 直接取得底層 legacy context（同套件互操作用；handler 業務請用型別取值）。 */
    CommandContext legacy() {
        return legacy;
    }
}
