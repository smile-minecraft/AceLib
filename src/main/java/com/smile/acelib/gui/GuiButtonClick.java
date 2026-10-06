package com.smile.acelib.gui;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 按鈕點擊事件快照（Supported API）。
 *
 * <p>由 {@link GuiScope} 在按鈕回呼觸發時建立並傳入下游註冊的 handler；
 * 物件為不可變快照，不持有任何 Bukkit reference。</p>
 *
 * @param playerUuid 點擊玩家 UUID
 * @param generation 點擊當下的 session generation（過時點擊不會產生本事件）
 * @param slot 點擊欄位編號
 * @param buttonId 按鈕識別字（見 {@link GuiButton#id()}）
 * @param anvilText 鐵砧視圖結果欄位當下的更名文字；非鐵砧視圖一律為 empty
 * @see GuiView
 * @since 1.4.0
 */
public record GuiButtonClick(UUID playerUuid, long generation, int slot,
                             String buttonId, Optional<String> anvilText) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當任一 reference 參數為 null
     * @throws IllegalArgumentException 當 generation 非正、slot 為負或按鈕識別字空白
     */
    public GuiButtonClick {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(buttonId, "buttonId");
        Objects.requireNonNull(anvilText, "anvilText");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] generation 必須 > 0；實際: "
                    + generation);
        }
        if (slot < 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] slot 不可為負；實際: " + slot);
        }
        if (buttonId.isBlank()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] buttonId 不可為空白");
        }
    }
}
