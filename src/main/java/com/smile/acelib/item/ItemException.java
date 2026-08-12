package com.smile.acelib.item;

import java.util.Objects;

/**
 * Item 例外（extends {@link RuntimeException}）。
 *
 * <p>對應 Plan Phase 12「錯誤分類代碼」：所有對外拋出或記錄的 item 錯誤
 * 必須攜帶 {@code ACELIB-ITEM-*} 格式分類代碼，方便
 * {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 正確歸類。</p>
 *
 * <h2>設計約定</h2>
 * <ul>
 *   <li>{@link #getCode()} 永遠不為 null</li>
 *   <li>{@link #getCause()} 保留底層例外（不安靜吞錯）</li>
 *   <li>錯誤訊息須含「失敗位置 + 失敗原因」，避免只回傳 {@code ex.getMessage()}</li>
 * </ul>
 *
 * @see ItemErrorCode
 * @see com.smile.acelib.diagnostics.ErrorCodeRegistry
 */
public class ItemException extends RuntimeException {

    private final String code;

    /**
     * 建構子（無 cause）。
     *
     * @param code    錯誤代碼（{@code ACELIB-ITEM-*}）；不可為 null
     * @param message 詳細訊息；不可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public ItemException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 建構子（含 cause）。
     *
     * @param code    錯誤代碼（{@code ACELIB-ITEM-*}）；不可為 null
     * @param message 詳細訊息；不可為 null
     * @param cause   底層例外；可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public ItemException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 取得錯誤分類代碼。
     *
     * @return {@code ACELIB-ITEM-*} 格式字串；永遠不為 null
     */
    public String getCode() {
        return code;
    }
}
