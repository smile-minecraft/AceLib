package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.function.BiFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BedrockFallbackRenderer} 單元測試（純函式轉換，不依賴 Bukkit / BedrockService / LangManager）。
 *
 * <p>對應 Task3 核心交付之一：基岩玩家 click 互動降級 renderer。重點驗證：
 * <ul>
 *   <li>無 click 的 Component 原樣回傳（同一實例），不被不必要改寫；</li>
 *   <li>click 被剝離並附加可讀 hint，輸出 tree 不得殘留任何 ClickEvent；</li>
 *   <li>payload 視為純文字，不被 MiniMessage 解析（防注入）；</li>
 *   <li>巢狀 / 多個 click、hover 保留、style 保留；</li>
 *   <li>HoverEvent.showText 內可遞迴的 Component 若含 ClickEvent，必須被清理且 hover 外層保留（security blocking）。</li>
 * </ul>
 */
@DisplayName("BedrockFallbackRenderer")
class BedrockFallbackRendererTest {

    private static final BiFunction<ClickEvent, Locale, Component> HINT =
        (click, locale) -> Component.text("[" + click.action().name() + ":" + click.value() + "]");

    @Test
    @DisplayName("render：無 click event 時原樣回傳同一實例，不附加 hint")
    void render_noClick_returnsSameInstance() {
        Component src = Component.text("hello").color(NamedTextColor.RED);
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertEquals(src, out, "無 click 應回傳同一實例");
        assertFalse(hasClick(out), "無 click 的 Component 不得含 ClickEvent");
        assertEquals("hello", text(out));
    }

