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
 * 固定字串選項引數（package 內實作；經 {@link Arguments#fixed} 建立）。
 *
 * <p>列舉之外的固定選項（例如開關、模式字串）：語意與 {@link EnumArgument}
 * 相同（literal 分支；原本預期基岩可見，但 2026-10-08 真人基岩客戶端
 * 實測顯示基岩端建議列並未出現，Geyser Current Limitations，Unfixable，
 * 見模組頁補全支援矩陣），回傳宣告形式的 canonical 字串。
 * 解析大小寫不敏感。</p>
 */
final class FixedOptionsArgument extends BaseArgument<String> {

    private final List<String> options;

    FixedOptionsArgument(String name, List<String> options) {
        super(name);
        Objects.requireNonNull(options, "options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options cannot be empty");
        }
        List<String> copy = new ArrayList<>(options);
        for (String option : copy) {
            if (option == null || option.isEmpty()) {
                throw new IllegalArgumentException("options cannot contain null/empty");
            }
        }
        this.options = List.copyOf(copy);
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
    public String parse(String raw, CommandMessages messages) {
        Objects.requireNonNull(raw, "raw");
        requireSingleToken(raw);
        for (String option : options) {
            if (option.equalsIgnoreCase(raw)) {
                return option;
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
    public String resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        throw new UnsupportedOperationException(
            "fixed-options argument '" + name + "' has no argument node; "
                + "values arrive via literal branches");
    }
}
