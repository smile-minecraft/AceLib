package com.smile.acelib.command;

/**
 * 型別化子指令處理器（函數式介面）。
 *
 * <p>與 {@link SubCommand} 對應，但收到的是已解析的 {@link TypedContext}：
 * handler 只處理業務邏輯，不做字串解析。解析失敗不會進入 handler，
 * 由相容層轉為 {@code ACELIB-CMD-015} 經 {@link ReplySink} 回覆。</p>
 *
 * @see TypedContext
 * @see TypedSubCommand
 * @since 1.4.0
 */
@FunctionalInterface
public interface TypedHandler {

    /**
     * 執行子指令。
     *
     * @param ctx 已解析的型別化 context；不可為 null
     * @throws CommandException 業務錯誤（由 dispatcher 經 {@link ReplySink} 回覆）
     */
    void execute(TypedContext ctx) throws CommandException;
}
