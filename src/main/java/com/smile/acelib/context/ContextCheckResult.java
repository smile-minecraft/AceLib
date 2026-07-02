package com.smile.acelib.context;

import java.util.Objects;

/**
 * 上下文檢查結果（immutable record）。
 *
 * <p>對應 Plan §八 Phase 3：{@link ContextInspector#check(ThreadContext, OperationType, Platform)}
 * 回傳的結果封裝。三個欄位：</p>
 * <ul>
 *   <li>{@link #safe} — 是否允許執行該操作</li>
 *   <li>{@link #code} — 拒絕時的錯誤代碼（{@code ACELIB-CTX-xxx}）；允許時為 null</li>
 *   <li>{@link #reason} — 拒絕時的人類可讀原因；允許時為 null</li>
 * </ul>
 *
 * <p>使用靜態 factory 方法建立：</p>
 * <ul>
 *   <li>{@link #allowed()} — 允許的標準結果</li>
 *   <li>{@link #denied(String, String)} — 拒絕時指定錯誤代碼與原因</li>
 * </ul>
 *
 * @since Phase 3 (Plan §八)
 */
public record ContextCheckResult(
    boolean safe,
    String code,
    String reason
) {

    /**
     * 建立「允許」結果。
     *
     * @return safe=true、code=null、reason=null 的標準結果
     */
    public static ContextCheckResult allowed() {
        return ALLOWED;
    }

    /**
     * 建立「拒絕」結果。
     *
     * @param code   錯誤代碼（{@code ACELIB-CTX-xxx}）；不可為 null
     * @param reason 人類可讀的拒絕原因；不可為 null
     * @return safe=false 且攜帶 code/reason 的結果
     * @throws NullPointerException 當 {@code code} 或 {@code reason} 為 null
     */
    public static ContextCheckResult denied(String code, String reason) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(reason, "reason");
        return new ContextCheckResult(false, code, reason);
    }

    /** 單例的「允許」結果，避免重複配置。 */
    private static final ContextCheckResult ALLOWED = new ContextCheckResult(true, null, null);
}