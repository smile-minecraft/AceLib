package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 指令執行 context。
 *
 * <p>由 {@link CommandRegistry} 在 dispatch 時建立，傳遞給對應的
 * {@link SubCommand#execute}。handler 透過本 context 取得 sender、玩家、參數、
 * 並透過 {@link ReplySink} 回覆訊息。</p>
 *
 * <h2>重要 API</h2>
 * <ul>
 *   <li>{@link #sender()} — sender 抽象（玩家 / console / 測試 stub）</li>
 *   <li>{@link #requirePlayer()} — 取得玩家；若非玩家或離線，拋
 *       {@link CommandException}（{@code ACELIB-CMD-004} 或
 *       {@code ACELIB-CMD-007}）</li>
 *   <li>{@link #reply(String)} — 同步回覆訊息</li>
 *   <li>{@link #replyError(Throwable)} — 回覆錯誤（dispatcher 標準化）</li>
 *   <li>{@link #replyPlayerAsync(String)} — 跨執行緒回覆（Folia 安全，
 *       內部透過 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 派送）</li>
 *   <li>{@link #args()} — 完整 args（含子指令名），不可變</li>
 *   <li>{@link #commandArgs()} — 子指令之後的 args（不含子指令名），不可變</li>
 * </ul>
 *
 * <h2>不可變性</h2>
 * <p>本類別所有欄位在建構時設定，後續不變；handler 修改 context 內部狀態不會
 * 影響 dispatch 流程。</p>
 *
 * @see CommandRegistry
 * @see SubCommand
 * @since 1.0.0
 */
public final class CommandContext {

    private final Sender sender;
    private final String commandLabel;
    private final List<String> args;          // 完整 args（含子指令名）
    private final CommandSpec root;
    private final SubCommandSpec sub;
    private final ReplySink replySink;

    CommandContext(Sender sender,
                   String commandLabel,
                   List<String> args,
                   CommandSpec root,
                   SubCommandSpec sub,
                   ReplySink replySink) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.commandLabel = Objects.requireNonNull(commandLabel, "commandLabel");
        this.args = args == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(args));
        this.root = Objects.requireNonNull(root, "root");
        this.sub = Objects.requireNonNull(sub, "sub");
        this.replySink = Objects.requireNonNull(replySink, "replySink");
    }

    /**
     * 取得 sender 抽象。
     */
    public Sender sender() { return sender; }

    /**
     * 取得 dispatch 時傳入的指令標籤（主指令名或別名）。
     */
    public String commandLabel() { return commandLabel; }

    /**
     * 取得完整 args（含子指令名）；不可變。
     *
     * <p>例如 {@code /acelib reload config} → {@code ["reload", "config"]}。</p>
     */
    public List<String> args() { return args; }

    /**
     * 取得子指令名之後的 args；不可變。
     *
     * <p>例如 {@code /acelib reload config} 對應 {@code reload} 子指令時，
     * 回傳 {@code ["config"]}。空 list 表示無後續參數。</p>
     */
    public List<String> commandArgs() {
        if (args.isEmpty()) return Collections.emptyList();
        return args.subList(1, args.size());
    }

    /** 對應主指令規格。 */
    public CommandSpec root() { return root; }

    /** 對應子指令規格。 */
    public SubCommandSpec sub() { return sub; }

    /**
     * 取得玩家 handle；若非玩家拋 {@link CommandException}。
     *
     * <p>注意：本方法<strong>不</strong>檢查玩家是否離線 — 玩家可能在指令
     * dispatch 後立刻離線，handler 內部若需要 mutate 玩家，應透過
     * {@link #requireOnlinePlayer()} 或手動檢查。</p>
     *
     * @return 永不為 null 的玩家 handle
     * @throws CommandException {@link CommandErrorKind#CONSOLE_NOT_ALLOWED}
     *         （{@code ACELIB-CMD-004}）當 sender 非玩家
     */
    public PlayerHandle requirePlayer() {
        if (!sender.isPlayer()) {
            throw new CommandException(CommandErrorKind.CONSOLE_NOT_ALLOWED,
                "this subcommand is player-only",
                java.util.Map.of("sub", sub.name()));
        }
        return sender.asPlayer();
    }

    /**
     * 取得<strong>在線</strong>玩家 handle；若離線拋 {@link CommandException}。
     *
     * <p>語意對應「玩家執行後立刻離線」邊界條件：handler 在取得
     * 玩家後若需要立即 mutate，應呼叫本方法以確保玩家仍在線上。</p>
     *
     * @return 永不為 null 且 {@link PlayerHandle#isOnline()} 為 true 的玩家
     * @throws CommandException
     *         <ul>
     *           <li>{@link CommandErrorKind#CONSOLE_NOT_ALLOWED}（{@code ACELIB-CMD-004}）— 非玩家</li>
     *           <li>{@link CommandErrorKind#PLAYER_OFFLINE}（{@code ACELIB-CMD-007}）— 玩家已離線</li>
     *         </ul>
     */
    public PlayerHandle requireOnlinePlayer() {
        PlayerHandle handle = requirePlayer();
        if (!handle.isOnline()) {
            throw new CommandException(CommandErrorKind.PLAYER_OFFLINE,
                "player is offline",
                java.util.Map.of("player", handle.getName()));
        }
        return handle;
    }

    /**
     * 同步回覆訊息給 sender。
     *
     * <p>內部呼叫 {@link ReplySink#send(Sender, String)}。</p>
     *
     * @param message 訊息內容；不可為 null
     */
    public void reply(String message) {
        Objects.requireNonNull(message, "message");
        replySink.send(sender, message);
    }

    /**
     * 回覆錯誤給 sender（攜帶 code 與 message）。
     *
     * <p>內部呼叫 {@link ReplySink#sendError(Sender, Throwable)}。
     * dispatcher 統一處理 — 即使 handler 內部拋例外也會自動經由此路徑。</p>
     *
     * @param error 錯誤；不可為 null
     */
    public void replyError(Throwable error) {
        Objects.requireNonNull(error, "error");
        replySink.sendError(sender, error);
    }

    /**
     * 跨執行緒回覆訊息給玩家（Folia 安全）。
     *
     * <p>內部呼叫 {@link ReplySink#sendPlayerAsync(PlayerHandle, String)}，
     * 透過 {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 把訊息
     * 派送進玩家 region thread；non-player sender 時拋
     * {@link CommandException}。</p>
     *
     * <p>典型用例：handler 從資料庫查詢完成（非同步）後回覆玩家。透過本方法
     * 派送，保證 mutate 發生在 region thread。</p>
     *
     * @param message 訊息內容；不可為 null
     * @throws CommandException {@link CommandErrorKind#CONSOLE_NOT_ALLOWED} 當非玩家
     */
    public void replyPlayerAsync(String message) {
        Objects.requireNonNull(message, "message");
        PlayerHandle handle = requirePlayer();
        replySink.sendPlayerAsync(handle, message);
    }

    /** 直接取得 reply sink（測試或進階用法）。 */
    public ReplySink replySink() { return replySink; }
}