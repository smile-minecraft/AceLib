package com.smile.acelib.form;

import java.util.ArrayList;
import java.util.List;

/**
 * 基岩原生表單規格（AceLib 自有 DSL，Supported API）。
 *
 * <p>下游插件以 fluent builder 描述表單內容，產出不可變的 {@link FormSpec}；
 * 交給 {@link FormService#sendForm(java.util.UUID, FormSpec)} 發送。本型別是
 * AceLib 對外表單契約的唯一模型：Cumulus 型別只在內部翻譯層出現，
 * 不出現在任何公開簽章。</p>
 *
 * <h2>三種表單</h2>
 * <ul>
 *   <li>{@link #simple(String)} — 按鈕列表表單（標題＋說明＋一顆以上按鈕）。</li>
 *   <li>{@link #modal(String)} — 是／否確認對話框（兩顆按鈕可省略）。</li>
 *   <li>{@link #custom(String)} — 元件式表單（label / input / dropdown / slider /
 *       step slider / toggle）。</li>
 * </ul>
 *
 * <p>所有 builder 參數在呼叫當下即驗證（fail-fast）；{@code build()} 產出的
 * spec 為不可變物件，集合存取器回傳不可變快照。文字參數一律拒絕 null 與空白
 * （{@code String#isBlank}），以 {@link IllegalArgumentException} 拒絕並附可診斷訊息。</p>
 *
 * @since 1.0.0
 */
public abstract sealed class FormSpec permits FormSpec.Simple, FormSpec.Modal, FormSpec.Custom {

    /** 表單種類。 */
    public enum Kind {
        /** 按鈕列表表單。 */
        SIMPLE,
        /** 是／否確認對話框。 */
        MODAL,
        /** 元件式表單。 */
        CUSTOM
    }

    private final String title;

    /**
     * @param title 表單標題；必須非 null 且非空白
     * @throws IllegalArgumentException title 為 null 或空白
     */
    protected FormSpec(String title) {
        requireNonBlank(title, "title");
        this.title = title;
    }

    /** @return 表單標題；永不為 null */
    public final String title() {
        return title;
    }

    /** @return 表單種類；永不為 null */
    public abstract Kind kind();

    // -----------------------------------------------------------------
    // 工廠
    // -----------------------------------------------------------------

    /**
     * 開始建立按鈕列表表單。
     *
     * @param title 表單標題；必須非 null 且非空白
     * @return simple 表單 builder
     * @throws IllegalArgumentException title 為 null 或空白
     */
    public static Simple.Builder simple(String title) {
        return new Simple.Builder(title);
    }

    /**
     * 開始建立是／否確認對話框。
     *
     * @param title 表單標題；必須非 null 且非空白
     * @return modal 表單 builder
     * @throws IllegalArgumentException title 為 null 或空白
     */
    public static Modal.Builder modal(String title) {
        return new Modal.Builder(title);
    }

    /**
     * 開始建立元件式表單。
     *
     * @param title 表單標題；必須非 null 且非空白
     * @return custom 表單 builder
     * @throws IllegalArgumentException title 為 null 或空白
     */
    public static Custom.Builder custom(String title) {
        return new Custom.Builder(title);
    }

