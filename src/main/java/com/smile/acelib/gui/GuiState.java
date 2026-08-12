package com.smile.acelib.gui;

/**
 * GUI 操作結果狀態（Plan §十六 Phase 11 共同契約）。
 *
 * <p>所有 {@link GuiResult} 皆攜帶下列狀態之一：</p>
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 同步操作建立成功（例如 openInventory / closeInventory），
 *       或非同步更新在 player region 內<strong>已執行 renderer 完成</strong></li>
 *   <li>{@link #ALLOWED} — 驗證型操作通過（例如 validateClick 在非受保護 slot）</li>
 *   <li>{@link #REJECTED} — 輸入合法但操作被拒絕（例如 player 已有 session、重複 close）</li>
 *   <li>{@link #FAILED} — 內部執行失敗（內部拋例外 / 平台不一致）</li>
 *   <li>{@link #CLOSED} — session 已關閉（用於 shutdown 觸發的 cleanup 回報）</li>
 *   <li>{@link #ACCEPTED} — 非同步更新請求已被 player context executor 接受派送
 *       （enqueue 成功），但 renderer 尚未執行；完成結果需待執行時的重新驗證決定，
 *       不得視為 renderer 已完成。追加於既有五個狀態之後，不變更其 ordinal。</li>
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
 * 狀態順序凍結，不得更動。既有五個狀態 {@code SUCCESS(0) / ALLOWED(1) /
 * REJECTED(2) / FAILED(3) / CLOSED(4)} 的 ordinal 固定不變；新增狀態
 * （如 {@code ACCEPTED(5)}）只能追加到末尾，不得插入既有常數之間。
 *
 * @see GuiResult
 * @since Phase 11 (Plan §十六)
 */
public enum GuiState {
    SUCCESS,
    ALLOWED,
    REJECTED,
    FAILED,
    CLOSED,
    ACCEPTED
}
