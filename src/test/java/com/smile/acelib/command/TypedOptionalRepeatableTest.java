package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 省略引數與重複引數測試（1.5.0 可變數量引數）。
 *
 * <p>同一個 {@link TypedSubCommand} builder 同時產出兩條執行路徑（傳統
 * {@code toSubCommandSpec} 與 Brigadier {@code buildBranch}）；本測試以同一案例
 * 在兩條路徑各跑一次，斷言零／一／多個、數量錯誤與補全的行為一致。</p>
 */
@DisplayName("省略引數與重複引數")
class TypedOptionalRepeatableTest {

    private CommandRegistryTest.RecordingReplySink sink;
    private CommandRegistryTest.TestClock clock;
    private CommandRegistryImpl registry;

    @BeforeEach
    void setUp() {
        sink = new CommandRegistryTest.RecordingReplySink();
        clock = new CommandRegistryTest.TestClock(1_000L);
        registry = new CommandRegistryImpl(sink, new CooldownTracker(clock));
    }

    private static CommandRegistryTest.TestSender console() {
        return new CommandRegistryTest.TestSender("Console", false);
    }

    private static CommandRegistryTest.TestSender player(String name) {
        return new CommandRegistryTest.TestSender(name, true);
    }

    /** PlayerHandle 型別測試替身（parse 不碰 Bukkit；省略時走不到 parse）。 */
    static final class HandleArg implements CommandArgument<PlayerHandle> {
        private final String argName;

        HandleArg(String name) {
            this.argName = name;
        }

        @Override
        public String name() {
            return argName;
        }

        @Override
        public String usageToken() {
            return "<" + argName + ">";
        }

        @Override
        public PlayerHandle parse(String raw, CommandMessages messages) {
            return new CommandRegistryTest.TestPlayerHandle(
                java.util.UUID.nameUUIDFromBytes(raw.getBytes()), raw);
        }

        @Override
        public List<String> suggest(String prefix) {
            return List.of();
        }

        @Override
        public com.mojang.brigadier.arguments.ArgumentType<?> brigadierType(
                ArgumentTypeFactory factory) {
            return factory.stringWord();
        }

        @Override
        public PlayerHandle resolve(
                com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
            return parse(ctx.getArgument(argName, String.class),
                DefaultCommandMessages.instance());
        }
    }

    /** 「預設為自己」：玩家回自己 handle；console 無法預設自己時走在地化錯誤。 */
    static TypedSubCommand.ArgumentDefault<PlayerHandle> selfDefault() {
        return sender -> {
            PlayerHandle handle = sender.asPlayer();
            if (handle == null) {
                throw new CommandException(CommandErrorKind.PLAYER_OFFLINE,
                    "player is offline: " + sender.getName(),
                    Map.of("player", sender.getName()));
            }
            return handle;
        };
    }

    // -----------------------------------------------------------------
    // 傳統路徑：省略引數
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("傳統路徑省略引數")
    class OptionalLegacy {

        @Test
        @DisplayName("省略尾段引數 → handler 拿到型別化預設值，provider 只算一次")
        void omitted_usesTypedDefaultOnce() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> seenItem = new AtomicReference<>();
            AtomicReference<Long> seenAmount = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(amountArg, sender -> {
                        calls.incrementAndGet();
                        return 1L;
                    })
                    .executes(ctx -> {
                        seenItem.set(ctx.get(itemArg));
                        seenAmount.set(ctx.get(amountArg));
                    })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give", "apple"));

