package com.smile.acelib.diagnostics;

import java.util.Objects;

/**
 * 節流決策結果（immutable record）。
 *
 * <p>對應 Plan §十九 Phase 14「同類錯誤大量發生時不無限制洗版」需求。
 * 由 {@link ErrorThrottler#tryRecord(String, String)} 回傳，告知 caller
 * 「是否被允許 / 抑制 / 在視窗外允許」以及對應的 code / detail。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #kind} — {@link Kind#ALLOWED} 視為「已記錄並放行」；
 *       {@link Kind#SUPPRESSED} 視為「在視窗內已被抑制」</li>
 *   <li>{@link #code} — 對應的錯誤代碼（不可為 null）</li>
 *   <li>{@link #detail} — 此事件的詳細訊息（若被抑制則保留
 *       <em>最近一次 ALLOWED 事件的 detail</em>，避免上層丟失訊息）</li>
 * </ul>
 *
 * @see ErrorThrottler
 * @since Phase 14 (Plan §十九)
 */
public record ThrottleDecision(Kind kind, String code, String detail) {

    /**
     * 節流決策分類。
     */
    public enum Kind {
        /** 視窗內尚未達上限，事件已記錄並放行。 */
        ALLOWED,
        /** 視窗內已達上限，事件被抑制。 */
        SUPPRESSED
    }

    /**
     * Compact constructor：對不可空欄位做 null 檢查。
     *
     * @throws NullPointerException 當 {@code kind} / {@code code} 為 null
     */
    public ThrottleDecision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        // detail 允許 null（caller 對空訊息可以傳 null 或空字串）；讓我們在 accessor 內做 null-safe
    }

    /**
     * 取得 detail；null-safe，永遠回傳非 null 字串（空字串表示沒訊息）。
     *
     * @return detail 或空字串
     */
    public String safeDetail() {
        return detail != null ? detail : "";
    }
}
