package com.smile.acelib.gui;

/**
 * 輸入提示的結果（Internal）。
 *
 * @param result 提示結果；永不為 null
 * @param ticket 輸入票券；失敗時為 null
 * @param replaced 被取代的舊 session（僅 ANVIL 開啟新視圖時可能非 null）
 */
record InputPromptOutcome(GuiResult result, GuiInputTicket ticket,
                         GuiSession replaced) {
}
