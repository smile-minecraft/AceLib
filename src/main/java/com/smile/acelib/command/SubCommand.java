package com.smile.acelib.command;

import java.util.List;

/**
 * 子指令處理器函式介面。
 *
 * <p>由 {@link SubCommandSpec#handler()} 持有；dispatcher 會在通過權限 / 玩家 /
 * console / 參數 / 冷卻檢查後呼叫。handler 內部可以：</p>
 * <ul>
 *   <li>讀取 {@link CommandContext#args()} 解析後續參數</li>
 *   <li>透過 {@link CommandContext#reply(String)} 回覆 sender</li>
 *   <li>透過 {@link CommandContext#replyError(Throwable)} 回覆錯誤</li>
 *   <li>透過 {@link CommandContext#requirePlayer()} 取得玩家（離線時自動拋例外）</li>
 *   <li>透過 {@link CommandContext#replyPlayerAsync(String)} 跨執行緒回覆（Folia 安全）</li>
 *   <li>拋出 {@link CommandException} 表達業務語意錯誤</li>
 * </ul>
 *
 * <p>handler 不應宣告 {@code throws Exception} — 內部錯誤以
 * {@link CommandException} 或 {@link RuntimeException} 表達即可。</p>
 *
 * @see CommandContext
 * @see SubCommandSpec
 * @since 1.0.0
 */
@FunctionalInterface
public interface SubCommand {

    /**
     * 執行此子指令。
     *
     * @param context 指令執行 context（不可為 null，由 dispatcher 保證）
     * @throws CommandException 業務語意錯誤
     */
    void execute(CommandContext context) throws CommandException;

    /**
     * 不做任何事的空 handler；測試與 placeholder 使用。
     */
    SubCommand NOOP = context -> { };

    /**
     * 工具方法：建立一個委派到固定 runnable 的 handler（忽略 context）。
     *
     * @param runnable 實際行為；不可為 null
     * @return 包裝後的 handler
     */
    static SubCommand of(Runnable runnable) {
        return context -> runnable.run();
    }

    /**
     * 工具方法：建立一個委派到 {@link java.util.function.Consumer} 的 handler
     * （接收但不處理 context 內部細節）。
     */
    static SubCommand ofConsumer(java.util.function.Consumer<CommandContext> consumer) {
        return consumer::accept;
    }

    /**
     * 預設常數：固定回覆某字串的 handler（給測試使用）。
     */
    static SubCommand replyFixed(String reply) {
        return context -> context.reply(reply);
    }

    /**
     * 預設常數：固定拋 {@link CommandException} 的 handler（給測試使用）。
     */
    static SubCommand throwing(CommandException ex) {
        return context -> {
            throw ex;
        };
    }

    /**
     * 工具：多個 handler 依序執行（給組合子指令使用）。
     */
    static SubCommand all(SubCommand... handlers) {
        List<SubCommand> list = List.of(handlers);
        return context -> {
            for (SubCommand h : list) {
                h.execute(context);
            }
        };
    }
}