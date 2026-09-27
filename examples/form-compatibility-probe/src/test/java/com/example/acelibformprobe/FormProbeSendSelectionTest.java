package com.example.acelibformprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code /fprobe send} 案例選擇測試。
 *
 * <p>本測試只驗證「挑選邏輯」（純函式，不需要伺服器、不真的送出表單）：
 * 識別碼正確時只選出該案例、未知時拋錯、未給時維持全部。
 * 「未知時不發送」由呼叫端保證：{@code handleSend} 先做選擇、
 * 選擇拋錯就直接回報並返回，任何發送都發生在選擇成功之後。</p>
 */
class FormProbeSendSelectionTest {

    private List<FormProbeCase> catalog() {
        return FormProbeCases.buildCatalog();
    }

    @Test
    void knownIdSelectsSingleCase() {
        List<FormProbeCase> selected =
            FormProbeSendSelection.selectCases(catalog(), "icon-url");
        assertEquals(1, selected.size(), "指定識別碼時應只選出一個案例");
        assertEquals("icon-url", selected.get(0).id());
    }

    @Test
    void idMatchingIgnoresCaseAndSurroundingWhitespace() {
        List<FormProbeCase> selected =
            FormProbeSendSelection.selectCases(catalog(), "  Icon-URL  ");
        assertEquals(1, selected.size(), "大小寫與前後空白應被容忍");
        assertEquals("icon-url", selected.get(0).id());
    }

    @Test
    void unknownIdThrowsAndSelectsNothing() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FormProbeSendSelection.selectCases(catalog(), "no-such-case"),
            "未知識別碼應拋錯（呼叫端據此回報且不發送）");
        assertTrue(ex.getMessage().contains("no-such-case"),
            "錯誤訊息應包含使用者輸入的識別碼，訊息：" + ex.getMessage());
    }

    @Test
    void unknownIdWithWhitespaceStillThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> FormProbeSendSelection.selectCases(catalog(), "  no-such-case  "),
            "含空白的未知識別碼同樣應拋錯");
    }

    @Test
    void errorMessageListsAvailableIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FormProbeSendSelection.selectCases(catalog(), "nope"));
        assertTrue(ex.getMessage().contains("icon-url"),
            "錯誤訊息應列出可用識別碼，訊息：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("/fprobe list"),
            "錯誤訊息應提示可用 /fprobe list 查看，訊息：" + ex.getMessage());
    }

    @Test
    void nullOrBlankKeepsAllInOrder() {
        List<String> expectedIds = catalog().stream().map(FormProbeCase::id).toList();
        for (String input : new String[] {null, "", "   "}) {
            List<String> actualIds = FormProbeSendSelection
                .selectCases(catalog(), input).stream().map(FormProbeCase::id).toList();
            assertEquals(expectedIds, actualIds,
                "未給識別碼（null／空白）時應維持全部且順序不變，輸入：" + input);
        }
    }
}
