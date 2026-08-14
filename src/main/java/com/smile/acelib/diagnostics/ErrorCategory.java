package com.smile.acelib.diagnostics;

import java.util.Objects;

/**
 * 錯誤分類 enum。
 *
 * <p>從 {@code ACELIB-<AREA>-<CODE>} 抽出 {@code AREA} 後可對應到對應分類。
 * areaPrefix 為反向對應的字串前綴，用於報告輸出。</p>
 *
 * <h2>規範對應</h2>
 * <ul>
 *   <li>分類必須 1:1 對應 {@code ACELIB-<AREA>-*} 形式</li>
 *   <li>{@link #UNKNOWN} 是 fallback — 任何未登錄區段一律歸類到 UNKNOWN</li>
 *   <li>大小寫嚴格遵守（{@code ACELIB-SCHED-*} 而非 {@code acelib-sched-*}）</li>
 * </ul>
 *
 * @see ErrorCodeRegistry
 * @since 1.0.0
 */
public enum ErrorCategory {

    /** 平台偵測相關錯誤（{@code ACELIB-PLAT-*}）。 */
    PLATFORM("PLAT"),

    /** 排程相關錯誤（{@code ACELIB-SCHED-*}）。 */
    SCHEDULER("SCHED"),

    /** 上下文／執行緒安全相關錯誤（{@code ACELIB-CTX-*}）。 */
    CONTEXT("CTX"),

    /** 設定檔相關錯誤（{@code ACELIB-CFG-*}）。 */
    CONFIG("CFG"),

    /** 訊息相關錯誤（{@code ACELIB-MSG-*}）。 */
    MESSAGE("MSG"),

    /** 語言檔相關錯誤（{@code ACELIB-LANG-*}）。 */
    LANGUAGE("LANG"),

    /** 指令相關錯誤（{@code ACELIB-CMD-*}）。 */
    COMMAND("CMD"),

    /** 事件相關錯誤（{@code ACELIB-EVT-*}）。 */
    EVENT("EVT"),

    /** 玩家資料相關錯誤（{@code ACELIB-PLAYER-*}）。 */
    PLAYER("PLAYER"),

    /** 世界操作相關錯誤（{@code ACELIB-WORLD-*}）。 */
    WORLD("WORLD"),

    /** GUI 相關錯誤（{@code ACELIB-GUI-*}）。 */
    GUI("GUI"),

    /** Item 相關錯誤（{@code ACELIB-ITEM-*}）。 */
    ITEM("ITEM"),

    /** 資料儲存相關錯誤（{@code ACELIB-DATA-*}）。 */
    DATA("DATA"),

    /** 外部整合相關錯誤（{@code ACELIB-EXT-*}）。 */
    EXTERNAL("EXT"),

    /** 診斷／除錯模組自身錯誤（{@code ACELIB-DBG-*}）。 */
    DEBUG("DBG"),

    /** 無法識別的代碼（fallback）。 */
    UNKNOWN("UNKNOWN");

    private final String areaPrefix;

    ErrorCategory(String areaPrefix) {
        this.areaPrefix = Objects.requireNonNull(areaPrefix, "areaPrefix");
    }

    /**
     * 取得 {@code ACELIB-<AREA>-*} 形式中的 {@code <AREA>} 字串前綴。
     *
     * @return 對應的 area prefix（如 {@code "SCHED"}、{@code "CFG"}），永遠不為 null
     */
    public String areaPrefix() {
        return areaPrefix;
    }
}
