package com.smile.acelib.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * {@link DisplayPlatform} 的 Bukkit 預設實作（Internal）。
 *
 * <p>無狀態：所有追蹤由 {@link DisplayService} 持有。
 * 所有方法假設已在正確的擁有者上下文內被呼叫，不自行做排程派送。</p>
 *
 * @since 1.4.0
 */
final class BukkitDisplayPlatform implements DisplayPlatform {

    private final Plugin owner;

    /**
     * 建立預設後端。
     *
     * @param owner 擁有者 plugin（可見性操作的第一參數）；不可為 null
     */
    BukkitDisplayPlatform(Plugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public Player findPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Bukkit.getPlayer(playerId);
    }

    @Override
    public Scoreboard createScoreboard() {
        return Objects.requireNonNull(Bukkit.getScoreboardManager(),
            "scoreboardManager").getNewScoreboard();
    }

    @Override
    public Scoreboard mainScoreboard() {
        return Objects.requireNonNull(Bukkit.getScoreboardManager(),
            "scoreboardManager").getMainScoreboard();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void renderScoreboard(Scoreboard board, String objectiveName, Component title,
            List<Component> lines) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(objectiveName, "objectiveName");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(lines, "lines");
        Objective objective = board.getObjective(objectiveName);
        if (objective == null) {
            // Criteria.DUMMY eagerly calls Bukkit.getScoreboardCriteria during static init;
            // the string overload keeps the backend usable in headless runtimes and MockBukkit.
            objective = board.registerNewObjective(objectiveName, "dummy", title);
        } else {
            objective.displayName(title);
        }
        for (String stale : new ArrayList<>(board.getEntries())) {
            board.resetScores(stale);
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            Score entry = objective.getScore("acelib-line-" + i);
            entry.customName(lines.get(i));
            entry.setScore(score--);
        }
    }

    @Override
    public BossBar createBossBar(Component title, BarColor color, BarStyle style) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(style, "style");
        return Bukkit.createBossBar(toLegacy(title), color, style);
    }

    @Override
    public void writeBossBar(BossBar bar, Component title, double progress) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(title, "title");
        bar.setTitle(toLegacy(title));
        bar.setProgress(progress);
    }

    @Override
    public Entity spawnHologram(Location location, Component text) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(text, "text");
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("[" + DisplayErrorCode.INVALID_INPUT
                + "] location 必須帶有世界");
        }
        return world.spawn(location, TextDisplay.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setVisibleByDefault(false);
            spawned.text(text);
        });
    }

    @Override
    public void writeHologramText(Entity entity, Component text) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(text, "text");
        asTextDisplay(entity).text(text);
    }

    @Override
    public boolean isHologramAlive(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return entity.isValid();
    }

    @Override
    public void discardHologram(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        entity.remove();
    }

    @Override
    public boolean runPlayerCleanupInOwnerContext(JavaPlugin owner, Player player,
            Runnable cleanup, Runnable retired) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(retired, "retired");
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            cleanup.run();
            return true;
        }
        return player.getScheduler().execute(owner, cleanup, retired, 1L);
    }

    @Override
    public boolean runEntityCleanupInOwnerContext(JavaPlugin owner, Entity entity,
            Runnable cleanup, Runnable retired) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(retired, "retired");
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            cleanup.run();
            return true;
        }
        return entity.getScheduler().execute(owner, cleanup, retired, 1L);
    }

    @Override
    public void concealFrom(Player viewer, Entity entity) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(entity, "entity");
        viewer.hideEntity(owner, entity);
    }

    @Override
    public void revealTo(Player viewer, Entity entity) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(entity, "entity");
        viewer.showEntity(owner, entity);
    }

    private static TextDisplay asTextDisplay(Entity entity) {
        if (entity instanceof TextDisplay textDisplay) {
            return textDisplay;
        }
        throw new IllegalArgumentException("[" + DisplayErrorCode.INVALID_INPUT
            + "] 全息字實體必須為 TextDisplay，實際為 "
            + entity.getClass().getName());
    }

    private static String toLegacy(Component text) {
        return LegacyComponentSerializer.legacySection().serialize(text);
    }
}
