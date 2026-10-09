package com.smile.acelib.message;

import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * 單次渲染結果：聊天、ActionBar、GUI 與表單共用的同一份輸出。
 *
 * <p>由 {@link MessageService#render(String, java.util.Map)} 產生：
 * 同一個語系模板只讀取、替換、解析一次，三種呈現（{@link #component}、
 * {@link #text}、{@link #formText}）皆由該次結果衍生，不會各自重讀語言檔。</p>
 *
 * <ul>
 *   <li>{@link #component} — 富文字（含 {@code message.prefix}），供聊天／GUI 使用</li>
 *   <li>{@link #text} — 與 {@code format} 輸出一致的字串（含 prefix，保留 MiniMessage
 *       標記不解析），供 ActionBar／title／console 相容路徑使用</li>
 *   <li>{@link #formText} — 基岩表單安全字串（不帶 prefix），供表單使用</li>
 *   <li>{@link #missing}／{@link #diagnosis} — 缺 key 或渲染失敗時的可診斷資訊；
 *       正常時 {@code missing} 為 false 且 {@code diagnosis} 為空字串</li>
 * </ul>
 *
 * @param key 訊息 key；never null
 * @param locale 實際使用的語系；never null
 * @param component 富文字結果；never null（缺 key 時為 {@link Component#empty()}）
 * @param text 與 {@code format} 輸出一致的字串結果（含 prefix，保留 MiniMessage
 *             標記不解析）；never null（缺 key 時為空字串）
 * @param formText 表單安全字串；never null（缺 key 時為空字串）
 * @param missing 模板在磁碟／內建各層皆缺失時為 true
 * @param diagnosis 診斷字串（含 {@code ACELIB-MSG-*} 代碼）；正常時為空字串
 * @since 1.4.0
 */
public record RenderedMessage(
        String key,
        Locale locale,
        Component component,
        String text,
        String formText,
        boolean missing,
        String diagnosis) {

    /**
     * 緊湊建構子：拒絕 null 欄位（缺 key 時以空值表達，不以 null 表達）。
     */
    public RenderedMessage {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(locale, "locale");
        java.util.Objects.requireNonNull(component, "component");
        java.util.Objects.requireNonNull(text, "text");
        java.util.Objects.requireNonNull(formText, "formText");
        java.util.Objects.requireNonNull(diagnosis, "diagnosis");
    }
}
