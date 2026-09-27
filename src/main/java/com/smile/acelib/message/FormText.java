package com.smile.acelib.message;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;

/**
 * Adventure Component → 基岩表單可安全顯示字串（Supported API）。
 *
 * <p>讓下游用同一份 MiniMessage 語系同時餵 Java 聊天與基岩表單，
 * 不必自行處理 legacy 色碼或 click / hover。轉換規則：</p>
 * <ul>
 *   <li>遞迴移除 {@link ClickEvent}（含 hover 內 Component payload 中的 click）；
 *       {@code clickHints} 為 true 時在原文字後附加可讀提示，提示本身不帶 click；</li>
 *   <li>移除 {@code HoverEvent}（表單沒有 hover，hover 內容不進入輸出）；</li>
 *   <li>hex 色、gradient、rainbow 一律降為最接近的 16 色具名色，輸出不含 {@code §x}；</li>
 *   <li>保留粗體（{@code §l}）、斜體（{@code §o}）、混淆（{@code §k}），
 *       移除底線與刪除線（輸出不含 {@code §n}／{@code §m}）；</li>
 *   <li>{@code translatable} 以 {@link GlobalTranslator} 依 locale 解析，
 *       解析不到用 fallback，都沒有保留 key（只讀，不註冊或移除來源）；</li>
 *   <li>換行保留，每行結尾補 {@code §r}，最終輸出亦以 {@code §r} 結尾；
 *       跨行延續的樣式在下一行重新套用；</li>
 *   <li>{@code maxLength} 為 0 不截斷；正值以完整可見字元計數
 *       （色碼與換行不計入，省略號 {@code …} 計入上限），
 *       不切斷代理對、組合字元序列、emoji，也不切在色碼中間；</li>
 *   <li>渲染內部失敗記錄 {@code ACELIB-MSG-005} warning 並回傳純文字版本
 *       （仍遵守長度限制與安全清理），不拋例外。</li>
 * </ul>
 *
 * <p>本類別可在任意執行緒呼叫，不依賴插件啟用狀態。但因會讀取全域翻譯來源
 * （{@link GlobalTranslator} 進程單例）並在失敗時記錄警告，
 * 不宣稱為嚴格純函式。</p>
 *
 * @since 1.3.0
 */
public final class FormText {

    /** 表單文字渲染失敗的錯誤代碼。 */
    static final String ERR_FORM_TEXT_RENDER = "ACELIB-MSG-005";

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private static final char SECTION = '§';

    private static final String ELLIPSIS = "…";

    private FormText() {
    }

    /**
     以預設選項渲染。
     *
     * @param component 來源 Component；不可為 null
     * @return 基岩表單可安全顯示的字串；never null
     * @throws IllegalArgumentException 當 {@code component} 為 null
     */
    public static String render(Component component) {
        if (component == null) {
            throw new IllegalArgumentException("form text component must not be null");
        }
        return render(component, FormTextOptions.defaults());
    }

    /**
     * 以指定選項渲染。
     *
     * @param component 來源 Component；不可為 null
     * @param options   渲染選項；不可為 null
     * @return 基岩表單可安全顯示的字串；never null
     * @throws IllegalArgumentException 當任一參數為 null
     */
    public static String render(Component component, FormTextOptions options) {
        if (component == null) {
            throw new IllegalArgumentException("form text component must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("form text options must not be null");
        }
        Locale locale = options.locale() != null ? options.locale() : Locale.ROOT;
        return renderInternal(component, locale, options.clickHints(), options.maxLength(),
            FormText::builtinHint);
    }

    /**
     * 完整管線（package-private；供 {@link MessageService} 重用插件語系提示）。
     *
     * @param component    來源 Component；不可為 null
     * @param locale       翻譯與提示的 locale；不可為 null
     * @param clickHints   是否附加可讀提示
     * @param maxLength    可見字元上限；0 不截斷
     * @param hintProvider click → 可讀提示；不得回傳帶 click 的 Component
     * @return 安全字串；never null，不拋例外（VM 致命錯誤除外）
     */
    static String renderInternal(Component component,
            Locale locale,
            boolean clickHints,
            int maxLength,
            BiFunction<ClickEvent, Locale, Component> hintProvider) {
        try {
            Component resolved = GlobalTranslator.render(component, locale);
            Component downgraded = downgradeColors(resolved);
            Component noUnderline = stripUnsupportedDecorations(downgraded);
            Component noClick = clickHints
                ? BedrockFallbackRenderer.render(noUnderline, locale, hintProvider)
                : BedrockFallbackRenderer.stripClickEvents(noUnderline);
            Component noHover = stripHover(noClick);
            String legacy = legacySerialize(noHover);
            String withResets = applyLineResets(legacy);
            return truncate(withResets, maxLength);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                "[" + ERR_FORM_TEXT_RENDER + "] form text render failed; "
                    + "returning plain-text fallback: " + ex.getMessage(), ex);
            return plainFallback(component, maxLength);
        }
    }

    /**
     * 純文字退回（package-private；可單測）：仍套用換行 {@code §r} 與長度限制。
     *
     * @param component 來源 Component；可為 null（→ 空字串）
     * @param maxLength 可見字元上限；0 不截斷
     * @return 純文字安全字串；never null
     */
    static String plainFallback(Component component, int maxLength) {
        if (component == null) {
            return "";
        }
        try {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            return truncate(applyLineResets(plain), maxLength);
        } catch (Exception ex) {
            return "";
        }
    }

