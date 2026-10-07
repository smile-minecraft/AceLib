package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 型別化引數解析測試（Slice 1：declarative parse / validate / suggest）。
 *
 * <p>八種引數型別各自覆蓋：正常解析、錯誤分類（{@link CommandErrorKind} +
 * {@code ACELIB-CMD-NNN}）、邊界（範圍上下限、溢位、空字串、空白）與
 * 伺服器端補全前綴過濾。需要 Bukkit Server 的型別（玩家、離線玩家、世界）
 * 另見 {@code TypedArgumentsBukkitTest}（MockBukkit 環境）。</p>
 */
@DisplayName("型別化引數解析")
class TypedArgumentsTest {

    // -----------------------------------------------------------------
    // 整數範圍
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("整數範圍引數")
    class IntArgument {

        @Test
        @DisplayName("範圍內整數正常解析")
        void inRange_parses() {
            CommandArgument<Integer> arg = Arguments.intArg("amount", 1, 64);
            assertEquals("amount", arg.name());
            assertEquals(32, arg.parse("32"));
            assertEquals(1, arg.parse("1"));
            assertEquals(64, arg.parse("64"));
        }

        @Test
        @DisplayName("超出上下限拋 INVALID_ARGUMENT（ACELIB-CMD-015）")
        void outOfRange_invalidArgument() {
            CommandArgument<Integer> arg = Arguments.intArg("amount", 1, 64);
            CommandException low = assertThrows(CommandException.class, () -> arg.parse("0"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, low.getKind());
            assertEquals("ACELIB-CMD-015", low.getCode());
            CommandException high = assertThrows(CommandException.class, () -> arg.parse("65"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, high.getKind());
        }

        @Test
        @DisplayName("非數字與 int 溢位拋 INVALID_ARGUMENT（不 wrap）")
        void nonNumericAndOverflow_invalidArgument() {
            CommandArgument<Integer> arg = Arguments.intArg("amount", 1, 64);
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("abc")).getKind());
            // 超出 int 範圍：必須是錯誤，不可靜默 wrap 成負數
            CommandException overflow =
                assertThrows(CommandException.class, () -> arg.parse("99999999999999999999"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, overflow.getKind());
            assertEquals("ACELIB-CMD-015", overflow.getCode());
        }

        @Test
        @DisplayName("min 大於 max 建構時即拋 IllegalArgumentException")
        void minGreaterThanMax_throwsAtBuild() {
            assertThrows(IllegalArgumentException.class,
                () -> Arguments.intArg("amount", 64, 1));
        }

        @Test
        @DisplayName("開放式引數不對基岩可見（bedrockVisible=false）")
        void openArgument_notBedrockVisible() {
            assertTrue(!Arguments.intArg("amount", 1, 64).bedrockVisible());
        }
    }

    // -----------------------------------------------------------------
    // 小數範圍
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("小數範圍引數")
    class DoubleArgument {

        @Test
        @DisplayName("範圍內小數正常解析")
        void inRange_parses() {
            CommandArgument<Double> arg = Arguments.doubleArg("ratio", 0.0, 1.0);
            assertEquals(0.5, arg.parse("0.5"));
            assertEquals(0.0, arg.parse("0"));
            assertEquals(1.0, arg.parse("1.0"));
        }

