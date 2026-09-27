package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FormText} 靜態路徑 Red/Green 測試（Adventure 4.26.1 lane）。
 *
 * <p>不依賴 MockBukkit / 語言檔：靜態路徑必須在純 JVM 下運作，
 * 證明提示不依賴語言檔。
 */
@DisplayName("FormText 靜態渲染（Adventure 4 lane）")
class FormTextTest {

    private List<LogRecord> captured;
    private Handler handler;

    @BeforeEach
    void captureLogs() {
        captured = new ArrayList<>();
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger acelib = Logger.getLogger("AceLib");
        acelib.setLevel(Level.ALL);
        acelib.addHandler(handler);
    }

    @AfterEach
    void releaseLogs() {
        Logger acelib = Logger.getLogger("AceLib");
        acelib.removeHandler(handler);
    }

    // ---- 參數驗證 ----

    @Test
    @DisplayName("null component 以 IllegalArgumentException 拒絕")
    void render_nullComponent_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> FormText.render(null));
        assertThrows(IllegalArgumentException.class,
            () -> FormText.render(null, FormTextOptions.defaults()));
    }

    @Test
    @DisplayName("null options 以 IllegalArgumentException 拒絕")
    void render_nullOptions_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> FormText.render(Component.text("hi"), null));
    }

    @Test
    @DisplayName("負數 maxLength 以 IllegalArgumentException 拒絕")
    void options_negativeMaxLength_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FormTextOptions(false, -1, null));
    }

    @Test
    @DisplayName("defaults() 回傳 (false, 0, null)")
    void options_defaults() {
        FormTextOptions def = FormTextOptions.defaults();
        assertEquals(false, def.clickHints());
        assertEquals(0, def.maxLength());
        assertEquals(null, def.locale());
    }

    // ---- click ----

    @Test
    @DisplayName("click 預設被移除且不加提示")
    void render_clickRemovedByDefault() {
        Component src = Component.text("click me")
            .clickEvent(ClickEvent.runCommand("/say hi"));
        String out = FormText.render(src);
        assertEquals("click me§r", out, "實際：" + escape(out));
        assertFalse(out.contains("/say hi"), "click payload 不得殘留，實際：" + out);
        assertFalse(out.contains("Run command"), "預設不得加提示，實際：" + out);
    }

    @Test
    @DisplayName("clickHints=true 時附加可讀提示")
    void render_clickHints_appendsReadableHint() {
        Component src = Component.text("click me")
            .clickEvent(ClickEvent.runCommand("/say hi"));
        String out = FormText.render(src,
            new FormTextOptions(true, 0, null));
        assertEquals("click me[Run command: /say hi]§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("hover 內 click 也被移除")
    void render_clickInsideHover_removed() {
        Component inner = Component.text("inner")
            .clickEvent(ClickEvent.openUrl("https://example.com"));
        Component src = Component.text("visible")
            .hoverEvent(HoverEvent.showText(inner));
        String out = FormText.render(src,
            new FormTextOptions(true, 0, null));
        // hover 整段移除：正文外不留 hover 文字、click 提示或 payload
        assertEquals("visible§r", out, "實際：" + escape(out));
    }

    // ---- hover ----

    @Test
    @DisplayName("hover 被移除且 hover 內容不進入輸出")
    void render_hoverRemoved() {
        Component src = Component.text("main")
            .hoverEvent(HoverEvent.showText(Component.text("tooltip")));
        String out = FormText.render(src);
        assertEquals("main§r", out, "實際：" + escape(out));
    }

    // ---- 顏色降級 ----

    @Test
    @DisplayName("具名色保留")
    void render_namedColor_preserved() {
        Component src = Component.text("hi").color(NamedTextColor.RED);
        String out = FormText.render(src);
        assertTrue(out.contains("hi"), "實際：" + out);
        assertTrue(out.contains("§c"), "紅色應為 §c，實際：" + out);
    }

    @Test
    @DisplayName("hex 色降為 16 色且不含 §x")
    void render_hexColor_downgradedToNamed() {
        Component src = Component.text("hi").color(TextColor.color(0xFF5555));
        String out = FormText.render(src);
        // 0xFF5555 恰為具名紅 §c 的值，最近色即紅色
        assertEquals("§chi§r", out, "實際：" + escape(out));
        assertFalse(out.contains("§x"), "不得出現 §x 形式，實際：" + escape(out));
    }

    @Test
    @DisplayName("非具名 hex 色降為最近的 16 色")
    void render_hexColor_nonNamed_downgradedToNearest() {
        Component src = Component.text("hi").color(TextColor.color(0x123456));
        String out = FormText.render(src);
        assertTrue(stripCodes(out).contains("hi"), "實際：" + escape(out));
        assertHasNamedColorCode(out, "非具名色必須降為具名色而非被移除");
        assertFalse(out.contains("§x"), "不得出現 §x 形式，實際：" + escape(out));
    }

    @Test
    @DisplayName("gradient 降為 16 色且不含 §x")
    void render_gradient_downgradedToNamed() {
        Component src = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize("<gradient:red:blue>hi</gradient>");
        String out = FormText.render(src);
        // 漸層按字元降色（h→紅 §c、i→藍 §9），色碼插在字母之間
        assertEquals("§ch§9i§r", out, "實際：" + escape(out));
        assertHasNamedColorCode(out, "漸層必須降為具名色而非被移除");
        assertFalse(out.contains("§x"), "不得出現 §x 形式，實際：" + escape(out));
    }

    // ---- 樣式 ----

    @Test
    @DisplayName("粗體／斜體／混淆保留")
    void render_boldItalicObfuscated_preserved() {
        Component src = Component.text("b").decorate(TextDecoration.BOLD)
            .append(Component.text("i").decorate(TextDecoration.ITALIC))
            .append(Component.text("o").decorate(TextDecoration.OBFUSCATED));
        String out = FormText.render(src);
        assertTrue(out.contains("§l"), "粗體 §l 保留，實際：" + out);
        assertTrue(out.contains("§o"), "斜體 §o 保留，實際：" + out);
        assertTrue(out.contains("§k"), "混淆 §k 保留，實際：" + out);
    }

    @Test
    @DisplayName("底線／刪除線移除且不含 §n／§m")
    void render_underlineStrikethrough_removed() {
        Component src = Component.text("u").decorate(TextDecoration.UNDERLINED)
            .append(Component.text("s").decorate(TextDecoration.STRIKETHROUGH));
        String out = FormText.render(src);
        // 裝飾被整段剝除，不留任何樣式碼
        assertEquals("us§r", out, "實際：" + escape(out));
        assertFalse(out.contains("§n"), "不得出現 §n，實際：" + out);
        assertFalse(out.contains("§m"), "不得出現 §m，實際：" + out);
    }

    @Test
    @DisplayName("巢狀樣式降級後仍正確")
    void render_nestedStyle_correct() {
        Component src = Component.text("parent").color(NamedTextColor.RED)
            .append(Component.text("child").color(NamedTextColor.BLUE));
        String out = FormText.render(src);
        assertEquals("§cparent§9child§r", out, "實際：" + escape(out));
    }

    // ---- translatable ----

    @Test
    @DisplayName("translatable 有 fallback 時用 fallback")
    void render_translatable_usesFallback() {
        TranslatableComponent src = Component.translatable()
            .key("no.such.key.anywhere")
            .fallback("Fallback Text")
            .build();
        String out = FormText.render(src);
        assertEquals("Fallback Text§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("translatable 無 fallback 時保留 key")
    void render_translatable_keepsKey() {
        TranslatableComponent src = Component.translatable()
            .key("no.such.key.anywhere")
            .build();
        String out = FormText.render(src);
        assertEquals("no.such.key.anywhere§r", out, "實際：" + escape(out));
    }

    // ---- 換行 ----

    @Test
    @DisplayName("換行保留且每行以 §r 結尾")
    void render_newlines_eachLineEndsWithReset() {
        Component src = Component.text("a\nb");
        String out = FormText.render(src);
        assertEquals("a§r\nb§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("跨行樣式在下一行重新套用")
    void render_crossLineStyle_reapplied() {
        Component src = Component.text("a\nb").color(NamedTextColor.RED);
        String out = FormText.render(src);
        String[] lines = out.split("\n", -1);
        assertEquals(2, lines.length, "實際：" + escape(out));
        assertTrue(lines[1].contains("§c"),
            "第二行必須重新套用紅色，實際：" + escape(out));
    }

    // ---- maxLength ----

    @Test
    @DisplayName("maxLength=0 不截斷")
    void render_maxLengthZero_noTruncation() {
        Component src = Component.text("hello world");
        String out = FormText.render(src, new FormTextOptions(false, 0, null));
        assertEquals("hello world§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("長度正好等於上限不截斷")
    void render_exactLength_noTruncation() {
        Component src = Component.text("hi");
        String out = FormText.render(src, new FormTextOptions(false, 2, null));
        assertEquals("hi§r", out, "實際：" + escape(out));
        assertFalse(out.contains("…"), "不得加省略號，實際：" + escape(out));
    }

    @Test
    @DisplayName("超過上限截斷且省略號計入上限")
    void render_overLength_truncatedWithEllipsis() {
        Component src = Component.text("hello");
        String out = FormText.render(src, new FormTextOptions(false, 4, null));
        // 預算 4-1=3：hel＋…＝4 個可見字元
        assertEquals("hel…§r", out, "實際：" + escape(out));
        assertTrue(visibleLength(out) <= 4, "可見長度不得超過上限，實際：" + escape(out));
    }

    @Test
    @DisplayName("截斷不切斷代理對 emoji")
    void render_truncation_keepsEmojiIntact() {
        Component src = Component.text("ab\uD83D\uDE00cd");
        String out = FormText.render(src, new FormTextOptions(false, 3, null));
        assertEquals("ab…§r", out, "實際：" + escape(out));
        assertFalse(out.contains("\uD83D") && !out.contains("\uDE00"),
            "不得切斷代理對，實際：" + escape(out));
        assertTrue(visibleLength(out) <= 3, "實際：" + escape(out));
    }

    @Test
    @DisplayName("截斷不切在色碼中間")
    void render_truncation_notInsideColorCode() {
        Component src = Component.text("hello").color(NamedTextColor.RED)
            .append(Component.text("world").color(NamedTextColor.BLUE));
        String out = FormText.render(src, new FormTextOptions(false, 4, null));
        assertFalse(out.endsWith("§"), "不得切在色碼中間，實際：" + escape(out));
        assertTrue(out.endsWith("§r"), "截斷後仍以 §r 結尾，實際：" + escape(out));
    }

    @Test
    @DisplayName("maxLength=1 退化為單一省略號")
    void render_maxLengthOne_ellipsisOnly() {
        Component src = Component.text("hello");
        String out = FormText.render(src, new FormTextOptions(false, 1, null));
        assertEquals("…§r", out, "實際：" + escape(out));
        assertTrue(visibleLength(out) <= 1, "實際：" + escape(out));
    }

    @Test
    @DisplayName("空字串輸入輸出安全且以 §r 結尾")
    void render_emptyInput_safe() {
        String out = FormText.render(Component.text(""));
        assertEquals("§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("多行截斷：預算視窗內含換行時換行不佔預算")
    void render_multilineTruncation_newlineNotCounted() {
        Component src = Component.text("ab\ncdef");
        String out = FormText.render(src, new FormTextOptions(false, 5, null));
        assertTrue(out.contains("…"), "應截斷，實際：" + escape(out));
        assertEquals(5, visibleLength(out),
            "換行不算可見字元，結果應填滿上限 5，實際：" + escape(out));
        assertTrue(out.contains("\n"), "換行保留，實際：" + escape(out));
    }

    @Test
    @DisplayName("多行截斷：換行位於預算視窗邊界時不偷走一個字元")
    void render_multilineTruncation_newlineAtWindowEdge() {
        Component src = Component.text("a\nbcde");
        String out = FormText.render(src, new FormTextOptions(false, 3, null));
        assertEquals("a§r\nb…§r", out, "實際：" + escape(out));
        assertEquals(3, visibleLength(out), "實際：" + escape(out));
    }

    @Test
    @DisplayName("多行截斷：多個連續換行都不佔預算")
    void render_multilineTruncation_consecutiveNewlines() {
        Component src = Component.text("a\n\nbcde");
        String out = FormText.render(src, new FormTextOptions(false, 3, null));
        assertEquals("a§r\n§r\nb…§r", out, "實際：" + escape(out));
        assertEquals(3, visibleLength(out), "實際：" + escape(out));
    }

    @Test
    @DisplayName("多行且長度正好等於上限不截斷")
    void render_multilineExactLength_noTruncation() {
        Component src = Component.text("a\nb");
        String out = FormText.render(src, new FormTextOptions(false, 2, null));
        assertEquals("a§r\nb§r", out, "實際：" + escape(out));
        assertFalse(out.contains("…"), "實際：" + escape(out));
    }

    @Test
    @DisplayName("多行截斷仍不得超過上限")
    void render_multilineTruncation_neverExceedsLimit() {
        Component src = Component.text("ab\ncdefgh");
        String out = FormText.render(src, new FormTextOptions(false, 4, null));
        assertTrue(out.contains("…"), "實際：" + escape(out));
        assertTrue(visibleLength(out) <= 4, "實際：" + escape(out));
        assertTrue(out.endsWith("§r"), "實際：" + escape(out));
    }

    // ---- 渲染失敗 ----

    @Test
    @DisplayName("渲染失敗記錄 MSG-005 並回傳純文字，不拋例外")
    void render_failure_logsMsg005AndReturnsPlain() {
        Component broken = org.mockito.Mockito.mock(Component.class,
            invocation -> {
                throw new RuntimeException("boom-" + invocation.getMethod().getName());
            });
        String out = null;
        try {
            out = FormText.render(broken);
        } catch (Throwable t) {
            org.junit.jupiter.api.Assertions.fail("不得拋出例外，實際拋出：" + t);
        }
        assertTrue(captured.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("ACELIB-MSG-005")),
            "必須記錄 ACELIB-MSG-005");
        // mock 的所有方法都拋錯，連 plain 退回也取不到文字：回傳空字串，不拋例外
        assertEquals("", out, "實際：" + escape(String.valueOf(out)));
    }

    // ---- 失敗退回路徑（plainFallback 直接測試） ----

    @Test
    @DisplayName("退回：多行超長時截斷且每行以 §r 結尾")
    void plainFallback_multilineOverlong_truncatedWithResets() {
        Component src = Component.text("ab\ncdefgh");
        String out = FormText.plainFallback(src, 4);
        // plain 退回走同樣的換行 §r＋截斷：ab＋換行＋c＋…＝4 可見字元
        assertEquals("ab§r\nc…§r", out, "實際：" + escape(out));
        assertTrue(visibleLength(out) <= 4, "實際：" + escape(out));
        assertTrue(out.contains("…"), "確實有截斷，實際：" + escape(out));
        for (String line : out.split("\n", -1)) {
            assertTrue(line.endsWith("§r"), "每行以 §r 結尾，實際：" + escape(out));
        }
    }

    @Test
    @DisplayName("退回：maxLength=0 不截斷")
    void plainFallback_maxLengthZero_noTruncation() {
        Component src = Component.text("ab\ncdefgh");
        String out = FormText.plainFallback(src, 0);
        assertEquals("ab§r\ncdefgh§r", out, "實際：" + escape(out));
        assertFalse(out.contains("…"), "實際：" + escape(out));
    }

    @Test
    @DisplayName("退回：maxLength=1 退化為單一省略號")
    void plainFallback_maxLengthOne_ellipsisOnly() {
        Component src = Component.text("ab\ncdefgh");
        String out = FormText.plainFallback(src, 1);
        assertEquals("…§r", out, "實際：" + escape(out));
    }

    @Test
    @DisplayName("退回：空字串與 null 輸入安全")
    void plainFallback_emptyAndNull_safe() {
        assertEquals("§r", FormText.plainFallback(Component.text(""), 4));
        assertEquals("", FormText.plainFallback(null, 4));
        assertDoesNotThrow(() -> FormText.plainFallback(null, 0));
    }

    @Test
    @DisplayName("退回：已帶樣式的輸入去樣式且守住長度")
    void plainFallback_styledInput_plainAndBounded() {
        Component src = Component.text("hello").color(NamedTextColor.RED)
            .decorate(TextDecoration.BOLD)
            .append(Component.text("world").color(NamedTextColor.BLUE));
        String out = FormText.plainFallback(src, 4);
        // plain 序列化本就無樣式碼：helloworld 取 3 字＋…＝4 可見字元
        assertEquals("hel…§r", out, "實際：" + escape(out));
        assertTrue(visibleLength(out) <= 4, "實際：" + escape(out));
    }

    // ---- 多執行緒 ----

    @Test
    @DisplayName("多執行緒同時呼叫不出錯")
    void render_concurrent_noErrors() throws Exception {        Component src = Component.text("concurrent").color(NamedTextColor.GREEN);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return FormText.render(src);
                }));
            }
            start.countDown();
            for (Future<String> f : futures) {
                String out = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(out.contains("concurrent"), "實際：" + escape(out));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- rainbow ----

    @Test
    @DisplayName("rainbow 降為 16 色且不含 §x（多字母）")
    void render_rainbow_downgradedToNamed() {
        Component src = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize("<rainbow>hello</rainbow>");
        String out = FormText.render(src);
        assertEquals("§4h§6e§2l§1l§5o§r", out, "實際：" + escape(out));
        assertFalse(out.contains("§x"), "實際：" + escape(out));
    }

    @Test
    @DisplayName("rainbow 降為 16 色且不含 §x（單一字母）")
    void render_rainbow_singleChar_downgradedToNamed() {
        Component src = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize("<rainbow>h</rainbow>");
        String out = FormText.render(src);
        assertEquals("§4h§r", out, "實際：" + escape(out));
        assertFalse(out.contains("§x"), "實際：" + escape(out));
    }

    @Test
    @DisplayName("gradient 單一字母降為具名色")
    void render_gradient_singleChar_downgradedToNamed() {
        Component src = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize("<gradient:red:blue>h</gradient>");
        String out = FormText.render(src);
        assertEquals("§ch§r", out, "實際：" + escape(out));
        assertFalse(out.contains("§x"), "實際：" + escape(out));
    }

    // ---- helper ----

    /** 16 色具名色碼（§0-9a-f；不含 §r／§l／§o／§k／§n／§m／§x）。 */
    private static final Pattern NAMED_COLOR_CODE = Pattern.compile("§[0-9a-f]");

    static void assertHasNamedColorCode(String out, String context) {
        assertTrue(NAMED_COLOR_CODE.matcher(out).find(),
            context + "：應產出具名色碼，實際：" + escape(out));
    }

    static int visibleLength(String legacy) {
        int count = 0;
        for (int i = 0; i < legacy.length();) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                i += 2;
                continue;
            }
            if (c == '\n') {
                i++;
                continue;
            }
            int cp = legacy.codePointAt(i);
            count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    static String stripCodes(String legacy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < legacy.length();) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("§", "<S>").replace("\n", "<NL>");
    }
}