    private static Component builtinHint(ClickEvent click, Locale locale) {
        ClickEventCompat.Descriptor d = ClickEventCompat.describe(click);
        String prefix = switch (d.kind) {
            case RUN_COMMAND -> "[Run command: ";
            case SUGGEST_COMMAND -> "[Suggest command: ";
            case OPEN_URL -> "[Open URL: ";
            case COPY_TO_CLIPBOARD -> "[Copy to clipboard: ";
            default -> "[Action: ";
        };
        String payload = d.payload == null ? "" : d.payload;
        return Component.text(prefix + payload + "]");
    }

    private static Component downgradeColors(Component component) {
        TextColor color = component.color();
        Component result = component;
        if (color != null && !(color instanceof NamedTextColor)) {
            result = result.color(NamedTextColor.nearestTo(color));
        }
        List<Component> children = result.children();
        if (!children.isEmpty()) {
            List<Component> mapped = new ArrayList<>(children.size());
            for (Component child : children) {
                mapped.add(downgradeColors(child));
            }
            result = result.children(mapped);
        }
        return result;
    }

    private static Component stripUnsupportedDecorations(Component component) {
        Component result = component
            .decoration(TextDecoration.UNDERLINED, false)
            .decoration(TextDecoration.STRIKETHROUGH, false);
        List<Component> children = result.children();
        if (!children.isEmpty()) {
            List<Component> mapped = new ArrayList<>(children.size());
            for (Component child : children) {
                mapped.add(stripUnsupportedDecorations(child));
            }
            result = result.children(mapped);
        }
        return result;
    }

    private static Component stripHover(Component component) {
        List<Component> children = component.children();
        List<Component> mapped = new ArrayList<>(children.size());
        for (Component child : children) {
            mapped.add(stripHover(child));
        }
        Component result = component.hoverEvent(null);
        return result.children(mapped);
    }

    private static String legacySerialize(Component component) {
        return LegacyComponentSerializer.builder()
            .character(SECTION)
            .build()
            .serialize(component);
    }

    /**
     * 每行結尾補 {@code §r}（已是 {@code §r} 結尾不重複），
     * 跨行延續的樣式在下一行開頭重新套用。
     */
    static String applyLineResets(String legacy) {
        String[] lines = legacy.split("\n", -1);
        StringBuilder out = new StringBuilder(legacy.length() + lines.length * 2);
        String carry = "";
        for (int i = 0; i < lines.length; i++) {
            String line = carry + lines[i];
            carry = scanCarry(line);
            if (!line.endsWith(SECTION + "r")) {
                line = line + SECTION + "r";
            }
            out.append(line);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String scanCarry(String line) {
        String color = null;
        boolean bold = false;
        boolean italic = false;
        boolean obfuscated = false;
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) != SECTION) {
                continue;
            }
            char code = Character.toLowerCase(line.charAt(i + 1));
            switch (code) {
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                     'a', 'b', 'c', 'd', 'e', 'f' -> {
                    color = String.valueOf(SECTION) + line.charAt(i + 1);
                    bold = false;
                    italic = false;
                    obfuscated = false;
                }
                case 'l' -> bold = true;
                case 'o' -> italic = true;
                case 'k' -> obfuscated = true;
                case 'r' -> {
                    color = null;
                    bold = false;
                    italic = false;
                    obfuscated = false;
                }
                default -> {
                }
            }
            i++;
        }
        StringBuilder carry = new StringBuilder();
        if (color != null) {
            carry.append(color);
        }
        if (bold) {
            carry.append(SECTION).append('l');
        }
        if (italic) {
            carry.append(SECTION).append('o');
        }
        if (obfuscated) {
            carry.append(SECTION).append('k');
        }
        return carry.toString();
    }

    /**
     * 以完整可見字元截斷（package-private；可單測）。
     *
     * <p>可見字元 = 去除色碼（{@code §X}）後的實際顯示字元，換行不算可見字元
     * （與 {@link #countVisible(String)} 一致）。切割以 {@link BreakIterator}
     * 字元邊界進行，不切斷代理對、組合字元或 emoji。</p>
     */
    static String truncate(String text, int maxLength) {
        if (maxLength <= 0) {
            return text;
        }
        if (countVisible(text) <= maxLength) {
            return text;
        }
        int budget = maxLength - ELLIPSIS.length();
        StringBuilder out = new StringBuilder();
        int visible = 0;
        BreakIterator boundary = BreakIterator.getCharacterInstance(Locale.ROOT);
        boundary.setText(text);
        int i = 0;
        while (i < text.length() && visible < budget) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) {
                out.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '\n') {
                out.append(c);
                i++;
                continue;
            }
            int next = boundary.following(i);
            if (next == BreakIterator.DONE) {
                next = text.length();
            }
            if (next <= i) {
                next = i + Character.charCount(text.codePointAt(i));
            }
            out.append(text, i, Math.min(next, text.length()));
            i = Math.min(next, text.length());
            visible++;
        }
        out.append(ELLIPSIS);
        if (!out.toString().endsWith(SECTION + "r")) {
            out.append(SECTION).append('r');
        }
        return out.toString();
    }

    static int countVisible(String text) {
        int count = 0;
        BreakIterator boundary = BreakIterator.getCharacterInstance(Locale.ROOT);
        boundary.setText(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == '\n') {
                i++;
                continue;
            }
            int next = boundary.following(i);
            if (next == BreakIterator.DONE) {
                next = text.length();
            }
            if (next <= i) {
                next = i + Character.charCount(text.codePointAt(i));
            }
            i = Math.min(next, text.length());
            count++;
        }
        return count;
    }
}
