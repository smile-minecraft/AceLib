package com.smile.acelib.diagnostics;

import java.util.Objects;

/**
 * 節流統計資訊（immutable record）。
 *
 * <p>對應 Plan §十九 Phase 14「同類錯誤大量發生時不無限制洗版」需求，
 * 在 report debug 區塊中用以呈現每個代碼的視窗內計數。</p>
 *
 * @param allowed    視窗內被允許的次數（>= 0）
 * @param suppressed 視窗內被抑制的次數（>= 0）
 * @param windowMs   視窗長度（毫秒，> 0）
 * @since Phase 14 (Plan §十九)
 */
public record ThrottleStats(int allowed, int suppressed, long windowMs) {

    /**
     * Compact constructor：欄位範圍檢查。
     *
     * @throws IllegalArgumentException 當 {@code allowed} / {@code suppressed} < 0 或
     *                                  {@code windowMs} <= 0
     */
    public ThrottleStats {
        if (allowed < 0) {
            throw new IllegalArgumentException("allowed must be >= 0, got: " + allowed);
        }
        if (suppressed < 0) {
            throw new IllegalArgumentException("suppressed must be >= 0, got: " + suppressed);
        }
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0, got: " + windowMs);
        }
    }

    /**
     * 累加一筆「被允許」事件並回傳新的 {@link ThrottleStats}。
     *
     * @return 新 {@link ThrottleStats}，{@code allowed} + 1
     */
    public ThrottleStats incrementAllowed() {
        return new ThrottleStats(allowed + 1, suppressed, windowMs);
    }

    /**
     * 累加一筆「被抑制」事件並回傳新的 {@link ThrottleStats}。
     *
     * @return 新 {@link ThrottleStats}，{@code suppressed} + 1
     */
    public ThrottleStats incrementSuppressed() {
        return new ThrottleStats(allowed, suppressed + 1, windowMs);
    }

    /**
     * 保留向後相容的 helper：null-safe 累加。
     *
     * @param previous  既有 {@link ThrottleStats}；可為 null（視為零）
     * @param allowed   欲加入的 allowed 增量
     * @param suppressed 欲加入的 suppressed 增量
     * @param windowMs  視窗長度（亦即 previous 既有值時使用既有值）
     * @return 新 {@link ThrottleStats}
     */
    public static ThrottleStats combined(ThrottleStats previous, int allowed, int suppressed, long windowMs) {
        Objects.requireNonNull(previous, "previous");
        return new ThrottleStats(
            previous.allowed + allowed,
            previous.suppressed + suppressed,
            windowMs
        );
    }
}
