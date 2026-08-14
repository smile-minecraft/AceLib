package com.smile.acelib.context;

import java.util.Objects;

/**
 * 上下文安全例外（Supported，extends {@link RuntimeException}）。
 *
 * <p>當 {@link SafeExecutor} 偵測到操作將在錯誤上下文中執行時，
 * 拋出此例外。例外攜帶的欄位：</p>
 * <ul>
 *   <li>{@link #getCode()} — 錯誤分類代碼（{@code ACELIB-CTX-001} ~ {@code 004}）</li>
 *   <li>{@link #getCurrentContext()} — 拋出時的當前執行緒/區域上下文</li>
 *   <li>{@link #getOperationType()} — 嘗試執行的操作類型</li>
 *   <li>{@link #getTargetInfo()} — 目標資訊（玩家名稱、世界名稱、實體類型等）</li>
 *   <li>{@link #getMessage()} — 人類可讀訊息</li>
 *   <li>{@link #getCause()} — 觸發的原始例外（可為 null）</li>
 * </ul>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>code 不可為 null</li>
 *   <li>currentContext / operationType 不可為 null</li>
 *   <li>targetInfo 可為 null（無目標情境）</li>
 * </ul>
 *
 * @see SafeExecutor
 * @see ContextInspector
 * @since 1.0.0
 */
public class ContextException extends RuntimeException {

    private final String code;
    private final ThreadContext currentContext;
    private final OperationType operationType;
    private final String targetInfo;

    /**
     * 主要建構子（無 cause）。
     */
    public ContextException(String code,
                            ThreadContext currentContext,
                            OperationType operationType,
                            String targetInfo,
                            String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.currentContext = Objects.requireNonNull(currentContext, "currentContext");
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        this.targetInfo = targetInfo;
    }

    /**
     * 完整建構子（含 cause）。
     */
    public ContextException(String code,
                            ThreadContext currentContext,
                            OperationType operationType,
                            String targetInfo,
                            String message,
                            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.currentContext = Objects.requireNonNull(currentContext, "currentContext");
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        this.targetInfo = targetInfo;
    }

    /**
     * 取得錯誤分類代碼。
     */
    public String getCode() {
        return code;
    }

    /**
     * 取得拋出時的當前上下文。
     */
    public ThreadContext getCurrentContext() {
        return currentContext;
    }

    /**
     * 取得嘗試執行的操作類型。
     */
    public OperationType getOperationType() {
        return operationType;
    }

    /**
     * 取得目標資訊（如 {@code "player=smile"}, {@code "world=world_main"}）。
     * 無目標情境下可能為 null。
     */
    public String getTargetInfo() {
        return targetInfo;
    }
}