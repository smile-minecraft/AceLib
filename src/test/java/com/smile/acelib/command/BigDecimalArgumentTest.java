package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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
 * {@link BigDecimal} 精確解析（不經 double 中轉）；Brigadier 路徑以
 * {@code stringWord} 節點承接單 token，伺服器端執行委派把原始輸入切分後
 * 走同一個解析器，兩條路徑結果一致。科學記號兩條路徑皆拒絕
 *（{@code ACELIB-CMD-015}，訊息來自指定的訊息表）。</p>
 *
 * <p>取捨：字串節點沒有 vanilla double 的客戶端數值提示（範圍、科學記號
 * 皆由伺服器端解析器判定），換來兩條路徑完全一致的伺服器端錯誤。</p>
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
        @DisplayName("Brigadier 型別是 stringWord（合法性完全由伺服器端解析器決定）")
        void brigadierType_isStringWord() {
            CommandArgument<BigDecimal> arg = amount();
            assertInstanceOf(StringArgumentType.class,
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
                        .<CommandSourceStack, String>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<String>)
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
        @DisplayName("resolve 走同一解析器：四類非法值皆為 ACELIB-CMD-015")
        void resolve_rejectsAllCategories() {
            CommandArgument<BigDecimal> arg = amount();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<String>)
                                arg.brigadierType(new TestArgs.TestTypes()))
                        .executes(ctx -> {
                            arg.resolve(ctx);
                            return 1;
                        }))
                    .build());
            // 語法錯、超範圍、超小數位、科學記號：stringWord 節點全部放行到
            // resolve，再由同一個解析器以 ACELIB-CMD-015 拒絕。
            for (String bad : List.of("abc", "1000.01", "1.234", "1E3")) {
                CommandException ex = assertThrows(CommandException.class,
                    () -> dispatcher.execute("go " + bad, stack),
                    "輸入: " + bad);
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind(),
                    "輸入: " + bad);
                assertEquals("ACELIB-CMD-015", ex.getCode(), "輸入: " + bad);
            }
        }

        @Test
        @DisplayName("resolve 接受 +.5（與 parse 一致，double 節點時代此值到不了 resolve）")
        void resolve_acceptsPlusDotFive() throws Exception {
            CommandArgument<BigDecimal> arg = amount();
            AtomicReference<BigDecimal> resolved = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<CommandSourceStack>literal("go")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("amount",
                            (com.mojang.brigadier.arguments.ArgumentType<String>)
                                arg.brigadierType(new TestArgs.TestTypes()))
                        .executes(ctx -> {
                            resolved.set(arg.resolve(ctx));
                            return 1;
                        }))
                    .build());

            int result = dispatcher.execute("go +.5", stack);
            assertEquals(1, result);
            assertEquals(arg.parse("+.5"), resolved.get());
            assertEquals(0, resolved.get().compareTo(new BigDecimal("0.5")));
        }
    }

    // -----------------------------------------------------------------
    // 完整路徑一致性（Brigadier 指令樹＋registry＋自訂訊息表）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("完整路徑一致性（Brigadier 指令樹＋registry＋自訂訊息表）")
    class FullPathParity {

        private CommandRegistryTest.RecordingReplySink sink;
        private CommandRegistryImpl registry;
        private CommandRegistryTest.TestSender sender;

        private final CommandMessages stub = new CommandMessages() {
            @Override
            public String invalidArgument(String name, String value, String reason) {
                return "金額錯誤：[" + name + "] 不接受 '" + value + "'";
            }
        };

        @BeforeEach
        void setUp() {
            sink = new CommandRegistryTest.RecordingReplySink();
            CommandRegistryTest.TestClock clock = new CommandRegistryTest.TestClock(1_000L);
            registry = new CommandRegistryImpl(sink, new CooldownTracker(clock));
            sender = new CommandRegistryTest.TestSender("Steve", true);
        }

        /**
         * 註冊商店指令並組裝 Brigadier 指令樹：執行委派走內部 registry
         *（與 BrigadierRegistrar 同路由），錯誤訊息來自指定的訊息表。
         */
        private CommandDispatcher<CommandSourceStack> registerShop(
                CommandArgument<BigDecimal> priceArg,
                AtomicReference<BigDecimal> seen) {
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
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (source, label, args) -> registry.dispatch(sender, label, args)));
            return dispatcher;
        }

        @Test
        @DisplayName("合法輸入兩條路徑都成功（+.5、0001.20、maxScale 邊界 1.23）")
        void fullTree_validInputs_bothPaths() throws Exception {
            CommandArgument<BigDecimal> priceArg = Arguments.bigDecimal("price",
                new BigDecimal("0"), new BigDecimal("1000"), 2);
            AtomicReference<BigDecimal> seen = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher =
                registerShop(priceArg, seen);

            for (String good : List.of("+.5", "0001.20", "1.23")) {
                // Brigadier 路徑：stringWord 節點承接，執行委派走 registry 解析。
                seen.set(null);
                sink.errors.clear();
                int result = dispatcher.execute("shop buy " + good, stack);
                assertEquals(1, result, "輸入: " + good);
                assertEquals(priceArg.parse(good), seen.get(), "輸入: " + good);
                assertTrue(sink.errors.isEmpty(), "不應有錯誤；輸入: " + good);

                // 傳統路徑：直接 dispatch，同一個解析器、同一個訊息表。
                seen.set(null);
                sink.errors.clear();
                registry.dispatch(sender, "shop", List.of("buy", good));
                assertEquals(priceArg.parse(good), seen.get(), "輸入: " + good);
                assertTrue(sink.errors.isEmpty(), "不應有錯誤；輸入: " + good);
            }
        }

        @Test
        @DisplayName("極大合法值兩條路徑都成功（10^30，不經 double 中轉）")
        void fullTree_hugeValue_bothPaths() throws Exception {
            BigDecimal limit = new BigDecimal("1000000000000000000000000000000");
            CommandArgument<BigDecimal> priceArg = Arguments.bigDecimal("price",
                new BigDecimal("0"), limit, 2);
            AtomicReference<BigDecimal> seen = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher =
                registerShop(priceArg, seen);
            String huge = "1000000000000000000000000000000";

            seen.set(null);
            sink.errors.clear();
            dispatcher.execute("shop buy " + huge, stack);
            assertEquals(priceArg.parse(huge), seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);

            seen.set(null);
            sink.errors.clear();
            registry.dispatch(sender, "shop", List.of("buy", huge));
            assertEquals(priceArg.parse(huge), seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("四類非法值兩條路徑皆回 ACELIB-CMD-015 且訊息來自自訂訊息表")
        void fullTree_illegalInputs_localizedError() throws Exception {
            CommandArgument<BigDecimal> priceArg = Arguments.bigDecimal("price",
                new BigDecimal("0"), new BigDecimal("1000"), 2);
            AtomicReference<BigDecimal> seen = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher =
                registerShop(priceArg, seen);

            // 語法錯、超範圍、超小數位、科學記號。
            for (String bad : List.of("abc", "1000.01", "1.234", "1E3")) {
                // Brigadier 路徑。
                seen.set(null);
                sink.errors.clear();
                dispatcher.execute("shop buy " + bad, stack);
                assertNull(seen.get(), "handler 不應執行；輸入: " + bad);
                CommandException err = sink.lastError();
                assertTrue(err != null, "應有錯誤輸出；輸入: " + bad);
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind(),
                    "輸入: " + bad);
                assertEquals("ACELIB-CMD-015", err.getCode(), "輸入: " + bad);
                assertTrue(err.getMessage().contains("金額錯誤：[price] 不接受 '" + bad + "'"),
                    "錯誤訊息應來自在地化表；輸入: " + bad + "；實際: " + err.getMessage());

                // 傳統路徑：同一個解析器、同一個訊息表，結果一致。
                seen.set(null);
                sink.errors.clear();
                registry.dispatch(sender, "shop", List.of("buy", bad));
                assertNull(seen.get(), "handler 不應執行；輸入: " + bad);
                CommandException legacy = sink.lastError();
                assertTrue(legacy != null, "應有錯誤輸出；輸入: " + bad);
                assertEquals("ACELIB-CMD-015", legacy.getCode(), "輸入: " + bad);
                assertTrue(
                    legacy.getMessage().contains("金額錯誤：[price] 不接受 '" + bad + "'"),
                    "錯誤訊息應來自在地化表；輸入: " + bad + "；實際: " + legacy.getMessage());
            }
        }
    }
}
