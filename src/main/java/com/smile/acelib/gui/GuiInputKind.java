package com.smile.acelib.gui;

/**
 * 輸入提示種類（Supported API）。
 *
 * @see GuiInputPrompt
 * @since 1.4.0
 */
public enum GuiInputKind {
    /** 聊天輸入：在指定 session 有效期間內玩家的下一則聊天訊息即為輸入內容。 */
    CHAT,
    /** 鐵砧輸入：開啟鐵砧視圖，玩家在更名欄打字後按結果欄送出。 */
    ANVIL
}
