package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主指令規格。
 *
 * <p>由 {@link CommandRegistry#register} 註冊；包含：</p>
 * <ul>
 *   <li>主指令名稱（必填，唯一）與別名（可選）</li>
 *   <li>主指令權限（{@code null} = 無；玩家觸發主指令時檢查，但不影響子指令）</li>
 *   <li>描述與 usage（給 help 用）</li>
 *   <li>子指令 map（name → {@link SubCommandSpec}）</li>
 * </ul>
 *
 * <h2>別名語意</h2>
 * <p>註冊時主名與所有別名都會指向同一個 {@link CommandSpec} 實例；
 * dispatch 時透過大小寫不敏感比對查找。</p>
 *
 * <h2>不可變性</h2>
 * <ul>
 *   <li>所有欄位在建構後不可變</li>
 *   <li>{@link #subCommands()} 回傳不可變 map（保留插入順序）</li>
 *   <li>新增子指令透過 {@link Builder#subCommand(SubCommandSpec)} 在 build 前設定</li>
 * </ul>
 *
 * @see SubCommandSpec
 * @see CommandRegistry
 * @since 1.0.0
 */
public final class CommandSpec {

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final String permission;
    private final Map<String, SubCommandSpec> subCommands;
    /** key = 小寫子指令別名 → 主規格（dispatch／tab complete 用）。 */
    private final Map<String, SubCommandSpec> subByAlias;

    private CommandSpec(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name")
            .toLowerCase(java.util.Locale.ROOT);
        if (b.name.isEmpty()) {
            throw new IllegalArgumentException("command name cannot be empty");
        }
        this.aliases = b.aliases == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.aliases));
        this.description = b.description == null ? "" : b.description;
        this.usage = b.usage == null ? "" : b.usage;
        this.permission = b.permission;  // null allowed
        // 用 LinkedHashMap 保留插入順序，方便 help 顯示
        Map<String, SubCommandSpec> map = new LinkedHashMap<>();
        Map<String, SubCommandSpec> aliasMap = new LinkedHashMap<>();
        for (SubCommandSpec sub : b.subCommands) {
            String subName = sub.name();
            if (map.containsKey(subName)
                || aliasMap.containsKey(subName)) {
                throw new IllegalArgumentException(
                    "duplicate subcommand name: " + subName);
            }
            map.put(subName, sub);
            for (String alias : sub.aliases()) {
                String key = alias.toLowerCase(java.util.Locale.ROOT);
                if (map.containsKey(key) || aliasMap.containsKey(key)) {
                    throw new IllegalArgumentException(
                        "subcommand alias '" + alias
                            + "' conflicts with existing subcommand or alias");
                }
                aliasMap.put(key, sub);
            }
        }
        this.subCommands = Collections.unmodifiableMap(map);
        this.subByAlias = Collections.unmodifiableMap(aliasMap);
    }

    /** 主指令名稱（小寫）。 */
    public String name() { return name; }

    /** 別名清單（以呼叫端提供的形式保留，不轉小寫；不可變）。 */
    public List<String> aliases() { return aliases; }

    /** 描述。 */
    public String description() { return description; }

    /** 用法字串。 */
    public String usage() { return usage; }

    /** 主指令權限節點；null 表示無權限需求。 */
    public String permission() { return permission; }

    /** 子指令 map（name → spec）；不可變。 */
    public Map<String, SubCommandSpec> subCommands() { return subCommands; }

    /**
 查找子指令（含別名與大小寫不敏感比對）。別名視為主名。
 *
 * @param name 子指令名稱或別名
 * @return 對應 spec 或 null（不存在時）
 */
public SubCommandSpec findSubCommand(String name) {
    if (name == null) return null;
    SubCommandSpec direct = subCommands.get(name.toLowerCase(java.util.Locale.ROOT));
    if (direct != null) {
        return direct;
    }
    return subByAlias.get(name.toLowerCase(java.util.Locale.ROOT));
}

    /**
     * 建立 builder。
     *
     * @param name 主指令名稱；不可為 null 或空字串
     * @return 新的 builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 主指令規格 builder。
     */
    public static final class Builder {
        private final String name;
        private List<String> aliases;
        private String description;
        private String usage;
        private String permission;
        private final List<SubCommandSpec> subCommands = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder aliases(String... aliases) {
            this.aliases = aliases == null ? null : Arrays.asList(aliases);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder subCommand(SubCommandSpec sub) {
            Objects.requireNonNull(sub, "sub");
            this.subCommands.add(sub);
            return this;
        }

        public CommandSpec build() {
            return new CommandSpec(this);
        }
    }
}