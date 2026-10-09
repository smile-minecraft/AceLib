package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 有上下限的整數引數（package 內實作；經 {@link Arguments#intArg} 建立）。
 *
 * <p>非數字、超出 {@code int} 範圍（溢位字串）、超出 {@code [min, max]}
 * 一律拋 {@code ACELIB-CMD-015}，不做靜默 wrap 或截斷。
 * Brigadier 路徑用 vanilla {@code integer(min, max)}（客戶端即驗證範圍）。</p>
 */
final class IntArgument extends BaseArgument<Integer> {

    private final int min;
    private final int max;

    IntArgument(String name, int min, int max) {
        super(name);
        if (min > max) {
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
    public Integer parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw invalid(raw, "not an integer in range " + min + "-" + max, messages);
        }
        if (value < min || value > max) {
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
        return factory.boundedInt(min, max);
    }

    @Override
    public Integer resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        return ctx.getArgument(name, Integer.class);
    }
}
