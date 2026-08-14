package com.smile.acelib.diagnostics;

/**
 * 模組狀態分類 enum。
 *
 * <p>描述 {@link ModuleState} 當前屬於哪一類狀態：</p>
 * <ul>
 *   <li>{@link #READY} — 已綁定並可運作</li>
 *   <li>{@link #NOT_INITIALIZED} — 尚未綁定（典型為該模組在 AceLib 內 opt-in）</li>
 *   <li>{@link #UNAVAILABLE} — 已嘗試綁定但失敗／依賴缺失</li>
 *   <li>{@link #FAILED} — 運行期間狀態異常（攜帶 errorCode）</li>
 *   <li>{@link #DEGRADED} — 部分功能可運作但使用 fallback</li>
 * </ul>
 *
 * @see ModuleState
 * @since 1.0.0
 */
public enum ModuleStatus {
    READY,
    NOT_INITIALIZED,
    UNAVAILABLE,
    FAILED,
    DEGRADED
}
