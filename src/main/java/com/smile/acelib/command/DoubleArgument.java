package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 有上下限的小數引數（package 內實作；經 {@link Arguments#doubleArg} 建立）。
 *
 * <p>非數字、{@code NaN}、無限大、超出 {@code [min, max]} 一律拋
 * {@code ACELIB-CMD-015}。Brigadier 路徑用 vanilla
 * {@code double(min, max)}（客戶端即驗證範圍）。</p>
 */
final class DoubleArgument extends BaseArgument<Double> {

    private final double min;
    private final double max;

    DoubleArgument(String name, double min, double max) {
        super(name);
        if (Double.isNaN(min) || Double.isNaN(max) || min > max) {
            throw new IllegalArgumentException(
                "min (" + min + ") must be <= max (" + max + ")");
        }
        this.min = min;
        this.max = max;
    }

    @Override
    public String usageToken() {
        return "<" + name + ":" + min + "-" + max + ">";
    }

    @Override
    public Double parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw invalid(raw, "not a number in range " + min + "-" + max, messages);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)
            || value < min || value > max) {
            CommandMessages effective = effective(messages);
            String reason = "out of range " + min + "-" + max;
            throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                effective.invalidArgument(name, raw, reason),
                Map.of("arg", name, "value", raw, "reason", reason,
                    "min", min, "max", max));
        }
        return value;
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return List.of();
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.boundedDouble(min, max);
    }

    @Override
    public Double resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        return ctx.getArgument(name, Double.class);
    }
}
