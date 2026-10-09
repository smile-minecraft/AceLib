package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Brigadier 樹結構測試（Slice 3）：以<strong>真實</strong>
 * {@code com.mojang.brigadier} 節點驗證固定選項編譯為 literal 分支、
 * 權限 {@code requires}、補全與執行委派。
 *
 * <p>vanilla 引數型別需伺服器 runtime（ServiceLoader），此處以
 * {@link TestArgs.TestTypes} 純替身覆蓋結構；各引數的真實 vanilla 型別
 * 對應由生產 {@code PaperArgumentTypes} 負責，另以實機探針驗證。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("型別化指令 Brigadier 樹")
class TypedCommandTreeTest {

    @Mock
    CommandSourceStack stack;
    @Mock
    CommandSender sender;

    private ArgumentTypeFactory types;

    @BeforeEach
    void setUp() {
        types = new TestArgs.TestTypes();
    }

    private void stubSource() {
        when(stack.getSender()).thenReturn(sender);
    }

    private TypedCommand sampleCommand(AtomicReference<String> dispatched) {
        TestArgs.NameArg target = new TestArgs.NameArg("target");
        return TypedCommand.builder("punish")
            .description("處分指令")
            .aliases("p")
            .subcommand(TypedSubCommand.builder("ban")
                .description("停權")
                .permission("punish.ban")
                .argument(target)
                .argument(new TestArgs.LongArg("length"))
                .executes(ctx -> { })
                .build())
            .subcommand(TypedSubCommand.builder("mode")
                .description("模式")
                .argument(new TestArgs.FixedArg("mode", List.of("silent", "public")))
                .executes(ctx -> { })
                .build())
            .build();
    }

    @Nested
    @DisplayName("樹拓撲（真實 Brigadier 節點）")
    class Topology {

