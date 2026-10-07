package com.smile.acelib.display;

/**
 * 顯示操作的結果狀態（Supported）。
 *
 * <p>語意與 {@code GuiState} 對齊：</p>
 * <ul>
 *   <li>{@link #SUCCESS} — 變更已在擁有者上下文內完成</li>
 *   <li>{@link #ACCEPTED} — 派送已被擁有者排程接受，但變更尚未執行
 *       （非同步排程下全息字生成的正常回應；實際完成需另行查詢）</li>
 *   <li>{@link #REJECTED} — 呼叫端可修正的拒絕（離線、退休、輸入不合法等），
 *       攜帶 {@code ACELIB-DISP-*} 代碼</li>
 *   <li>{@link #FAILED} — 服務不可用或執行期失敗，攜帶 {@code ACELIB-DISP-*} 代碼</li>
 * </ul>
 *
 * @since 1.4.0
 */
public enum DisplayState {

    /** 變更已完成。 */
    SUCCESS,

    /** 派送已接受，變更尚未執行。 */
    ACCEPTED,

    /** 可修正的拒絕（呼叫端問題）。 */
    REJECTED,

    /** 服務不可用或執行期失敗。 */
    FAILED
}
