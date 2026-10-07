package com.example.acelibcmdprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 探針案例目錄完整性測試。
 *
 * <p>案例目錄是維護者在 Paper／Folia 上實際執行的清單，因此它的穩定性
 * 本身就是契約：id 不可重複、每種型別化引數都必須有解析與錯誤案例、
 * 生命週期案例必須齊全。</p>
 */
@DisplayName("指令探針案例目錄")
class CommandProbeCasesTest {

    @Test
    @DisplayName("案例 id 唯一且非空")
    void caseIdsAreUniqueAndNonEmpty() {
        Set<String> seen = new HashSet<>();
        for (ProbeCase probeCase : CommandProbeCases.all()) {
            assertNotNull(probeCase.id());
            assertTrue(!probeCase.id().isBlank(), "案例 id 不可為空");
            assertTrue(seen.add(probeCase.id()), "案例 id 重複：" + probeCase.id());
        }
    }

    @Test
    @DisplayName("每個案例都有分組、輸入與預期觀察")
    void everyCaseIsComplete() {
        for (ProbeCase probeCase : CommandProbeCases.all()) {
            assertNotNull(probeCase.group(), "案例缺少分組：" + probeCase.id());
            assertNotNull(probeCase.input(), "案例缺少輸入：" + probeCase.id());
            assertNotNull(probeCase.expectation(), "案例缺少預期觀察：" + probeCase.id());
            assertTrue(!probeCase.expectation().isBlank(),
                "案例預期觀察不可為空：" + probeCase.id());
        }
    }

    @Test
    @DisplayName("八種型別化引數都有解析案例")
    void everyArgumentTypeHasParseCase() {
        List<ProbeCase> parseCases = CommandProbeCases.all().stream()
            .filter(c -> CommandProbeCases.GROUP_PARSE.equals(c.group()))
            .toList();
        List<String> inputs = parseCases.stream().map(ProbeCase::input).toList();
        assertTrue(inputs.contains("parse Steve"), "缺少玩家解析案例");
        assertTrue(inputs.contains("parse-offline Steve"), "缺少離線玩家解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-int ")),
            "缺少整數解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-double ")),
            "缺少小數解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-duration ")),
            "缺少時間長度解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-world ")),
            "缺少世界解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-mode ")),
            "缺少列舉解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-fixed ")),
            "缺少固定選項解析案例");
        assertTrue(inputs.stream().anyMatch(i -> i.startsWith("parse-material ")),
            "缺少材質解析案例");
    }

    @Test
    @DisplayName("錯誤案例涵蓋溢位、範圍與未知目標")
    void errorCasesCoverOverflowRangeAndUnknownTargets() {
        Set<String> ids = CommandProbeCases.all().stream()
            .filter(c -> CommandProbeCases.GROUP_ERROR.equals(c.group()))
            .map(ProbeCase::id)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains("err-int-overflow"), "缺少 int 溢位案例");
        assertTrue(ids.contains("err-double-nan"), "缺少 NaN 案例");
        assertTrue(ids.contains("err-duration-overflow"), "缺少時間溢位案例");
        assertTrue(ids.contains("err-int-below-min"), "缺少下限案例");
        assertTrue(ids.contains("err-int-above-max"), "缺少上限案例");
        assertTrue(ids.contains("err-world-unknown"), "缺少未知世界案例");
        assertTrue(ids.contains("err-material-unknown"), "缺少未知材質案例");
        assertTrue(ids.contains("err-offline-unknown"), "缺少從未上線玩家案例");
        assertTrue(ids.contains("err-player-offline"), "缺少離線玩家案例");
    }

    @Test
    @DisplayName("補全案例涵蓋基岩可見與不可見兩類")
    void completeCasesCoverBothBedrockVisibilityClasses() {
        List<ProbeCase> completeCases = CommandProbeCases.all().stream()
            .filter(c -> CommandProbeCases.GROUP_COMPLETE.equals(c.group()))
            .toList();
        assertTrue(completeCases.stream()
                .anyMatch(c -> c.expectation().contains("基岩版應可見")),
            "缺少基岩可見（固定選項 literal）的補全案例");
        assertTrue(completeCases.stream()
                .anyMatch(c -> c.expectation().contains("基岩版不可見")),
            "缺少基岩不可見（伺服器建議）的補全案例");
    }

    @Test
    @DisplayName("生命週期案例涵蓋重複註冊與 shutdown 殘留")
    void lifecycleCasesCoverResidueChecks() {
        List<String> inputs = CommandProbeCases.all().stream()
            .filter(c -> CommandProbeCases.GROUP_LIFECYCLE.equals(c.group()))
            .map(ProbeCase::input)
            .toList();
        assertTrue(inputs.contains("lifecycle re-register"), "缺少重複註冊案例");
        assertTrue(inputs.contains("lifecycle shutdown"), "缺少 shutdown 殘留案例");
        assertTrue(inputs.contains("lifecycle status"), "缺少 status 案例");
    }

    @Test
    @DisplayName("parse-world 案例使用合法的預設維度名 overworld（不是 world）")
    void parseWorldCaseUsesRealDimensionName() {
        // 實機教訓：'world' 不是合法的維度名稱，預設維度是 'overworld'。
        // 案例字串寫錯會讓解析案例在實機上必然失敗，卻在單元測試裡看起來正常。
        ProbeCase worldCase = CommandProbeCases.byId("parse-world");
        assertNotNull(worldCase, "缺少 parse-world 案例");
        assertEquals("parse-world overworld", worldCase.input(),
            "parse-world 案例必須使用 overworld（預設維度名）");
    }

    @Test
    @DisplayName("byId 對未知 id 回 null（不拋例外）")
    void byIdReturnsNullForUnknown() {
        assertNull(CommandProbeCases.byId("no-such-case"));
        assertNull(CommandProbeCases.byId(null));
        assertEquals("parse-player", CommandProbeCases.byId("parse-player").id());
    }
}