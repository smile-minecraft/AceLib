package com.smile.acelib.command;

/**
 * 指令目錄發布結果。
 *
 * <p>每一次 {@link CommandCatalog#publish} 呼叫恰回傳其中一種，
 * 判定優先序為 {@code UNCHANGED} ＞ {@code DUPLICATE_NAME} ＞
 * {@code REPLACED} ＞ {@code PUBLISHED}，與
 * {@link CommandCatalog#revision()} 的變化對應如下：</p>
 * <ul>
 *   <li>{@link #PUBLISHED} — 新增項目（revision +1）</li>
 *   <li>{@link #REPLACED} — 同擁有者同名、內容不同，且無其他擁有者使用同名，
 *       覆蓋舊項目（revision +1）</li>
 *   <li>{@link #UNCHANGED} — 同擁有者同名、內容相同，不做任何變更
 *       （revision 不變，不記錄警告；優先於 {@link #DUPLICATE_NAME}）</li>
 *   <li>{@link #DUPLICATE_NAME} — 該次發布的名稱有其他擁有者也在使用；
 *       資料成功收錄或更新（revision +1），並記錄 {@code ACELIB-CMD-013} warning</li>
 *   <li>{@link #REJECTED} — 服務未就緒或已停用，發布被拒（revision 不變），
 *       並記錄 {@code ACELIB-CMD-014}</li>
 * </ul>
 *
 * @since 1.3.0
 */
public enum CatalogResult {
    /** 新增項目。 */
    PUBLISHED,
    /** 同擁有者同名覆蓋。 */
    REPLACED,
    /** 同擁有者同名且內容相同，無變更。 */
    UNCHANGED,
    /** 跨擁有者同名，資料仍收錄並記錄警告。 */
    DUPLICATE_NAME,
    /** 服務不可用，發布被拒。 */
    REJECTED
}
