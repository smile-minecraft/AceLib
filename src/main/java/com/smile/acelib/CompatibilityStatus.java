package com.smile.acelib;

import java.util.Objects;

/**
 * 內部相容性狀態（package-private；不屬於 v1 對外契約）。
 *
 * <p>三態分類：</p>
 * <ul>
 *   <li>{@link State#SUPPORTED} — 平台 + 版本落在內建已驗證矩陣（僅 26.1.2 Paper/Folia）</li>
 *   <li>{@link State#UNVERIFIED} — 關鍵 capability 皆存在，但版本不在已驗證矩陣；
 *       仍視為 ready，但輸出 warning 提示 best-effort</li>
 *   <li>{@link State#INCOMPATIBLE} — 任一 required capability 缺失；fail-closed，
 *       不應標記 plugin ready</li>
 * </ul>
 *
 * <p>本型別刻意為 package-private，避免被 api-surface scanner 視為對外契約。</p>
 *
 * @see CompatibilityGate
 * @since 1.1.2
 */
final class CompatibilityStatus {

    /** 相容性三態。 */
    enum State {
        SUPPORTED,
        UNVERIFIED,
        INCOMPATIBLE
    }

    final State state;
    final String reason;
    final String runtimeSummary;

    private CompatibilityStatus(State state, String reason, String runtimeSummary) {
        this.state = state;
        this.reason = reason;
        this.runtimeSummary = runtimeSummary;
    }

    /** INCOMPATIBLE 才視為 not ready；SUPPORTED / UNVERIFIED 皆 ready。 */
    boolean isReady() {
        return state != State.INCOMPATIBLE;
    }

    static CompatibilityStatus supported(String summary) {
        return new CompatibilityStatus(State.SUPPORTED, "verified runtime", summary);
    }

    static CompatibilityStatus unverified(String reason, String summary) {
        return new CompatibilityStatus(State.UNVERIFIED, reason, summary);
    }

    static CompatibilityStatus incompatible(String reason, String summary) {
        return new CompatibilityStatus(State.INCOMPATIBLE, reason, summary);
    }

    /** 便於 diagnostics / log 輸出。 */
    String describe() {
        return state.name() + " | " + reason + " | " + runtimeSummary;
    }
}
