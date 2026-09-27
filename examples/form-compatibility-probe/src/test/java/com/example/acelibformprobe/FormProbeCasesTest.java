package com.example.acelibformprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 表單探針案例目錄完整性測試。
 *
 * <p>本測試是探針的 TDD 錨點：它斷言 {@link FormProbeCases#buildCatalog()}
 * 不遺漏任何矩陣要求的必要案例、案例識別碼唯一且穩定、每個案例都能建構出
 * 合法的 {@link FormSpec}（不合法在測試階段就被抓到），以及按鈕順序與點擊
 * 索引的對應正確。這讓「案例目錄建構不遺漏必要案例」可被自動化驗證，
 * 而不需要真實伺服器或客戶端。</p>
 *
 * <p>注意：本測試只驗證「案例被正確建構」，不宣稱任何 Geyser / Bedrock 轉換結果；
 * 轉換結果必須由真人客戶端實機觀察後填入矩陣報告。特別是各案例的轉換屬性
 *（例如 hex 降級後的實際顏色）屬於產品行為，由 AceLib 自身測試覆蓋，
 * 本測試只斷言結構完整性（非空白標題／內容／按鈕、按鈕順序、圖示 presence）。</p>
 */
class FormProbeCasesTest {

    private static final List<String> REQUIRED_IDS = List.of(
        "icon-path-item",
        "icon-path-block",
        "icon-url",
        "icon-broken-fallback",
        "text-hex-downgrade",
        "text-gradient-downgrade",
        "text-decoration-stripped",
        "translatable-with-fallback",
        "translatable-key-only",
        "multiline",
        "overlong-truncation");

    private Map<String, FormProbeCase> index() {
        List<FormProbeCase> catalog = FormProbeCases.buildCatalog();
        assertTrue(catalog != null && !catalog.isEmpty(), "catalog 不得為空");
        Map<String, FormProbeCase> byId = new LinkedHashMap<>();
        for (FormProbeCase c : catalog) {
            assertTrue(!byId.containsKey(c.id()), "案例識別碼重複：" + c.id());
            assertNotNull(c.description(), "案例 " + c.id() + " 的說明不得為 null");
            assertTrue(!c.description().isBlank(), "案例 " + c.id() + " 的說明不得為空白");
            byId.put(c.id(), c);
        }
        return byId;
    }

    @Test
    void catalogContainsAllRequiredCases() {
        Map<String, FormProbeCase> byId = index();
        for (String id : REQUIRED_IDS) {
            assertTrue(byId.containsKey(id),
                "案例目錄缺少必要案例：" + id);
        }
        assertEquals(REQUIRED_IDS.size(), byId.size(),
            "案例數量應恰好等於必要案例數（不得多塞或漏放）");
    }

    @Test
    void caseIdsAreStableAcrossBuilds() {
        List<String> first = FormProbeCases.buildCatalog().stream()
            .map(FormProbeCase::id).toList();
        List<String> second = FormProbeCases.buildCatalog().stream()
            .map(FormProbeCase::id).toList();
        assertEquals(first, second, "案例識別碼必須穩定：兩次建構的 id 順序應一致");
        assertEquals(REQUIRED_IDS, first, "案例順序應與矩陣縱軸順序一致");
    }

    @Test
    void everyCaseBuildsLegalSpec() {
        for (FormProbeCase c : FormProbeCases.buildCatalog()) {
            FormSpec spec = c.buildSpec();
            assertNotNull(spec, "案例 " + c.id() + " 建構出的規格不得為 null");
            assertTrue(!spec.title().isBlank(), "案例 " + c.id() + " 的標題不得為空白");
        }
    }

    @Test
    void simpleCasesHaveMatchingButtonsAndEntries() {
        for (FormProbeCase c : FormProbeCases.buildCatalog()) {
            FormSpec spec = c.buildSpec();
            if (spec instanceof FormSpec.Simple simple) {
                assertTrue(!simple.content().isBlank(),
                    "案例 " + c.id() + " 的說明文字不得為空白");
                assertTrue(!simple.buttons().isEmpty(),
                    "案例 " + c.id() + " 至少要有一顆按鈕");
                assertEquals(simple.buttons().size(), simple.buttonEntries().size(),
                    "案例 " + c.id() + " 的按鈕文字與 entries 數量必須一致（索引對應）");
                for (int i = 0; i < simple.buttons().size(); i++) {
                    assertTrue(!simple.buttons().get(i).isBlank(),
                        "案例 " + c.id() + " 的第 " + i + " 顆按鈕文字不得為空白（否則無法點擊）");
                    assertEquals(simple.buttons().get(i), simple.buttonEntries().get(i).text(),
                        "案例 " + c.id() + " 的第 " + i + " 顆按鈕文字與 entry 必須同索引對應");
                    assertNotNull(simple.buttonEntries().get(i).image(),
                        "案例 " + c.id() + " 的第 " + i + " 顆按鈕圖示 optional 不得為 null");
                }
            }
        }
    }

    @Test
    void brokenIconCaseKeepsButtonOrder() {
        FormProbeCase c = index().get("icon-broken-fallback");
        FormSpec.Simple simple = (FormSpec.Simple) c.buildSpec();
        // 三顆按鈕：正常 → 圖示遺失 → 正常；中間圖示遺失時按鈕仍可點且索引不位移。
        assertEquals(
            List.of("第一顆", "第二顆（圖示遺失）", "第三顆"),
            simple.buttons(),
            "錯誤路徑案例的按鈕順序必須固定，點擊索引才有意義");
        assertTrue(simple.buttonEntries().get(0).image().isEmpty(),
            "第一顆按鈕應無圖示（對照）");
        assertTrue(simple.buttonEntries().get(1).image().isPresent(),
            "第二顆按鈕應帶有圖示（遺失路徑，客戶端顯示空白但仍可點）");
        assertTrue(simple.buttonEntries().get(2).image().isEmpty(),
            "第三顆按鈕應無圖示（對照）");
    }

    @Test
    void iconCasesCarryExpectedImageTypes() {
        Map<String, FormProbeCase> byId = index();
        assertImageType(byId.get("icon-path-item"),
            FormImage.Type.PATH);
        assertImageType(byId.get("icon-path-block"),
            FormImage.Type.PATH);
        assertImageType(byId.get("icon-url"),
            FormImage.Type.URL);
    }

    private void assertImageType(FormProbeCase c, FormImage.Type expected) {
        assertNotNull(c, "圖示案例不得為 null");
        FormSpec.Simple simple = (FormSpec.Simple) c.buildSpec();
        boolean anyMatch = simple.buttonEntries().stream()
            .flatMap(e -> e.image().stream())
            .anyMatch(img -> img.type() == expected);
        assertTrue(anyMatch, "案例 " + c.id() + " 應帶有 " + expected + " 圖示");
    }

    @Test
    void specsAreReproducible() {
        for (FormProbeCase c : FormProbeCases.buildCatalog()) {
            FormSpec first = c.buildSpec();
            FormSpec second = c.buildSpec();
            assertEquals(first.title(), second.title(),
                "案例 " + c.id() + " 重複建構的標題應一致");
            if (first instanceof FormSpec.Simple s1 && second instanceof FormSpec.Simple s2) {
                assertEquals(s1.content(), s2.content(),
                    "案例 " + c.id() + " 重複建構的說明應一致");
                assertEquals(s1.buttons(), s2.buttons(),
                    "案例 " + c.id() + " 重複建構的按鈕順序應一致");
            }
        }
    }

    @Test
    void textCasesRenderToNonBlankContent() {
        Map<String, FormProbeCase> byId = index();
        List<String> textIds = new ArrayList<>(REQUIRED_IDS);
        textIds.removeAll(List.of(
            "icon-path-item", "icon-path-block", "icon-url", "icon-broken-fallback"));
        assertTrue(!textIds.isEmpty(), "文字案例清單不得為空");
        for (String id : textIds) {
            FormSpec.Simple simple = (FormSpec.Simple) byId.get(id).buildSpec();
            assertTrue(!simple.content().isBlank(),
                "文字案例 " + id + " 渲染後的說明不得為空白");
        }
    }
}
