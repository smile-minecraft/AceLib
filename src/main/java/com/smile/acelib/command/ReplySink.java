package com.smile.acelib.command;

import java.util.UUID;

/**
 * 指令回覆出口。
 *
 * <p>core dispatcher 不直接接觸 Bukkit {@code CommandSender}；所有訊息輸出、
 * 玩家查詢、跨執行緒派送都透過本介面委派，caller 在初始化 registry 時注入實作
 * （通常為 {@code com.smile.acelib.command.bukkit.MessageServiceReplySink}）。</p>
 *
 * <h2>三類回覆</h2>
 * <ul>
 *   <li>{@link #send(Sender, String)} — 同步回覆（chat 給玩家 / log 給 console）</li>
 *   <li>{@link #sendError(Sender, Throwable)} — 錯誤回覆（含 code 與 message）</li>
 *   <li>{@link #sendPlayerAsync(PlayerHandle, String)} — 跨執行緒回覆（Folia 安全）：
 *       透過 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 派送到
 *       玩家 region，async 完成後的 mutate 操作仍符合 Folia 上下文安全</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>實作須為 thread-safe；dispatcher 在 main thread / region thread / async thread
 * 都可能呼叫。</p>
 *
 * @since 1.0.0
 */
public interface ReplySink {

    /**
     * 同步回覆訊息。
     *
     * <p>玩家 sender → 透過 chat / MessageService.sendChat 送出。
     * 非玩家 sender（console）→ 透過 plugin logger.info 送出。</p>
     *
     * @param sender  目標 sender；不可為 null
     * @param message 訊息內容；不可為 null
     */
    void send(Sender sender, String message);

    /**
     * 回覆錯誤訊息。
     *
     * <p>實作應該：</p>
     * <ul>
     *   <li>若丟出的是 {@link CommandException}：攜帶 {@link CommandException#getCode()}
     *       與 {@link CommandException#getMessage()}；可選擇套用 lang template</li>
     *   <li>若丟出的是其他 {@link RuntimeException}：降級為 generic error message
     *       + 對應 code（{@code ACELIB-CMD-008} async execution failed 等）</li>
     * </ul>
     *
     * @param sender 目標 sender；不可為 null
     * @param error  錯誤；不可為 null
     */
    void sendError(Sender sender, Throwable error);

    /**
     * 跨執行緒回覆訊息給玩家（Folia 安全）。
     *
     * <p>內部委派給 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion}
     * ，把訊息派送進玩家 region；當流程完成時執行緒已回到 region thread，
     * mutate 操作符合 Folia 上下文安全。</p>
     *
     * <p>若玩家離線，本方法為 no-op + 不丟例外（reply 已失敗但指令整體已完成）。</p>
     *
     * @param player  目標玩家；不可為 null
     * @param message 訊息內容；不可為 null
     */
    void sendPlayerAsync(PlayerHandle player, String message);
}