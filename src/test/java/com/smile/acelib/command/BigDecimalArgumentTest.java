package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 精確數值引數測試（第三階段第三項）。
 *
 * <p>同一引數定義服務兩條路徑：傳統路徑 {@code parse} 以
 * {@link BigDecimal} 精確解析（不經 double 中轉）；Brigadier 路徑
 * {@code resolve} 從原始輸入重取 token 走同一個解析器，兩條路徑結果一致。
 * 科學記號兩條路徑皆拒絕（客戶端 vanilla double 型別雖放行，伺服器端解析擋下）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("精確數值引數（BigDecimal）")
class BigDecimalArgumentTest {

    @Mock
    CommandSourceStack stack;

    private static CommandArgument<BigDecimal> amount() {
        return Arguments.bigDecimal("amount", new BigDecimal("0"), new BigDecimal("1000"), 2);
    }

    // -----------------------------------------------------------------
    // 工廠契約
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("工廠契約")
    class FactoryContract {

        @Test
        @DisplayName("名稱如實回傳；開放式引數預設（無補全、基岩不可見）")
        void nameAndDefaults() {
            CommandArgument<BigDecimal> arg = amount();
            assertEquals("amount", arg.name());
            assertTrue(arg.suggest("").isEmpty());
            assertTrue(!arg.bedrockVisible());
            assertTrue(!arg.isFixedOptions());
        }

        @Test
        @DisplayName("null 與非法界限在建構時即拒絕")
        void illegalBounds_rejectedAtBuild() {
            assertThrows(NullPointerException.class,
                () -> Arguments.bigDecimal("amount", null, new BigDecimal("1"), 2));
            assertThrows(NullPointerException.class,
                () -> Arguments.bigDecimal("amount", new BigDecimal("0"), null, 2));
            assertThrows(IllegalArgumentException.class,
                () -> Arguments.bigDecimal("amount",
                    new BigDecimal("1000"), new BigDecimal("0"), 2));
            assertThrows(IllegalArgumentException.class,
                () -> Arguments.bigDecimal("amount",
                    new BigDecimal("0"), new BigDecimal("1000"), -1));
            assertThrows(NullPointerException.class,
                () -> Arguments.bigDecimal(null,
                    new BigDecimal("0"), new BigDecimal("1000"), 2));
            assertThrows(IllegalArgumentException.class,
                () -> Arguments.bigDecimal("",
                    new BigDecimal("0"), new BigDecimal("1000"), 2));
        }

        @Test
        @DisplayName("parse／suggest／brigadierType／resolve 的 null 契約")
        void nullInputs_rejected() {
            CommandArgument<BigDecimal> arg = amount();
            assertThrows(NullPointerException.class, () -> arg.parse(null));
            assertThrows(NullPointerException.class,
                () -> arg.parse(null, DefaultCommandMessages.instance()));
            assertThrows(NullPointerException.class, () -> arg.suggest(null));
            assertThrows(NullPointerException.class, () -> arg.brigadierType(null));
            assertThrows(NullPointerException.class, () -> arg.resolve(null));
        }
    }

    // -----------------------------------------------------------------
    // 精確解析
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("精確解析")
    class PreciseParse {

        @Test
        @DisplayName("精確值保留（0.10 與 0.1 的 scale 差可觀察）")
        void scalePreserved() {
            CommandArgument<BigDecimal> arg = amount();
            assertEquals(new BigDecimal("0.10"), arg.parse("0.10"));
            assertEquals(2, arg.parse("0.10").scale());
            assertEquals(1, arg.parse("0.1").scale());
        }

        @Test
        @DisplayName("0.1 相關運算不受 double 誤差影響")
        void noDoubleRoundingError() {
            CommandArgument<BigDecimal> arg = Arguments.bigDecimal("amount",
                new BigDecimal("0"), new BigDecimal("10"), 10);
            BigDecimal sum = arg.parse("0.1").add(arg.parse("0.2"));
            assertEquals(0, sum.compareTo(new BigDecimal("0.3")));
            // 對照：double 0.1 + 0.2 != 0.3（本引數不得有此誤差）
            assertTrue(0.1 + 0.2 != 0.3);
        }