            assertEquals("apple", seenItem.get());
            assertEquals(1L, seenAmount.get());
            assertEquals(1, calls.get(), "預設值每執行一次只算一次");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("提供引數 → 拿解析值，provider 不被呼叫")
        void provided_usesParsedValue_providerNotCalled() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Long> seenAmount = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(amountArg, sender -> {
                        calls.incrementAndGet();
                        return 1L;
                    })
                    .executes(ctx -> seenAmount.set(ctx.get(amountArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give", "apple", "5"));

            assertEquals(5L, seenAmount.get());
            assertEquals(0, calls.get(), "有提供值時不得計算預設值");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("預設值每次執行重算，不跨執行快取")
        void default_recomputedPerExecution() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            AtomicInteger calls = new AtomicInteger();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(amountArg, sender -> (long) calls.incrementAndGet())
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give", "apple"));
            registry.dispatch(console(), "shop", List.of("give", "apple"));

            assertEquals(2, calls.get(), "兩次執行各算一次");
        }

        @Test
        @DisplayName("玩家省略玩家引數 → 預設為自己（同一 handle）；console → 在地化錯誤")
        void selfDefault_playerGetsSelf_consoleLocalizedError() {
            HandleArg whoArg = new HandleArg("who");
            AtomicReference<PlayerHandle> seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("msg")
                .subcommand(TypedSubCommand.builder("send")
                    .optional(whoArg, selfDefault())
                    .executes(ctx -> seen.set(ctx.get(whoArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            CommandRegistryTest.TestSender steve = player("Steve");
            registry.dispatch(steve, "msg", List.of("send"));
            assertSame(steve.asPlayer(), seen.get(), "預設玩家必須是 sender 自己");

            registry.dispatch(console(), "msg", List.of("send"));
            CommandException err = sink.lastError();
            assertNotNull(err, "console 無法預設自己時應有錯誤");
            assertEquals(CommandErrorKind.PLAYER_OFFLINE, err.getKind());
            assertEquals("ACELIB-CMD-007", err.getCode());
        }

        @Test
        @DisplayName("console 預設自己失敗經在地化出口 → 在地化文字，不靜默 fallback")
        void selfDefaultConsole_localizedMessage() {
            HandleArg whoArg = new HandleArg("who");
            CommandRegistryTest.RecordingReplySink raw = new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localizedRegistry = new CommandRegistryImpl(
                new LocalizingReplySink(raw, DefaultCommandMessages.instance()),
                new CooldownTracker(clock));
            TypedCommand cmd = TypedCommand.builder("msg")
                .subcommand(TypedSubCommand.builder("send")
                    .optional(whoArg, selfDefault())
                    .executes(ctx -> { })
                    .build())
                .build();
            localizedRegistry.register(cmd.toCommandSpec());

            localizedRegistry.dispatch(console(), "msg", List.of("send"));

            CommandException err = raw.lastError();
            assertNotNull(err);
            assertEquals("ACELIB-CMD-007", err.getCode());
            assertTrue(err.getMessage().contains("player is offline"),
                "應為在地化文字；實際: " + err.getMessage());
        }

        @Test
        @DisplayName("全省略子指令（只剩預設）→ minArgs=0，直接執行")
        void allOptional_minZero() {
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            AtomicReference<Long> seen = new AtomicReference<>();
            TypedSubCommand sub = TypedSubCommand.builder("bonus")
                .optional(amountArg, sender -> 10L)
                .executes(ctx -> seen.set(ctx.get(amountArg)))
                .build();
            assertEquals(0, sub.toSubCommandSpec().minArgs());
            assertEquals(1, sub.toSubCommandSpec().maxArgs());
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(sub).build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("bonus"));

            assertEquals(10L, seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }
    }

    // -----------------------------------------------------------------
    // 傳統路徑：重複引數
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("傳統路徑重複引數")
    class RepeatableLegacy {

        private TypedCommand takeCommand(TestArgs.NameArg itemArg,
                                         TestArgs.NameArg tagArg,
                                         AtomicReference<List<String>> seen) {
            return TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .repeatable(tagArg)
                    .executes(ctx -> seen.set(ctx.getList(tagArg)))
                    .build())
                .build();
        }

        @Test
        @DisplayName("零個重複 → 空 List（不可變）")
        void zero_repeats_emptyImmutableList() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            AtomicReference<List<String>> seen = new AtomicReference<>();
            registry.register(takeCommand(itemArg, tagArg, seen).toCommandSpec());

            registry.dispatch(console(), "shop", List.of("take", "apple"));

            assertEquals(List.of(), seen.get());
            assertThrows(UnsupportedOperationException.class,
                () -> seen.get().add("x"), "重複值必須不可變");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("一／多個重複 → 對應型別化 List")
        void oneAndMany_typedLists() {
            TestArgs.LongArg baseArg = new TestArgs.LongArg("base");
            TestArgs.LongArg extraArg = new TestArgs.LongArg("extra");
            AtomicReference<List<Long>> seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("calc")
                .subcommand(TypedSubCommand.builder("sum")
                    .argument(baseArg)
                    .repeatable(extraArg)
                    .executes(ctx -> seen.set(ctx.getList(extraArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "calc", List.of("sum", "1", "2"));
            assertEquals(List.of(2L), seen.get());

            registry.dispatch(console(), "calc", List.of("sum", "1", "2", "3", "4"));
            assertEquals(List.of(2L, 3L, 4L), seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("重複元素非法 → INVALID_ARGUMENT，handler 不執行")
        void invalidElement_invalidArgument_handlerNotRun() {
            TestArgs.LongArg baseArg = new TestArgs.LongArg("base");
            TestArgs.LongArg extraArg = new TestArgs.LongArg("extra");
            AtomicReference<List<Long>> seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("calc")
                .subcommand(TypedSubCommand.builder("sum")
                    .argument(baseArg)
                    .repeatable(extraArg)
                    .executes(ctx -> seen.set(ctx.getList(extraArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "calc", List.of("sum", "1", "oops"));

            assertEquals(null, seen.get());
            CommandException err = sink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());
        }

        @Test
        @DisplayName("重複引數無上限：十個額外 token 照收")
        void manyExtras_noUpperBound() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            AtomicReference<List<String>> seen = new AtomicReference<>();
            registry.register(takeCommand(itemArg, tagArg, seen).toCommandSpec());
            List<String> args = new ArrayList<>(List.of("take", "apple"));
            for (int i = 0; i < 10; i++) {
                args.add("t" + i);
            }

            registry.dispatch(console(), "shop", args);

            assertEquals(10, seen.get().size());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("get(重複引數) 拋 IAE；getList(單值／未知引數) 抛 IAE")
        void getVsGetList_contract() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            TestArgs.NameArg foreignArg = new TestArgs.NameArg("foreign");
            AtomicReference<TypedContext> captured = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .repeatable(tagArg)
                    .executes(captured::set)
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "shop", List.of("take", "apple", "x"));

            TypedContext ctx = captured.get();
            assertNotNull(ctx);
            assertThrows(IllegalArgumentException.class, () -> ctx.get(tagArg),
                "重複引數必須用 getList 取值");
            assertThrows(IllegalArgumentException.class, () -> ctx.getList(itemArg),
                "單值引數必須用 get 取值");
            assertThrows(IllegalArgumentException.class, () -> ctx.getList(foreignArg),
                "未參與解析的引數抛 IAE");
            assertEquals("apple", ctx.get(itemArg));
        }
    }

    // -----------------------------------------------------------------
    // 數量錯誤
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("數量錯誤")
    class CountErrors {

        @Test
        @DisplayName("必要引數不足 → MISSING_ARGUMENTS（ACELIB-CMD-001）")
        void missingRequired_missingArguments() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(amountArg, sender -> 1L)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give"));

            CommandException err = sink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, err.getKind());
            assertEquals("ACELIB-CMD-001", err.getCode());
            assertEquals(1, err.getVars().get("minArgs"));
        }

        @Test
        @DisplayName("無重複時過多引數 → tooMany（同 MISSING_ARGUMENTS kind）")
        void tooManyWithoutRepeatable_tooManyArguments() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(amountArg, sender -> 1L)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give", "apple", "3", "extra"));

            CommandException err = sink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, err.getKind());
            assertEquals("ACELIB-CMD-001", err.getCode());
            assertEquals(2, err.getVars().get("maxArgs"));
            assertTrue(err.getMessage().contains("too many"),
                "訊息應為過多引數；實際: " + err.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // 非法宣告
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("非法宣告")
    class IllegalDeclarations {

        @Test
        @DisplayName("必要引數接在省略引數後 → build 拒絕")
        void requiredAfterOptional_rejected() {
            TestArgs.NameArg a = new TestArgs.NameArg("a");
            TestArgs.NameArg b = new TestArgs.NameArg("b");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("cmd")
                    .optional(a, sender -> "dflt")
                    .argument(b)
                    .executes(ctx -> { })
                    .build());
            assertTrue(ex.getMessage().contains("b"),
                "訊息應指出違規引數；實際: " + ex.getMessage());
        }

        @Test
        @DisplayName("重複引數非最後（後面還有引數）→ build 拒絕")
        void repeatableNotLast_rejected() {
            TestArgs.NameArg a = new TestArgs.NameArg("a");
            TestArgs.NameArg b = new TestArgs.NameArg("b");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("cmd")
                    .repeatable(a)
                    .argument(b)
                    .executes(ctx -> { })
                    .build());
            assertTrue(ex.getMessage().contains("a"),
                "訊息應指出違規引數；實際: " + ex.getMessage());
        }

        @Test
        @DisplayName("省略引數接在重複引數後 → build 拒絕")
        void optionalAfterRepeatable_rejected() {
            TestArgs.NameArg a = new TestArgs.NameArg("a");
            TestArgs.LongArg b = new TestArgs.LongArg("b");
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("cmd")
                    .repeatable(a)
                    .optional(b, sender -> 1L)
                    .executes(ctx -> { })
                    .build());
        }

        @Test
        @DisplayName("兩個重複引數 → build 拒絕")
        void doubleRepeatable_rejected() {
            TestArgs.NameArg a = new TestArgs.NameArg("a");
            TestArgs.NameArg b = new TestArgs.NameArg("b");
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("cmd")
                    .repeatable(a)
                    .repeatable(b)
                    .executes(ctx -> { })
                    .build());
        }
    }

    // -----------------------------------------------------------------
    // 傳統路徑補全
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("傳統路徑補全")
    class LegacyCompletion {

        @Test
        @DisplayName("省略位置與重複位置各補對應引數的建議")
        void omitAndRepeatPositions_suggest() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .repeatable(tagArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            assertTrue(registry.tabComplete(console(), "shop", List.of("take", "St"))
                .contains("Steve"));
            assertTrue(registry.tabComplete(console(), "shop", List.of("take", "apple", "A"))
                .contains("Alex"));
            assertTrue(registry.tabComplete(console(), "shop",
                    List.of("take", "apple", "x", "y", "A")).contains("Alex"));
            List<String> atRepeatEmpty = registry.tabComplete(console(), "shop",
                List.of("take", "apple", "x", ""));
            assertTrue(atRepeatEmpty.contains("Steve") && atRepeatEmpty.contains("Alex"),
                "重複位置空前綴應列全部；實際: " + atRepeatEmpty);
        }

        @Test
        @DisplayName("無重複時超出範圍 → 空補全")
        void beyondRangeWithoutRepeatable_empty() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            assertTrue(registry.tabComplete(console(), "shop",
                List.of("take", "apple", "extra")).isEmpty());
        }
    }

    // -----------------------------------------------------------------
    // Brigadier 路徑：與傳統路徑一致
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Brigadier 路徑一致")
    class BrigadierParity {

        private CommandSourceStack stack;
        private CommandSender sender;

        @BeforeEach
        void stubSource() {
            stack = mock(CommandSourceStack.class);
            sender = mock(CommandSender.class);
            when(stack.getSender()).thenReturn(sender);
        }

        record Snap(String item, Long count, List<Long> tags,
                    CommandErrorKind errKind, String errCode, String errMsg) {
        }

        private TypedCommand giveCommand(TestArgs.NameArg itemArg,
                                         TestArgs.LongArg countArg,
                                         TestArgs.LongArg nArg,
                                         AtomicReference<Snap> snap) {
            return TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .argument(itemArg)
                    .optional(countArg, s -> 7L)
                    .repeatable(nArg)
                    .executes(ctx -> snap.set(new Snap(ctx.get(itemArg),
                        ctx.get(countArg), ctx.getList(nArg), null, null, null)))
                    .build())
                .build();
        }

        private Snap runLegacy(List<String> args) {
            CommandRegistryTest.RecordingReplySink localSink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localRegistry = new CommandRegistryImpl(localSink,
                new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
            AtomicReference<Snap> snap = new AtomicReference<>();
            localRegistry.register(giveCommand(new TestArgs.NameArg("item"),
                new TestArgs.LongArg("count"), new TestArgs.LongArg("n"), snap)
                .toCommandSpec());
            localRegistry.dispatch(console(), "shop", args);
            if (snap.get() != null) {
                return snap.get();
            }
            CommandException err = localSink.lastError();
            assertNotNull(err, "handler 未執行時應有錯誤；args=" + args);
            return new Snap(null, null, null, err.getKind(), err.getCode(),
                err.getMessage());
        }

        private Snap runBrigadier(String input) throws Exception {
            AtomicReference<Snap> snap = new AtomicReference<>();
            TypedCommand cmd = giveCommand(new TestArgs.NameArg("item"),
                new TestArgs.LongArg("count"), new TestArgs.LongArg("n"), snap);
            AtomicReference<List<String>> routed = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> routed.set(a)));
            dispatcher.execute(input, stack);
            assertNotNull(routed.get(), "Brigadier 應委派執行；input=" + input);

            CommandRegistryTest.RecordingReplySink localSink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localRegistry = new CommandRegistryImpl(localSink,
                new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
            AtomicReference<Snap> snap2 = new AtomicReference<>();
            localRegistry.register(giveCommand(new TestArgs.NameArg("item"),
                new TestArgs.LongArg("count"), new TestArgs.LongArg("n"), snap2)
                .toCommandSpec());
            localRegistry.dispatch(console(), "shop", routed.get());
            if (snap2.get() != null) {
                return snap2.get();
            }
            CommandException err = localSink.lastError();
            assertNotNull(err, "handler 未執行時應有錯誤；input=" + input);
            return new Snap(null, null, null, err.getKind(), err.getCode(),
                err.getMessage());
        }

        @Test
        @DisplayName("省略／一／多個在兩條路徑結果相同")
        void omitOneMany_parity() throws Exception {
            assertEquals(runLegacy(List.of("give", "apple")),
                runBrigadier("shop give apple"));
            assertEquals(new Snap("apple", 7L, List.of(), null, null, null),
                runLegacy(List.of("give", "apple")));
            assertEquals(runLegacy(List.of("give", "apple", "3")),
                runBrigadier("shop give apple 3"));
            assertEquals(runLegacy(List.of("give", "apple", "3", "4", "5", "6")),
                runBrigadier("shop give apple 3 4 5 6"));
        }

        @Test
        @DisplayName("非法元素與數量不足在兩條路徑錯誤相同")
        void errors_parity() throws Exception {
            assertEquals(runLegacy(List.of("give", "apple", "3", "oops")),
                runBrigadier("shop give apple 3 oops"));
            assertEquals(runLegacy(List.of("give")),
                runBrigadier("shop give"));
        }

        @Test
        @DisplayName("重複位置補全在兩條路徑相同")
        void repeatCompletion_parity() throws Exception {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .repeatable(tagArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> { }));

            for (String[] kase : new String[][]{
                {"take apple ", "take", "apple", ""},
                {"take apple A", "take", "apple", "A"},
                {"take apple x y ", "take", "apple", "x", "y", ""},
            }) {
                String tail = kase[0];
                List<String> legacy = registry.tabComplete(console(), "shop",
                    List.of(kase).subList(1, kase.length));
                var parse = dispatcher.parse(new StringReader("shop " + tail), stack);
                List<String> brigadier = dispatcher.getCompletionSuggestions(parse).get()
                    .getList().stream().map(s -> s.getText()).sorted().toList();
                List<String> legacySorted = new TreeSet<>(legacy).stream().toList();
                assertEquals(legacySorted, brigadier,
                    "補全不一致；input=shop " + tail);
            }
        }

        @Test
        @DisplayName("省略位置補全在兩條路徑相同")
        void omitCompletion_parity() throws Exception {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg optArg = new TestArgs.NameArg("opt");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .optional(optArg, s -> "dflt")
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> { }));

            String[][] cases = {
                {"take apple ", "take", "apple", ""},
                {"take apple A", "take", "apple", "A"},
            };
            for (String[] kase : cases) {
                String tail = kase[0];
                List<String> legacy = registry.tabComplete(console(), "shop",
                    List.of(kase).subList(1, kase.length));
                var parse = dispatcher.parse(new StringReader("shop " + tail), stack);
                List<String> brigadier = dispatcher.getCompletionSuggestions(parse).get()
                    .getList().stream().map(s -> s.getText()).sorted().toList();
                List<String> legacySorted = new TreeSet<>(legacy).stream().toList();
                assertEquals(legacySorted, brigadier, "補全不一致；tail=" + tail);
            }
        }

