package com.smile.acelib.display;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;

/**
 * 顯示後端的 Bukkit 觸碰點（Internal seam）。
 *
 * <p>所有直接碰 Bukkit 顯示 API 的呼叫都收斂在這個介面，
 * {@link DisplayService} 只負責排程、去重與生命週期追蹤，
 * 不直接呼叫 {@code Bukkit} 靜態入口。如此切分有三個目的：</p>
 * <ul>
 *   <li>單元測試可以注入假後端，不依賴 MockBukkit 的實體生成支援</li>
 *   <li>排程歸屬在 service 層一次看清（玩家操作走玩家上下文、
 *       生成走位置上下文、實體操作走實體上下文）</li>
 *   <li>呼叫端看到的公開契約只有 {@link DisplayService}，
 *       本介面僅為可替換的實作細節</li>
 * </ul>
 *
 * <p>實作必須是無狀態的：所有追蹤狀態由 service 持有。
 * 所有方法都假設已在正確的擁有者上下文內被呼叫，
 * 不自行做排程派送。</p>
 *
 * @since 1.4.0
 */
interface DisplayPlatform {

    /**
     * 依 UUID 查詢在線玩家。
     *
     * @param playerId 玩家 UUID；不可為 null
     * @return 在線玩家；離線或未知時回 null
     */
    Player findPlayer(UUID playerId);

    /**
     * 建立一份新的空白計分板（per-player 專用，不動主計分板）。
     *
     * @return 新的計分板；永不為 null
     */
    Scoreboard createScoreboard();

    /**
     * 取得伺服器主計分板（清理時把玩家設回此處）。
     *
     * @return 主計分板；永不為 null
     */
    Scoreboard mainScoreboard();

    /** 在呼叫端已派送到玩家上下文時渲染計分板內容。 */
    void renderScoreboard(Scoreboard board, String objectiveName, Component title,
        List<Component> lines);

    /**
     * 建立一條新的 BossBar（per-player 專用）。
     *
     * @param title 標題；不可為 null
     * @param color 顏色；不可為 null
     * @param style 樣式；不可為 null
     * @return 新的 BossBar；永不為 null
     */
    BossBar createBossBar(Component title, BarColor color, BarStyle style);

    /**
     * 更新既有 BossBar 的標題與進度。
     *
     * @param bar 目標 BossBar；不可為 null
     * @param title 新標題；不可為 null
     * @param progress 新進度；必須在 [0.0, 1.0] 內
     */
    void writeBossBar(BossBar bar, Component title, double progress);

    /**
     * 在指定位置生成一隻全息字實體（native TextDisplay）。
     *
     * <p>生成時必須套用最小安全預設：{@code setVisibleByDefault(false)}
     * ＋{@code setPersistent(false)}，避免殘留實體被全服看見或寫入 chunk。</p>
     *
     * @param location 生成位置；不可為 null
     * @param text 初始文字；不可為 null
     * @return 生成的實體；永不為 null
     */
    Entity spawnHologram(Location location, Component text);

    /**
     * 覆寫全息字實體的文字。
     *
     * @param entity 目標實體；不可為 null
     * @param text 新文字；不可為 null
     */
    void writeHologramText(Entity entity, Component text);

    /**
     * 判斷全息字實體是否仍然有效。
     *
     * @param entity 目標實體；不可為 null
     * @return 有效為 true
     */
    boolean isHologramAlive(Entity entity);

    /**
     * 移除全息字實體。
     *
     * @param entity 目標實體；不可為 null
     */
    void discardHologram(Entity entity);

    /**
     * 在指定玩家的擁有者上下文執行關閉清理；不經 SafeScheduler 的 plugin task tracking。
     *
     * @return 已執行或已接受派送時為 true；已退休／無法派送時為 false
     */
    boolean runPlayerCleanupInOwnerContext(JavaPlugin owner, Player player,
        Runnable cleanup, Runnable retired);

    /**
     * 在指定實體的擁有者上下文執行關閉清理；不經 SafeScheduler 的 plugin task tracking。
     *
     * @return 已執行或已接受派送時為 true；已退休／無法派送時為 false
     */
    boolean runEntityCleanupInOwnerContext(JavaPlugin owner, Entity entity,
        Runnable cleanup, Runnable retired);

    /**
     * 對指定觀看者隱藏全息字實體（per-plugin 疊加，不影響其他 plugin 的可見性）。
     *
     * @param viewer 觀看者；不可為 null
     * @param entity 目標實體；不可為 null
     */
    void concealFrom(Player viewer, Entity entity);

    /**
     * 對指定觀看者重新顯示全息字實體。
     *
     * @param viewer 觀看者；不可為 null
     * @param entity 目標實體；不可為 null
     */
    void revealTo(Player viewer, Entity entity);
}
