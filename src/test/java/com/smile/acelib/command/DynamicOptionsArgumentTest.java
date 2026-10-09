package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 動態選項引數測試（第三階段第五項：供應函式引數）。
 *
 * <p>同一個引數實例同時服務兩條執行路徑：傳統路徑以 {@code parse}
 * 把原始字串轉為宣告形式；Brigadier 路徑以 {@code stringWord} 節點承接
 * 單 token，再以 {@code resolve} 取出原始字串走同一個比對。每次解析與補全
 * 都重新呼叫供應函式，執行期增刪立刻反映，不重建指令樹。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("動態選項引數（供應函式）")
class DynamicOptionsArgumentTest {

    @Mock
    CommandSourceStack stack;

    /** 原因鏈是否含指定實例（紀錄的 thrown 本身或其任一原因）。 */
    private static boolean chainContains(Throwable thrown, Throwable needle) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current == needle) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 工廠契約
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("工廠契約")
    class FactoryContract {

        @Test
        @DisplayName("名稱如實回傳；開放式引數（非固定選項、基岩不可見）")
        void openArgumentDefaults() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("Easy", "Hard"));
            assertEquals("mode", arg.name());
            assertFalse(arg.isFixedOptions());
            assertFalse(arg.bedrockVisible());
            assertTrue(arg.fixedOptions().isEmpty());
        }

        @Test
        @DisplayName("空值與空字串在建構時即拒絕")
        void nullAndEmpty_rejectedAtBuild() {
            Supplier<List<String>> supplier = () -> List.of("a");
            assertThrows(NullPointerException.class,
                () -> Arguments.dynamic(null, supplier));
            assertThrows(IllegalArgumentException.class,
                () -> Arguments.dynamic("", supplier));
            assertThrows(NullPointerException.class,
                () -> Arguments.dynamic("mode", null));
        }

        @Test
        @DisplayName("parse／suggest／brigadierType／resolve 的 null 契約")
        void nullInputs_rejected() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("a"));
            assertThrows(NullPointerException.class, () -> arg.parse(null));
            assertThrows(NullPointerException.class,
                () -> arg.parse(null, DefaultCommandMessages.instance()));
            assertThrows(NullPointerException.class, () -> arg.suggest(null));
            assertThrows(NullPointerException.class, () -> arg.brigadierType(null));
            assertThrows(NullPointerException.class, () -> arg.resolve(null));
        }

        @Test
        @DisplayName("Brigadier 型別是 stringWord（單 token 開放式引數）")
        void brigadierType_isStringWord() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("a"));
            assertTrue(arg.brigadierType(new TestArgs.TestTypes())
                instanceof StringArgumentType,
                "動態選項是開放式引數節點，應為 stringWord");
        }
    }

    // -----------------------------------------------------------------
    // 傳統路徑解析
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("傳統路徑解析")
    class TraditionalParse {

        @Test
        @DisplayName("大小寫不敏感，回傳宣告形式")
        void caseInsensitive_returnsCanonical() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("Easy", "Hard"));
            assertEquals("Easy", arg.parse("easy"));
            assertEquals("Hard", arg.parse("HARD"));
            assertEquals("Easy", arg.parse("Easy"));
        }

        @Test
        @DisplayName("集合外的值拋 INVALID_ARGUMENT（ACELIB-CMD-015）")
        void unknownValue_invalidArgument() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("Easy", "Hard"));
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("nightmare"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("錯誤訊息來自傳入的訊息表")
        void errorMessage_fromTable() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("Easy"));
            CommandMessages stub = new CommandMessages() {
                @Override
                public String invalidArgument(String name, String value, String reason) {
                    return "動態錯誤：[" + name + "] 不接受 '" + value + "'";
                }
            };
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("nope", stub));
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertTrue(ex.getMessage().contains("動態錯誤：[mode] 不接受 'nope'"),
                "錯誤訊息應來自傳入的訊息表；實際: " + ex.getMessage());
        }

        @Test
        @DisplayName("含空白的輸入被拒（單 token 不變條件）")
        void whitespace_rejected() {
            CommandArgument<String> arg =
                Arguments.dynamic("mode", () -> List.of("Easy"));
            for (String bad : List.of("", " ", "Easy ", "Ea sy", " Easy")) {
                CommandException ex =
                    assertThrows(CommandException.class, () -> arg.parse(bad));
                assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
                assertEquals("ACELIB-CMD-015", ex.getCode());
            }
        }
    }

    // -----------------------------------------------------------------
    // 執行期增刪
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("執行期增刪")
    class LiveUpdates {

        @Test
        @DisplayName("兩次解析之間新增選項，新選項立即可解析（回傳宣告形式）")
        void addedOption_immediatelyParseable() {
            List<String> options = new ArrayList<>(List.of("Easy"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            CommandException before =
                assertThrows(CommandException.class, () -> arg.parse("hard"));
            assertEquals("ACELIB-CMD-015", before.getCode());

            options.add("Hard");
            assertEquals("Hard", arg.parse("HARD"));
        }

        @Test
        @DisplayName("兩次解析之間移除選項，舊選項立即被拒")
        void removedOption_immediatelyRejected() {
            List<String> options = new ArrayList<>(Arrays.asList("Easy", "Hard"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            assertEquals("Easy", arg.parse("easy"));

            options.remove("Easy");
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("補全同樣立即反映（前綴過濾、大小寫不敏感）")
        void suggest_reflectsLiveChanges() {
            List<String> options = new ArrayList<>(List.of("Easy"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            assertEquals(List.of(), arg.suggest("h"));

            options.add("Hard");
            assertEquals(List.of("Hard"), arg.suggest("h"));
            assertEquals(List.of("Hard"), arg.suggest("H"));
            assertEquals(List.of("Easy", "Hard"), arg.suggest(""));

            options.remove("Easy");
            assertEquals(List.of(), arg.suggest("e"));
        }

        @Test
        @DisplayName("供應集合為空時任何值都拒")
        void emptyOptions_rejectsEverything() {
            List<String> options = new ArrayList<>(List.of("Easy"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            assertEquals("Easy", arg.parse("easy"));
            assertEquals(List.of("Easy"), arg.suggest(""));

            options.clear();
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertEquals(List.of(), arg.suggest(""));
        }

        @Test
        @DisplayName("含 null 元素的清單不崩潰且 null 不入選")
        void nullElements_ignored() {
            List<String> options = new ArrayList<>(Arrays.asList("Easy", null, "Hard"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            assertEquals("Easy", arg.parse("easy"));
            assertEquals(List.of("Easy", "Hard"), arg.suggest(""));
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("null"));
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("供應函式拋錯時解析得在地化錯誤、補全回空")
        void supplierThrows_localizedErrorAndEmptySuggest() {
            CommandMessages stub = new CommandMessages() {
                @Override
                public String invalidArgument(String name, String value, String reason) {
                    return "動態錯誤：[" + name + "] 選項不可用";
                }
            };
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> {
                throw new RuntimeException("boom");
            });
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy", stub));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertTrue(ex.getMessage().contains("動態錯誤：[mode] 選項不可用"),
                "供應函式拋錯不得洩漏例外，錯誤訊息應來自訊息表；實際: "
                    + ex.getMessage());
            assertEquals(List.of(), arg.suggest(""));
        }

        @Test
        @DisplayName("供應函式拋錯時解析例外的 cause 為原始例外（管理員可追查）")
        void supplierThrows_parseCauseIsOriginal() {
            RuntimeException boom = new RuntimeException("boom");
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> {
                throw boom;
            });
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertSame(boom, ex.getCause(),
                "解析例外應以原因鏈附上原始例外，否則管理員無法追查供應失敗");
        }

        @Test
        @DisplayName("供應函式拋錯時補全記 WARNING（含引數名與原始例外）")
        void supplierThrows_suggestLogsWarning() {
            RuntimeException boom = new RuntimeException("boom");
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> {
                throw boom;
            });
            Logger logger = Logger.getLogger("AceLib");
            List<LogRecord> records = new ArrayList<>();
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                    // 測試不需要 flush
                }

                @Override
                public void close() {
                    // 測試不需要 close
                }
            };
            logger.addHandler(handler);
            try {
                assertEquals(List.of(), arg.suggest(""),
                    "供應失敗時補全仍回空，不中斷輸入");
            } finally {
                logger.removeHandler(handler);
            }
            List<LogRecord> warnings = records.stream()
                .filter(record -> Level.WARNING.equals(record.getLevel()))
                .toList();
            assertTrue(warnings.stream().anyMatch(record ->
                    record.getMessage().contains("mode")
                        && chainContains(record.getThrown(), boom)),
                "補全路徑的供應失敗應有 WARNING 紀錄（含引數名與原始例外堆疊）；實際: "
                    + warnings.stream().map(LogRecord::getMessage).toList());
        }

        @Test
        @DisplayName("供應函式回傳 null 視為空集合（全拒、補全空）")
        void supplierReturnsNull_treatedAsEmpty() {
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> null);
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertEquals(List.of(), arg.suggest(""));
        }

        @Test
        @DisplayName("複製期間並發修改視為供應失敗（在地化錯誤＋原因可追查、無 CME 外洩）")
        void concurrentModificationDuringCopy_treatedAsSupplierFailure() {
            ConcurrentModificationException simulated =
                new ConcurrentModificationException("simulated");
            List<String> flaky = new AbstractList<>() {
                @Override
                public String get(int index) {
                    if (index == 0) {
                        return "Easy";
                    }
                    throw simulated;
                }

                @Override
                public int size() {
                    return 2;
                }
            };
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> flaky);
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("easy"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertSame(simulated, ex.getCause(),
                "複製期間的並發修改不得以外洩的 CME 呈現，應轉為在地化錯誤並附原因");
            assertEquals(List.of(), arg.suggest(""),
                "複製期間並發修改時補全同樣回空，不中斷輸入");
        }
    }

    // -----------------------------------------------------------------
    // Brigadier 路徑一致性（含整樹不重建）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Brigadier 路徑一致性")
    class BrigadierParity {

        private CommandRegistryTest.RecordingReplySink sink;
        private CommandRegistryImpl registry;
        private CommandRegistryTest.TestSender sender;
        private AtomicReference<String> seen;

        /** 同一個引數實例、同一棵指令樹：註冊一次，供應集合隨後增刪。 */
        private CommandDispatcher<CommandSourceStack> registerOnce(
                CommandArgument<String> arg) {
            sink = new CommandRegistryTest.RecordingReplySink();
            CommandRegistryTest.TestClock clock = new CommandRegistryTest.TestClock(1_000L);
            registry = new CommandRegistryImpl(sink, new CooldownTracker(clock));
            sender = new CommandRegistryTest.TestSender("Steve", true);
            seen = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("quest")
                .description("任務指令")
                .subcommand(TypedSubCommand.builder("set")
                    .description("設定難度")
                    .argument(arg)
                    .executes(ctx -> seen.set(ctx.get(arg)))
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(cmd.toBrigadierNode(new TestArgs.TestTypes(),
                (source, label, args) -> registry.dispatch(sender, label, args)));
            return dispatcher;
        }

        @Test
        @DisplayName("resolve 與 parse 結果一致（走同一個供應集合）")
        void resolve_matchesParse() throws Exception {
            List<String> options = new ArrayList<>(List.of("Easy", "Hard"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            CommandDispatcher<CommandSourceStack> dispatcher = registerOnce(arg);
            dispatcher.execute("quest set easy", stack);
            assertEquals("Easy", seen.get());
            assertEquals(arg.parse("easy"), seen.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("同一子指令實例：供應集合增刪後不重建指令樹即反映")
        void sameInstance_liveChangesWithoutRebuild() throws Exception {
            List<String> options = new ArrayList<>(List.of("Easy"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            CommandDispatcher<CommandSourceStack> dispatcher = registerOnce(arg);

            dispatcher.execute("quest set easy", stack);
            assertEquals("Easy", seen.get());

            // 同一 dispatcher（同一指令樹）不重建，只改供應集合。
            options.add("Hard");
            seen.set(null);
            dispatcher.execute("quest set hard", stack);
            assertEquals("Hard", seen.get());
            assertTrue(sink.errors.isEmpty(), "新增選項應可執行；實際: " + sink.errors);

            options.remove("Easy");
            seen.set(null);
            sink.errors.clear();
            dispatcher.execute("quest set easy", stack);
            assertEquals(null, seen.get(), "已移除的選項不應執行 handler");
            CommandException err = sink.lastError();
            assertTrue(err != null, "已移除的選項應有錯誤輸出");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());
        }

        @Test
        @DisplayName("兩條路徑的補全一致（Brigadier 建議即時反映供應集合）")
        void suggestions_matchTraditional() throws Exception {
            List<String> options = new ArrayList<>(List.of("Easy"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            CommandDispatcher<CommandSourceStack> dispatcher = registerOnce(arg);

            var before = dispatcher
                .getCompletionSuggestions(dispatcher.parse("quest set ", stack)).get();
            List<String> beforeTexts = before.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(beforeTexts.contains("Easy"), "補全應含 Easy；實際: " + beforeTexts);

            options.add("Hard");
            var after = dispatcher
                .getCompletionSuggestions(dispatcher.parse("quest set ", stack)).get();
            List<String> afterTexts = after.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(afterTexts.containsAll(List.of("Easy", "Hard")),
                "同一指令樹不重建，補全應即時反映新增選項；實際: " + afterTexts);
            assertEquals(new java.util.HashSet<>(arg.suggest("")),
                new java.util.HashSet<>(afterTexts),
                "傳統補全與 Brigadier 補全應一致");
        }

        @Test
        @DisplayName("word 字元集外的值：傳統解析成功、Brigadier 路徑無法執行、補全仍列出")
        void wordCharsetLimitation() throws Exception {
            List<String> options = new ArrayList<>(List.of("中文選項"));
            CommandArgument<String> arg = Arguments.dynamic("mode", () -> options);
            assertEquals("中文選項", arg.parse("中文選項"),
                "傳統路徑解析不設字元集限制");
            CommandDispatcher<CommandSourceStack> dispatcher = registerOnce(arg);
            assertThrows(
                com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("quest set 中文選項", stack),
                "stringWord 節點只接受 [0-9A-Za-z_-.+]，字元集外的值到不了 resolve");
            var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("quest set ", stack)).get();
            List<String> texts = suggestions.getList().stream()
                .map(s -> s.getText()).toList();
            assertTrue(texts.contains("中文選項"),
                "補全是伺服器端建議，不受 word 字元集限制；實際: " + texts);
        }
    }
}
