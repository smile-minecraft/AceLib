package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 自訂引數型別測試（第三階段第一、二項：公開 SPI＋外部 consumer 範例）。
 *
 * <p>同一個自訂引數定義同時服務兩條執行路徑：傳統路徑以
 * {@code parse} 把原始字串轉為型別值；Brigadier 路徑以
 * {@code resolve} 從解析結果取值，兩條路徑走同一個解析函式。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("自訂引數型別（外部 SPI）")
class CustomArgumentTest {

    @Mock
    CommandSourceStack stack;
    @Mock
    CommandSender sender;

    /**
     * 與外部 consumer 範例（{@code com.example.CommandV150Example#percent}）
     * 同格式的百分比引數：裸數字 {@code 75} 解析為 {@code 0.75}。
     *
     * <p>選項集、接受範圍與錯誤原因與範例一致，兩邊行為相同。</p>
     *
     * @param name 引數名；不可為 null
     * @return 百分比自訂引數；永不為 null
     */
    static CommandArgument<Double> percent(String name) {
        return CommandArgument.custom(name, "<" + name + ":percent>",
            (raw, messages) -> {
                CommandMessages effective = messages == null
                    ? DefaultCommandMessages.instance()
                    : messages;
                try {
                    double value = Double.parseDouble(raw);
                    if (!Double.isNaN(value) && !Double.isInfinite(value)
                        && value >= 0 && value <= 100) {
                        return value / 100.0;
                    }
                } catch (NumberFormatException ignored) {
                    // 落到下方的統一錯誤。
                }
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    effective.invalidArgument(name, raw,
                        "expected <number> in 0-100"),
                    Map.of("arg", name, "value", raw));
            },
            prefix -> {
                List<String> out = new ArrayList<>();
                for (String candidate : List.of("25", "50", "75", "100")) {
                    if (candidate.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                        out.add(candidate);
                    }
                }
                return List.copyOf(out);
            });
    }

    /**
     * 字元集安全的自訂引數（Brigadier {@code word} 可承接）：轉大寫。
     *
     * <p>{@code %} 等非 word 字元到不了 {@code resolve}（見
     * {@link #percent} 測試的說明），故 resolve 路徑的驗證改用此引數；
     * 兩者走同一個 {@link CommandArgument#custom} 實作。</p>
     */
    static CommandArgument<String> upper(String name) {
        return CommandArgument.custom(name, "<" + name + ":upper>",
            (raw, messages) -> {
                CommandMessages effective = messages == null
                    ? DefaultCommandMessages.instance()
                    : messages;
                if (raw.chars().anyMatch(Character::isDigit)) {
                    throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                        effective.invalidArgument(name, raw, "digits not allowed"),
                        Map.of("arg", name, "value", raw));
                }
                return raw.toUpperCase(java.util.Locale.ROOT);
            },
            prefix -> {
                List<String> out = new ArrayList<>();
                for (String candidate : List.of("hello", "world")) {
                    if (candidate.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                        out.add(candidate);
                    }
                }
                return List.copyOf(out);
            });
    }

    // -----------------------------------------------------------------
    // 工廠契約
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("工廠契約")
    class FactoryContract {

        @Test
        @DisplayName("名稱與用法 token 如實回傳；開放式引數預設")
        void nameAndUsageToken() {
            CommandArgument<Double> arg = percent("rate");
            assertEquals("rate", arg.name());
            assertEquals("<rate:percent>", arg.usageToken());
            assertFalse(arg.isFixedOptions());
            assertFalse(arg.bedrockVisible());
            assertTrue(arg.fixedOptions().isEmpty());
        }

        @Test
        @DisplayName("空值與空字串在建構時即拒絕")
        void nullAndEmpty_rejectedAtBuild() {
            CommandArgument.Parser<Double> parser = (raw, messages) -> 0.0;
            CommandArgument.Suggester suggester = prefix -> List.of();
            assertThrows(NullPointerException.class,
                () -> CommandArgument.custom(null, "<rate>", parser, suggester));
            assertThrows(IllegalArgumentException.class,
                () -> CommandArgument.custom("", "<rate>", parser, suggester));
            assertThrows(NullPointerException.class,
                () -> CommandArgument.custom("rate", null, parser, suggester));
            assertThrows(IllegalArgumentException.class,
                () -> CommandArgument.custom("rate", "", parser, suggester));
            assertThrows(NullPointerException.class,
                () -> CommandArgument.custom("rate", "<rate>", null, suggester));
            assertThrows(NullPointerException.class,
                () -> CommandArgument.custom("rate", "<rate>", parser, null));
        }

        @Test
        @DisplayName("parse／suggest／brigadierType／resolve 的 null 契約")
        void nullInputs_rejected() {
            CommandArgument<Double> arg = percent("rate");
            assertThrows(NullPointerException.class, () -> arg.parse(null));
            assertThrows(NullPointerException.class,
                () -> arg.parse(null, DefaultCommandMessages.instance()));
            assertThrows(NullPointerException.class, () -> arg.suggest(null));
            assertThrows(NullPointerException.class, () -> arg.brigadierType(null));
            assertThrows(NullPointerException.class, () -> arg.resolve(null));
        }
    }

    // -----------------------------------------------------------------
    // 傳統路徑解析
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("傳統路徑解析")
    class TraditionalParse {

        @Test
        @DisplayName("百分比正常解析（裸數字 0–100）")
        void percent_parses() {
            CommandArgument<Double> arg = percent("rate");
            assertEquals(0.75, arg.parse("75"));
            assertEquals(1.0, arg.parse("100"));
            assertEquals(0.0, arg.parse("0"));
        }

        @Test
        @DisplayName("非法值拋 INVALID_ARGUMENT（ACELIB-CMD-015）")
        void illegalValue_invalidArgument() {
            CommandArgument<Double> arg = percent("rate");
            for (String bad : List.of("abc", "75%", "101", "250", "-5", "NaN", "Infinity")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("含空白的輸入被拒（單 token 不變條件）")
        void whitespace_rejected() {
            CommandArgument<Double> arg = percent("rate");
            for (String bad : List.of("", " ", "75 ", "7 5", " 75")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("null 訊息表退回預設英文（不斷言 NPE）")
        void nullMessages_fallsBackToDefault() {
            CommandArgument<Double> arg = percent("rate");
            assertEquals(0.75, arg.parse("75", null));
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("abc", null));
            assertTrue(!ex.getMessage().isEmpty());
        }
    }

    // -----------------------------------------------------------------
    // 在地化錯誤
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("在地化錯誤")
    class LocalizedErrors {

        @Test
        @DisplayName("非法值的錯誤訊息來自傳入的訊息表")
        void illegalValue_messageFromTable() {
            CommandArgument<Double> arg = percent("rate");
            CommandMessages stub = new CommandMessages() {
                @Override
                public String invalidArgument(String name, String value, String reason) {
                    return "自訂訊息：[" + name + "] 不接受 '" + value + "'";
                }
            };
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("abc", stub));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertTrue(ex.getMessage().contains("自訂訊息：[rate] 不接受 'abc'"),
                "錯誤訊息應來自傳入的訊息表；實際: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Brigadier 路徑一致性
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Brigadier 路徑一致性")
    class BrigadierParity {

        @Test
        @DisplayName("ArgumentTypeFactory 已公開（外部套件可實作引數）")
        void factoryIsPublic() {
            assertTrue(Modifier.isPublic(ArgumentTypeFactory.class.getModifiers()),
                "ArgumentTypeFactory 必須是 public，下游才能實作 brigadierType");
        }

        @Test
        @DisplayName("自訂引數的 Brigadier 型別是 stringWord（單 token 開放式引數）")
        void brigadierType_isStringWord() {
            CommandArgument<Double> arg = percent("rate");
            assertInstanceOf(StringArgumentType.class,
                arg.brigadierType(new TestArgs.TestTypes()));
        }

        @Test
        @DisplayName("resolve 與 parse 得到相同的型別值（走同一解析函式）")
        void resolve_matchesParse() throws Exception {
            CommandArgument<String> arg = upper("level");
            AtomicReference<String> resolved = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("level",
                            StringArgumentType.word())
                        .executes(ctx -> {
                            resolved.set(arg.resolve(ctx));
                            return 1;
                        }))
                    .build());

            int result = dispatcher.execute("go hello", stack);
            assertEquals(1, result);
            assertEquals(arg.parse("hello"), resolved.get());
            assertEquals("HELLO", resolved.get());
        }

        @Test
        @DisplayName("resolve 的非法值同樣拋 INVALID_ARGUMENT")
        void resolveIllegalValue_invalidArgument() {
            CommandArgument<String> arg = upper("level");
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("level",
                            StringArgumentType.word())
                        .executes(ctx -> {
                            arg.resolve(ctx);
                            return 1;
                        }))
                    .build());
            CommandException ex = assertThrows(CommandException.class,
                () -> dispatcher.execute("go abc123", stack));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("非 word 字元走不到 resolve（標準 Brigadier 字元集錯誤）")
        void nonWordCharacter_rejectedByWordType() {
            CommandArgument<Double> arg = percent("rate");
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("rate",
                            StringArgumentType.word())
                        .executes(ctx -> {
                            arg.resolve(ctx);
                            return 1;
                        }))
                    .build());
            // `%` 不在 word 字元集內：未加引號由 word 節點拒絕，
            // 加引號同樣由 word 節點拒絕（word 不處理引號）；此類值只在傳統路徑可用。
            assertThrows(
                com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("go 75%", stack));
        }

        @Test
        @DisplayName("兩條路徑的補全一致（Brigadier 建議含自訂選項）")
        void suggestions_matchTraditional() throws Exception {
            CommandArgument<Double> arg = percent("rate");
            assertEquals(List.of("75"), arg.suggest("7"));
            assertEquals(List.of("25", "50", "75", "100"), arg.suggest(""));

            TypedCommand cmd = TypedCommand.builder("pct")
                .description("百分比")
                .subcommand(TypedSubCommand.builder("set")
                    .description("設定")
                    .argument(arg)
                    .executes(ctx -> { })
                    .build())
                .build();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (source, label, args) -> { }));
            var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("pct set ", stack)).get();
            List<String> texts = suggestions.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(texts.containsAll(List.of("25", "50", "75", "100")),
                "Brigadier 補全應列出自訂選項；實際: " + texts);
        }
    }

    // -----------------------------------------------------------------
    // 外部 consumer 完整路徑（審查退回修正：範例指令必須真的可執行）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("外部 consumer 完整路徑")
    class ConsumerFullPath {

        private CommandRegistryTest.RecordingReplySink sink;
        private CommandRegistryImpl registry;
        private CommandRegistryTest.TestSender sender;
        private AtomicReference<Double> seenRate;
        private CommandArgument<Double> rateArg;

        private final CommandMessages stub = new CommandMessages() {
            @Override
            public String invalidArgument(String name, String value, String reason) {
                return "折扣錯誤：[" + name + "] 不接受 '" + value + "'";
            }
        };

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            sink = new CommandRegistryTest.RecordingReplySink();
            CommandRegistryTest.TestClock clock = new CommandRegistryTest.TestClock(1_000L);
            registry = new CommandRegistryImpl(sink, new CooldownTracker(clock));
            sender = new CommandRegistryTest.TestSender("Steve", true);
            seenRate = new AtomicReference<>();
            // 與外部範例同格式的引數（選項集、範圍、錯誤原因一致）。
            rateArg = percent("rate");
            TypedCommand cmd = TypedCommand.builder("discount")
                .description("折扣指令")
                .subcommand(TypedSubCommand.builder("set")
                    .description("設定折扣")
                    .argument(rateArg)
                    .messages(stub)
                    .executes(ctx -> seenRate.set(ctx.get(rateArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
        }

        private CommandDispatcher<CommandSourceStack> dispatcher() {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            TypedCommand cmd = TypedCommand.builder("discount")
                .description("折扣指令")
                .subcommand(TypedSubCommand.builder("set")
                    .description("設定折扣")
                    .argument(rateArg)
                    .messages(stub)
                    .executes(ctx -> seenRate.set(ctx.get(rateArg)))
                    .build())
                .build();
            // Brigadier 執行委派走內部 registry（與 BrigadierRegistrar 同路由）。
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (source, label, args) -> registry.dispatch(sender, label, args)));
            return dispatcher;
        }

        @Test
        @DisplayName("完整指令樹執行合法輸入 → handler 拿到型別值")
        void fullTree_validInput_typedValue() throws Exception {
            int result = dispatcher().execute("discount set 75", stack);
            assertEquals(1, result);
            assertEquals(0.75, seenRate.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("邊界值 100 成功")
        void fullTree_boundaryValue() throws Exception {
            int result = dispatcher().execute("discount set 100", stack);
            assertEquals(1, result);
            assertEquals(1.0, seenRate.get());
        }

        @Test
        @DisplayName("非法輸入 → ACELIB-CMD-015 且訊息來自在地化表")
        void fullTree_illegalInput_localizedError() throws Exception {
            for (String bad : List.of("abc", "101", "250")) {
                seenRate.set(null);
                sink.errors.clear();
                dispatcher().execute("discount set " + bad, stack);
                assertEquals(null, seenRate.get(), "handler 不應執行；輸入: " + bad);
                CommandException err = sink.lastError();
                assertTrue(err != null, "應有錯誤輸出；輸入: " + bad);
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
                assertEquals("ACELIB-CMD-015", err.getCode());
                assertTrue(err.getMessage().contains("折扣錯誤：[rate] 不接受 '" + bad + "'"),
                    "錯誤訊息應來自在地化表；實際: " + err.getMessage());
            }
        }

        @Test
        @DisplayName("完整指令樹的補全列出範例的裸數字選項")
        void fullTree_suggestsBareNumbers() throws Exception {
            CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
            var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("discount set ", stack)).get();
            List<String> texts = suggestions.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(texts.containsAll(List.of("25", "50", "75", "100")),
                "補全應列出範例的裸數字選項；實際: " + texts);
        }
    }
}
