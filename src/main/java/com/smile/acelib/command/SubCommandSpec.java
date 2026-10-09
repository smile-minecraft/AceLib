package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 子指令規格。
 *
 * <p>由 {@link CommandSpec} 持有；dispatcher 透過本規格判斷「給定 sender + args
 * 是否能執行該子指令」，包含以下檢查：</p>
 *
 * <ul>
 *   <li>{@link #permission} — 權限節點；{@code null} 表示無權限需求</li>
 *   <li>{@link #playerOnly} — 僅限玩家（console 觸發拒絕；{@code ACELIB-CMD-004}）</li>
 *   <li>{@link #consoleOnly} — 僅限 console（玩家觸發拒絕；{@code ACELIB-CMD-005}）</li>
 *   <li>{@link #minArgs} / {@link #maxArgs} — 參數數量上下限
 *       （{@code maxArgs < 0} 表示無上限）</li>
 *   <li>{@link #cooldownMillis} — 同玩家冷卻時間（毫秒；{@code <= 0} 表示無冷卻）</li>
 * </ul>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>不可變（immutable）：一經 build，欄位不可變動</li>
 *   <li>{@link #handler()} 不可為 null；{@link #completer()} 可為 null（表示無 tab 補全）</li>
 *   <li>dispatcher 對 {@link CommandException} 採 unwrap 處理：handler 拋例外
 *       時 dispatcher 自動呼叫 {@link ReplySink#sendError} 而非中斷整個 registry</li>
 * </ul>
 *
 * @see CommandSpec
 * @see CommandContext
 * @since 1.0.0
 */
public final class SubCommandSpec {

    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final String permission;
    private final boolean playerOnly;
    private final boolean consoleOnly;
    private final int minArgs;
    private final int maxArgs;
    private final List<String> argNames;
    private final long cooldownMillis;
    private final SubCommand handler;
    private final SubCommandCompleter completer;

    private SubCommandSpec(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name")
            .toLowerCase(java.util.Locale.ROOT);
        if (b.name.isEmpty()) {
            throw new IllegalArgumentException("subcommand name cannot be empty");
        }
        this.aliases = checkAliases(b.name, b.aliases);
        this.description = b.description == null ? "" : b.description;
        this.usage = b.usage == null ? "" : b.usage;
        this.permission = b.permission;  // null is allowed
        this.playerOnly = b.playerOnly;
        this.consoleOnly = b.consoleOnly;
        if (playerOnly && consoleOnly) {
            throw new IllegalArgumentException(
                "subcommand cannot be both playerOnly and consoleOnly: " + b.name);
        }
        this.minArgs = b.minArgs;
        this.maxArgs = b.maxArgs;
        if (minArgs < 0) {
            throw new IllegalArgumentException("minArgs must be >= 0");
        }
        if (maxArgs >= 0 && maxArgs < minArgs) {
            throw new IllegalArgumentException(
                "maxArgs (" + maxArgs + ") must be >= minArgs (" + minArgs + ")");
        }
        this.argNames = b.argNames == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.argNames));
        this.cooldownMillis = b.cooldownMillis < 0 ? 0 : b.cooldownMillis;
        this.handler = Objects.requireNonNull(b.handler, "handler");
        this.completer = b.completer;  // null allowed
    }

    /**
     * 檢查子指令別名：不可為 null／空、不可與主名相同、彼此不可重複
     * （一律小寫比較）。
     *
     * @param name    子指令主名（錯誤訊息用）
     * @param aliases 呼叫端提供的別名；null 視為無別名
     * @return 不可變別名清單（保留原形式）；永不為 null
     * @throws IllegalArgumentException 別名非法或衝突並說明原因
     */
    static List<String> checkAliases(String name, List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptyList();
        }
        String primary = name.toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String alias : aliases) {
            if (alias == null || alias.isEmpty()) {
                throw new IllegalArgumentException(
                    "subcommand alias cannot be null or empty: " + name);
            }
            String key = alias.toLowerCase(java.util.Locale.ROOT);
            if (key.equals(primary)) {
                throw new IllegalArgumentException(
                    "subcommand alias '" + alias
                        + "' conflicts with its own name: " + name);
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                    "duplicate subcommand alias: " + alias);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(aliases));
    }

    /** 子指令名稱（小寫）。 */
    public String name() { return name; }

    /**
     * 子指令別名（以呼叫端提供的形式保留，不轉小寫；不可變）。
     *
     * <p>比較一律小寫；dispatch 與 tab complete 把別名視為主名。
     * help 只列主名。</p>
     */
    public List<String> aliases() { return aliases; }

    /** 描述（給 help 用）。 */
    public String description() { return description; }

    /** 用法字串（給參數錯誤時回覆）。 */
    public String usage() { return usage; }

    /** 權限節點；null 表示無權限需求。 */
    public String permission() { return permission; }

    /** 是否僅限玩家。 */
    public boolean playerOnly() { return playerOnly; }

    /** 是否僅限 console。 */
    public boolean consoleOnly() { return consoleOnly; }

    /** 最小參數數量（不含子指令名本身）。 */
    public int minArgs() { return minArgs; }

    /** 最大參數數量（不含子指令名本身）；-1 表示無上限。 */
    public int maxArgs() { return maxArgs; }

    /** 參數名稱（給 help 用）。 */
    public List<String> argNames() { return argNames; }

    /** 冷卻毫秒數；&le;0 表示無冷卻。 */
    public long cooldownMillis() { return cooldownMillis; }

    /** 處理器。 */
    public SubCommand handler() { return handler; }

    /** Tab 補全器；可能為 null。 */
    public SubCommandCompleter completer() { return completer; }

    /**
     * 建立 builder。
     *
     * @param name 子指令名稱；不可為 null 或空字串
     * @return 新的 builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 子指令規格 builder。
     */
    public static final class Builder {
        private final String name;
        private List<String> aliases;
        private String description;
        private String usage;
        private String permission;
        private boolean playerOnly;
        private boolean consoleOnly;
        private int minArgs;
        private int maxArgs = -1;
        private List<String> argNames;
        private long cooldownMillis;
        private SubCommand handler;
        private SubCommandCompleter completer;

        private Builder(String name) {
            this.name = name;
        }

        /**
         * 設定子指令別名（可選；保留原形式，比較一律小寫）。
         *
         * @param aliases 別名；不可含 null／空字串，不可與主名或彼此相同
         *                （大小寫不敏感；違反時 {@link #build()} 拒絕）
         */
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

        public Builder playerOnly() {
            this.playerOnly = true;
            this.consoleOnly = false;
            return this;
        }

        public Builder consoleOnly() {
            this.consoleOnly = true;
            this.playerOnly = false;
            return this;
        }

        public Builder minArgs(int min) {
            this.minArgs = min;
            return this;
        }

        public Builder maxArgs(int max) {
            this.maxArgs = max;
            return this;
        }

        public Builder args(String... names) {
            this.argNames = names == null ? null : Arrays.asList(names);
            return this;
        }

        public Builder cooldownMillis(long cooldown) {
            this.cooldownMillis = cooldown;
            return this;
        }

        public Builder handler(SubCommand handler) {
            this.handler = handler;
            return this;
        }

        public Builder completer(SubCommandCompleter completer) {
            this.completer = completer;
            return this;
        }

        public SubCommandSpec build() {
            return new SubCommandSpec(this);
        }
    }
}