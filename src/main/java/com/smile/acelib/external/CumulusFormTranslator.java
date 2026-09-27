package com.smile.acelib.external;

import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geysermc.cumulus.component.util.ComponentType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.cumulus.response.ModalFormResponse;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.response.result.FormResponseResult;
import org.geysermc.cumulus.response.result.ResultType;
import org.geysermc.cumulus.response.result.ValidFormResponseResult;

/**
 * AceLib FormSpec → Cumulus form 翻譯層（package-private，Internal）。
 *
 * <p>這是 {@code org.geysermc.cumulus.*} 型別在表單路徑的唯一產出點：
 * 以窮舉 pattern matching（{@link FormSpec} 為 sealed 型別）把 AceLib 自有
 * DSL 逐欄翻成 Cumulus 1.1 的 form 物件。翻譯為純函式——不觸發任何
 * Floodgate 查詢、不持有狀態（單一例外：圖示退回時寫一筆
 * {@code ACELIB-FORM-003} warning 日誌，見 {@link #addButton}）。</p>
 *
 * <p>回應路徑：handler 由本層掛在 builder 上（{@code resultHandler}），
 * Cumulus 原始回應的解析與映射（{@code ResultType} →
 * {@link FormResponseStatus}、回應內容 → {@link FormValue} 清單）也在本層完成；
 * form 套件不見任何 Cumulus 型別。Floodgate 於未知執行緒觸發 handler。</p>
 *
 * @since 1.0.0
 */
final class CumulusFormTranslator {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private CumulusFormTranslator() {
        // utility class
    }

    /**
     * 把 AceLib 表單規格翻成對應的 Cumulus form。
     *
     * @param spec 表單規格；不可為 null
     * @return Cumulus form；never null
     * @throws NullPointerException spec 為 null
     */
    static Form toCumulus(FormSpec spec) {
        return switch (spec) {
            case FormSpec.Simple simple -> simple(simple, null, CumulusFormTranslator::defaultImage);
            case FormSpec.Modal modal -> modal(modal, null);
            case FormSpec.Custom custom -> custom(custom, null);
        };
    }

    /**
     * 把 AceLib 表單規格翻成對應的 Cumulus form，並掛接回應 handler。
     *
     * @param spec       表單規格；不可為 null
     * @param onResponse 已映射回應的接收端；不可為 null（Floodgate 於未知執行緒觸發）
     * @return Cumulus form；never null
     * @throws NullPointerException 任一參數為 null
     */
    static Form toCumulus(FormSpec spec, Consumer<FormResponse> onResponse) {
        Objects.requireNonNull(onResponse, "onResponse");
        return toCumulus(spec, onResponse, CumulusFormTranslator::defaultImage);
    }

    /**
     * 測試接縫：同上，但 simple 表單的圖示映射經由 {@code imageMapper} 進行。
     *
     * <p>接縫以參數傳遞（無全域可變狀態）：預設映射見 {@link #defaultImage}。
     * Cumulus 1.1.2 的 {@code FormImage.of}／{@code ButtonComponent.of}／
     * {@code SimpleForm.Builder.button} 對合法輸入不做內容驗證，真實失敗僅來自
     * 未預期的運行期例外；測試以拋例外的 mapper 重現該路徑。</p>
     *
     * @param spec 表單規格；不可為 null
     * @param onResponse 已映射回應的接收端；不可為 null
     * @param imageMapper AceLib 圖示 → Cumulus 圖示的映射；不可為 null
     * @return Cumulus form；never null
     * @throws NullPointerException 任一參數為 null
     */
    static Form toCumulus(FormSpec spec, Consumer<FormResponse> onResponse,
            Function<FormImage, org.geysermc.cumulus.util.FormImage> imageMapper) {
        Objects.requireNonNull(onResponse, "onResponse");
        Objects.requireNonNull(imageMapper, "imageMapper");
        return switch (spec) {
            case FormSpec.Simple simple -> simple(simple, onResponse, imageMapper);
            case FormSpec.Modal modal -> modal(modal, onResponse);
            case FormSpec.Custom custom -> custom(custom, onResponse);
        };
    }

    private static Form simple(FormSpec.Simple spec, Consumer<FormResponse> onResponse,
            Function<FormImage, org.geysermc.cumulus.util.FormImage> imageMapper) {
        SimpleForm.Builder builder = SimpleForm.builder()
            .title(spec.title())
            .content(spec.content());
        for (FormSpec.Simple.Button entry : spec.buttonEntries()) {
            addButton(builder, entry, imageMapper);
        }
        if (onResponse != null) {
            builder.resultHandler((form, result) -> onResponse.accept(
                mapResult(result,
                    response -> ((SimpleFormResponse) response).clickedButtonId(),
                    response -> List.of())));
        }
        return builder.build();
    }

