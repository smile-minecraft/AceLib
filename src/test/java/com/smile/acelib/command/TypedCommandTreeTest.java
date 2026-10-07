package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
        @DisplayName("固定選項編譯為 literal 分支（Geyser 可見結構），而非 argument 節點")
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
            for (var grandchild : mode.getChildren()) {
                assertTrue(grandchild instanceof LiteralCommandNode,
                    "固定選項必須是 literal 節點（基岩可見），不可是 argument；實際: "
                        + grandchild.getClass());
                options.add(((LiteralCommandNode<?>) grandchild).getLiteral());
            }
            assertTrue(options.contains("silent") && options.contains("public"),
                "應含 silent/public 分支；實際: " + options);
            long argNodes = mode.getChildren().stream()
                .filter(c -> c instanceof ArgumentCommandNode).count();
            assertEquals(0, argNodes, "固定選項下不應有 argument 節點");
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
        @DisplayName("固定選項非法值 → Brigadier 解析錯誤（字面不匹配）")
        void fixedOptionMismatch_parseError() {
            stubSource();
            AtomicReference<String> dispatched = new AtomicReference<>();
            TypedCommand cmd = sampleCommand(dispatched);
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(types, (s, label, a) -> {
                    dispatched.set(label + (a.isEmpty() ? "" : " " + String.join(" ", a)));
                }));
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("punish mode loud", stack));
            assertEquals(null, dispatched.get());
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
}
