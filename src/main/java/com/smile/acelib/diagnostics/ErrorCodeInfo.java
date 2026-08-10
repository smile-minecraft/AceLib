package com.smile.acelib.diagnostics;

import java.util.Objects;

/**
 * 已知錯誤代碼的 metadata（immutable record）。
 *
 * <p>對應 Plan §十九 Phase 14「錯誤代碼對應錯誤分類」需求。
 * 描述特定代碼的「分類」與「人話說明」，供 report 與文件輸出。</p>
 *
 * @param category    對應的 {@link ErrorCategory}；不可為 null
 * @param description 人類可讀說明（不可為 null 或空字串）
 * @see ErrorCodeRegistry
 * @since Phase 14 (Plan §十九)
 */
public record ErrorCodeInfo(ErrorCategory category, String description) {

    /**
     * Compact constructor：對不可空欄位做 null 與空字串檢查。
     *
     * @throws NullPointerException     當 {@code category} 或 {@code description} 為 null
     * @throws IllegalArgumentException 當 {@code description} 為空字串
     */
    public ErrorCodeInfo {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
