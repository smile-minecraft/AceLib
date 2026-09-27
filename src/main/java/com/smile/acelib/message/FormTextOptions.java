package com.smile.acelib.message;

import java.util.Locale;

/**
 * 表單文字渲染選項（Supported API）。
 *
 * @param clickHints 是否在原本帶 click 的文字之後附加可讀提示；
 *                   靜態路徑使用內建提示（不讀語言檔），
 *                   {@link MessageService} 路徑使用插件語系提示
 * @param maxLength  可見字元上限（色碼不計入，省略號計入）；0 表示不截斷；
 *                   負數以 {@link IllegalArgumentException} 拒絕
 * @param locale     翻譯與提示的 locale；null 表示由呼叫路徑決定
 *                   （靜態路徑用 {@link Locale#ROOT}，
 *                   {@link MessageService} 路徑用預設語系）
 * @since 1.3.0
 */
public record FormTextOptions(boolean clickHints, int maxLength, Locale locale) {

    /**
     * 緊湊建構子：負數 {@code maxLength} 直接拒絕。
     *
     * @throws IllegalArgumentException 當 {@code maxLength} 為負數
     */
    public FormTextOptions {
        if (maxLength < 0) {
            throw new IllegalArgumentException("form text maxLength must not be negative: " + maxLength);
        }
    }

    /**
     * 預設選項：不加提示、不截斷、locale 由呼叫路徑決定。
     *
     * @return {@code (false, 0, null)}；never null
     */
    public static FormTextOptions defaults() {
        return new FormTextOptions(false, 0, null);
    }
}
