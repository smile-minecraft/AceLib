package com.example.acelibguiprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * GUI 探針案例目錄的完整性測試（TDD 錨點）。
 *
 * <p>目錄是主代理實機驗證的操作清單：每個案例對應一種要觀察的行為
 * （導航、票券、輸入、冷卻、基岩表單派送）。新增或刪除案例時必須同步更新
 * 本測試的期望識別碼集合與 {@code docs/modules/gui.md} 的探針小節。</p>
 */
class GuiProbeCasesTest {

    @Test
    void catalogCoversAllFiveAcceptanceAreas() {
        List<GuiProbeCase> catalog = GuiProbeCases.buildCatalog();

        assertTrue(catalog.size() >= 5, "目錄至少覆蓋五個驗收面向");
        Set<String> ids = catalog.stream()
            .map(GuiProbeCase::id)
            .collect(Collectors.toSet());
        assertEquals(catalog.size(), ids.size(), "識別碼不可重複");
        assertTrue(ids.contains("nav"), "缺導航案例（open/push/replace/back/close）");
        assertTrue(ids.contains("ticket"), "缺一次性票券案例");
        assertTrue(ids.contains("input"), "缺聊天輸入案例");
        assertTrue(ids.contains("cooldown"), "缺按鈕冷卻案例");
        assertTrue(ids.contains("form"), "缺基岩表單派送案例");
        assertTrue(ids.contains("flow"), "缺共用流程案例（openFlow Java／基岩雙呈現）");
    }

    @Test
    void everyCaseHasHumanReadableDescription() {
        for (GuiProbeCase c : GuiProbeCases.buildCatalog()) {
            assertTrue(c.id() != null && !c.id().isBlank(), "識別碼不可空白");
            assertTrue(c.description() != null && !c.description().isBlank(),
                "案例 " + c.id() + " 缺少人類可讀說明");
        }
    }

    @Test
    void catalogCoversPhaseFiveProbeCases() {
        List<GuiProbeCase> catalog = GuiProbeCases.buildCatalog();
        Set<String> ids = catalog.stream()
            .map(GuiProbeCase::id)
            .collect(Collectors.toSet());
        assertTrue(ids.contains("mask-icons"), "缺遮罩物品位置案例（字元遮罩＋物品按鈕）");
        assertTrue(ids.contains("pager"), "缺分頁翻頁案例（多頁導覽按鈕與頁碼標題）");
        assertTrue(ids.contains("label-form"), "缺標籤表單案例（共用流程標籤步驟雙呈現）");
        assertTrue(catalog.size() >= 9, "目錄至少覆蓋九個驗收面向（既有六案＋新增三案）");
    }

    @Test
    void newCasesDocumentLogPrefixAndHumanObservation() {
        java.util.Map<String, String> expectedPrefix = java.util.Map.of(
            "mask-icons", "[gprobe-mask-icons]",
            "pager", "[gprobe-pager]",
            "label-form", "[gprobe-label-form]");
        java.util.Map<String, GuiProbeCase> byId = GuiProbeCases.buildCatalog().stream()
            .collect(Collectors.toMap(GuiProbeCase::id, c -> c));
        for (java.util.Map.Entry<String, String> entry : expectedPrefix.entrySet()) {
            GuiProbeCase c = byId.get(entry.getKey());
            assertTrue(c != null, "缺案例：" + entry.getKey());
            assertTrue(c.description().contains(entry.getValue()),
                "案例 " + entry.getKey() + " 說明須含 log 前綴 " + entry.getValue());
            assertTrue(c.description().contains("真人觀察"),
                "案例 " + entry.getKey() + " 說明須標註哪些觀察點屬真人客戶端");
        }
    }

    @Test
    void catalogIsRebuildableAndOrderStable() {
        List<String> first = GuiProbeCases.buildCatalog().stream()
            .map(GuiProbeCase::id)
            .toList();
        List<String> second = GuiProbeCases.buildCatalog().stream()
            .map(GuiProbeCase::id)
            .toList();
        assertEquals(first, second, "目錄重建順序必須穩定（實機操作清單可重現）");
    }
}
