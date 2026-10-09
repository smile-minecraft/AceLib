package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

/**
 * 子指令別名測試（第三階段收尾）。
 *
 * <p>別名在傳統路徑（{@link CommandRegistryImpl} 經
 * {@link CommandSpec#findSubCommand}）與 Brigadier 路徑
 * （別名 literal 分支）都能觸發同一子指令；別名與主名或彼此衝突
 * （大小寫不敏感）在建構時拒絕。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("子指令別名")
class TypedSubcommandAliasTest {

    @Mock
    CommandSourceStack stack;
    @Mock
    CommandSender sender;

    private CommandRegistryTest.RecordingReplySink sink;
    private CommandRegistryImpl registry;

    @BeforeEach
    void setUp() {
        sink = new CommandRegistryTest.RecordingReplySink();
        registry = new CommandRegistryImpl(sink,
            new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
    }

    private void stubSource() {
        when(stack.getSender()).thenReturn(sender);
    }

    private CommandRegistryTest.TestSender console() {
        return new CommandRegistryTest.TestSender("Console", false);
    }

    @Nested
    @DisplayName("傳統路徑")
    class TraditionalPath {

        @Test
        @DisplayName("別名觸發子指令，handler 收到相同型別值")
        void alias_dispatchesToSameHandler() {
            AtomicReference<String> seenTarget = new AtomicReference<>();
            AtomicReference<Long> seenLength = new AtomicReference<>();
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TestArgs.LongArg lengthArg = new TestArgs.LongArg("length");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .aliases("b")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> {
                        seenTarget.set(ctx.get(targetArg));
                        seenLength.set(ctx.get(lengthArg));
                    })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "punish", List.of("b", "Steve", "100"));
            assertEquals("Steve", seenTarget.get());
            assertEquals(100L, seenLength.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("別名大小寫不敏感")
        void alias_caseInsensitive() {
            AtomicReference<String> seen = new AtomicReference<>();
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .aliases("Prohibit")
                    .argument(targetArg)
                    .executes(ctx -> seen.set(ctx.get(targetArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "punish", List.of("PROHIBIT", "Steve"));
            assertEquals("Steve", seen.get());
        }

        @Test
        @DisplayName("別名補全委派給主規格的 completer")
        void alias_tabCompleteDelegates() {
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TestArgs.LongArg lengthArg = new TestArgs.LongArg("length");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .aliases("b")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            List<String> result =
                registry.tabComplete(console(), "punish", List.of("b", "St"));
            assertTrue(result.contains("Steve"), "應委派 target 引數補全；實際: " + result);
        }

        @Test
        @DisplayName("未知別名仍為 UNKNOWN_SUBCOMMAND")
        void unknownAlias_stillUnknown() {
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .aliases("b")
                    .argument(targetArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            registry.dispatch(console(), "punish", List.of("zzz", "Steve"));
            assertEquals(CommandErrorKind.UNKNOWN_SUBCOMMAND,
                sink.lastError().getKind());
        }
    }

    @Nested
    @DisplayName("Brigadier 路徑")
    class BrigadierPath {

        @Test
        @DisplayName("別名 literal 分支執行成功，handler 收到相同型別值")
        void aliasBranch_dispatchesWithSameValues() throws Exception {
            stubSource();
            AtomicReference<String> seenTarget = new AtomicReference<>();
            AtomicReference<Long> seenLength = new AtomicReference<>();
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TestArgs.LongArg lengthArg = new TestArgs.LongArg("length");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .aliases("b")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> {
                        seenTarget.set(ctx.get(targetArg));
                        seenLength.set(ctx.get(lengthArg));
                    })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());

            AtomicReference<List<String>> delegated = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(
                new TestArgs.TestTypes(),
                (source, label, args) -> delegated.set(List.copyOf(args))));
            dispatcher.execute("punish b Steve 100", stack);

            assertTrue(delegated.get() != null && !delegated.get().isEmpty(),
                "別名分支應進入執行委派");
            assertEquals("b", delegated.get().get(0));
            registry.dispatch(console(), "punish", delegated.get());
            assertEquals("Steve", seenTarget.get());
            assertEquals(100L, seenLength.get());
        }

        @Test
        @DisplayName("固定選項子指令的別名分支同樣可執行")
        void fixedOptionSubcommand_aliasBranchExecutes() throws Exception {
            stubSource();
            AtomicReference<String> seen = new AtomicReference<>();
            TestArgs.FixedArg modeArg =
                new TestArgs.FixedArg("mode", List.of("silent", "public"));
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("mode")
                    .aliases("m")
                    .argument(modeArg)
                    .executes(ctx -> seen.set(ctx.get(modeArg)))
                    .build())
                .build();

            AtomicReference<List<String>> delegated = new AtomicReference<>();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(
                new TestArgs.TestTypes(),
                (source, label, args) -> delegated.set(List.copyOf(args))));
            dispatcher.execute("punish m silent", stack);

            assertTrue(delegated.get() != null && !delegated.get().isEmpty(),
                "別名分支應進入執行委派");
            assertEquals(List.of("m", "silent"), delegated.get());
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "punish", delegated.get());
            assertEquals("silent", seen.get());
        }
    }

    @Nested
    @DisplayName("衝突拒絕")
    class Conflicts {

        @Test
        @DisplayName("別名與自身主名相同 → 建構時拒絕")
        void aliasSameAsOwnName_rejected() {
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .aliases("ban")
                    .executes(ctx -> { })
                    .build());
        }

        @Test
        @DisplayName("別名與自身主名僅大小寫不同 → 建構時拒絕")
        void aliasSameAsOwnNameIgnoreCase_rejected() {
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .aliases("BAN")
                    .executes(ctx -> { })
                    .build());
        }

        @Test
        @DisplayName("同一子指令內別名重複（含大小寫變體）→ 建構時拒絕")
        void duplicateAliasesWithinSub_rejected() {
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .aliases("b", "B")
                    .executes(ctx -> { })
                    .build());
        }

        @Test
        @DisplayName("別名與其他子指令主名相同 → 根指令建構時拒絕")
        void aliasConflictsWithOtherPrimary_rejected() {
            TypedSubCommand ban = TypedSubCommand.builder("ban")
                .executes(ctx -> { })
                .build();
            TypedSubCommand kick = TypedSubCommand.builder("kick")
                .aliases("ban")
                .executes(ctx -> { })
                .build();
            assertThrows(IllegalArgumentException.class,
                () -> TypedCommand.builder("punish")
                    .subcommand(ban)
                    .subcommand(kick)
                    .build());
        }

        @Test
        @DisplayName("別名與其他子指令別名相同（含大小寫變體）→ 根指令建構時拒絕")
        void aliasConflictsWithOtherAlias_rejected() {
            TypedSubCommand ban = TypedSubCommand.builder("ban")
                .aliases("x")
                .executes(ctx -> { })
                .build();
            TypedSubCommand kick = TypedSubCommand.builder("kick")
                .aliases("X")
                .executes(ctx -> { })
                .build();
            assertThrows(IllegalArgumentException.class,
                () -> TypedCommand.builder("punish")
                    .subcommand(ban)
                    .subcommand(kick)
                    .build());
        }

        @Test
        @DisplayName("null／空別名 → 建構時拒絕")
        void nullOrEmptyAlias_rejected() {
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .aliases("b", "")
                    .executes(ctx -> { })
                    .build());
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .aliases("b", null)
                    .executes(ctx -> { })
                    .build());
        }
    }

    @Nested
    @DisplayName("回歸：無別名行為不變")
    class NoAliasRegression {

        @Test
        @DisplayName("未設別名時為空清單，主名照常執行")
        void noAliases_emptyList_primaryWorks() {
            AtomicReference<String> seen = new AtomicReference<>();
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TypedSubCommand sub = TypedSubCommand.builder("ban")
                .argument(targetArg)
                .executes(ctx -> seen.set(ctx.get(targetArg)))
                .build();
            assertTrue(sub.aliases().isEmpty());
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(sub)
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "punish", List.of("ban", "Steve"));
            assertEquals("Steve", seen.get());
        }

        @Test
        @DisplayName("根指令既有別名機制不受影響")
        void rootAliases_unchanged() {
            AtomicReference<String> seen = new AtomicReference<>();
            TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
            TypedCommand cmd = TypedCommand.builder("punish")
                .aliases("p")
                .subcommand(TypedSubCommand.builder("ban")
                    .argument(targetArg)
                    .executes(ctx -> seen.set(ctx.get(targetArg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "p", List.of("ban", "Steve"));
            assertEquals("Steve", seen.get());
        }
    }

    @Nested
    @DisplayName("語系正規化")
    class LocaleNormalization {

        @Test
        @DisplayName("預設語系為土耳其語時，含大寫 I 的別名兩路徑一致")
        void turkishLocale_aliasWithCapitalI_bothPaths() throws Exception {
            Locale previous = Locale.getDefault();
            Locale.setDefault(Locale.forLanguageTag("tr"));
            try {
                AtomicReference<String> seen = new AtomicReference<>();
                TestArgs.NameArg targetArg = new TestArgs.NameArg("target");
                TypedCommand cmd = TypedCommand.builder("punish")
                    .subcommand(TypedSubCommand.builder("ban")
                        .aliases("BIG")
                        .argument(targetArg)
                        .executes(ctx -> seen.set(ctx.get(targetArg)))
                        .build())
                    .build();
                registry.register(cmd.toCommandSpec());
                // 傳統路徑：大小寫兩種輸入都到同一子指令（預設語系下
                // "BIG" 的小寫在土耳其語是 "bıg"，必須以 Locale.ROOT 比對）。
                registry.dispatch(console(), "punish", List.of("big", "Steve"));
                assertEquals("Steve", seen.get());
                seen.set(null);
                registry.dispatch(console(), "punish", List.of("BIG", "Alex"));
                assertEquals("Alex", seen.get());
                // Brigadier 路徑：樹字面不受預設語系影響。
                stubSource();
                AtomicReference<List<String>> delegated = new AtomicReference<>();
                CommandDispatcher<CommandSourceStack> dispatcher =
                    new CommandDispatcher<>();
                dispatcher.getRoot().addChild(cmd.toBrigadierNode(
                    new TestArgs.TestTypes(),
                    (source, label, args) -> delegated.set(List.copyOf(args))));
                dispatcher.execute("punish big Steve", stack);
                assertEquals(List.of("big", "Steve"), delegated.get());
                registry.dispatch(console(), "punish", delegated.get());
                assertEquals("Steve", seen.get());
            } finally {
                Locale.setDefault(previous);
            }
        }
    }
}