    /**
     * 加入單顆按鈕：無圖示者為純文字；有圖示者經 {@code imageMapper} 映射。
     *
     * <p>映射失敗時該按鈕退回純文字並記錄 {@code ACELIB-FORM-003} warning，
     * 表單其餘部分不受影響。Cumulus 的 {@code button(text, image)} 為單一
     * {@code buttons.add(...)} 陳述式：映射（引數求值）先於加入執行，拋例外時
     * 該按鈕尚未加入；catch 內只加一次純文字按鈕，故不重複加入、後續索引不變。
     * 環境性 {@link LinkageError}（如 Cumulus 缺席）不在此處理，必須原樣傳播。</p>
     */
    private static void addButton(SimpleForm.Builder builder, FormSpec.Simple.Button entry,
            Function<FormImage, org.geysermc.cumulus.util.FormImage> imageMapper) {
        Optional<FormImage> image = entry.image();
        if (image.isEmpty()) {
            builder.button(entry.text());
            return;
        }
        try {
            builder.button(entry.text(), imageMapper.apply(image.get()));
        } catch (RuntimeException mappingFailure) {
            LOGGER.log(Level.WARNING,
                "ACELIB-FORM-003: simple form button image mapping failed, "
                    + "falling back to plain text button '" + entry.text() + "'",
                mappingFailure);
            builder.button(entry.text());
        }
    }

    /** AceLib 圖示 → Cumulus 圖示的預設映射（型別一一對應，資料原樣傳遞）。 */
    private static org.geysermc.cumulus.util.FormImage defaultImage(FormImage image) {
        org.geysermc.cumulus.util.FormImage.Type type = switch (image.type()) {
            case PATH -> org.geysermc.cumulus.util.FormImage.Type.PATH;
            case URL -> org.geysermc.cumulus.util.FormImage.Type.URL;
        };
        return org.geysermc.cumulus.util.FormImage.of(type, image.data());
    }

    private static Form modal(FormSpec.Modal spec, Consumer<FormResponse> onResponse) {
        // 省略的按鈕以空字串傳遞（Cumulus builder 預設即空字串），語意一致
        ModalForm.Builder builder = ModalForm.builder()
            .title(spec.title())
            .content(spec.content())
            .button1(spec.button1())
            .button2(spec.button2());
        if (onResponse != null) {
            builder.resultHandler((form, result) -> onResponse.accept(
                mapResult(result,
                    response -> ((ModalFormResponse) response).clickedButtonId(),
                    response -> List.of())));
        }
        return builder.build();
    }

    private static Form custom(FormSpec.Custom spec, Consumer<FormResponse> onResponse) {
        CustomForm.Builder builder = CustomForm.builder().title(spec.title());
        for (FormSpec.Custom.Component component : spec.components()) {
            switch (component) {
                case FormSpec.Custom.Label label -> builder.label(label.text());
                case FormSpec.Custom.Input input ->
                    builder.input(input.label(), input.placeholder(), input.defaultText());
                case FormSpec.Custom.Dropdown dropdown ->
                    builder.dropdown(dropdown.label(), dropdown.options(),
                        dropdown.defaultOption());
                case FormSpec.Custom.Slider slider ->
                    builder.slider(slider.label(), slider.min(), slider.max(),
                        slider.step(), slider.defaultValue());
                case FormSpec.Custom.StepSlider stepSlider ->
                    builder.stepSlider(stepSlider.label(), stepSlider.steps(),
                        stepSlider.defaultStep());
                case FormSpec.Custom.Toggle toggle ->
                    builder.toggle(toggle.label(), toggle.defaultValue());
            }
        }
        if (onResponse != null) {
            builder.resultHandler((form, result) -> onResponse.accept(
                mapResult(result,
                    response -> null,
                    response -> customValues((CustomFormResponse) response))));
        }
        return builder.build();
    }

    /**
     * Cumulus 回應結果 → AceLib {@link FormResponse}：
     * {@code ResultType} 直接映射三態；只有 VALID 攜帶內容，
     * CLOSED／INVALID 的按鈕為 null、values 為空清單。
     */
    private static <R extends org.geysermc.cumulus.response.FormResponse> FormResponse mapResult(
            FormResponseResult<R> result,
            Function<R, Integer> clickedExtractor,
            Function<R, List<FormValue>> valuesExtractor) {
        FormResponseStatus status = mapStatus(result.responseType());
        if (result instanceof ValidFormResponseResult<R> valid) {
            R response = valid.response();
            return new FormResponse(status, clickedExtractor.apply(response),
                valuesExtractor.apply(response));
        }
        return new FormResponse(status, null, List.of());
    }

    /** {@code ResultType} → {@link FormResponseStatus}（語意一一對應）。 */
    private static FormResponseStatus mapStatus(ResultType type) {
        return switch (type) {
            case VALID -> FormResponseStatus.VALID;
            case CLOSED -> FormResponseStatus.CLOSED;
            case INVALID -> FormResponseStatus.INVALID;
        };
    }

    /**
     * 抽取 custom 表單各元件答案：依 {@code getComponentTypes()} 順序走訪，
     * label 不產值（跳過），其餘按元件型別取對應答案。
     */
    private static List<FormValue> customValues(CustomFormResponse response) {
        List<ComponentType> types = response.getComponentTypes();
        List<FormValue> values = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            FormValue value = switch (types.get(i)) {
                case DROPDOWN -> new FormValue.Option(response.getDropdown(i));
                case INPUT -> new FormValue.Text(response.getInput(i));
                case SLIDER -> new FormValue.Number(response.getSlider(i));
                case STEP_SLIDER -> new FormValue.Option(response.getStepSlide(i));
                case TOGGLE -> new FormValue.Switch(response.getToggle(i));
                case LABEL -> null;
            };
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