        @Test
        @DisplayName("根 literal 名、別名由 registrar 層處理；子指令為 literal 分支")
        void rootAndSubLiterals() {
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            LiteralCommandNode<CommandSourceStack> node =
                cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                });
            assertEquals("punish", node.getLiteral());
            Collection<?> children = node.getChildren();
            List<String> literals = new ArrayList<>();
            for (Object child : children) {
                literals.add(((LiteralCommandNode<?>) child).getLiteral());
            }
            assertTrue(literals.contains("ban"), "應含 ban 分支；實際: " + literals);
            assertTrue(literals.contains("mode"), "應含 mode 分支；實際: " + literals);
        }

        @Test
        @DisplayName("固定選項編譯為 literal 分支＋一個錯誤後備 argument 節點")
        void fixedOptions_compileToLiterals() {
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            LiteralCommandNode<CommandSourceStack> node =
                cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                });
            LiteralCommandNode<CommandSourceStack> mode = null;
            for (var child : node.getChildren()) {
                if (child instanceof LiteralCommandNode<?> literal
                    && literal.getLiteral().equals("mode")) {
                    @SuppressWarnings("unchecked")
                    LiteralCommandNode<CommandSourceStack> cast =
                        (LiteralCommandNode<CommandSourceStack>) child;
                    mode = cast;
                }
            }
            assertTrue(mode != null, "應有 mode 分支");
            List<String> options = new ArrayList<>();
            int argNodes = 0;
            for (var grandchild : mode.getChildren()) {
                if (grandchild instanceof LiteralCommandNode) {
                    options.add(((LiteralCommandNode<?>) grandchild).getLiteral());
                } else if (grandchild instanceof ArgumentCommandNode) {
                    argNodes++;
                } else {
                    assertTrue(false, "固定選項下只有 literal 與後備節點；實際: "
                        + grandchild.getClass());
                }
            }
            assertTrue(options.contains("silent") && options.contains("public"),
                "應含 silent/public 分支；實際: " + options);
            assertEquals(1, argNodes, "固定選項下應恰有一個錯誤後備節點");
        }

        @Test
        @DisplayName("開放式引數編譯為 argument 節點（名稱一致）")
        void openArgs_compileToArgumentNodes() {
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            LiteralCommandNode<CommandSourceStack> node =
                cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                });
            LiteralCommandNode<CommandSourceStack> ban = null;
            for (var child : node.getChildren()) {
                if (child instanceof LiteralCommandNode<?> literal
                    && literal.getLiteral().equals("ban")) {
                    @SuppressWarnings("unchecked")
                    LiteralCommandNode<CommandSourceStack> cast =
                        (LiteralCommandNode<CommandSourceStack>) child;
                    ban = cast;
                }
            }
            assertTrue(ban != null, "應有 ban 分支");
            List<String> argNames = new ArrayList<>();
            for (var grandchild : ban.getChildren()) {
                assertTrue(grandchild instanceof ArgumentCommandNode,
                    "開放式引數必須是 argument 節點；實際: " + grandchild.getClass());
                argNames.add(((ArgumentCommandNode<?, ?>) grandchild).getName());
            }
            assertEquals(List.of("target"), argNames,
                "ban 下第一層應為 target 引數；實際: " + argNames);
        }
    }

    @Nested
    @DisplayName("權限 requires 與執行委派（真實 dispatcher）")
    class Dispatch {

        @Test
        @DisplayName("有權限執行 → 委派收到完整原始輸入（含根標籤）")
        void execute_delegatesWithRawInput() throws Exception {
            stubSource();
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                }));
            when(sender.hasPermission("punish.ban")).thenReturn(true);
            int result = dispatcher.execute("punish ban Steve 100", stack);
            assertEquals(1, result);
            assertEquals("punish ban Steve 100", dispatched.get());
        }

        @Test
        @DisplayName("無權限 → requires 拒絕（Brigadier 標準錯誤，不進入委派）")
        void noPermission_requiresRejects() {
            stubSource();
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                }));
            when(sender.hasPermission("punish.ban")).thenReturn(false);
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("punish ban Steve 100", stack));
            assertEquals(null, dispatched.get());
        }

        @Test
        @DisplayName("固定選項非法值 → 後備節點承接：ACELIB-CMD-015 自訂訊息，handler 不執行")
        void fixedOptionMismatch_fallbackToLocalizedError() throws Exception {
            stubSource();
            AtomicReference<Boolean> ran = new AtomicReference<>(false);
            InvalidArgumentLocalizeTest.RecordingMessages messages =
                new InvalidArgumentLocalizeTest.RecordingMessages();
            messages.reply = "自訂選項錯誤";
            CommandArgument<String> modeArg = Arguments.fixed("mode", "buy", "sell");
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("mode")
                    .argument(modeArg)
                    .messages(messages)
                    .executes(ctx -> ran.set(true))
                    .build())
                .build();
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(cmd.toCommandSpec());
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types,
                (source, label, args) -> registry.dispatch(console, label, args)));
            dispatcher.execute("punish mode loud", stack);
            assertEquals(false, ran.get(), "非法值不得進入 handler");
            CommandException err = sink.lastError();
            assertTrue(err != null, "應有錯誤輸出");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());
            assertEquals("自訂選項錯誤", err.getMessage());
        }

        @Test
        @DisplayName("真實補全：固定選項由 literal 結構提供")
        void suggestions_fromLiteralStructure() throws Exception {
            stubSource();
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                }));
            var parse = dispatcher.parse("punish mode ", stack);
            var suggestions = dispatcher.getCompletionSuggestions(parse).get();
            List<String> texts = suggestions.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(texts.contains("silent") && texts.contains("public"),
                "補全應含固定選項；實際: " + texts);
        }
    }

    @Nested
    @DisplayName("錯誤後備節點")
    class Fallback {

        private TypedCommand modeCommand(AtomicReference<String> seen,
                                         AtomicReference<Integer> runs) {
            CommandArgument<String> modeArg = Arguments.fixed("mode", "buy", "sell");
            return TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("mode")
                    .argument(modeArg)
                    .executes(ctx -> {
                        runs.set(runs.get() + 1);
                        seen.set(ctx.get(modeArg));
                    })
                    .build())
                .build();
        }

        @Test
        @DisplayName("合法值走 literal 分支：執行恰一次，型別值正確")
        void legalValue_literalBranchRunsOnce() throws Exception {
            stubSource();
            AtomicReference<String> seen = new AtomicReference<>();
            AtomicReference<Integer> runs = new AtomicReference<>(0);
            TypedCommand cmd = modeCommand(seen, runs);
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(cmd.toCommandSpec());
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types,
                (source, label, args) -> registry.dispatch(console, label, args)));
            dispatcher.execute("punish mode buy", stack);
            assertEquals("buy", seen.get());
            assertEquals(1, runs.get(), "合法值只應執行一次（後備不得搶分支）");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("補全不受後備節點污染：恰為兩個選項")
        void suggestions_notPollutedByFallback() throws Exception {
            stubSource();
            TypedCommand cmd = modeCommand(new AtomicReference<>(),
                new AtomicReference<>(0));
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types, (s, label, a) -> { }));
            var parse = dispatcher.parse("punish mode ", stack);
            var suggestions = dispatcher.getCompletionSuggestions(parse).get();
            List<String> texts = suggestions.getList().stream()
                .map(s -> s.getText()).toList();
            assertEquals(List.of("buy", "sell"), texts,
                "補全應恰為 literal 選項；實際: " + texts);
        }

        @Test
        @DisplayName("固定選項後接引數：打錯值進後備給引數錯誤，而非平台錯誤")
        void fixedFollowedByArg_wrongValueGivesArgumentError() throws Exception {
            stubSource();
            AtomicReference<String> seenMode = new AtomicReference<>();
            AtomicReference<Integer> seenAmount = new AtomicReference<>();
            CommandArgument<String> modeArg = Arguments.fixed("mode", "buy", "sell");
            CommandArgument<Integer> amountArg = Arguments.intArg("amount", 1, 64);
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("trade")
                    .argument(modeArg)
                    .argument(amountArg)
                    .executes(ctx -> {
                        seenMode.set(ctx.get(modeArg));
                        seenAmount.set(ctx.get(amountArg));
                    })
                    .build())
                .build();
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(cmd.toCommandSpec());
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types,
                (source, label, args) -> registry.dispatch(console, label, args)));
            dispatcher.execute("punish trade loud 5", stack);
            CommandException err = sink.lastError();
            assertTrue(err != null, "應有錯誤輸出");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());
            assertTrue(seenMode.get() == null, "非法值不得進入 handler");
            dispatcher.execute("punish trade buy 5", stack);
            assertEquals("buy", seenMode.get());
            assertEquals(5, seenAmount.get());
        }

        @Test
        @DisplayName("後備節點型別為 Paper CustomArgumentType，原生為 word 型別")
        void fallbackType_isPaperCustomArgumentType() {
            TypedCommand cmd = modeCommand(new AtomicReference<>(),
                new AtomicReference<>(0));
            LiteralCommandNode<CommandSourceStack> node =
                cmd.toBrigadierNode(types, (s, label, a) -> { });
            LiteralCommandNode<CommandSourceStack> mode = null;
            for (var child : node.getChildren()) {
                if (child instanceof LiteralCommandNode<?> literal
                    && literal.getLiteral().equals("mode")) {
                    @SuppressWarnings("unchecked")
                    LiteralCommandNode<CommandSourceStack> cast =
                        (LiteralCommandNode<CommandSourceStack>) child;
                    mode = cast;
                }
            }
            assertTrue(mode != null, "應有 mode 分支");
            com.mojang.brigadier.arguments.ArgumentType<?> fallbackType = null;
            for (var grandchild : mode.getChildren()) {
                if (grandchild instanceof ArgumentCommandNode<?, ?> argNode) {
                    fallbackType = argNode.getType();
                }
            }
            assertTrue(fallbackType != null, "應有後備節點");
            // 平台註冊只接受 CustomArgumentType（普通 CommandDispatcher
            // 不經轉換所以測不出，平台轉換本身由實機註冊驗證；此處斷言型別契約）。
            assertTrue(fallbackType instanceof CustomArgumentType,
                "後備型別須為 Paper CustomArgumentType；實際: "
                    + fallbackType.getClass());
            CustomArgumentType<?, ?> custom =
                (CustomArgumentType<?, ?>) fallbackType;
            assertTrue(custom.getNativeType() instanceof StringArgumentType,
                "原生型別須為 word；實際: "
                    + custom.getNativeType().getClass());
        }

        @Test
        @DisplayName("大寫變體經後備進相容層：兩路徑都成功且拿 canonical 值")
        void upperCaseValue_fallbackToCompatSuccess() throws Exception {
            stubSource();
            AtomicReference<String> seen = new AtomicReference<>();
            AtomicReference<Integer> runs = new AtomicReference<>(0);
            TypedCommand cmd = modeCommand(seen, runs);
            CommandRegistryTest.RecordingReplySink sink =
                new CommandRegistryTest.RecordingReplySink();
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(cmd.toCommandSpec());
            CommandRegistryTest.TestSender console =
                new CommandRegistryTest.TestSender("Console", false);
            // 傳統路徑。
            registry.dispatch(console, "punish", List.of("mode", "BUY"));
            assertEquals("buy", seen.get());
            // Brigadier 路徑：字面 "buy" 對不上 "BUY"，後備承接後相容層
            // 大小寫不敏感解析成功。
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types,
                (source, label, args) -> registry.dispatch(console, label, args)));
            dispatcher.execute("punish mode BUY", stack);
            assertEquals("buy", seen.get());
            assertEquals(2, runs.get(), "兩次執行都應進入 handler");
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }
    }
}
