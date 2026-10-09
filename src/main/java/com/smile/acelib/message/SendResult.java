package com.smile.acelib.message;

/**
 * 單次發送的結果（Supported API）。
 *
 * <p>由 {@code *WithFallbackResult} 系列入口回傳：</p>
 * <ul>
 *   <li>{@link #delivered} — 是否實際呼叫了發送（{@code sendMessage}／
 *       {@code sendActionBar}／{@code showTitle} 未拋錯）。目標為 null、離線、
 *       服務停用或發送拋錯時為 false。廣播時任一玩家送達即為 true。</li>
 *   <li>{@link #fallbackApplied} — 本次是否確實套用了基岩降級：
 *       {@link BedrockFallbackStyle#HINTS} 為實際執行降級器且輸出與輸入不同
 *       （click 被剝離並插入可讀提示；無 click 的訊息不算套用），
 *       {@link BedrockFallbackStyle#PLAIN_TEXT} 為走了基岩分支並攤成純文字。
 *       此旗標只代表降級已套用到送出的 Component，<strong>不</strong>代表客戶端
 *       已經看見（發送仍可能因執行緒、region 或連線原因失敗）。</li>
 * </ul>
 *
 * @param delivered 是否實際送出；發送未執行或拋錯時為 false
 * @param fallbackApplied 本次是否確實套用基岩降級
 * @since 1.5.0
 */
public record SendResult(boolean delivered, boolean fallbackApplied) {
}
