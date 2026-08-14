package com.smile.acelib.command;

import java.util.Collection;
import java.util.List;

/**
 * 指令註冊中心介面。
 *
 * <p>後續插件透過本介面註冊主指令 + 子指令、查詢既有指令、執行 dispatch
 * 與 tab complete。實作為 {@link CommandRegistryImpl}。</p>
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>{@link #register(CommandSpec)} — 註冊主指令（包含其子指令）</li>
 *   <li>{@link #unregister(String)} — 解除主指令（依名稱或別名）</li>
 *   <li>{@link #dispatch(Sender, String, List)} — 內部 dispatch（測試 / Bukkit adapter 使用）</li>
 *   <li>{@link #tabComplete(Sender, String, List)} — 內部 tab complete</li>
 *   <li>{@link #onPluginDisable()} — 標記停用、清除所有指令</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法為 thread-safe；Bukkit 環境下
 * dispatch 在 main thread（Folia 在 region thread）呼叫，
 * tab complete 也在該 thread 觸發。</p>
 *
 * @see CommandSpec
 * @see SubCommandSpec
 * @since 1.0.0
 */
public interface CommandRegistry {

    /**
     * 註冊一個主指令（包含其所有子指令）。
     *
     * <p>主指令名稱 + 所有別名都會指向同一個 {@link CommandSpec} 實例。</p>
     *
     * @param spec 主指令規格；不可為 null
     * @throws CommandException {@link CommandErrorKind#REGISTRY_DISABLED}
     *         （{@code ACELIB-CMD-009}）當 registry 已 disable
     * @throws IllegalArgumentException 當主指令名稱或別名與既有指令衝突
     * @throws NullPointerException 當 {@code spec} 為 null
     */
    void register(CommandSpec spec);

    /**
     * 依主指令名稱（或別名）解除註冊。
     *
     * <p>若名稱不存在，呼叫為 no-op，不丟例外。</p>
     *
     * @param name 主指令名稱或別名；不可為 null
     * @throws NullPointerException 當 {@code name} 為 null
     */
    void unregister(String name);

    /**
     * 取得所有已註冊的主指令（不可變快照）。
     *
     * @return 不可變的 {@link CommandSpec} 清單
     */
    Collection<CommandSpec> getRegisteredCommands();

    /**
     * 依名稱（或別名）查詢主指令。
     *
     * @param name 主指令名稱或別名
     * @return 對應 spec；若不存在回傳 null
     */
    CommandSpec findCommand(String name);

    /**
     * 執行指令 dispatch。
     *
     * <p>內部流程：</p>
     * <ol>
     *   <li>查詢主指令（null → 回覆 {@code ACELIB-CMD-002} unknown subcommand 或 generic 拒絕）</li>
     *   <li>解析第一個 arg 為子指令名；空 → 回覆主指令 help</li>
     *   <li>子指令查找失敗 → 回覆 {@code ACELIB-CMD-002} unknown subcommand</li>
     *   <li>權限檢查 → 不符 → 回覆 {@code ACELIB-CMD-003}</li>
     *   <li>玩家/console 限定檢查 → 不符 → 回覆 {@code ACELIB-CMD-004} /
     *       {@code ACELIB-CMD-005}</li>
     *   <li>參數數量檢查 → 不符 → 回覆 {@code ACELIB-CMD-001}</li>
     *   <li>冷卻檢查 → 命中 → 回覆 {@code ACELIB-CMD-006}</li>
     *   <li>呼叫 handler；若拋例外則呼叫 {@link ReplySink#sendError}</li>
     * </ol>
     *
     * @param sender       指令 sender；不可為 null
     * @param commandLabel 玩家輸入的指令標籤（主指令名或別名）；不可為 null
     * @param args         完整 args；不可為 null（可為空 list）
     */
    void dispatch(Sender sender, String commandLabel, List<String> args);

    /**
     * 執行 tab complete。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>args 為空 → 列出該 sender 可見的所有主指令名 + 別名</li>
     *   <li>args[0] 為已知主指令（繼續）或未知字串（不再繼續）→ 列出 sender 可見的子指令</li>
     *   <li>args[0] 為主指令 + 已知子指令 + args[1+] → 委派給該子指令的
     *       {@link SubCommandCompleter}</li>
     *   <li>無論如何，<strong>不暴露無權限子指令</strong>（tab completion 的權限收斂契約）</li>
     * </ul>
     *
     * @param sender       指令 sender；不可為 null
     * @param commandLabel 玩家輸入的指令標籤；不可為 null
     * @param args         完整 args（含尚未完成的字串）；不可為 null
     * @return 不可變的補全候選清單；永不為 null（可能為空）
     */
    List<String> tabComplete(Sender sender, String commandLabel, List<String> args);

    /**
     * 產生給 sender 看的 help 文字。
     *
     * <p>格式：</p>
     * <pre>
     * === {commandLabel} ===
     *  {description}
     *  {subName}: {description}
     *  ...
     * </pre>
     *
     * @param commandLabel 主指令名稱或別名；不可為 null
     * @param sender       接收 help 的 sender；不可為 null。可見子指令依其權限過濾
     * @return help 文字；永不為 null（可能為空字串表示無對應指令）
     */
    String formatHelp(String commandLabel, Sender sender);

    /**
     * 標記 registry 為 disabled（plugin disable / reload 流程）。
     *
     * <p>呼叫後：</p>
     * <ul>
     *   <li>後續 {@link #register} 拋 {@link CommandErrorKind#REGISTRY_DISABLED}</li>
     *   <li>後續 {@link #dispatch} 對已註冊指令拋 {@code ACELIB-CMD-009}</li>
     *   <li>既有指令 map 與冷卻狀態保留，供 reload 後重新註冊使用
     *       （reload 不破壞冷卻 / 防重複觸發狀態）</li>
     *   <li>重複呼叫不丟例外（idempotent）</li>
     * </ul>
     */
    void onPluginDisable();

    /**
     * 判斷 registry 是否已被標記為 disabled。
     *
     * @return true 表示已停用
     */
    boolean isDisabled();
}