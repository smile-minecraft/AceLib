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
    @DisplayName("補全案例基岩期望與實測一致（建議列不顯示）")
    void completeCasesMatchBedrockReality() {
        List<ProbeCase> completeCases = CommandProbeCases.all().stream()
            .filter(c -> CommandProbeCases.GROUP_COMPLETE.equals(c.group()))
            .toList();
        // literal 分支（complete-enum／complete-fixed）與玩家引數：
        // 2026-10-08 真人基岩實測建議列未顯示，不得再寫「應可見」。
        assertTrue(completeCases.stream()
                .filter(c -> c.id().equals("complete-enum")
                    || c.id().equals("complete-fixed")
                    || c.id().equals("complete-player"))
                .allMatch(c -> c.expectation().contains("實測建議列不顯示")),
            "literal 分支與玩家引數的基岩期望必須記實測建議列不顯示");
        // 其餘型別未逐項實測：記推論，不可寫成已實測。
        assertTrue(completeCases.stream()
                .filter(c -> c.id().equals("complete-world")
                    || c.id().equals("complete-material")
                    || c.id().equals("complete-duration")
                    || c.id().equals("complete-offline"))
                .allMatch(c -> c.expectation().contains("推論無建議列")),
            "未逐項實測的型別必須記推論無建議列");
        // 全體不得再出現舊的錯誤期望。
        assertTrue(completeCases.stream()
                .noneMatch(c -> c.expectation().contains("應可見")),
            "補全案例不得再出現「基岩版應可見」");
    }

    @Test
    @DisplayName("第三階段案例齊全（省略／重複／精確數值／動態／自訂／別名／固定選項在地化）")
    void thirdStageCasesPresent() {
        List<String> inputs = CommandProbeCases.all().stream()
            .map(ProbeCase::input)
            .toList();
        Set<String> ids = CommandProbeCases.all().stream()
            .map(ProbeCase::id)
            .collect(java.util.stream.Collectors.toSet());
        // 省略引數＋重複引數（同一 give 子指令：[player] [amount 預設1] [extra...]）。
        assertTrue(inputs.contains("give Steve"), "缺少省略預設值案例");
        assertTrue(inputs.contains("give Steve 5"), "缺少提供可選引數案例");
        assertTrue(inputs.contains("give Steve 5 stone"), "缺少單一重複值案例");
        assertTrue(inputs.contains("give Steve 5 stone dirt"), "缺少多個重複值案例");
        // 精確數值：合法含 scale、超小數位、科學記號、超範圍各一。
        assertTrue(inputs.contains("parse-bigdecimal 0.10"), "缺少 BigDecimal 合法案例");
        assertTrue(ids.contains("err-bigdecimal-scale"), "缺少 BigDecimal 超小數位案例");
        assertTrue(ids.contains("err-bigdecimal-scientific"), "缺少 BigDecimal 科學記號案例");
        assertTrue(ids.contains("err-bigdecimal-range"), "缺少 BigDecimal 超範圍案例");
        // 動態選項：初始解析、增、增後解析、刪、刪後解析、補全。
        assertTrue(inputs.contains("parse-dyn alpha"), "缺少動態選項初始解析案例");
        assertTrue(inputs.contains("dyn-add gamma"), "缺少動態選項新增案例");
        assertTrue(inputs.contains("parse-dyn gamma"), "缺少新增後解析案例");
        assertTrue(inputs.contains("dyn-remove beta"), "缺少動態選項移除案例");
        assertTrue(inputs.contains("parse-dyn beta"), "缺少移除後解析案例");
        assertTrue(inputs.contains("parse-dyn "), "缺少動態選項補全案例");
        // 自訂引數（0-100 裸數字）：合法、超範圍、非數字、補全。
        assertTrue(inputs.contains("parse-percent 75"), "缺少自訂引數合法案例");
        assertTrue(ids.contains("err-percent-range"), "缺少自訂引數超範圍案例");
        assertTrue(ids.contains("err-percent-nonnumeric"), "缺少自訂引數非數字案例");
        assertTrue(inputs.contains("parse-percent "), "缺少自訂引數補全案例");
        // 精確數值刻意不給建議（同整數）。
        assertTrue(inputs.contains("parse-bigdecimal "), "缺少 BigDecimal 無補全案例");
        // 子指令別名與主名等價。
        assertTrue(inputs.contains("pi 5"), "缺少子指令別名案例");
        // 固定選項在地化（/cprobe-args 根：成功基線＋集合外值）。
        assertTrue(inputs.contains("trade buy 5"), "缺少 trade 成功基線案例");
        assertTrue(ids.contains("err-args-trade-loud"), "缺少固定選項在地化錯誤案例");
    }

    @Test
    @DisplayName("分組只用四種合法值")
    void groupsAreKnown() {
        Set<String> known = Set.of(CommandProbeCases.GROUP_PARSE,
            CommandProbeCases.GROUP_ERROR, CommandProbeCases.GROUP_COMPLETE,
            CommandProbeCases.GROUP_LIFECYCLE);
        for (ProbeCase probeCase : CommandProbeCases.all()) {
            assertTrue(known.contains(probeCase.group()),
                "案例分組不合法：" + probeCase.id() + " group=" + probeCase.group());
        }
    }

    @Test
    @DisplayName("新增補全案例不斷言基岩可見性")
    void newCompleteCasesDoNotClaimBedrock() {
        for (String id : List.of("complete-dyn", "complete-percent")) {
            ProbeCase probeCase = CommandProbeCases.byId(id);
            assertNotNull(probeCase, "缺少補全案例：" + id);
            assertTrue(probeCase.expectation().contains("不斷言"),
                "補全案例不得斷言基岩可見性：" + id);
        }
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