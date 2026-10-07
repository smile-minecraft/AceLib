package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 時間長度引數（package 內實作；經 {@link Arguments#duration(String)} 建立）。
 *
 * <h2>語法（與 vanilla time 一致）</h2>
 * <p>整數或小數＋可選單位：{@code 100}（ticks）、{@code 1t}、
 * {@code 1.5s}（30 ticks）、{@code 1d}（24000 ticks）。
 * 回傳 ticks（long）。{@code h}/{@code m} 等單位兩端皆不接受 —
 * 客戶端 vanilla time 語法同樣拒絕，兩端一致才不會出現「客戶端擋、
 * 伺服器放」的分歧。</p>
 *
 * <h2>溢位</h2>
 * <p>全程以 long 精確運算（{@code multiplyExact}／加法檢查），不走
 * double（大數精度遺失）；任何溢位拋 {@code ACELIB-CMD-015}。</p>
 */
final class DurationArgument extends BaseArgument<Long> {

    private static final Pattern GRAMMAR =
        Pattern.compile("(\\d+)(?:\\.(\\d+))?([dst])?");

    private static final long TICKS_PER_DAY = 24_000L;
    private static final long TICKS_PER_SECOND = 20L;

    /** 補全範例（皆為合法語法）。 */
    private static final List<String> EXAMPLES =
        List.of("20t", "1s", "30s", "5s", "1d");

    DurationArgument(String name) {
        super(name);
    }

    @Override
    public String usageToken() {
        return "<" + name + ":ticks>";
    }

    @Override
    public Long parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        Matcher matcher = GRAMMAR.matcher(raw);
        if (!matcher.matches()) {
            throw invalid(raw, "expected <number>[t|s|d], e.g. 100, 1s, 1d", messages);
        }
        long unit = unitOf(matcher.group(3));
        long whole;
        try {
            whole = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw invalid(raw, "number too large", messages);
        }
        long ticks;
        try {
            ticks = Math.multiplyExact(whole, unit);
        } catch (ArithmeticException ex) {
            throw invalid(raw, "duration overflows", messages);
        }
        String fraction = matcher.group(2);
        if (fraction != null && !fraction.isEmpty()) {
            ticks = addFraction(ticks, fraction, unit, raw, messages);
        }
        return ticks;
    }

    private static long unitOf(String suffix) {
        if (suffix == null) {
            return 1L;
        }
        return switch (suffix) {
            case "d" -> TICKS_PER_DAY;
            case "s" -> TICKS_PER_SECOND;
            default -> 1L;
        };
    }

    /**
     * 小數部分精確換算（向下取整）：{@code frac * unit / 10^len}。
     * {@code frac * unit} 可能溢位（例：超長小數位），先檢查。
     */
    private long addFraction(long base, String fraction, long unit,
                             String raw, CommandMessages messages) {
        long numerator;
        try {
            numerator = Long.parseLong(fraction);
        } catch (NumberFormatException ex) {
            throw invalid(raw, "number too large", messages);
        }
        long scale = 1L;
        for (int i = 0; i < fraction.length(); i++) {
            try {
                scale = Math.multiplyExact(scale, 10L);
            } catch (ArithmeticException ex) {
                throw invalid(raw, "number too large", messages);
            }
        }
        long extra;
        try {
            extra = Math.multiplyExact(numerator, unit) / scale;
        } catch (ArithmeticException ex) {
            throw invalid(raw, "duration overflows", messages);
        }
        try {
            return Math.addExact(base, extra);
        } catch (ArithmeticException ex) {
            throw invalid(raw, "duration overflows", messages);
        }
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return filterPrefix(EXAMPLES, prefix);
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.duration();
    }

    @Override
    public Long resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        return (long) ctx.getArgument(name, Integer.class);
    }
}
