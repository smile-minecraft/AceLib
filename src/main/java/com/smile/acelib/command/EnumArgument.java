package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 列舉引數（package 內實作；經 {@link Arguments#enumArg} 建立）。
 *
 * <p>固定選項引數：在 Brigadier 樹中編譯為 <strong>literal 分支</strong>
 * （小寫常數名），是基岩版（Geyser）看得見補全的結構。傳統路徑解析
 * 大小寫不敏感；Brigadier 路徑由字面分支精確匹配（客戶端補全即小寫，
 * 見模組頁限制說明）。</p>
 *
 * @param <E> 列舉型別
 */
final class EnumArgument<E extends Enum<E>> extends BaseArgument<E> {

    private final Class<E> enumClass;
    private final List<String> options;

    EnumArgument(String name, Class<E> enumClass) {
        super(name);
        this.enumClass = Objects.requireNonNull(enumClass, "enumClass");
        List<String> names = new ArrayList<>();
        for (E constant : enumClass.getEnumConstants()) {
            names.add(constant.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException(
                "enum " + enumClass.getName() + " has no constants");
        }
        this.options = List.copyOf(names);
    }

    @Override
    public String usageToken() {
        return "<" + name + ":" + String.join("|", options) + ">";
    }

    @Override
    public boolean isFixedOptions() {
        return true;
    }

    @Override
    public List<String> fixedOptions() {
        return options;
    }

    @Override
    public E parse(String raw, CommandMessages messages) {
        Objects.requireNonNull(raw, "raw");
        requireSingleToken(raw);
        for (E constant : enumClass.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(raw)) {
                return constant;
            }
        }
        CommandMessages effective = effective(messages);
        throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
            effective.invalidArgument(name, raw,
                "expected one of " + String.join("|", options)),
            Map.of("arg", name, "value", raw, "options", String.join(",", options)));
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return filterPrefix(options, prefix);
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        // 固定選項不使用 argument 節點；回傳合法 placeholder 滿足契約。
        Objects.requireNonNull(factory, "factory");
        return factory.stringWord();
    }

    @Override
    public E resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        // 固定選項在樹中是 literal 分支，沒有同名 argument 節點；
        // 正常流程不呼叫此方法（執行委派走原始輸入重建）。
        throw new UnsupportedOperationException(
            "fixed-options argument '" + name + "' has no argument node; "
                + "values arrive via literal branches");
    }
}
