package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨 slice 共用的測試替身引數（非公開測試工具，不進 testFixtures）。
 */
final class TestArgs {

    private TestArgs() {
    }

    /** 字串引數：固定名單補全（Steve / Alex）。 */
    static final class NameArg implements CommandArgument<String> {
        private final String name;

        NameArg(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String usageToken() {
            return "<" + name + ">";
        }

        @Override
        public String parse(String raw, CommandMessages messages) {
            Objects.requireNonNull(raw, "raw");
            if (raw.isBlank() || raw.chars().anyMatch(Character::isWhitespace)) {
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    "invalid name: " + raw, Map.of("arg", name, "value", raw));
            }
            return raw;
        }

        @Override
        public List<String> suggest(String prefix) {
            Objects.requireNonNull(prefix, "prefix");
            List<String> out = new ArrayList<>();
            for (String candidate : List.of("Steve", "Alex")) {
                if (candidate.toLowerCase().startsWith(prefix.toLowerCase())) {
                    out.add(candidate);
                }
            }
            return List.copyOf(out);
        }

        @Override
        public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
            return factory.stringWord();
        }

        @Override
        public String resolve(CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException {
            return ctx.getArgument(name, String.class);
        }
    }

    /** 長整數引數。 */
    static final class LongArg implements CommandArgument<Long> {
        private final String name;

        LongArg(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String usageToken() {
            return "<" + name + ">";
        }

        @Override
        public Long parse(String raw, CommandMessages messages) {
            Objects.requireNonNull(raw, "raw");
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    "invalid number: " + raw, Map.of("arg", name, "value", raw));
            }
        }

        @Override
        public List<String> suggest(String prefix) {
            return List.of();
        }

        @Override
        public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
            return factory.stringWord();
        }

        @Override
        public Long resolve(CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException {
            return Long.parseLong(ctx.getArgument(name, String.class));
        }
    }

    /** 固定選項測試替身：字面分支結構。 */
    static final class FixedArg implements CommandArgument<String> {
        private final String name;
        private final List<String> options;

        FixedArg(String name, List<String> options) {
            this.name = Objects.requireNonNull(name, "name");
            this.options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (this.options.isEmpty()) {
                throw new IllegalArgumentException("options cannot be empty");
            }
        }

        @Override
        public String name() {
            return name;
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
            for (String option : options) {
                if (option.equalsIgnoreCase(raw)) {
                    return option;
                }
            }
            throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                "invalid option: " + raw, Map.of("arg", name, "value", raw));
        }

        @Override
        public List<String> suggest(String prefix) {
            Objects.requireNonNull(prefix, "prefix");
            List<String> out = new ArrayList<>();
            for (String option : options) {
                if (option.toLowerCase().startsWith(prefix.toLowerCase())) {
                    out.add(option);
                }
            }
            return List.copyOf(out);
        }

        @Override
        public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
            return factory.stringWord();
        }

        @Override
        public String resolve(CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException {
            return ctx.getArgument(name, String.class);
        }
    }

    /** 純 Brigadier 測試用的 {@link ArgumentTypeFactory}（無需伺服器）。 */    static final class TestTypes implements ArgumentTypeFactory {
        @Override
        public ArgumentType<?> player() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<?> offlinePlayer() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<?> boundedInt(int min, int max) {
            return com.mojang.brigadier.arguments.IntegerArgumentType.integer(min, max);
        }

        @Override
        public ArgumentType<?> boundedDouble(double min, double max) {
            return com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(min, max);
        }

        @Override
        public ArgumentType<?> duration() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<?> world() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<?> material() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<?> stringWord() {
            return StringArgumentType.word();
        }
    }

    static ArgumentType<String> word() {
        return StringArgumentType.word();
    }
}