        @Test
        @DisplayName("範圍端點包含")
        void endpoints_inclusive() {
            CommandArgument<BigDecimal> arg = amount();
            assertEquals(new BigDecimal("0"), arg.parse("0"));
            assertEquals(new BigDecimal("1000"), arg.parse("1000"));
            assertEquals(new BigDecimal("0.00"), arg.parse("0.00"));
            assertEquals(new BigDecimal("1000.00"), arg.parse("1000.00"));
        }

        @Test
        @DisplayName("界限外拒絕（ACELIB-CMD-015）")
        void outOfRange_invalidArgument() {
            CommandArgument<BigDecimal> arg = amount();
            for (String bad : List.of("-0.01", "1000.01", "99999999999999999999999")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("超過小數位上限拒絕（不四捨五入）")
        void tooManyFractionDigits_invalidArgument() {
            CommandArgument<BigDecimal> arg = amount();
            assertEquals(new BigDecimal("1.23"), arg.parse("1.23"));
            for (String bad : List.of("1.234", "0.001", "999.999")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("maxScale=0 只接受整數寫法")
        void zeroScale_integersOnly() {
            CommandArgument<BigDecimal> arg = Arguments.bigDecimal("count",
                new BigDecimal("0"), new BigDecimal("100"), 0);
            assertEquals(new BigDecimal("10"), arg.parse("10"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("10.5")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("10.0")).getKind());
        }

        @Test
        @DisplayName("科學記號一律拒絕（ACELIB-CMD-015，訊息明確說明）")
        void scientificNotation_rejected() {
            CommandArgument<BigDecimal> arg = Arguments.bigDecimal("amount",
                new BigDecimal("0"), new BigDecimal("100000"), 10);
            for (String bad : List.of("1E3", "1e3", "1e-2", "1E+3", "2.5E2")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
                assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("scientific"),
                    "科學記號錯誤訊息應明確說明；實際: " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("語法錯誤拒絕（非數字、多小數點、NaN、無限大）")
        void malformed_invalidArgument() {
            CommandArgument<BigDecimal> arg = amount();
            for (String bad : List.of("abc", "1.2.3", "NaN", "Infinity", "--1", "+-2")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("含e字母的非數字仍是語法錯誤（不是科學記號錯誤）")
        void nonNumericWithE_isSyntaxError() {
            CommandArgument<BigDecimal> arg = amount();
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("hello"));
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertTrue(!ex.getMessage().toLowerCase(java.util.Locale.ROOT)
                .contains("scientific"),
                "hello 不是科學記號，不應誤報；實際: " + ex.getMessage());
        }

        @Test
        @DisplayName("含空白與空字串拒絕（單 token 不變條件）")
        void whitespace_rejected() {
            CommandArgument<BigDecimal> arg = amount();
            for (String bad : List.of("", " ", "1 2", " 10", "10 ")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }

        @Test
        @DisplayName("null 訊息表退回預設英文")
        void nullMessages_fallsBackToDefault() {
            CommandArgument<BigDecimal> arg = amount();
            assertEquals(new BigDecimal("1.5"), arg.parse("1.5", null));
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
        @DisplayName("四類錯誤訊息皆來自傳入的訊息表")
        void allCategories_messageFromTable() {
            CommandArgument<BigDecimal> arg = Arguments.bigDecimal("amount",
                new BigDecimal("0"), new BigDecimal("100000"), 2);
            CommandMessages stub = new CommandMessages() {
                @Override
                public String invalidArgument(String name, String value, String reason) {
                    return "金額錯誤：[" + name + "] 不接受 '" + value + "'";
                }
            };
            // 語法錯、超範圍、超小數位、科學記號。
            for (String bad : List.of("abc", "200000", "1.234", "1E3")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad, stub));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
                assertTrue(ex.getMessage().contains("金額錯誤：[amount] 不接受 '" + bad + "'"),
                    "錯誤訊息應來自傳入的訊息表；輸入: " + bad + "；實際: " + ex.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------
    // Brigadier 路徑一致性
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Brigadier 路徑一致性")
    class BrigadierParity {

        @Test
        @DisplayName("Brigadier 型別是客戶端 double 提示（範圍由客戶端先驗）")
        void brigadierType_isBoundedDouble() {
            CommandArgument<BigDecimal> arg = amount();
            assertInstanceOf(DoubleArgumentType.class,
                arg.brigadierType(new TestArgs.TestTypes()));
        }

        @Test
        @DisplayName("resolve 與 parse 結果一致（含 scale，不經 double 中轉）")
        void resolve_matchesParse() throws Exception {
            CommandArgument<BigDecimal> arg = amount();
            AtomicReference<BigDecimal> resolved = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Double>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<Double>)
                                arg.brigadierType(new TestArgs.TestTypes()))
                        .executes(ctx -> {
                            resolved.set(arg.resolve(ctx));
                            return 1;
                        }))
                    .build());

            int result = dispatcher.execute("go 0.10", stack);
            assertEquals(1, result);
            // BigDecimal.equals 區分 scale：經 double 中轉會掉成 scale 1。
            assertEquals(arg.parse("0.10"), resolved.get());
            assertEquals(2, resolved.get().scale());
        }

        @Test
        @DisplayName("科學記號到不了 resolve（客戶端 double 型別先以標準錯誤拒絕）")
        void scientificNotation_rejectedByClientType() {
            CommandArgument<BigDecimal> arg = Arguments.bigDecimal("amount",
                new BigDecimal("0"), new BigDecimal("100000"), 10);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Double>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<Double>)
                                arg.brigadierType(new TestArgs.TestTypes()))
                        .executes(ctx -> {
                            arg.resolve(ctx);
                            return 1;
                        }))
                    .build());
            // 客戶端 double 型別放行前即拒絕 1E3（標準 Brigadier 字元集錯誤）；
            // 傳統路徑則由解析器以明確訊息拒絕（見精確解析的科學記號測試）。
            assertThrows(
                com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("go 1E3", stack));
        }

        @Test
        @DisplayName("resolve 同樣擋下超小數位（ACELIB-CMD-015，不信任客戶端值）")
        void resolveOverScale_invalidArgument() {
            CommandArgument<BigDecimal> arg = amount();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, Double>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<Double>)
                                arg.brigadierType(new TestArgs.TestTypes()))
                        .executes(ctx -> {
                            arg.resolve(ctx);
                            return 1;
                        }))
                    .build());
            // 1.234 通過客戶端 double 驗證，resolve 重取原始 token 再以
            // BigDecimal 解析器檢查，超小數位仍被拒。
            CommandException ex = assertThrows(CommandException.class,
                () -> dispatcher.execute("go 1.234", stack));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("完整指令樹：合法輸入 handler 拿到精確值；非法輸入在地化錯誤")
        void fullTree_endToEnd() {
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryTest.TestClock clock = new CommandRegistryTest.TestClock(1_000L);
            CommandRegistryImpl registry =
                new CommandRegistryImpl(sink, new CooldownTracker(clock));
            CommandRegistryTest.TestSender sender =
                new CommandRegistryTest.TestSender("Steve", true);
            CommandArgument<BigDecimal> priceArg = Arguments.bigDecimal("price",
                new BigDecimal("0"), new BigDecimal("1000"), 2);
            AtomicReference<BigDecimal> seen = new AtomicReference<>();
            CommandMessages stub = new CommandMessages() {
                @Override
                public String invalidArgument(String name, String value, String reason) {
                    return "金額錯誤：[" + name + "] 不接受 '" + value + "'";
                }
            };
            TypedCommand cmd = TypedCommand.builder("shop")
                .description("商店")
                .subcommand(TypedSubCommand.builder("buy")
                    .description("購買")
                    .argument(priceArg)
                    .messages(stub)
                    .executes(ctx -> seen.set(ctx.get(priceArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(sender, "shop", List.of("buy", "123.45"));
            assertEquals(new BigDecimal("123.45"), seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);

            seen.set(null);
            registry.dispatch(sender, "shop", List.of("buy", "1E3"));
            assertEquals(null, seen.get());
            CommandException err = sink.lastError();
            assertTrue(err != null, "應有錯誤輸出");
            assertEquals("ACELIB-CMD-015", err.getCode());
            assertTrue(err.getMessage().contains("金額錯誤：[price] 不接受 '1E3'"),
                "錯誤訊息應來自在地化表；實際: " + err.getMessage());
        }
    }
}
