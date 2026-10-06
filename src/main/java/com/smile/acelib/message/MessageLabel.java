package com.smile.acelib.message;

/**
 * 顯示標籤：程式識別字與顯示文案分離的值型別。
 *
 * <p>{@link #id} 是穩定的程式識別字（例如 {@code "confirm"}），永不本地化，
 * 供按鈕分支、表單答案比對等程式邏輯使用；{@link #text} 是渲染後的顯示文字，
 * 供 GUI 按鈕與表單選項顯示。管理員改文案只會改變 {@code text}，不會動到
 * {@code id}，因此顯示改名不會弄壞程式分支。</p>
 *
 * <p>缺標籤 key 時 {@code text} 退回 {@code id} 本身（並記錄
 * {@code ACELIB-MSG-001}），程式邏輯仍可用 {@code id} 繼續運作。</p>
 *
 * @param id 程式識別字；never null
 * @param text 顯示文字（表單安全字串視圖：無聊天 prefix，MiniMessage 已解析）；never null
 * @since 1.4.0
 */
public record MessageLabel(String id, String text) {

    /**
     * 緊湊建構子：拒絕 null。
     */
    public MessageLabel {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(text, "text");
    }
}
