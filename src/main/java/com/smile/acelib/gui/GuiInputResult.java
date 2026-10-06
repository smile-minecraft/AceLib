package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家輸入結果（Supported API）。
 *
 * <p>輸入送出並通過重新驗證後，註冊的 consumer 在玩家 region context 內收到
 * 本物件（恰好一次）。物件為不可變快照，不持有 Bukkit reference。</p>
 *
 * @param playerUuid 輸入玩家 UUID；不可為 null
 * @param generation 送出當下的 session generation；必須 {@code > 0}
 * @param kind 輸入種類；不可為 null
 * @param text 輸入文字（已通過長度上限檢查）；不可為 null
 * @see GuiScope
 * @since 1.4.0
 */
public record GuiInputResult(UUID playerUuid, long generation, GuiInputKind kind,
                            String text) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當任一 reference 參數為 null
     * @throws IllegalArgumentException 當 generation 非正
     */
    public GuiInputResult {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] generation 必須 > 0；實際: "
                    + generation);
        }
    }
}
