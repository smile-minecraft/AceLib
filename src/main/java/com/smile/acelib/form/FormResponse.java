package com.smile.acelib.form;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基岩表單回應值型別（Supported API）。
 *
 * <p>玩家對表單做出回應後，由 {@link FormService#sendForm(java.util.UUID, FormSpec,
 * java.util.function.Consumer)} 註冊的 consumer 在玩家所屬 region context 內收到
 * 本型別。物件為 immutable：</p>
 *
 * <ul>
 *   <li>{@link #status()} — 回應落在 {@link FormResponseStatus} 三種狀態的哪一種。</li>
 *   <li>{@link #clickedButton()} — simple 表單為被點按鈕索引、modal 表單為 0（第一顆）
 *       或 1（第二顆）；其餘狀態／表單種類為 empty。</li>
 *   <li>{@link #values()} — custom 表單各元件答案（依產值元件順序，label 不產值）；
 *       其餘表單種類為空清單。</li>
 * </ul>
 *
 * <p>只有 {@link FormResponseStatus#VALID} 的回應攜帶內容；CLOSED／INVALID 的
 * clickedButton 為 empty、values 為空清單，消費者不得把非 VALID 狀態的內容
 * 解讀為玩家意圖。</p>
 *
 * @since 1.0.0
 */
public final class FormResponse {

    private final FormResponseStatus status;
    private final Integer clickedButton;
    private final List<FormValue> values;

    /**
     * @param status        回應狀態；不可為 null
     * @param clickedButton 被點按鈕索引；無按鈕語意時為 null
     * @param values        元件答案清單；null 視為空清單
     * @throws NullPointerException status 為 null
     */
    public FormResponse(FormResponseStatus status, Integer clickedButton,
            List<FormValue> values) {
        this.status = Objects.requireNonNull(status, "status");
        this.clickedButton = clickedButton;
        this.values = values == null ? List.of() : List.copyOf(values);
    }

    /** @return 回應狀態；永不為 null */
    public FormResponseStatus status() {
        return status;
    }

    /** @return 被點按鈕索引；無按鈕語意時為 {@link Optional#empty()} */
    public Optional<Integer> clickedButton() {
        return Optional.ofNullable(clickedButton);
    }

    /** @return 元件答案清單（依產值元件順序）；immutable、非 null */
    public List<FormValue> values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FormResponse other)) {
            return false;
        }
        return status == other.status
            && Objects.equals(clickedButton, other.clickedButton)
            && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, clickedButton, values);
    }

    @Override
    public String toString() {
        return "FormResponse[status=" + status + ", clickedButton=" + clickedButton
            + ", values=" + values + "]";
    }
}