    /**
     * 共用驗證：文字參數不得為 null 或空白。
     *
     * <p>輸入驗證屬於程式設計錯誤防護，以 {@link IllegalArgumentException} 拒絕；
     * 不佔用 {@code ACELIB-FORM-*} 運行期錯誤代碼（registry 未登記輸入驗證碼）。</p>
     */
    static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "form " + fieldName + " must not be null or blank");
        }
    }

    // =================================================================
    // Simple
    // =================================================================

    /** 按鈕列表表單（標題＋說明＋一顆以上按鈕）。 */
    public static final class Simple extends FormSpec {

        private final String content;
        private final List<String> buttons;

        private Simple(Builder builder) {
            super(builder.title);
            this.content = builder.content;
            this.buttons = List.copyOf(builder.buttons);
        }

        /** @return 表單說明文字；永不為 null */
        public String content() {
            return content;
        }

        /** @return 按鈕文字清單（依加入順序）；不可變、至少一個元素 */
        public List<String> buttons() {
            return buttons;
        }

        @Override
        public Kind kind() {
            return Kind.SIMPLE;
        }

        /** {@link Simple} 的 fluent builder。 */
        public static final class Builder {

            private final String title;
            private String content;
            private final List<String> buttons = new ArrayList<>();

            private Builder(String title) {
                // 先通過 title 驗證（與父類別建構路徑一致）
                requireNonBlank(title, "title");
                this.title = title;
            }

            /**
             * 設定表單說明文字。
             *
             * @param content 說明文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException content 為 null 或空白
             */
            public Builder content(String content) {
                requireNonBlank(content, "content");
                this.content = content;
                return this;
            }

            /**
             * 新增一顆按鈕。
             *
             * @param text 按鈕文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException text 為 null 或空白
             */
            public Builder button(String text) {
                requireNonBlank(text, "button text");
                buttons.add(text);
                return this;
            }

            /**
             * 產出不可變的 simple 表單 spec。
             *
             * @return 不可變的 {@link Simple}
             * @throws IllegalStateException 尚未設定 content
             * @throws IllegalArgumentException 沒有任何按鈕
             */
            public Simple build() {
                if (content == null) {
                    throw new IllegalStateException(
                        "simple form requires content(...) before build()");
                }
                if (buttons.isEmpty()) {
                    throw new IllegalArgumentException(
                        "simple form requires at least one button");
                }
                return new Simple(this);
            }
        }
    }

    // =================================================================
    // Modal
    // =================================================================

    /** 是／否確認對話框（兩顆按鈕可省略，省略時以空字串交由基岩端預設文案呈現）。 */
    public static final class Modal extends FormSpec {

        private final String content;
        private final String button1;
        private final String button2;

        private Modal(Builder builder) {
            super(builder.title);
            this.content = builder.content;
            this.button1 = builder.button1;
            this.button2 = builder.button2;
        }

        /** @return 表單說明文字；永不為 null */
        public String content() {
            return content;
        }

        /** @return 第一顆（主要）按鈕文字；省略時為空字串，永不為 null */
        public String button1() {
            return button1;
        }

        /** @return 第二顆（次要）按鈕文字；省略時為空字串，永不為 null */
        public String button2() {
            return button2;
        }

        @Override
        public Kind kind() {
            return Kind.MODAL;
        }

        /** {@link Modal} 的 fluent builder。 */
        public static final class Builder {

            private final String title;
            private String content;
            private String button1 = "";
            private String button2 = "";

            private Builder(String title) {
                requireNonBlank(title, "title");
                this.title = title;
            }

            /**
             * 設定表單說明文字。
             *
             * @param content 說明文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException content 為 null 或空白
             */
            public Builder content(String content) {
                requireNonBlank(content, "content");
                this.content = content;
                return this;
            }

            /**
             * 設定第一顆（主要）按鈕文字。
             *
             * @param text 按鈕文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException text 為 null 或空白
             */
            public Builder button1(String text) {
                requireNonBlank(text, "button1");
                this.button1 = text;
                return this;
            }

            /**
             * 設定第二顆（次要）按鈕文字。
             *
             * @param text 按鈕文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException text 為 null 或空白
             */
            public Builder button2(String text) {
                requireNonBlank(text, "button2");
                this.button2 = text;
                return this;
            }

            /**
             * 產出不可變的 modal 表單 spec。
             *
             * @return 不可變的 {@link Modal}
             * @throws IllegalStateException 尚未設定 content
             */
            public Modal build() {
                if (content == null) {
                    throw new IllegalStateException(
                        "modal form requires content(...) before build()");
                }
                return new Modal(this);
            }
        }
    }

    // =================================================================
    // Custom
    // =================================================================

    /** 元件式表單（label / input / dropdown / slider / step slider / toggle）。 */
    public static final class Custom extends FormSpec {

        /** 自訂表單元件（sealed：六種元件型別固定，翻譯層可窮舉）。 */
        public sealed interface Component permits Label, Input, Dropdown, Slider, StepSlider, Toggle {
            // marker：欄位由各 record 承載
        }

        /** 靜態文字標籤。 */
        public record Label(String text) implements Component {
        }

        /** 單行文字輸入框；placeholder / defaultText 允許 null（正規化為空字串）。 */
        public record Input(String label, String placeholder, String defaultText)
                implements Component {
        }

        /** 下拉選單；options 至少一項且不得含空白項，defaultOption 為選項索引。 */
        public record Dropdown(String label, List<String> options, int defaultOption)
                implements Component {

            /** 正規化建構子：options 複製為不可變清單。 */
            public Dropdown {
                options = List.copyOf(options);
            }
        }

        /** 數值滑桿；min &lt; max、step &gt; 0、defaultValue ∈ [min, max]，皆為有限值。 */
        public record Slider(String label, float min, float max, float step, float defaultValue)
                implements Component {
        }

        /** 步階滑桿；steps 至少一項且不得含空白項，defaultStep 為步階索引。 */
        public record StepSlider(String label, List<String> steps, int defaultStep)
                implements Component {

            /** 正規化建構子：steps 複製為不可變清單。 */
            public StepSlider {
                steps = List.copyOf(steps);
            }
        }

        /** 開關。 */
        public record Toggle(String label, boolean defaultValue) implements Component {
        }

        private final List<Component> components;

        private Custom(Builder builder) {
            super(builder.title);
            this.components = List.copyOf(builder.components);
        }

        /** @return 元件清單（依加入順序）；不可變、可為空清單 */
        public List<Component> components() {
            return components;
        }

        @Override
        public Kind kind() {
            return Kind.CUSTOM;
        }

        /** {@link Custom} 的 fluent builder。 */
        public static final class Builder {

            private final String title;
            private final List<Component> components = new ArrayList<>();

            private Builder(String title) {
                requireNonBlank(title, "title");
                this.title = title;
            }

            /** 新增靜態文字標籤。
             *
             * @param text 標籤文字；必須非 null 且非空白
             * @return this
             * @throws IllegalArgumentException text 為 null 或空白
             */
            public Builder label(String text) {
                requireNonBlank(text, "label text");
                components.add(new Label(text));
                return this;
            }

            /** 新增單行文字輸入框；placeholder / defaultText 允許 null（正規化為空字串）。
             *
             * @param label       元件標籤；必須非 null 且非空白
             * @param placeholder 佔位提示；可為 null
             * @param defaultText 預設文字；可為 null
             * @return this
             * @throws IllegalArgumentException label 為 null 或空白
             */
            public Builder input(String label, String placeholder, String defaultText) {
                requireNonBlank(label, "input label");
                components.add(new Input(label,
                    placeholder == null ? "" : placeholder,
                    defaultText == null ? "" : defaultText));
                return this;
            }

            /** 新增下拉選單（預設選第一項）。
             *
             * @param label   元件標籤；必須非 null 且非空白
             * @param options 選項；至少一項且皆非空白
             * @return this
             * @throws IllegalArgumentException 參數不合法
             */
            public Builder dropdown(String label, List<String> options) {
                return dropdown(label, options, 0);
            }

            /** 新增下拉選單。
             *
             * @param label         元件標籤；必須非 null 且非空白
             * @param options       選項；至少一項且皆非空白
             * @param defaultOption 預設選項索引；必須在選項範圍內
             * @return this
             * @throws IllegalArgumentException 參數不合法或索引越界
             */
            public Builder dropdown(String label, List<String> options, int defaultOption) {
                requireNonBlank(label, "dropdown label");
                requireNonBlankEntries(options, "dropdown options");
                if (defaultOption < 0 || defaultOption >= options.size()) {
                    throw new IllegalArgumentException(
                        "dropdown defaultOption out of range: " + defaultOption);
                }
                components.add(new Dropdown(label, options, defaultOption));
                return this;
            }

            /** 新增數值滑桿。
             *
             * @param label        元件標籤；必須非 null 且非空白
             * @param min          最小值；須小於 max 且為有限值
             * @param max          最大值；須為有限值
             * @param step         步幅；須大於 0 且為有限值
             * @param defaultValue 預設值；須落在 [min, max] 且為有限值
             * @return this
             * @throws IllegalArgumentException 參數不合法
             */
            public Builder slider(String label, float min, float max, float step,
                                  float defaultValue) {
                requireNonBlank(label, "slider label");
                requireFinite("slider min", min);
                requireFinite("slider max", max);
                requireFinite("slider step", step);
                requireFinite("slider defaultValue", defaultValue);
                if (!(min < max)) {
                    throw new IllegalArgumentException(
                        "slider requires min < max, got: " + min + " >= " + max);
                }
                if (!(step > 0f)) {
                    throw new IllegalArgumentException("slider requires step > 0, got: " + step);
                }
                if (defaultValue < min || defaultValue > max) {
                    throw new IllegalArgumentException(
                        "slider defaultValue out of range [" + min + ", " + max + "]: "
                            + defaultValue);
                }
                components.add(new Slider(label, min, max, step, defaultValue));
                return this;
            }

            /** 新增步階滑桿（預設停在第一步）。
             *
             * @param label 元件標籤；必須非 null 且非空白
             * @param steps 步階文字；至少一項且皆非空白
             * @return this
             * @throws IllegalArgumentException 參數不合法
             */
            public Builder stepSlider(String label, List<String> steps) {
                return stepSlider(label, steps, 0);
            }

            /** 新增步階滑桿。
             *
             * @param label       元件標籤；必須非 null 且非空白
             * @param steps       步階文字；至少一項且皆非空白
             * @param defaultStep 預設步階索引；必須在範圍內
             * @return this
             * @throws IllegalArgumentException 參數不合法或索引越界
             */
            public Builder stepSlider(String label, List<String> steps, int defaultStep) {
                requireNonBlank(label, "stepSlider label");
                requireNonBlankEntries(steps, "stepSlider steps");
                if (defaultStep < 0 || defaultStep >= steps.size()) {
                    throw new IllegalArgumentException(
                        "stepSlider defaultStep out of range: " + defaultStep);
                }
                components.add(new StepSlider(label, steps, defaultStep));
                return this;
            }

            /** 新增開關。
             *
             * @param label        元件標籤；必須非 null 且非空白
             * @param defaultValue 預設開關狀態
             * @return this
             * @throws IllegalArgumentException label 為 null 或空白
             */
            public Builder toggle(String label, boolean defaultValue) {
                requireNonBlank(label, "toggle label");
                components.add(new Toggle(label, defaultValue));
                return this;
            }

            /**
             * 產出不可變的 custom 表單 spec；允許零元件（純標題表單）。
             *
             * @return 不可變的 {@link Custom}
             */
            public Custom build() {
                return new Custom(this);
            }

            private static void requireNonBlankEntries(List<String> entries, String fieldName) {
                if (entries == null || entries.isEmpty()) {
                    throw new IllegalArgumentException(
                        "form " + fieldName + " must not be null or empty");
                }
                for (String entry : entries) {
                    if (entry == null || entry.isBlank()) {
                        throw new IllegalArgumentException(
                            "form " + fieldName + " must not contain null or blank entries");
                    }
                }
            }

            private static void requireFinite(String fieldName, float value) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(
                        "form " + fieldName + " must be finite, got: " + value);
                }
            }
        }
    }
}
