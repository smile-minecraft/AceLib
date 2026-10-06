package com.smile.acelib.gui;

import java.util.Objects;

/**
 * 輸入提示描述（Supported API）。
 *
 * <p>下游以 {@link #chat}／{@link #anvil} 描述一次輸入請求，交給
 * {@link GuiScope#promptChat}／{@link GuiScope#promptAnvil}；
 * 玩家送出後，註冊的 consumer 在玩家 region context 內收到
 * {@link GuiInputResult}（恰好一次）。</p>
 *
 * <ul>
 *   <li>票券一次性：送出成功、逾時或 session 結束後，票券即失效，
 *       重複送出回 {@code INPUT_EXPIRED}</li>
 *   <li>超長文字被拒（{@code INVALID_INPUT}）時票券保留，可重試</li>
 *   <li>{@code timeoutMillis = 0} 表示不逾時</li>
 * </ul>
 *
 * @param kind 輸入種類；不可為 null
 * @param title 視圖標題（鐵砧用；聊天提示為空字串）；不可為 null
 * @param hint 聊天提示文字（送出時傳給玩家；鐵砧用不到，為空字串）；不可為 null
 * @param maxLength 輸入文字長度上限（字元數）；必須 {@code > 0}
 * @param timeoutMillis 逾時毫秒數；{@code 0} 表示不逾時，不可為負
 * @see GuiScope
 * @since 1.4.0
 */
public record GuiInputPrompt(GuiInputKind kind, String title, String hint,
                            int maxLength, long timeoutMillis) {

    /**
     * 正規化建構子。
     *
     * @throws NullPointerException 當 {@code kind}／{@code title}／{@code hint} 為 null
     * @throws IllegalArgumentException 當長度上限非法或逾時為負
     */
    public GuiInputPrompt {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(hint, "hint");
        if (maxLength <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 輸入長度上限必須 > 0；實際: "
                    + maxLength);
        }
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 輸入逾時不可為負；實際: "
                    + timeoutMillis);
        }
    }

    /**
     * 建立聊天輸入提示。
     *
     * @param hint 送出給玩家的提示文字；不可為 null（可為空字串＝不另行提示）
     * @param maxLength 輸入文字長度上限；必須 {@code > 0}
     * @param timeoutMillis 逾時毫秒數；{@code 0} 表示不逾時
     * @return 新的提示
     */
    public static GuiInputPrompt chat(String hint, int maxLength, long timeoutMillis) {
        return new GuiInputPrompt(GuiInputKind.CHAT, "", hint, maxLength, timeoutMillis);
    }

    /**
     * 建立鐵砧輸入提示。
     *
     * @param title 鐵砧視圖標題；不可為 null
     * @param maxLength 輸入文字長度上限；必須 {@code > 0}
     * @param timeoutMillis 逾時毫秒數；{@code 0} 表示不逾時
     * @return 新的提示
     */
    public static GuiInputPrompt anvil(String title, int maxLength, long timeoutMillis) {
        return new GuiInputPrompt(GuiInputKind.ANVIL, title, "", maxLength, timeoutMillis);
    }
}
