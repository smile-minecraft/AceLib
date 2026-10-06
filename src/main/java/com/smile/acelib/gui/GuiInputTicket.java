package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * 輸入票券（Supported API）。
 *
 * <p>由 {@link GuiScope#promptChat}／{@link GuiScope#promptAnvil} 產生的
 * 不透明一次性票券：呼叫端持有本物件才能以
 * {@link GuiScope#submitInput} 送出文字，無法猜測。
 * 票券綁定玩家與 session generation — 導航、關閉、退服、停用後送出會被拒絕。</p>
 *
 * @param token 票券識別；不可為 null
 * @param playerUuid 目標玩家 UUID；不可為 null
 * @param generation 綁定的 session generation；必須 {@code > 0}
 * @param kind 輸入種類；不可為 null
 * @see GuiScope
 * @since 1.4.0
 */
public record GuiInputTicket(UUID token, UUID playerUuid, long generation,
                             GuiInputKind kind) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當任一 reference 參數為 null
     * @throws IllegalArgumentException 當 generation 非正
     */
    public GuiInputTicket {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(kind, "kind");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] generation 必須 > 0；實際: "
                    + generation);
        }
    }
}
