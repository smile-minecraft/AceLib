package com.smile.acelib.form;

/**
 * custom 表單元件的單一答案（Supported API）。
 *
 * <p>{@link com.smile.acelib.form.FormSpec.Custom} 的六種元件中，label 為純靜態
 * 文字、不產生答案；其餘四類元件各以一個 nested record 承載回答：</p>
 *
 * <ul>
 *   <li>{@link Text} — 單行文字輸入框（input）的回答。</li>
 *   <li>{@link Option} — 下拉選單（dropdown）或步階滑桿（step slider）的選擇索引。</li>
 *   <li>{@link Number} — 數值滑桿（slider）的選擇值。</li>
 *   <li>{@link Switch} — 開關（toggle）的布林狀態。</li>
 * </ul>
 *
 * <p>答案順序遵循 {@code FormSpec.Custom.components()} 中「會產值的元件」相對順序；
 * label 不佔位。本型別為 sealed：元件種類固定，消費者可窮舉 switch。</p>
 *
 * @since 1.0.0
 */
public sealed interface FormValue permits FormValue.Text, FormValue.Option,
        FormValue.Number, FormValue.Switch {

    /** 單行文字輸入框的回答；value 永不為 null。 */
    record Text(String value) implements FormValue {

        /** @throws NullPointerException value 為 null */
        public Text {
            java.util.Objects.requireNonNull(value, "value");
        }
    }

    /** 下拉選單或步階滑桿的回答：被選項目的索引（0 起）。 */
    record Option(int index) implements FormValue {
    }

    /** 數值滑桿的回答：玩家選擇的數值。 */
    record Number(float value) implements FormValue {
    }

    /** 開關的回答：開（true）或關（false）。 */
    record Switch(boolean on) implements FormValue {
    }
}
