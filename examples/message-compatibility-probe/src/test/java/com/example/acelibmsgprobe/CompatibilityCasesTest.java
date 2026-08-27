package com.example.acelibmsgprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * 相容性案例目錄完整性測試。
 *
 * <p>本測試是探針的 TDD 錨點：它斷言 {@link CompatibilityCases#buildCatalog()}
 * 不遺漏任何矩陣要求的必要案例，且每個案例的 Component 確實帶有預期的
 * Adventure 特性（hover / click action / translatable / 顏色等）。這讓「案例目錄
 * 建構不遺漏必要案例」可被自動化驗證，而不需要真實伺服器或客戶端。</p>
 *
 * <p>注意：本測試只驗證「案例被正確建構」，不宣稱任何 Geyser / Bedrock 轉換結果；
 * 轉換結果必須由真人客戶端實機觀察後填入矩陣報告。</p>
 */
class CompatibilityCasesTest {

    private static final List<String> REQUIRED_IDS = List.of(
        "plain",
        "style",
        "nested",
        "hover-showtext",
        "click-run-command",
        "click-suggest-command",
        "click-open-url",
        "click-copy-to-clipboard",
        "translatable",
        "hex",
        "gradient",
        "rainbow");

    private Map<String, CompatibilityCase> index() {
        List<CompatibilityCase> catalog = CompatibilityCases.buildCatalog();
        assertTrue(catalog != null && !catalog.isEmpty(), "catalog 不得為空");
        Map<String, CompatibilityCase> byId = new java.util.LinkedHashMap<>();
        for (CompatibilityCase c : catalog) {
            byId.put(c.id(), c);
        }
        return byId;
    }

    @Test
    void catalogContainsAllRequiredCases() {
        Map<String, CompatibilityCase> byId = index();
        for (String id : REQUIRED_IDS) {
            assertTrue(byId.containsKey(id),
                "案例目錄缺少必要案例：" + id);
        }
        assertEquals(REQUIRED_IDS.size(), byId.size(),
            "案例數量應恰好等於必要案例數（不得多塞或漏放）");
    }

    @Test
    void everyCaseHasNonNullComponent() {
        for (CompatibilityCase c : CompatibilityCases.buildCatalog()) {
            assertNotNull(c.component(), "案例 " + c.id() + " 的 Component 不得為 null");
        }
    }

    @Test
    void plainCaseIsPlainText() {
        CompatibilityCase c = index().get("plain");
        String plain = PlainTextComponentSerializer.plainText().serialize(c.component());
        assertEquals("Hello, AceLib compatibility probe", plain,
            "plain 案例的純文字應與基準一致");
    }

    @Test
    void styleCaseHasColorOrDecoration() {
        CompatibilityCase c = index().get("style");
        Component comp = c.component();
        boolean styled = comp.color() != null
            || comp.decorations().values().stream().anyMatch(v -> v == net.kyori.adventure.text.format.TextDecoration.State.TRUE);
        assertTrue(styled, "style 案例應帶有顏色或裝飾");
    }

    @Test
    void nestedCaseCombinesMultipleComponents() {
        CompatibilityCase c = index().get("nested");
        String plain = PlainTextComponentSerializer.plainText().serialize(c.component());
        assertTrue(plain.contains("Ace") && plain.contains("Lib"),
            "nested 案例應包含巢狀子元件的文字");
    }

    @Test
    void hoverCaseCarriesShowText() {
        CompatibilityCase c = index().get("hover-showtext");
        HoverEvent<?> hover = c.component().hoverEvent();
        assertNotNull(hover, "hover-showtext 案例應帶有 hoverEvent");
        assertNotNull(hover.value(), "hoverEvent 的 value 不得為 null");
    }

    @Test
    void clickCasesCarryExpectedActions() {
        Map<String, CompatibilityCase> byId = index();
        assertClickAction(byId.get("click-run-command"), ClickEvent.Action.RUN_COMMAND);
        assertClickAction(byId.get("click-suggest-command"), ClickEvent.Action.SUGGEST_COMMAND);
        assertClickAction(byId.get("click-open-url"), ClickEvent.Action.OPEN_URL);
        assertClickAction(byId.get("click-copy-to-clipboard"), ClickEvent.Action.COPY_TO_CLIPBOARD);
    }

    private void assertClickAction(CompatibilityCase c, ClickEvent.Action expected) {
        assertNotNull(c, "click 案例不得為 null");
        ClickEvent click = c.component().clickEvent();
        assertNotNull(click, "案例 " + c.id() + " 應帶有 clickEvent");
        assertEquals(expected, click.action(),
            "案例 " + c.id() + " 的 click action 應為 " + expected);
    }

    @Test
    void translatableCaseIsTranslatableComponent() {
        CompatibilityCase c = index().get("translatable");
        assertInstanceOf(TranslatableComponent.class, c.component(),
            "translatable 案例應是 TranslatableComponent");
    }

    @Test
    void hexCaseHasColor() {
        CompatibilityCase c = index().get("hex");
        assertNotNull(c.component().color(), "hex 案例應帶有顏色（hex TextColor）");
    }

    @Test
    void gradientAndRainbowCasesRenderToNonEmptyText() {
        Map<String, CompatibilityCase> byId = index();
        for (String id : List.of("gradient", "rainbow")) {
            String plain = PlainTextComponentSerializer.plainText()
                .serialize(byId.get(id).component());
            assertFalse(plain.isEmpty(), id + " 案例應能序列化出非空白文字");
        }
    }
}
