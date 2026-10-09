package com.smile.acelib.message;

/**
 * 基岩版玩家訊息降級的風格（Supported API）。
 *
 * <ul>
 *   <li>{@link #HINTS} — 預設：移除失效的 {@code ClickEvent}，在原文字後附加
 *       可讀的操作提示（語系來自插件語言檔，缺 key 時用內建安全預設文字）。
 *       正文、顏色與裝飾保留。</li>
 *   <li>{@link #PLAIN_TEXT} — 攤平：整棵 Component 攤成純文字後送出
 *       （無 click、無顏色與裝飾，只剩可讀文字）。適合基岩客戶端顯示異常、
 *       只需要文字內容的頻道。</li>
 * </ul>
 *
 * <p>既有 {@code *WithFallback} 入口與不帶風格參數的
 * {@code *WithFallbackResult} 入口一律使用 {@link #HINTS}，行為與 AceLib 1.4.0
 * 一致；只有明確傳入 {@link #PLAIN_TEXT} 時才攤平。</p>
 *
 * @since 1.5.0
 */
public enum BedrockFallbackStyle {
    /** 預設：click 轉可讀提示，保留樣式。 */
    HINTS,
    /** 攤平：整體轉純文字，不帶任何互動與樣式。 */
    PLAIN_TEXT
}
