package com.smile.acelib.diagnostics;

import java.util.Objects;

/**
 * 排程錯誤摘要（immutable record）。
 *
 * <p>將多筆同 {@code code} 排程錯誤合併為單行 summary 的內容單位，
 * 供 {@link DiagnosticReport} 輸出時使用。</p>
 *
 * @param code     錯誤代碼（如 {@code ACELIB-SCHED-001}）；不可為 null
 * @param detail   最近一次的詳細訊息（不可為 null；可為空字串）
 * @param count    觀察到的次數（>= 1）
 * @param category 對應的 {@link ErrorCategory}；不可為 null
 * @since 1.0.0
 */
public record ErrorSummaryLine(String code, String detail, int count, ErrorCategory category) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查；count 必須 >= 1。
     *
     * @throws NullPointerException     當 {@code code} / {@code detail} / {@code category} 為 null
     * @throws IllegalArgumentException 當 {@code count <= 0}
     */
    public ErrorSummaryLine {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(category, "category");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, got: " + count);
        }
    }
}
