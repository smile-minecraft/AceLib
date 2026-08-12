package com.smile.acelib.gui;

/**
 * GUI 操作結果狀態（Plan §十六 Phase 11 共同契約）。
 *
 * <p>所有 {@link GuiResult} 皆攜帶下列狀態之一：</p>
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 同步操作建立成功（例如 openInventory / closeInventory）</li>
 *   <li>{@link #ALLOWED} — 驗證型操作通過（例如 validateClick 在非受保護 slot）</li>
 *   <li>{@link #REJECTED} — 輸入合法但操作被拒絕（例如 player 已有 session、重複 close）</li>
 *   <li>{@link #FAILED} — 內部執行失敗（內部拋例外 / 平台不一致）</li>
 *   <li>{@link #CLOSED} — session 已關閉（用於 shutdown 觸發的 cleanup 回報）</li>
 * </ul>
 *
 * <p>與 {@code WorldState} 對齊：</p>
 * <ul>
 *   <li>{@link #SUCCESS} / {@link #ALLOWED} 對應「呼叫端可繼續」</li>
 *   <li>{@link #REJECTED} / {@link #FAILED} 對應「呼叫端必須中止此動作」</li>
 *   <li>{@link #CLOSED} 標記 session 已被清理（僅供診斷）</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * 狀態順序凍結，不得更動。
 *
 * @see GuiResult
 * @since Phase 11 (Plan §十六)
 */
public enum GuiState {
    SUCCESS,
    ALLOWED,
    REJECTED,
    FAILED,
    CLOSED
}
