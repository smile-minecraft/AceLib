package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Objects;

/**
 * 自訂引數實作（package 內；經 {@link CommandArgument#custom} 建立）。
 *
 * <p>下游只提供解析函式與補全函式：傳統路徑 {@code parse} 先過單 token
 * 不變條件再委派給解析函式；Brigadier 路徑以 {@code stringWord} 節點承接
 * 單 token，再以 {@code resolve} 取出原始字串走同一個解析函式（此時無
 * 訊息表在作用域，取預設英文；接受的值與傳統路徑一致）。</p>
 *
 * @param <T> 解析後的型別值
 */
final class CustomArgument<T> extends BaseArgument<T> {

    private final String usageToken;
    private final CommandArgument.Parser<T> parser;
    private final CommandArgument.Suggester suggester;

    CustomArgument(String name, String usageToken,
                   CommandArgument.Parser<T> parser,
                   CommandArgument.Suggester suggester) {
        super(name);
        Objects.requireNonNull(usageToken, "usageToken");
        if (usageToken.isEmpty()) {
            throw new IllegalArgumentException("usage token cannot be empty");
        }
        this.usageToken = usageToken;
        this.parser = Objects.requireNonNull(parser, "parser");
        this.suggester = Objects.requireNonNull(suggester, "suggester");
    }

    @Override
    public String usageToken() {
        return usageToken;
    }

    @Override
    public T parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        T value = parser.parse(raw, effective(messages));
        return Objects.requireNonNull(value, "parser must not return null");
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        List<String> result = suggester.suggest(prefix);
        return List.copyOf(Objects.requireNonNull(result,
            "suggester must not return null"));
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.stringWord();
    }

    @Override
    public T resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        String raw = ctx.getArgument(name, String.class);
        return parse(raw, DefaultCommandMessages.instance());
    }
}
