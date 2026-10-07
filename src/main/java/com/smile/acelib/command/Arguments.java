package com.smile.acelib.command;

import java.util.List;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;

/**
 * 型別化引數工廠（下游組裝入口）。
 *
 * <p>八種引數型別各自具備解析、驗證與自動補全：</p>
 * <ul>
 *   <li>{@link #player} — 在線玩家（離線／不存在 → {@code ACELIB-CMD-007}）</li>
 *   <li>{@link #offlinePlayer} — 離線玩家（從未上線 → {@code ACELIB-CMD-015}）</li>
 *   <li>{@link #intArg}／{@link #doubleArg} — 有上下限的整數／小數
 *       （含溢位檢查 → {@code ACELIB-CMD-015}）</li>
 *   <li>{@link #duration} — 時間長度（vanilla time 語法，ticks；溢位檢查）</li>
 *   <li>{@link #world} — 已載入世界（不存在 → {@code ACELIB-CMD-015}）</li>
 *   <li>{@link #enumArg}／{@link #fixed} — 固定選項（literal 分支，基岩可見）</li>
 *   <li>{@link #material} — 材質（不存在 → {@code ACELIB-CMD-015}）</li>
 * </ul>
 *
 * @see CommandArgument
 * @see TypedSubCommand
 * @since 1.4.0
 */
public final class Arguments {

    private Arguments() {
    }

    /**
     * 在線玩家引數。
     *
     * @param name 引數名；不可為 null 或空字串
     */
    public static CommandArgument<PlayerHandle> player(String name) {
        return new PlayerArgument(name);
    }

    /**
     * 離線玩家引數（目標可離線；在線玩家同樣可解析）。
     *
     * @param name 引數名；不可為 null 或空字串
     */
    public static CommandArgument<OfflinePlayer> offlinePlayer(String name) {
        return new OfflinePlayerArgument(name);
    }

    /**
     * 有上下限的整數引數。
     *
     * @param name 引數名；不可為 null 或空字串
     * @param min  下限（含）
     * @param max  上限（含）；必須 {@code >= min}
     */
    public static CommandArgument<Integer> intArg(String name, int min, int max) {
        return new IntArgument(name, min, max);
    }

    /**
     * 有上下限的小數引數。
     *
     * @param name 引數名；不可為 null 或空字串
     * @param min  下限（含）
     * @param max  上限（含）；必須 {@code >= min}
     */
    public static CommandArgument<Double> doubleArg(String name, double min, double max) {
        return new DoubleArgument(name, min, max);
    }

    /**
     * 時間長度引數（vanilla time 語法，回傳 ticks）。
     *
     * @param name 引數名；不可為 null 或空字串
     */
    public static CommandArgument<Long> duration(String name) {
        return new DurationArgument(name);
    }

    /**
     * 世界引數（已載入世界）。
     *
     * @param name 引數名；不可為 null 或空字串
     */
    public static CommandArgument<World> world(String name) {
        return new WorldArgument(name);
    }

    /**
     * 列舉引數（固定選項，基岩可見 literal 分支）。
     *
     * @param name      引數名；不可為 null 或空字串
     * @param enumClass 列舉類別；不可為 null（須有常數）
     * @param <E>       列舉型別
     */
    public static <E extends Enum<E>> CommandArgument<E> enumArg(String name,
                                                                Class<E> enumClass) {
        Objects.requireNonNull(enumClass, "enumClass");
        return new EnumArgument<>(name, enumClass);
    }

    /**
     * 固定字串選項引數（基岩可見 literal 分支，回傳宣告形式）。
     *
     * @param name    引數名；不可為 null 或空字串
     * @param options 選項（至少一個，不可含 null／空字串）；不可為 null
     */
    public static CommandArgument<String> fixed(String name, String... options) {
        Objects.requireNonNull(options, "options");
        return new FixedOptionsArgument(name, List.of(options));
    }

    /**
     * 材質引數。
     *
     * @param name 引數名；不可為 null 或空字串
     */
    public static CommandArgument<Material> material(String name) {
        return new MaterialArgument(name);
    }
}
