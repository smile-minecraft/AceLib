package com.smile.acelib.diagnostics;

import java.util.Objects;
import java.util.Optional;

/**
 * 模組狀態（immutable record）。
 *
 * <p>透過 {@link DiagnosticsService} 註冊後彙整到 {@link DiagnosticSnapshot}。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #name} — 模組識別名（e.g. {@code "scheduler"} / {@code "config"}）</li>
 *   <li>{@link #status} — 對應的 {@link ModuleStatus}</li>
 *   <li>{@link #detail} — 人類可讀的詳細訊息（含錯誤原因、追蹤狀態等）</li>
 *   <li>{@link #errorCodeValue} — 當 {@link #status} 為 {@link ModuleStatus#FAILED} 時
 *       攜帶的 {@code ACELIB-<AREA>-<CODE>}；其他情況為 null。
 *       對外請透過 {@link #errorCode()} 取得 {@link Optional} 視圖</li>
 * </ul>
 *
 * @param name            模組識別名；不可為 null
 * @param status          對應的 {@link ModuleStatus}；不可為 null
 * @param detail          人類可讀的詳細訊息；不可為 null
 * @param errorCodeValue  FAILED 時攜帶的 {@code ACELIB-<AREA>-<CODE>}；其他情況為 null
 * @see ModuleStatus
 * @see DiagnosticsService
 * @since 1.0.0
 */
public record ModuleState(
    String name,
    ModuleStatus status,
    String detail,
    String errorCodeValue
) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查。
     *
     * @throws NullPointerException 當 {@code name} / {@code status} / {@code detail} 為 null
     */
    public ModuleState {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        // errorCodeValue 允許 null
    }

    /**
     * 取得錯誤代碼的 {@link Optional} 視圖。
     *
     * <p>為何不用 record 預設的 {@code errorCodeValue()} accessor：測試與外部 API
     * 期望 {@code errorCode()} 回傳 {@link Optional}，以便與 null 區分；維持
     * param-level raw 欄位以 record accessor 對應 {@code errorCodeValue}，
     * 對外方法則另提供 {@code errorCode()}。</p>
     *
     * @return 可能的錯誤代碼（永遠不為 null 的 Optional）
     */
    public Optional<String> errorCode() {
        return Optional.ofNullable(errorCodeValue);
    }

    // -----------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------

    /**
     * 建立 {@link ModuleStatus#READY} 狀態。
     */
    public static ModuleState ready(String name, String detail) {
        return new ModuleState(name, ModuleStatus.READY, detail, null);
    }

    /**
     * 建立 {@link ModuleStatus#NOT_INITIALIZED} 狀態。
     */
    public static ModuleState notInitialized(String name, String detail) {
        return new ModuleState(name, ModuleStatus.NOT_INITIALIZED, detail, null);
    }

    /**
     * 建立 {@link ModuleStatus#UNAVAILABLE} 狀態。
     */
    public static ModuleState unavailable(String name, String detail) {
        return new ModuleState(name, ModuleStatus.UNAVAILABLE, detail, null);
    }

    /**
     * 建立 {@link ModuleStatus#FAILED} 狀態（攜帶錯誤代碼）。
     *
     * @param errorCode {@code ACELIB-<AREA>-<CODE>} 形式的錯誤代碼；可為 null
     */
    public static ModuleState failed(String name, String detail, String errorCode) {
        return new ModuleState(name, ModuleStatus.FAILED, detail, errorCode);
    }

    /**
     * 建立 {@link ModuleStatus#DEGRADED} 狀態。
     */
    public static ModuleState degraded(String name, String detail) {
        return new ModuleState(name, ModuleStatus.DEGRADED, detail, null);
    }
}
