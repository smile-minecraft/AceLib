package com.smile.acelib.command;

import java.util.List;
import java.util.Objects;
import org.bukkit.permissions.Permissible;

/**
 * 子指令的純描述投影。
 *
 * <p>由 {@link CommandCatalog} 從 {@link SubCommandSpec} 投影而來，
 * 只保留描述性欄位。<strong>不攜帶</strong> {@link SubCommand} handler、
 * {@link SubCommandCompleter} completer、{@link org.bukkit.plugin.Plugin} 實例，
 * 或 {@link SubCommandSpec} 實例本身。</p>
 *
 * @param name 子指令名稱；不可為 null 或空白
 * @param description 描述；不可為 null
 * @param usage 用法字串；不可為 null
 * @param permission 權限節點；null 表示無權限需求（保留 null，不折成空字串）
 * @param argNames 參數名稱；不可為 null（空集合表示沒有）
 * @param minArgs 最小參數數量；不可為負
 * @param maxArgs 最大參數數量；{@code -1} 表示無上限（沿用既有語意並保留此值）
 * @param playerOnly 是否僅限玩家
 * @param consoleOnly 是否僅限 console
 * @param cooldownMillis 冷卻毫秒數；{@code <= 0} 表示無冷卻
 * @param requiresConfirmation 是否需要二次確認
 * @since 1.3.0
 */
public record SubDoc(String name, String description, String usage, String permission,
        List<String> argNames, int minArgs, int maxArgs, boolean playerOnly,
        boolean consoleOnly, long cooldownMillis, boolean requiresConfirmation) {

    /**
     * 正規化建構子：拒絕 null 必要欄位與空白名稱，並對集合做深層防禦複製。
     *
     * @throws NullPointerException 當 name、description、usage 或 argNames 為 null
     * @throws IllegalArgumentException 當 name 為空白
     */
    public SubDoc {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(argNames, "argNames");
        if (name.isBlank()) {
            throw new IllegalArgumentException("subcommand name must not be blank");
        }
        argNames = List.copyOf(argNames);
    }

    /**
     * 判斷此子指令對檢視者是否可見：只判自己這一層的 {@code permission}。
     *
     * <p>子指令的可見性必須先通過根指令的可見性；本方法不代替執行時的權限檢查，
     * 也不是授權機制。</p>
     *
     * @param viewer 檢視者；null 一律回傳 false（不丟例外）
     * @return {@code permission == null} 或
     *         {@code viewer.hasPermission(permission)} 時為 true
     */
    public boolean visibleTo(Permissible viewer) {
        if (viewer == null) {
            return false;
        }
        return permission == null || viewer.hasPermission(permission);
    }
}
