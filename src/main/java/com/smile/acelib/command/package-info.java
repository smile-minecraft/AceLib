/**
 * 指令系統（Supported + SPI）。
 *
 * <p>提供 plugin 註冊主指令 / 子指令、dispatch、tab completion、權限與冷卻
 * 檢查的統一介面，並把 Bukkit {@code CommandSender} 抽象為
 * {@link com.smile.acelib.command.Sender}，避免核心 dispatcher 直接依賴
 * Bukkit 型別。</p>
 *
 * <h2>取得方式</h2>
 * <p>透過 {@link com.smile.acelib.AceLibApi} 取得
 * {@link com.smile.acelib.command.CommandRegistry}；以
 * {@link com.smile.acelib.command.CommandSpec#builder(String)} 建立主指令、
 * {@link com.smile.acelib.command.SubCommandSpec#builder(String)} 建立子指令，
 * 再以 {@link com.smile.acelib.command.CommandRegistry#register(
 * com.smile.acelib.command.CommandSpec)} 註冊。</p>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.command.CommandRegistry}（Supported）— 指令註冊服務介面</li>
 *   <li>{@link com.smile.acelib.command.CommandContext}（Supported）— 傳遞給
 *       {@link com.smile.acelib.command.SubCommand} 的執行上下文</li>
 *   <li>{@link com.smile.acelib.command.Sender} /
 *       {@link com.smile.acelib.command.PlayerHandle} /
 *       {@link com.smile.acelib.command.ReplySink}（Supported）— sender 抽象與回覆出口</li>
 *   <li>{@link com.smile.acelib.command.SubCommand} /
 *       {@link com.smile.acelib.command.SubCommandCompleter}（SPI）— 消費者實作的
 *       handler 與 tab 補全介面</li>
 *   <li>{@link com.smile.acelib.command.CommandException} /
 *       {@link com.smile.acelib.command.CommandErrorKind}（Supported）—
 *       指令錯誤與 {@code ACELIB-CMD-*} 錯誤代碼</li>
 * </ul>
 *
 * <h2>執行緒與 Folia 契約</h2>
 * <ul>
 *   <li>dispatch 在 Bukkit 指令執行緒（Paper main thread / Folia 對應 context）
 *       被呼叫；{@link com.smile.acelib.command.CommandRegistry} 所有方法皆為
 *       thread-safe</li>
 *   <li>對玩家的回覆（{@link com.smile.acelib.command.ReplySink#sendPlayerAsync}）
 *       會經 {@link com.smile.acelib.context.SafeExecutor} 派送到玩家 region thread，
 *       跨執行緒完成後的操作仍符合 Folia 上下文安全</li>
 *   <li>handler 取得玩家後若需要立即 mutate，應使用
 *       {@link com.smile.acelib.command.CommandContext#requireOnlinePlayer()}
 *       以確保玩家仍在線上</li>
 * </ul>
 *
 * <h2>生命週期</h2>
 * <p>plugin disable / reload 時呼叫
 * {@link com.smile.acelib.command.CommandRegistry#onPluginDisable()}：
 * 之後 register 拋 {@code ACELIB-CMD-009}、dispatch 回覆錯誤，但既有指令 map
 * 與冷卻狀態保留，供 reload 後重新註冊。</p>
 *
 * <h2>錯誤代碼</h2>
 * <p>所有指令錯誤以 {@link com.smile.acelib.command.CommandException} 表達，
 * 攜帶 {@code ACELIB-CMD-001} ~ {@code ACELIB-CMD-011} 標準代碼
 * （見 {@link com.smile.acelib.command.CommandErrorKind}）。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.command;
