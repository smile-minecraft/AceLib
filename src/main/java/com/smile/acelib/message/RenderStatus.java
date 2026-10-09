package com.smile.acelib.message;

/**
 * 單次渲染的狀態分類（Supported API）。
 *
 * <p>由 {@link MessageService#renderDetailed(String, java.util.Map)} 與
 * {@link MessageService#renderDetailed(String, java.util.Map, java.util.Locale)}
 * 回傳的 {@link DetailedRender} 攜帶，讓呼叫端能分辨三種失敗原因：</p>
 * <ul>
 *   <li>{@link #LOCALE_NOT_LOADED} — 整條查找鏈皆無內容（請求語系與預設語系的
 *       磁碟檔皆不存在，內建資源亦無；見 {@link DetailedRender} 說明）</li>
 *   <li>{@link #KEY_MISSING} — 語系已載入（查找鏈至少有一層內容），但該 key 不存在</li>
 *   <li>{@link #RENDER_FAILED} — 模板讀到了，但讀取過程拋錯或內容無法使用
 *       （例如語言檔讀取拋例外；此版 MiniMessage 解析器寬容，未閉合標記會留作
 *       原文而不算失敗）</li>
 * </ul>
 *
 * @since 1.5.0
 */
public enum RenderStatus {
    /** 渲染成功；診斷為空字串。 */
    OK,
    /** 整條查找鏈皆無內容（語系尚未載入）。 */
    LOCALE_NOT_LOADED,
    /** 語系存在，但該 key 不存在。 */
    KEY_MISSING,
    /** 模板讀取或解析過程失敗。 */
    RENDER_FAILED
}
