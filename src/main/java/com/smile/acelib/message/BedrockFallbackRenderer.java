package com.smile.acelib.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * 基岩版玩家 click 互動降級 renderer（Internal / package-private）。
 *
 * <p>將 {@link Component} 樹中的 {@link ClickEvent} 替換為可見且可讀的 action 提示，
 * 同時保留正文、style、nested children 與 hover。設計目標：</p>
 * <ul>
 *   <li>只有明確的基岩版玩家才會進入此 renderer（由 {@link MessageService} 判斷）；</li>
 *   <li>四種 action（RUN_COMMAND / SUGGEST_COMMAND / OPEN_URL / COPY_TO_CLIPBOARD）
 *       都只產生「可讀提示文字」，<strong>不</strong>執行 command、不開 URL、不操作 clipboard；</li>
 *   <li>輸出 tree 不得殘留任何 {@link ClickEvent}（含提示本身與 hover 內可遞迴 Component，防範 lang 模板誤植 click）；</li>
 *   <li>沒有 click 的 Component 原樣回傳，不被不必要改寫。</li>
 * </ul>
 *
 * <p>本類別為純函式轉換，不依賴 Bukkit / BedrockService / LangManager，便於單獨測試。
 * HoverEvent 外層與非 Component payload（如 showItem/showEntity）予以保留；
 * 僅當 hover value 可安全辨識為 Adventure Component（SHOW_TEXT）時，才遞迴清理其中 ClickEvent。</p>
 */
final class BedrockFallbackRenderer {

    private BedrockFallbackRenderer() {
    }

    /**
     * 對 source 執行 click 降級。
     *
     * @param source       原始 Component；可為 null（呼叫方應先保證非 null）
     * @param locale       fallback prompt 的 locale（傳給 hintProvider）
     * @param hintProvider 將單一 {@link ClickEvent} 轉為可讀提示 Component 的函式；
     *                    不得回傳帶 {@link ClickEvent} 的 Component（本方法會再剝離一次以防禦）
     * @return 降級後的 Component；若 source 完全不含 click，則原樣回傳同一實例
     */
    static Component render(Component source,
                           Locale locale,
                           BiFunction<ClickEvent, Locale, Component> hintProvider) {
        if (!containsClickEvent(source)) {
            return source;
        }
        return renderRecursive(source, locale, hintProvider);
    }

    private static boolean containsClickEvent(Component c) {
        if (c.clickEvent() != null) {
            return true;
        }
        HoverEvent<?> hover = c.hoverEvent();
        if (hover != null && hover.value() instanceof Component hoverComp) {
            if (containsClickEvent(hoverComp)) {
                return true;
            }
        }
        for (Component child : c.children()) {
            if (containsClickEvent(child)) {
                return true;
            }
        }
        return false;
    }

    private static Component renderRecursive(Component c,
                                            Locale locale,
                                            BiFunction<ClickEvent, Locale, Component> hintProvider) {
        List<Component> newChildren = new ArrayList<>();
        for (Component child : c.children()) {
            newChildren.add(renderRecursive(child, locale, hintProvider));
        }
        HoverEvent<?> origHover = c.hoverEvent();
        HoverEvent<?> sanitizedHover = sanitizeHover(origHover);
        ClickEvent click = c.clickEvent();
        if (click != null) {
            // 移除本節點的 click event，保留其餘內容（正文 / style / hover）。
            Component withoutClick = c.clickEvent(null);
            if (sanitizedHover != origHover) {
                withoutClick = withoutClick.hoverEvent(sanitizedHover);
            }
            Component hint = hintProvider.apply(click, locale);
            if (hint != null) {
                // 防禦：即使 lang 模板誤植 click（含 hover 內嵌 click），也確保提示本身不含 executable ClickEvent。
                newChildren.add(stripClickEvents(hint));
            }
            return withoutClick.children(newChildren);
        }
        if (sanitizedHover != origHover) {
            return c.hoverEvent(sanitizedHover).children(newChildren);
        }
        return c.children(newChildren);
    }

    /**
     * 遞迴剝離整棵 Component 樹中的所有 {@link ClickEvent}（不附加任何提示）。
     * 同時清理 hover 內可辨識為 Component 的 payload，避免透過 hover 殘留可執行 click。
     */
    static Component stripClickEvents(Component c) {
        List<Component> newChildren = new ArrayList<>();
        for (Component child : c.children()) {
            newChildren.add(stripClickEvents(child));
        }
        HoverEvent<?> origHover = c.hoverEvent();
        HoverEvent<?> sanitizedHover = sanitizeHover(origHover);
        Component result = c.clickEvent(null);
        if (sanitizedHover != origHover) {
            result = result.hoverEvent(sanitizedHover);
        }
        return result.children(newChildren);
    }

    /**
     * 若 hover 為 Component-valued（SHOW_TEXT）且內含 ClickEvent，則遞迴清理該 Component；
     * 非 Component payload（showItem/showEntity）與無 click 的 hover 原樣保留，避免因未驗證 Bedrock hover 而刪除外層。
     */
    private static HoverEvent<?> sanitizeHover(HoverEvent<?> hover) {
        if (hover == null) {
            return null;
        }
        Object v = hover.value();
        if (!(v instanceof Component hoverComp)) {
            return hover;
        }
        if (!containsClickEvent(hoverComp)) {
            return hover;
        }
        Component sanitized = stripClickEvents(hoverComp);
        if (hover.action() == HoverEvent.Action.SHOW_TEXT) {
            return HoverEvent.showText(sanitized);
        }
        @SuppressWarnings("unchecked")
        HoverEvent<Component> rebuilt =
            (HoverEvent<Component>) HoverEvent.hoverEvent(
                (HoverEvent.Action<Component>) hover.action(), sanitized);
        return rebuilt;
    }
}
