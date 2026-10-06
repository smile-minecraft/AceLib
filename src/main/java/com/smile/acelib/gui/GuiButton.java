package com.smile.acelib.gui;

import java.util.Objects;

/**
 * GUI 按鈕描述（Supported API）。
 *
 * <p>按鈕是 {@link GuiView} 上可點擊的欄位：點擊時走專屬回呼路徑
 * （見 {@link GuiButtonClick}），<strong>禁止</strong>拿
 * {@code SLOT_PROTECTED} 拒絕當按鈕事件 — 受保護欄位拒絕只代表「擋下」，
 * 不代表「玩家按了某顆按鈕」。</p>
 *
 * @param id 按鈕識別字（同一視圖內唯一；程式分支用，不直接顯示）
 * @param cooldownMillis 同一玩家重複點擊的冷卻毫秒數；{@code 0} 表示無冷卻
 * @see GuiView
 * @since 1.4.0
 */
public record GuiButton(String id, long cooldownMillis) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當 {@code id} 為 null
     * @throws IllegalArgumentException 當 {@code id} 為空白或冷卻為負
     */
    public GuiButton {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] button id 不可為空白");
        }
        if (cooldownMillis < 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 按鈕冷卻不可為負；實際: "
                    + cooldownMillis);
        }
    }
}
