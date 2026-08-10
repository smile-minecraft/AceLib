package com.smile.acelib.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 確定性、可注入時鐘的同類錯誤節流器。
 *
 * <p>對應 Plan §十九 Phase 14「同類錯誤大量發生時不無限制洗版」需求。
 * 內部為每個 error code 維護獨立視窗：</p>
 * <ul>
 *   <li>視窗內前 {@code maxPerWindow} 次 → {@link ThrottleDecision.Kind#ALLOWED}</li>
 *   <li>視窗內第 {@code maxPerWindow + 1} 次（含）以後 → {@link ThrottleDecision.Kind#SUPPRESSED}</li>
 *   <li>跨越 {@code windowMs} 視窗 → 視為新事件，{@code ALLOWED}</li>
 * </ul>
 *
 * <p>{@code maxPerWindow = 1} 為「duplicate suppression」語意：視窗內只放行第 1 次，
 * 第 2 次起全部 SUPPRESSED；{@code maxPerWindow = N > 1} 為「通用節流」語意：
 * 視窗內前 N 次都放行，第 N+1 次起 SUPPRESSED。
 * 預設 {@code DEFAULT_MAX_PER_WINDOW = 5} 採通用節流語意；
 * 欲使用 duplicate suppression 的 caller（例如
 * {@link DiagnosticsService}）需<strong>顯式</strong>以
 * {@code new ErrorThrottler(clock, 1, DEFAULT_WINDOW_MS)} 建立。</p>
 *
 * <h2>累計語意</h2>
 * <ul>
 *   <li>{@link #getAllowedCount(String)} / {@link #getSuppressedCount(String)}
 *       為<strong>跨視窗累計</strong>，跨視窗後繼續累加（不重置）</li>
 *   <li>{@link #getStats(String)} 回傳當前視窗內的 {@link ThrottleStats}
 *       （視窗切換時 {@code allowedCount} / {@code suppressedCount} 重新計算）</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <ul>
 *   <li>內部狀態以 {@link ConcurrentHashMap} 儲存</li>
 *   <li>{@link #tryRecord(String, String)} 採「compute + 內部 helper 回傳本次決策」，
 *       替代 race-prone 的 side-channel，避免殘留狀態污染其他 thread</li>
 * </ul>
 *
 * <h2>時鐘注入</h2>
 * 時間來源由 {@link Clock} 提供；測試全程使用 deterministic clock，
 * <strong>禁止 sleep</strong>。
 *
 * @see ThrottleDecision
 * @since Phase 14 (Plan §十九)
 */
public final class ErrorThrottler {

    /** 預設視窗內允許次數上限（保留為「視窗內 ALLOWED 上限」的擴充錨點）。 */
    public static final int DEFAULT_MAX_PER_WINDOW = 5;

    /** 預設視窗長度（毫秒）。 */
    public static final long DEFAULT_WINDOW_MS = 1_000L;

    private final Clock clock;
    private final int maxPerWindow;
    private final long windowMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 使用預設 {@value #DEFAULT_MAX_PER_WINDOW} 上限與 {@value #DEFAULT_WINDOW_MS} 視窗建立。
     *
     * @param clock 時鐘來源；不可為 null
     * @throws NullPointerException 當 {@code clock} 為 null
     */
    public ErrorThrottler(Clock clock) {
        this(clock, DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW_MS);
    }

    /**
     * 自訂視窗參數建立。
     *
     * @param clock         時鐘來源；不可為 null
     * @param maxPerWindow  視窗內最多 ALLOWED 次數（> 0）
     * @param windowMs      視窗長度（毫秒，> 0）
     * @throws NullPointerException     當 {@code clock} 為 null
     * @throws IllegalArgumentException 當 {@code maxPerWindow <= 0} 或 {@code windowMs <= 0}
     */
    public ErrorThrottler(Clock clock, int maxPerWindow, long windowMs) {
        Objects.requireNonNull(clock, "clock");
        if (maxPerWindow <= 0) {
            throw new IllegalArgumentException("maxPerWindow must be > 0, got: " + maxPerWindow);
        }
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0, got: " + windowMs);
        }
        this.clock = clock;
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /**
     * 嘗試記錄一筆錯誤事件。
     *
     * <p>行為：</p>
     * <ul>
     *   <li>code 為 null → {@link NullPointerException}</li>
     *   <li>detail 可為 null 或空字串（保留訊息語意）</li>
     *   <li>視窗內前 {@code maxPerWindow} 次 → {@link ThrottleDecision.Kind#ALLOWED}，
     *       decision.detail 為此次傳入的 detail</li>
     *   <li>視窗內第 {@code maxPerWindow + 1} 次（含）以後 →
     *       {@link ThrottleDecision.Kind#SUPPRESSED}，
     *       decision.detail 為最近一次 ALLOWED 事件的 detail（避免訊息丟失）</li>
     *   <li>跨越視窗（{@code currentTime - lastWindowStart >= windowMs}）→
     *       視為新事件，{@code ALLOWED}</li>
     * </ul>
     *
     * <p>實作說明：以 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
     * 同步更新視窗內部狀態，並透過 {@link AtomicReference} 將本次決策攜出，
     * 避免在 compute 外部對 {@code Window} 額外讀取造成 race-prone。</p>
     *
     * @param code   錯誤代碼；不可為 null
     * @param detail 詳細訊息；可為 null 或空字串
     * @return 對應的 {@link ThrottleDecision}；永遠不為 null
     * @throws NullPointerException 當 {@code code} 為 null
     */
    public ThrottleDecision tryRecord(String code, String detail) {
        Objects.requireNonNull(code, "code");
        long now = clock.currentTimeMillis();
        String safeDetail = detail != null ? detail : "";
        AtomicReference<ThrottleDecision.Kind> kindRef = new AtomicReference<>();

        Window updated = windows.compute(code, (key, existing) -> {
            if (existing == null || (now - existing.startMs) >= windowMs) {
                // 新視窗或新代碼：首次事件 = ALLOWED
                kindRef.set(ThrottleDecision.Kind.ALLOWED);
                int prevTotalAllowed = existing != null ? existing.totalAllowed : 0;
                int prevTotalSuppressed = existing != null ? existing.totalSuppressed : 0;
                return new Window(now, 1, 0, safeDetail,
                    prevTotalAllowed + 1, prevTotalSuppressed);
            }
            // 視窗內：依 maxPerWindow 判斷本次是否仍允許
            if (existing.allowedCount < maxPerWindow) {
                kindRef.set(ThrottleDecision.Kind.ALLOWED);
                return new Window(existing.startMs,
                    existing.allowedCount + 1,
                    existing.suppressedCount,
                    safeDetail,
                    existing.totalAllowed + 1,
                    existing.totalSuppressed);
            }
            // 視窗內已達上限：本次 SUPPRESSED；detail 保留最近一次 ALLOWED 的 detail
            kindRef.set(ThrottleDecision.Kind.SUPPRESSED);
            return new Window(existing.startMs,
                existing.allowedCount,
                existing.suppressedCount + 1,
                existing.lastDetail,
                existing.totalAllowed,
                existing.totalSuppressed + 1);
        });

        ThrottleDecision.Kind kind = kindRef.get();
        if (kind == null) {
            kind = ThrottleDecision.Kind.ALLOWED;
        }
        String detailForDecision = (kind == ThrottleDecision.Kind.SUPPRESSED)
            ? updated.lastDetail
            : safeDetail;
        return new ThrottleDecision(kind, code, detailForDecision);
    }

    /**
     * 取得指定 code 目前的 suppressed 累計次數（跨視窗累計）。
     *
     * @param code 錯誤代碼；可為 null（視為 0）
     * @return 跨視窗累計 suppressed 次數（>= 0）
     */
    public int getSuppressedCount(String code) {
        if (code == null) {
            return 0;
        }
        Window w = windows.get(code);
        return w != null ? w.totalSuppressed : 0;
    }

    /**
     * 取得指定 code 目前的 allowed 累計次數（跨視窗累計）。
     *
     * @param code 錯誤代碼；可為 null（視為 0）
     * @return 跨視窗累計 allowed 次數（>= 0）
     */
    public int getAllowedCount(String code) {
        if (code == null) {
            return 0;
        }
        Window w = windows.get(code);
        return w != null ? w.totalAllowed : 0;
    }

    /**
     * 取得當前視窗內的 {@link ThrottleStats}。
     *
     * <p>若該代碼尚未被記錄，回傳 null。</p>
     *
     * @param code 錯誤代碼
     * @return 對應的 {@link ThrottleStats}，或 null
     */
    public ThrottleStats getStats(String code) {
        if (code == null) {
            return null;
        }
        Window w = windows.get(code);
        if (w == null) {
            return null;
        }
        return new ThrottleStats(w.allowedCount, w.suppressedCount, windowMs);
    }

    /**
     * 清空所有 code 的視窗與計數。
     */
    public void reset() {
        windows.clear();
    }

    /**
     * 取得所有曾被記錄過的錯誤代碼（不可變視圖）。
     *
     * <p>回傳 {@link Set#copyOf} 結果，呼叫端修改不會影響本物件狀態。</p>
     *
     * @return 不可變的代碼集合
     */
    public Set<String> trackedKeys() {
        return Set.copyOf(windows.keySet());
    }

    /**
     * 取得所有已追蹤代碼的當前視窗 {@link ThrottleStats} 一致性快照。
     *
     * <p>本方法在內部以 {@link ConcurrentHashMap#forEach(java.util.function.BiConsumer)}
     * 走訪 {@code windows}，確保「迭代 keys」與「讀取 stats」位於同一個
     * concurrent traversal 內，避免外部 caller 先呼叫 {@link #trackedKeys()}
     * 再逐個呼叫 {@link #getStats(String)} 時，因其他執行緒同時呼叫
     * {@link #reset()} 或 {@link #tryRecord(String, String)} 而取得 null。</p>
     *
     * <p>回傳的 Map 為不可變複本，後續修改本物件狀態不會影響快照內容。</p>
     *
     * @return 不可變的 {@code code -> ThrottleStats} 映射；當前無 tracked 代碼時回傳空 map
     * @see #trackedKeys()
     * @see #getStats(String)
     * @since Phase 14 (Plan §十九) — 為 {@code DiagnosticsService.buildSnapshot}
     *      提供併發安全的 throttle 快照
     */
    public Map<String, ThrottleStats> snapshotStats() {
        Map<String, ThrottleStats> result = new LinkedHashMap<>();
        windows.forEach((key, w) -> {
            if (w != null) {
                result.put(key, new ThrottleStats(w.allowedCount, w.suppressedCount, windowMs));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * 取得當前的視窗長度（毫秒）。
     */
    public long getWindowMs() {
        return windowMs;
    }

    /**
     * 取得當前的視窗內允許次數上限。
     */
    public int getMaxPerWindow() {
        return maxPerWindow;
    }

    /**
     * 內部視窗狀態（immutable）。
     *
     * <p>保留當前視窗的 {@code allowedCount} / {@code suppressedCount} 與
     * 跨視窗累計的 {@code totalAllowed} / {@code totalSuppressed}。</p>
     */
    private static final class Window {
        final long startMs;
        final int allowedCount;
        final int suppressedCount;
        final String lastDetail;
        final int totalAllowed;
        final int totalSuppressed;

        Window(long startMs, int allowedCount, int suppressedCount, String lastDetail,
               int totalAllowed, int totalSuppressed) {
            this.startMs = startMs;
            this.allowedCount = allowedCount;
            this.suppressedCount = suppressedCount;
            this.lastDetail = lastDetail != null ? lastDetail : "";
            this.totalAllowed = totalAllowed;
            this.totalSuppressed = totalSuppressed;
        }
    }
}
