package com.smile.acelib.command;

import com.smile.acelib.form.FormImage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.permissions.Permissible;

/**
 * 指令的純描述投影。
 *
 * <p>由 {@link CommandCatalog} 從 {@link CommandSpec} 投影而來，
 * 只保留描述性欄位。<strong>不攜帶</strong> {@link SubCommand} handler、
 * {@link SubCommandCompleter} completer、{@link org.bukkit.plugin.Plugin} 實例，
 * 或 {@link CommandSpec}／{@link SubCommandSpec} 實例本身。</p>
 *
 * @param owner 擁有者插件名稱；不可為 null 或空白
 * @param name 根指令名稱；不可為 null 或空白
 * @param aliases 別名清單；不可為 null（空集合表示沒有）
 * @param description 描述；不可為 null
 * @param usage 用法字串；不可為 null
 * @param permission 權限節點；null 表示無權限需求（保留 null，不折成空字串）
 * @param category 展示分類；不可為 null（空字串表示未分類）
 * @param icon 表單圖示；不可為 null（{@link Optional#empty()} 表示沒有）
 * @param subcommands 子指令描述；不可為 null（空集合表示沒有）
 * @since 1.3.0
 */
public record CommandDoc(String owner, String name, List<String> aliases,
        String description, String usage, String permission, String category,
        Optional<FormImage> icon, List<SubDoc> subcommands) {

    /**
     * 正規化建構子：拒絕 null 必要欄位與空白擁有者／名稱，並對集合做深層防禦複製。
     *
     * @throws NullPointerException 當 owner、name、aliases、description、usage、
     *         category、icon 或 subcommands 為 null
     * @throws IllegalArgumentException 當 owner 或 name 為空白
     */
    public CommandDoc {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(subcommands, "subcommands");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        aliases = List.copyOf(aliases);
        subcommands = List.copyOf(subcommands);
    }

    /**
     * 判斷此指令對檢視者是否可見：只判自己這一層的 {@code permission}。
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