        @Test
        @DisplayName("無重複時過多引數：Brigadier 溢位節點委派，錯誤與傳統一致")
        void tooManyOptionalOnly_parity() throws Exception {
            TestArgs.NameArg reqArg = new TestArgs.NameArg("req");
            TestArgs.NameArg optArg = new TestArgs.NameArg("opt");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(reqArg)
                    .optional(optArg, s -> "dflt")
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            AtomicReference<List<String>> routed = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> routed.set(a)));
            dispatcher.execute("shop take a b c", stack);

            assertEquals(List.of("take", "a", "b", "c"), routed.get());
            registry.dispatch(console(), "shop", routed.get());
            CommandException viaBrigadier = sink.lastError();
            assertNotNull(viaBrigadier);
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, viaBrigadier.getKind());
            assertTrue(viaBrigadier.getMessage().contains("too many"),
                "實際: " + viaBrigadier.getMessage());

            sink.errors.clear();
            registry.dispatch(console(), "shop", List.of("take", "a", "b", "c"));
            assertEquals(sink.lastError().getMessage(), viaBrigadier.getMessage());
        }

        @Test
        @DisplayName("重複補全的替換範圍只覆蓋末 token，前段 token 不被覆蓋")
        void repeatCompletion_rangeCoversLastTokenOnly() throws Exception {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(itemArg)
                    .repeatable(tagArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> { }));

            String input = "shop take apple x A";
            var parse = dispatcher.parse(new StringReader(input), stack);
            var suggestions = dispatcher.getCompletionSuggestions(parse).get().getList();
            assertTrue(!suggestions.isEmpty(), "應有補全");
            int lastStart = input.length() - 1;
            for (var suggestion : suggestions) {
                assertEquals(lastStart, suggestion.getRange().getStart(),
                    "替換起點必須是末 token 起點；實際: " + suggestion.getRange());
                assertEquals(input.length(), suggestion.getRange().getEnd(),
                    "替換終點必須是輸入結尾；實際: " + suggestion.getRange());
            }

            String trailing = "shop take apple x ";
            var parseTrailing = dispatcher.parse(new StringReader(trailing), stack);
            var trailingSuggestions =
                dispatcher.getCompletionSuggestions(parseTrailing).get().getList();
            assertTrue(!trailingSuggestions.isEmpty(), "空前綴應有補全");
            for (var suggestion : trailingSuggestions) {
                assertEquals(trailing.length(), suggestion.getRange().getStart(),
                    "實際: " + suggestion.getRange());
                assertEquals(trailing.length(), suggestion.getRange().getEnd(),
                    "實際: " + suggestion.getRange());
            }
        }

        @Test
        @DisplayName("省略的固定選項在 Brigadier 路徑走開放節點，非法值同回 ACELIB-CMD-015")
        void optionalFixedBrigadierOpenNode() throws Exception {
            TestArgs.NameArg reqArg = new TestArgs.NameArg("req");
            CommandArgument<String> modeArg =
                Arguments.fixed("mode", "silent", "public");
            AtomicReference<String> seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(reqArg)
                    .optional(modeArg, s -> "silent")
                    .executes(ctx -> seen.set(ctx.get(modeArg)))
                    .build())
                .build();
            CommandRegistryTest.RecordingReplySink localSink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localRegistry = new CommandRegistryImpl(localSink,
                new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
            localRegistry.register(cmd.toCommandSpec());
            AtomicReference<List<String>> routed = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> routed.set(a)));

            dispatcher.execute("shop take a loud", stack);
            assertNotNull(routed.get(), "非法選項值不得被原生拒絕，應委派進共同解析");
            localRegistry.dispatch(console(), "shop", routed.get());
            CommandException err = localSink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());

            routed.set(null);
            dispatcher.execute("shop take a silent", stack);
            assertEquals(List.of("take", "a", "silent"), routed.get());
            localRegistry.dispatch(console(), "shop", routed.get());
            assertEquals("silent", seen.get());

            seen.set(null);
            localSink.errors.clear();
            localRegistry.dispatch(console(), "shop", List.of("take", "a"));
            assertEquals("silent", seen.get(), "省略時拿預設值");
            assertTrue(localSink.errors.isEmpty(), "不應有錯誤；實際: " + localSink.errors);
        }
    }

    // -----------------------------------------------------------------
    // MockBukkit：真實玩家引數的預設自己
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("MockBukkit 真實玩家預設")
    class BukkitSelfDefault {

        private ServerMock server;

        @BeforeEach
        void mockServer() {
            server = MockBukkit.mock();
        }

        @AfterEach
        void unmockServer() {
            MockBukkit.unmock();
        }

        @Test
        @DisplayName("線上玩家省略玩家引數 → 預設為自己；console → ACELIB-CMD-007")
        void onlinePlayer_defaultsToSelf() {
            var steve = server.addPlayer("Steve");
            CommandArgument<PlayerHandle> targetArg = Arguments.player("target");
            TestArgs.NameArg msgArg = new TestArgs.NameArg("msg");
            AtomicReference<PlayerHandle> seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("msg")
                .subcommand(TypedSubCommand.builder("send")
                    .argument(msgArg)
                    .optional(targetArg, selfDefault())
                    .executes(ctx -> seen.set(ctx.get(targetArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(new BukkitSender(steve), "msg", List.of("send", "hi"));
            assertNotNull(seen.get());
            assertEquals(steve.getUniqueId(), seen.get().getUniqueId());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);

            registry.dispatch(new BukkitSender(server.getConsoleSender()),
                "msg", List.of("send", "hi"));
            CommandException err = sink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.PLAYER_OFFLINE, err.getKind());
            assertEquals("ACELIB-CMD-007", err.getCode());
        }
    }

    // -----------------------------------------------------------------
    // 回歸：全必要引數行為不變
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("回歸全必要引數")
    class RequiredOnlyRegression {

        @Test
        @DisplayName("全必要宣告的數量語意與用法字串不變")
        void requiredOnly_unchanged() {
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TestArgs.LongArg lengthArg = new TestArgs.LongArg("length");
            AtomicReference<String> seenTarget = new AtomicReference<>();
            AtomicReference<Long> seenLength = new AtomicReference<>();
            TypedSubCommand sub = TypedSubCommand.builder("ban")
                .argument(targetArg)
                .argument(lengthArg)
                .executes(ctx -> {
                    seenTarget.set(ctx.get(targetArg));
                    seenLength.set(ctx.get(lengthArg));
                })
                .build();
            SubCommandSpec spec = sub.toSubCommandSpec();
            assertEquals(2, spec.minArgs());
            assertEquals(2, spec.maxArgs());
            assertEquals("<target> <length>", spec.usage());

            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(sub).build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "punish", List.of("ban", "Steve", "100"));
            assertEquals("Steve", seenTarget.get());
            assertEquals(100L, seenLength.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("可變形狀的用法字串標示省略與重複")
        void variadicUsage_marksOptionalAndRepeat() {
            TestArgs.NameArg itemArg = new TestArgs.NameArg("item");
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            TypedSubCommand sub = TypedSubCommand.builder("take")
                .argument(itemArg)
                .optional(amountArg, sender -> 1L)
                .repeatable(tagArg)
                .executes(ctx -> { })
                .build();
            String usage = sub.toSubCommandSpec().usage();
            assertTrue(usage.contains("[<amount>]"), "省略引數應以中括號標示；實際: " + usage);
            assertTrue(usage.contains("<tag>..."), "重複引數應以省略號標示；實際: " + usage);
        }
    }

    // -----------------------------------------------------------------
    // 真實數值省略引數的錯誤一致（原生節點不得前置拒絕）
    // -----------------------------------------------------------------

    /** 訊息表替身：證明錯誤文字來自 CommandMessages 而非平台原生錯誤。 */
    static final class TaggingMessages implements CommandMessages {
        @Override
        public String invalidArgument(String arg, String value, String reason) {
            return "custom-invalid:" + arg + ":" + value + ":" + reason;
        }
    }

    @Nested
    @DisplayName("真實數值省略引數的錯誤一致")
    class IntArgOptionalErrors {

        private CommandSourceStack stack;
        private CommandSender sender;

        @BeforeEach
        void stubSource() {
            stack = mock(CommandSourceStack.class);
            sender = mock(CommandSender.class);
            when(stack.getSender()).thenReturn(sender);
        }

        record IntSnap(Integer count,
                       CommandErrorKind errKind, String errCode, String errMsg) {
        }

        private TypedCommand intCommand(AtomicReference<IntSnap> snap) {
            CommandArgument<Integer> countArg = Arguments.intArg("count", 1, 64);
            return TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .optional(countArg, s -> 1)
                    .messages(new TaggingMessages())
                    .executes(ctx -> snap.set(
                        new IntSnap(ctx.get(countArg), null, null, null)))
                    .build())
                .build();
        }

        private IntSnap runLegacy(List<String> args) {
            CommandRegistryTest.RecordingReplySink localSink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localRegistry = new CommandRegistryImpl(localSink,
                new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
            AtomicReference<IntSnap> snap = new AtomicReference<>();
            localRegistry.register(intCommand(snap).toCommandSpec());
            localRegistry.dispatch(console(), "shop", args);
            if (snap.get() != null) {
                return snap.get();
            }
            CommandException err = localSink.lastError();
            assertNotNull(err, "handler 未執行時應有錯誤；args=" + args);
            return new IntSnap(null, err.getKind(), err.getCode(), err.getMessage());
        }

        private IntSnap runBrigadier(String input) throws Exception {
            AtomicReference<IntSnap> snap = new AtomicReference<>();
            TypedCommand cmd = intCommand(snap);
            AtomicReference<List<String>> routed = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (s, label, a) -> routed.set(a)));
            dispatcher.execute(input, stack);
            assertNotNull(routed.get(),
                "Brigadier 不得原生拒絕，應委派進共同解析；input=" + input);

            CommandRegistryTest.RecordingReplySink localSink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl localRegistry = new CommandRegistryImpl(localSink,
                new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
            AtomicReference<IntSnap> snap2 = new AtomicReference<>();
            localRegistry.register(intCommand(snap2).toCommandSpec());
            localRegistry.dispatch(console(), "shop", routed.get());
            if (snap2.get() != null) {
                return snap2.get();
            }
            CommandException err = localSink.lastError();
            assertNotNull(err, "handler 未執行時應有錯誤；input=" + input);
            return new IntSnap(null, err.getKind(), err.getCode(), err.getMessage());
        }

        @Test
        @DisplayName("合法值：省略拿預設、提供拿解析值，兩條路徑相同")
        void legalValues_parity() throws Exception {
            assertEquals(new IntSnap(1, null, null, null),
                runLegacy(List.of("give")));
            assertEquals(new IntSnap(1, null, null, null),
                runBrigadier("shop give"));
            assertEquals(new IntSnap(5, null, null, null),
                runLegacy(List.of("give", "5")));
            assertEquals(new IntSnap(5, null, null, null),
                runBrigadier("shop give 5"));
        }

        @Test
        @DisplayName("越界 65：兩條路徑同為 ACELIB-CMD-015 且訊息來自訊息表")
        void outOfRange_parity() throws Exception {
            IntSnap expected = new IntSnap(null, CommandErrorKind.INVALID_ARGUMENT,
                "ACELIB-CMD-015", "custom-invalid:count:65:out of range 1-64");
            assertEquals(expected, runLegacy(List.of("give", "65")));
            assertEquals(expected, runBrigadier("shop give 65"));
        }

        @Test
        @DisplayName("格式錯誤 abc：兩條路徑同為 ACELIB-CMD-015 且訊息來自訊息表")
        void notANumber_parity() throws Exception {
            IntSnap expected = new IntSnap(null, CommandErrorKind.INVALID_ARGUMENT,
                "ACELIB-CMD-015",
                "custom-invalid:count:abc:not an integer in range 1-64");
            assertEquals(expected, runLegacy(List.of("give", "abc")));
            assertEquals(expected, runBrigadier("shop give abc"));
        }
    }

    // -----------------------------------------------------------------
    // 預設值提供者回傳 null
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("預設值提供者回傳 null")
    class NullDefault {

        @Test
        @DisplayName("null 預設值包裝為執行失敗，不靜默、不進 handler")
        void nullDefault_wrappedAsExecutionFailed() {
            TestArgs.LongArg amountArg = new TestArgs.LongArg("amount");
            java.util.concurrent.atomic.AtomicBoolean ran =
                new java.util.concurrent.atomic.AtomicBoolean();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("give")
                    .optional(amountArg, sender -> null)
                    .executes(ctx -> ran.set(true))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "shop", List.of("give"));

            assertTrue(!ran.get(), "契約違規不得進入 handler");
            CommandException err = sink.lastError();
            assertNotNull(err);
            assertEquals(CommandErrorKind.EXECUTION_FAILED, err.getKind());
            assertEquals("ACELIB-CMD-008", err.getCode());
        }
    }

    // -----------------------------------------------------------------
    // 值型別為 List 的自訂引數與重複值共存
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("值型別為 List 的自訂引數")
    class ListValuedCustomArg {

        @Test
        @DisplayName("單值 get 取 List 值、重複 getList 取值，互不干擾")
        void listValuedSingle_coexistsWithRepeatable() {
            CommandArgument<List<String>> csvArg = CommandArgument.custom("csv", "<csv>",
                (raw, messages) -> List.of(raw.split("\\+")),
                prefix -> List.of());
            TestArgs.NameArg tagArg = new TestArgs.NameArg("tag");
            AtomicReference<TypedContext> captured = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("shop")
                .subcommand(TypedSubCommand.builder("take")
                    .argument(csvArg)
                    .repeatable(tagArg)
                    .executes(captured::set)
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "shop", List.of("take", "a+b", "x"));

            TypedContext ctx = captured.get();
            assertNotNull(ctx);
            assertEquals(List.of("a", "b"), ctx.get(csvArg));
            assertEquals(List.of("x"), ctx.getList(tagArg));
            assertThrows(IllegalArgumentException.class, () -> ctx.get(tagArg),
                "重複引數必須用 getList 取值");
            assertThrows(IllegalArgumentException.class, () -> ctx.getList(csvArg),
                "值為 List 的單值引數仍是單值，不得誤判為重複");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }
    }
}