    @Test
    @DisplayName("render：單一 click event 被剝離並附加 hint")
    void render_singleClick_stripsAndAppendsHint() {
        Component src = Component.text("click me").clickEvent(ClickEvent.runCommand("/say hi"));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "輸出不得含任何 ClickEvent");
        String t = text(out);
        assertTrue(t.contains("click me"), "原始文字應保留");
        assertTrue(t.contains("[RUN_COMMAND:/say hi]"), "hint 應附加且 payload 為指令內容");
    }

    @Test
    @DisplayName("render：payload 視為純文字，不被 MiniMessage 解析（防注入）")
    void render_payloadTreatedAsPlainText() {
        Component src = Component.text("x").clickEvent(ClickEvent.runCommand("<red>evil</red>"));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        String t = text(out);
        assertTrue(t.contains("[RUN_COMMAND:<red>evil</red>]"),
            "payload 應原樣呈現，不被解析為紅色樣式（防注入）");
    }

    @Test
    @DisplayName("render：巢狀子節點的 click 也被剝離並附加 hint")
    void render_nestedClick_stripped() {
        Component src = Component.text("root")
            .append(Component.text("child").clickEvent(ClickEvent.openUrl("https://example.com")));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "巢狀 click 也必須被剝離");
        assertTrue(text(out).contains("[OPEN_URL:https://example.com]"), "巢狀 hint 應附加");
    }

    @Test
    @DisplayName("render：hover 樣式保留，僅 click 被移除")
    void render_hoverPreserved() {
        Component src = Component.text("tip")
            .clickEvent(ClickEvent.suggestCommand("/warp"))
            .hoverEvent(HoverEvent.showText(Component.text("hover!")));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "click 必須被移除");
        assertNotNull(out.hoverEvent(), "hover 必須保留");
    }

    @Test
    @DisplayName("render：多個 sibling click 各自被剝離並附加 hint")
    void render_multipleClicks_eachGetsHint() {
        Component src = Component.text("a").clickEvent(ClickEvent.runCommand("/a"))
            .append(Component.text("b").clickEvent(ClickEvent.suggestCommand("/b")));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "所有 click 都必須被剝離");
        String t = text(out);
        assertTrue(t.contains("[RUN_COMMAND:/a]"), "第一個 click 的 hint 應附加");
        assertTrue(t.contains("[SUGGEST_COMMAND:/b]"), "第二個 click 的 hint 應附加");
    }

    // -----------------------------------------------------------------
    // Hover 內嵌 ClickEvent 防禦（blocking correctness）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("render：hover showText 內含 click 必須被清理，hover 外層與正文 style 保留")
    void render_hoverInnerClick_strippedButHoverPreserved() {
        Component hoverInner = Component.text("inner").clickEvent(ClickEvent.runCommand("/hidden"));
        Component src = Component.text("visible")
            .color(NamedTextColor.GREEN)
            .hoverEvent(HoverEvent.showText(hoverInner));
        // 本身無 click，僅 hover 內有 click；render 應進入降級路徑並清理 hover 內 click
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "hover 內嵌的 ClickEvent 必須被剝離，整棵輸出不得殘留 ClickEvent");
        assertNotNull(out.hoverEvent(), "hover 外層必須保留，不因未驗證 Bedrock hover 而刪除");
        // 正文與 style 保留
        assertTrue(text(out).contains("visible"), "正文必須保留");
        assertEquals(NamedTextColor.GREEN, out.color(), "style 顏色必須保留");
        // hover 內文字仍在，但 click 已移除
        HoverEvent<?> hover = out.hoverEvent();
        assertNotNull(hover);
        assertTrue(hover.value() instanceof Component, "hover value 應仍為 Component");
        Component hoverComp = (Component) hover.value();
        assertFalse(hasClick(hoverComp), "hover 內 Component 不得殘留 ClickEvent");
        assertTrue(text(hoverComp).contains("inner"), "hover 內文字必須保留");
    }

    @Test
    @DisplayName("render：root hover 內含多層 children click 均被清理")
    void render_hoverChildrenClick_stripped() {
        Component hoverInner = Component.text("a")
            .append(Component.text("b").clickEvent(ClickEvent.openUrl("https://evil.com")));
        Component src = Component.text("root")
            .hoverEvent(HoverEvent.showText(hoverInner));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "hover 內 children 的 ClickEvent 必須被剝離");
        assertNotNull(out.hoverEvent());
        Component hoverComp = (Component) out.hoverEvent().value();
        assertTrue(text(hoverComp).contains("a"));
        assertTrue(text(hoverComp).contains("b"));
    }

    @Test
    @DisplayName("render：同時含 root click 與 hover 內 click，均被清理且 root hint 附加")
    void render_bothRootAndHoverClick_stripped() {
        Component hoverInner = Component.text("hoverInner").clickEvent(ClickEvent.copyToClipboard("secret"));
        Component src = Component.text("main").clickEvent(ClickEvent.runCommand("/main"))
            .hoverEvent(HoverEvent.showText(hoverInner));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "root 與 hover 內的 ClickEvent 均必須被剝離");
        assertNotNull(out.hoverEvent(), "hover 外層保留");
        String t = text(out);
        assertTrue(t.contains("main"), "正文保留");
        assertTrue(t.contains("[RUN_COMMAND:/main]"), "root click 的 hint 應附加");
        Component hoverComp = (Component) out.hoverEvent().value();
        assertFalse(hasClick(hoverComp), "hover 內不得殘留 ClickEvent");
    }

    @Test
    @DisplayName("render：children 的 hover 內嵌 click 也被清理")
    void render_childrenHoverInnerClick_stripped() {
        Component hoverInner = Component.text("x").clickEvent(ClickEvent.suggestCommand("/suggest"));
        Component child = Component.text("child").hoverEvent(HoverEvent.showText(hoverInner));
        Component src = Component.text("root").append(child);
        Component out = BedrockFallbackRenderer.render(src, Locale.US, HINT);
        assertFalse(hasClick(out), "children 的 hover 內 click 必須被剝離");
        // 驗證 child hover 已清理
        Component outChild = out.children().get(0);
        assertNotNull(outChild.hoverEvent());
        Component hc = (Component) outChild.hoverEvent().value();
        assertFalse(hasClick(hc));
    }

    @Test
    @DisplayName("render：hint 本身含 hover 內 click 時，防禦性 strip 需清理 hint 的 hover")
    void render_hintWithHoverClick_isStripped() {
        BiFunction<ClickEvent, Locale, Component> evilHint = (click, locale) ->
            Component.text("hint")
                .hoverEvent(HoverEvent.showText(Component.text("evil").clickEvent(ClickEvent.openUrl("https://evil.com"))))
                .clickEvent(ClickEvent.runCommand("/shouldNotExist"));
        Component src = Component.text("x").clickEvent(ClickEvent.runCommand("/x"));
        Component out = BedrockFallbackRenderer.render(src, Locale.US, evilHint);
        assertFalse(hasClick(out), "輸出任何位置（root、children、hint、hover）均不得殘留 ClickEvent");
        // hint 已被附加為 child，其 hover 內 click 應被清理
        for (Component child : out.children()) {
            assertFalse(hasClick(child), "hint child 不得含 ClickEvent，含 hover 內也不得有");
            if (child.hoverEvent() != null && child.hoverEvent().value() instanceof Component hc2) {
                assertFalse(hasClick(hc2), "hint hover 內不得殘留 ClickEvent");
            }
        }
    }

    @Test
    @DisplayName("stripClickEvents：應清理 hover 內嵌 click")
    void strip_hoverInnerClick_stripped() {
        Component hoverInner = Component.text("inner").clickEvent(ClickEvent.runCommand("/a"));
        Component src = Component.text("t").hoverEvent(HoverEvent.showText(hoverInner));
        Component out = BedrockFallbackRenderer.stripClickEvents(src);
        assertFalse(hasClick(out), "stripClickEvents 必須清理 hover 內 ClickEvent");
        assertNotNull(out.hoverEvent(), "hover 外層保留");
        Component hc = (Component) out.hoverEvent().value();
        assertFalse(hasClick(hc));
        assertTrue(text(hc).contains("inner"));
    }

    @Test
    @DisplayName("stripClickEvents：非 Component hover（showItem）不影響")
    void strip_showItemHover_preserved() {
        HoverEvent<?> itemHover = HoverEvent.showItem(net.kyori.adventure.key.Key.key("minecraft:stone"), 1);
        Component src = Component.text("x").hoverEvent(itemHover).clickEvent(ClickEvent.runCommand("/x"));
        Component out = BedrockFallbackRenderer.stripClickEvents(src);
        assertFalse(hasClick(out), "click 必須被移除");
        assertNotNull(out.hoverEvent(), "非 Component hover 應保留");
        assertEquals(itemHover.action(), out.hoverEvent().action(), "showItem hover action 應保留");
    }

    private static boolean hasClick(Component c) {
        if (c == null) {
            return false;
        }
        if (c.clickEvent() != null) {
            return true;
        }
        HoverEvent<?> hover = c.hoverEvent();
        if (hover != null && hover.value() instanceof Component hoverComp) {
            if (hasClick(hoverComp)) {
                return true;
            }
        }
        for (Component child : c.children()) {
            if (hasClick(child)) {
                return true;
            }
        }
        return false;
    }

    private static String text(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