        @Test
        @DisplayName("超出範圍、NaN、無限大拋 INVALID_ARGUMENT")
        void outOfRangeNanInfinite_invalidArgument() {
            CommandArgument<Double> arg = Arguments.doubleArg("ratio", 0.0, 1.0);
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("1.5")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("NaN")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("Infinity")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("xyz")).getKind());
        }
    }

    // -----------------------------------------------------------------
    // 時間長度（vanilla time 語法：ticks，d/s/t 後綴）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("時間長度引數")
    class DurationArgument {

        @Test
        @DisplayName("純數字視為 ticks；d/s/t 後綴換算正確")
        void vanillaGrammar_convertsToTicks() {
            CommandArgument<Long> arg = Arguments.duration("length");
            assertEquals(100L, arg.parse("100"));
            assertEquals(1L, arg.parse("1t"));
            assertEquals(20L, arg.parse("1s"));
            assertEquals(24000L, arg.parse("1d"));
            assertEquals(30L, arg.parse("1.5s"));
        }

        @Test
        @DisplayName("不支援的單位（h/m）與非法格式拋 INVALID_ARGUMENT")
        void unsupportedUnit_invalidArgument() {
            CommandArgument<Long> arg = Arguments.duration("length");
            // 與客戶端 vanilla time 語法一致：h/m 在客戶端即被拒絕，
            // 伺服器端同樣拒絕，避免兩端語法不一致
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("1h")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("30m")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("abc")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("")).getKind());
        }

        @Test
        @DisplayName("換算溢位拋 INVALID_ARGUMENT（不 wrap、不走 double 精度遺失）")
        void overflow_invalidArgument() {
            CommandArgument<Long> arg = Arguments.duration("length");
            CommandException overflow =
                assertThrows(CommandException.class, () -> arg.parse("99999999999999999999d"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, overflow.getKind());
            assertEquals("ACELIB-CMD-015", overflow.getCode());
        }

        @Test
        @DisplayName("負數與空白字元拋 INVALID_ARGUMENT（單 token 不變條件）")
        void negativeAndWhitespace_invalidArgument() {
            CommandArgument<Long> arg = Arguments.duration("length");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("-5")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("1 d")).getKind());
        }
    }

    // -----------------------------------------------------------------
    // 列舉（固定選項 → 基岩可見 literal 分支）
    // -----------------------------------------------------------------

    enum Mode {
        SILENT,
        PUBLIC
    }

    @Nested
    @DisplayName("列舉引數")
    class EnumArgument {

        @Test
        @DisplayName("大小寫不敏感解析；回傳宣告常數")
        void caseInsensitive_parses() {
            CommandArgument<Mode> arg = Arguments.enumArg("mode", Mode.class);
            assertEquals(Mode.SILENT, arg.parse("silent"));
            assertEquals(Mode.SILENT, arg.parse("SILENT"));
            assertEquals(Mode.PUBLIC, arg.parse("Public"));
        }

        @Test
        @DisplayName("未知值拋 INVALID_ARGUMENT 並攜帶有效選項")
        void unknown_invalidArgumentWithOptions() {
            CommandArgument<Mode> arg = Arguments.enumArg("mode", Mode.class);
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("loud"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
            assertTrue(ex.getVars().containsKey("options"),
                "錯誤 vars 應攜帶有效選項；實際: " + ex.getVars());
        }

        @Test
        @DisplayName("固定選項對基岩可見（bedrockVisible=true），補全回全部選項")
        void fixedOptions_bedrockVisible() {
            CommandArgument<Mode> arg = Arguments.enumArg("mode", Mode.class);
            assertTrue(arg.bedrockVisible());
            List<String> all = arg.suggest("");
            assertTrue(all.contains("silent") && all.contains("public"),
                "補全應含全部選項；實際: " + all);
            List<String> filtered = arg.suggest("s");
            assertTrue(filtered.contains("silent") && !filtered.contains("public"),
                "補全應依前綴過濾；實際: " + filtered);
        }
    }

    // -----------------------------------------------------------------
    // 固定字串選項
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("固定字串選項引數")
    class FixedStrings {

        @Test
        @DisplayName("解析回傳宣告形式；大小寫不敏感")
        void parses_canonicalForm() {
            CommandArgument<String> arg = Arguments.fixed("action", "allow", "deny");
            assertEquals("allow", arg.parse("allow"));
            assertEquals("deny", arg.parse("DENY"));
        }

        @Test
        @DisplayName("未知值拋 INVALID_ARGUMENT；空選項建構即失敗")
        void unknown_invalidArgument_emptyOptions_rejected() {
            CommandArgument<String> arg = Arguments.fixed("action", "allow", "deny");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class, () -> arg.parse("maybe")).getKind());
            assertThrows(IllegalArgumentException.class, () -> Arguments.fixed("action"));
        }
    }

    // -----------------------------------------------------------------
    // 材質（無需 Server 的部分）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("材質引數")
    class MaterialArgument {

        @Test
        @DisplayName("大小寫與底線形式皆可解析")
        void parses_caseInsensitive() {
            CommandArgument<Material> arg = Arguments.material("mat");
            assertEquals(Material.DIAMOND_SWORD, arg.parse("diamond_sword"));
            assertEquals(Material.DIAMOND_SWORD, arg.parse("DIAMOND_SWORD"));
        }

        @Test
        @DisplayName("不存在材質拋 INVALID_ARGUMENT")
        void unknown_invalidArgument() {
            CommandArgument<Material> arg = Arguments.material("mat");
            CommandException ex =
                assertThrows(CommandException.class, () -> arg.parse("not_a_material_xyz"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, ex.getKind());
            assertEquals("ACELIB-CMD-015", ex.getCode());
        }

        @Test
        @DisplayName("補全依前綴過濾材質名")
        void suggest_filtersByPrefix() {
            CommandArgument<Material> arg = Arguments.material("mat");
            List<String> result = arg.suggest("diamond_");
            assertTrue(!result.isEmpty(), "diamond_ 前綴應有補全");
            assertTrue(result.contains("diamond_sword"), "應含 diamond_sword；實際: " + result);
            assertTrue(result.stream().allMatch(s -> s.startsWith("diamond_")),
                "全部結果須符合前綴；實際: " + result);
        }
    }

    // -----------------------------------------------------------------
    // 空白字元不變條件（Brigadier 委派 lossless-split 前提）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("單 token 不變條件")
    class SingleToken {

        @Test
        @DisplayName("含空白的輸入一律拒絕（各開放式引數）")
        void whitespace_rejected() {
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class,
                    () -> Arguments.intArg("n", 0, 10).parse("1 2")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class,
                    () -> Arguments.material("m").parse("diamond sword")).getKind());
            assertEquals(CommandErrorKind.INVALID_ARGUMENT,
                assertThrows(CommandException.class,
                    () -> Arguments.enumArg("e", Mode.class).parse("si lent")).getKind());
        }

        @Test
        @DisplayName("null 輸入拋 NullPointerException（契約）")
        void nullInput_npe() {
            assertThrows(NullPointerException.class,
                () -> Arguments.intArg("n", 0, 10).parse(null));
        }
    }
}
