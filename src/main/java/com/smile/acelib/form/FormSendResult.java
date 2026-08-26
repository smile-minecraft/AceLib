package com.smile.acelib.form;

/**
 * 表單發送結果（Supported API）。
 *
 * <p>{@code sendForm} 的明確結果型別：Floodgate 內部的 boolean 只代表
 * 「Floodgate 是否接受此次遞送」，本型別把該事實轉譯為具名狀態，
 * 不讓原始 boolean 直接外洩為「玩家已收到」。</p>
 *
 * <h2>語意邊界</h2>
 * <ul>
 *   <li>{@link #SENT} — Floodgate 已接受此表單並負責遞送；<strong>不代表</strong>
 *       玩家已開啟、已閱讀或已回應。回應結果以 {@link FormResponseStatus} 語意描述。</li>
 *   <li>{@link #REJECTED} — Floodgate 拒絕此次發送（典型原因：玩家已離線、
 *       目標不是基岩玩家）。呼叫端可安全重試或放棄，不需處理例外。</li>
 * </ul>
 *
 * @since 1.0.0
 */
public enum FormSendResult {

    /** Floodgate 已接受此次表單遞送（不等於玩家已回應）。 */
    SENT,

    /** Floodgate 拒絕此次發送（玩家離線、非基岩玩家等）；未產生任何遞送。 */
    REJECTED;

    /**
     * @return 此次發送是否被 Floodgate 接受
     */
    public boolean isSent() {
        return this == SENT;
    }

    /**
     * @return 此次發送是否被 Floodgate 拒絕
     */
    public boolean isRejected() {
        return this == REJECTED;
    }
}
