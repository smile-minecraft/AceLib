package com.smile.acelib.display;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * {@link DisplayService} 的 production 實作（package-private）。
 *
 * <p>排程歸屬：玩家操作走 {@code runForPlayer}、全息字生成走
 * {@code runAtLocation}、全息字後續操作走 {@code runForEntity}。
 * 派送內捕捉的例外不吞掉：記入結果並回 {@code FAILED + ACELIB-DISP-007}。</p>
 *
 * <p>追蹤狀態全放在記憶體 map（不長期持有 {@code Player}，
 * 全息字需持有 {@code Entity} 以便派送實體上下文，失效即清）。
 * 執行緒安全：追蹤表為 concurrent map，停用旗標為原子；已生成條目的快照欄位為
 * volatile，待生成狀態則以每個 id 的監視器協調更新、取消與生成。</p>
 */
final class DisplayServiceImpl implements DisplayService, DisplayServiceControl {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    /** 計分板 objective 名稱（per-player 專屬板內唯一即可）。 */
    private static final String OBJECTIVE_NAME = "acelib-display";

    private final JavaPlugin owner;
    private final SafeScheduler scheduler;
    private final DisplayPlatform platform;

    private final ConcurrentHashMap<UUID, ScoreboardEntry> scoreboards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BossBarEntry> bossBars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, HologramEntry> holograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingHologram> pendingHolograms =
        new ConcurrentHashMap<>();
    private final Set<InFlightDispatch> inFlightDispatches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger pendingEntityCleanupCount = new AtomicInteger();
    private final AtomicInteger entityCleanupDispatchCount = new AtomicInteger();
    private final Object lifecycleLock = new Object();

    DisplayServiceImpl(JavaPlugin owner, SafeScheduler scheduler, DisplayPlatform platform) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    int inFlightDispatchCount() {
        return inFlightDispatches.size();
    }

    int pendingHologramCount() {
        return pendingHolograms.size();
    }

    int pendingEntityCleanupCount() {
        return pendingEntityCleanupCount.get();
    }

    int entityCleanupDispatchCount() {
        return entityCleanupDispatchCount.get();
    }

    // -----------------------------------------------------------------
    // 計分板
    // -----------------------------------------------------------------

    @Override
    public DisplayResult showScoreboard(UUID playerId, Component title, List<Component> lines) {
        require(playerId, "playerId");
        require(title, "title");
        require(lines, "lines");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        if (lines.size() > MAX_SCOREBOARD_LINES) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                "計分板行數上限為 " + MAX_SCOREBOARD_LINES + "，實際為 " + lines.size());
        }
        for (Component line : lines) {
            if (line == null) {
                return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                    "計分板行不可含 null");
            }
        }
        List<Component> frozen = List.copyOf(lines);
        Player player = platform.findPlayer(playerId);
        if (!isOnline(player)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "目標玩家已離線");
        }
        ScoreboardEntry existing = scoreboards.get(playerId);
        if (existing != null && existing.title.equals(title) && existing.lines.equals(frozen)) {
            return DisplayResult.deduped("計分板內容相同，未重送");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(playerId,
                operation -> scheduler.runForPlayer(player, operation), () -> {
            try {
                Scoreboard board = scoreboards.computeIfAbsent(playerId,
                    ignored -> new ScoreboardEntry(platform.createScoreboard())).board;
                platform.renderScoreboard(board, OBJECTIVE_NAME, title, frozen);
                player.setScoreboard(board);
                ScoreboardEntry entry = scoreboards.get(playerId);
                if (entry != null) {
                    entry.title = title;
                    entry.lines = frozen;
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時玩家已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "計分板更新失敗：" + error);
        }
        ScoreboardEntry applied = scoreboards.get(playerId);
        if (applied != null && applied.title.equals(title) && applied.lines.equals(frozen)) {
            return DisplayResult.success("計分板已更新");
        }
        return DisplayResult.accepted("計分板更新已接受派送，尚未執行");
    }

    @Override
    public DisplayResult hideScoreboard(UUID playerId) {
        require(playerId, "playerId");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        if (!scoreboards.containsKey(playerId)) {
            return DisplayResult.success("無被追蹤的計分板");
        }
        Player player = platform.findPlayer(playerId);
        if (!isOnline(player)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "目標玩家已離線，保留追蹤待重連後清理");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(playerId,
                operation -> scheduler.runForPlayer(player, operation), () -> {
            try {
                ScoreboardEntry owned = scoreboards.remove(playerId);
                if (owned != null && player.getScoreboard() == owned.board) {
                    player.setScoreboard(platform.mainScoreboard());
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時玩家已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "計分板移除失敗：" + error);
        }
        if (!scoreboards.containsKey(playerId)) {
            return DisplayResult.success("計分板已移除");
        }
        return DisplayResult.accepted("計分板移除已接受派送，尚未執行");
    }

    // -----------------------------------------------------------------
    // BossBar
    // -----------------------------------------------------------------

    @Override
    public DisplayResult showBossBar(UUID playerId, Component title, double progress,
            BarColor color, BarStyle style) {
        require(playerId, "playerId");
        require(title, "title");
        require(color, "color");
        require(style, "style");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        String badProgress = checkProgress(progress);
        if (badProgress != null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT, badProgress);
        }
        Player player = platform.findPlayer(playerId);
        if (!isOnline(player)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "目標玩家已離線");
        }
        BossBarEntry existing = bossBars.get(playerId);
        if (existing != null && existing.title.equals(title)
                && Double.compare(existing.progress, progress) == 0) {
            return DisplayResult.deduped("BossBar 內容相同，未重送");
        }
        boolean creating = existing == null;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(playerId,
                operation -> scheduler.runForPlayer(player, operation), () -> {
            try {
                BossBarEntry entry = bossBars.computeIfAbsent(playerId,
                    ignored -> new BossBarEntry(platform.createBossBar(title, color, style)));
                platform.writeBossBar(entry.bar, title, progress);
                entry.bar.addPlayer(player);
                entry.title = title;
                entry.progress = progress;
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時玩家已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "BossBar 更新失敗：" + error);
        }
        BossBarEntry applied = bossBars.get(playerId);
        if (applied != null && applied.title.equals(title)
                && Double.compare(applied.progress, progress) == 0) {
            return DisplayResult.success(creating ? "BossBar 已建立" : "BossBar 已更新");
        }
        return DisplayResult.accepted("BossBar 更新已接受派送，尚未執行");
    }

    @Override
    public DisplayResult updateBossBar(UUID playerId, Component title, double progress) {
        require(playerId, "playerId");
        require(title, "title");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        String badProgress = checkProgress(progress);
        if (badProgress != null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT, badProgress);
        }
        BossBarEntry existing = bossBars.get(playerId);
        if (existing == null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                "該玩家沒有被追蹤的 BossBar，請先建立");
        }
        if (existing.title.equals(title) && Double.compare(existing.progress, progress) == 0) {
            return DisplayResult.deduped("BossBar 內容相同，未重送");
        }
        Player player = platform.findPlayer(playerId);
        if (!isOnline(player)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "目標玩家已離線");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(playerId,
                operation -> scheduler.runForPlayer(player, operation), () -> {
            try {
                BossBarEntry entry = bossBars.get(playerId);
                if (entry == null) {
                    return;
                }
                platform.writeBossBar(entry.bar, title, progress);
                entry.title = title;
                entry.progress = progress;
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時玩家已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "BossBar 更新失敗：" + error);
        }
        BossBarEntry applied = bossBars.get(playerId);
        if (applied != null && applied.title.equals(title)
                && Double.compare(applied.progress, progress) == 0) {
            return DisplayResult.success("BossBar 已更新");
        }
        return DisplayResult.accepted("BossBar 更新已接受派送，尚未執行");
    }

    @Override
    public DisplayResult hideBossBar(UUID playerId) {
        require(playerId, "playerId");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        BossBarEntry existing = bossBars.get(playerId);
        if (existing == null) {
            return DisplayResult.success("無被追蹤的 BossBar");
        }
        Player player = platform.findPlayer(playerId);
        if (!isOnline(player)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "目標玩家已離線，保留追蹤待重連後清理");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(playerId,
                operation -> scheduler.runForPlayer(player, operation), () -> {
            try {
                BossBarEntry entry = bossBars.remove(playerId);
                if (entry != null) {
                    entry.bar.removePlayer(player);
                    entry.bar.setVisible(false);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時玩家已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "BossBar 移除失敗：" + error);
        }
        if (!bossBars.containsKey(playerId)) {
            return DisplayResult.success("BossBar 已移除");
        }
        return DisplayResult.accepted("BossBar 移除已接受派送，尚未執行");
    }

    // -----------------------------------------------------------------
    // 全息字
    // -----------------------------------------------------------------

    @Override
    public DisplayResult showHologram(Location location, Component text) {
        require(location, "location");
        require(text, "text");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        if (location.getWorld() == null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                "生成位置必須帶有世界");
        }
        Location frozen = location.clone();
        UUID id = UUID.randomUUID();
        PendingHologram pending = new PendingHologram(frozen, text);
        pendingHolograms.put(id, pending);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task;
        try {
            task = dispatch(operation -> scheduler.runAtLocation(frozen, operation),
                () -> spawnHologram(id, pending, failure));
        } catch (RuntimeException | Error dispatchFailure) {
            cancelPendingHologram(id, pending);
            throw dispatchFailure;
        }
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.CHUNK_NOT_LOADED,
            "目標 chunk 尚未載入，全息字未生成");
        if (dispatchFailure != null) {
            cancelPendingHologram(id, pending);
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "全息字生成失敗：" + error);
        }
        if (holograms.containsKey(id)) {
            return DisplayResult.success(id, "全息字已生成");
        }
        return DisplayResult.accepted(id, "全息字生成已接受派送，尚未執行");
    }

    @Override
    public DisplayResult updateHologram(UUID hologramId, Component text) {
        require(hologramId, "hologramId");
        require(text, "text");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        PendingHologram pending = pendingHolograms.get(hologramId);
        if (pending != null) {
            synchronized (pending) {
                if (pendingHolograms.get(hologramId) == pending
                        && !pending.cancelRequested) {
                    if (pending.text.equals(text)) {
                        return DisplayResult.deduped("待生成全息字內容相同，未重送");
                    }
                    pending.text = text;
                    return DisplayResult.accepted("全息字文字更新已套用於待生成內容");
                }
            }
        }
        HologramEntry entry = holograms.get(hologramId);
        if (entry == null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                "未知全息字 id");
        }
        if (entry.text.equals(text)) {
            return DisplayResult.deduped("全息字內容相同，未重送");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean retiredRace = new AtomicBoolean(false);
        ScheduledTask task = dispatch(
                operation -> scheduler.runForEntity(entry.entity, operation), () -> {
            try {
                HologramEntry current = holograms.get(hologramId);
                if (current == null || !platform.isHologramAlive(current.entity)) {
                    holograms.remove(hologramId);
                    retiredRace.set(true);
                    return;
                }
                platform.writeHologramText(current.entity, text);
                current.text = text;
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.ENTITY_RETIRED,
            "全息字實體已失效，追蹤已清除");
        if (dispatchFailure != null || retiredRace.get()) {
            holograms.remove(hologramId);
            if (dispatchFailure != null) {
                return dispatchFailure;
            }
            return DisplayResult.rejected(DisplayErrorCode.ENTITY_RETIRED,
                "全息字實體已失效，追蹤已清除");
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "全息字更新失敗：" + error);
        }
        HologramEntry applied = holograms.get(hologramId);
        if (applied != null) {
            return DisplayResult.success("全息字已更新");
        }
        return DisplayResult.accepted("全息字更新已接受派送，尚未執行");
    }

    @Override
    public DisplayResult setHologramVisible(UUID hologramId, UUID viewerId, boolean visible) {
        require(hologramId, "hologramId");
        require(viewerId, "viewerId");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        HologramEntry entry = holograms.get(hologramId);
        if (entry == null) {
            return DisplayResult.rejected(DisplayErrorCode.INVALID_INPUT,
                "未知全息字 id");
        }
        // 生成預設對所有人隱藏（setVisibleByDefault(false)），追蹤集只記已顯示者；
        // 未在集合內即視為隱藏，與預設一致。
        boolean shown = entry.visibleViewers.contains(viewerId);
        if (shown == visible) {
            return DisplayResult.deduped("可見性相同，未重送");
        }
        Player viewer = platform.findPlayer(viewerId);
        if (!isOnline(viewer)) {
            return DisplayResult.rejected(DisplayErrorCode.PLAYER_OFFLINE,
                "觀看者已離線");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(viewerId,
                operation -> scheduler.runForPlayer(viewer, operation), () -> {
            try {
                HologramEntry current = holograms.get(hologramId);
                if (current == null) {
                    return;
                }
                if (visible) {
                    platform.revealTo(viewer, current.entity);
                    current.visibleViewers.add(viewerId);
                } else {
                    platform.concealFrom(viewer, current.entity);
                    current.visibleViewers.remove(viewerId);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.PLAYER_OFFLINE,
            "派送時觀看者已離線");
        if (dispatchFailure != null) {
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "可見性更新失敗：" + error);
        }
        HologramEntry applied = holograms.get(hologramId);
        if (applied != null && applied.visibleViewers.contains(viewerId) == visible) {
            return DisplayResult.success(visible ? "全息字已顯示" : "全息字已隱藏");
        }
        return DisplayResult.accepted("可見性更新已接受派送，尚未執行");
    }

    @Override
    public DisplayResult removeHologram(UUID hologramId) {
        require(hologramId, "hologramId");
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        PendingHologram pending = pendingHolograms.get(hologramId);
        if (pending != null && cancelPendingHologram(hologramId, pending)) {
            return DisplayResult.success("待生成的全息字已取消");
        }
        HologramEntry entry = holograms.get(hologramId);
        if (entry == null) {
            return DisplayResult.success("未知全息字 id，無操作");
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ScheduledTask task = dispatch(
                operation -> scheduler.runForEntity(entry.entity, operation), () -> {
            try {
                HologramEntry current = holograms.remove(hologramId);
                if (current != null && platform.isHologramAlive(current.entity)) {
                    platform.discardHologram(current.entity);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        DisplayResult dispatchFailure = dispatchFailure(task, DisplayErrorCode.ENTITY_RETIRED,
            "全息字實體已失效，追蹤已清除");
        if (dispatchFailure != null) {
            holograms.remove(hologramId);
            return dispatchFailure;
        }
        Throwable error = failure.get();
        if (error != null) {
            return DisplayResult.failed(DisplayErrorCode.OPERATION_FAILED,
                "全息字移除失敗：" + error);
        }
        if (!holograms.containsKey(hologramId)) {
            return DisplayResult.success("全息字已移除");
        }
        return DisplayResult.accepted("全息字移除已接受派送，尚未執行");
    }

    @Override
    public Optional<Hologram> findHologram(UUID hologramId) {
        require(hologramId, "hologramId");
        HologramEntry entry = holograms.get(hologramId);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new Hologram(hologramId, entry.location.clone(), entry.text));
    }

    // -----------------------------------------------------------------
    // 清理
    // -----------------------------------------------------------------

    @Override
    public boolean closePlayer(UUID playerId) {
        require(playerId, "playerId");
        cancelPlayerDispatches(playerId);
        ScoreboardEntry scoreboard = scoreboards.remove(playerId);
        BossBarEntry bar = bossBars.remove(playerId);
        List<HologramEntry> visibleHolograms = dropViewer(playerId);
        boolean tracked = scoreboard != null || bar != null || !visibleHolograms.isEmpty();
        Player player = platform.findPlayer(playerId);
        if (player != null && tracked) {
            dispatchPlayerCleanup(player, () -> {
                if (scoreboard != null) {
                    runBestEffort("restoreScoreboard", () -> {
                        if (player.getScoreboard() == scoreboard.board) {
                            player.setScoreboard(platform.mainScoreboard());
                        }
                    });
                }
                if (bar != null) {
                    runBestEffort("detachBossBar", () -> bar.bar.removePlayer(player));
                    runBestEffort("hideBossBar", () -> bar.bar.setVisible(false));
                }
                for (HologramEntry hologram : visibleHolograms) {
                    runBestEffort("concealHologram", () ->
                        platform.concealFrom(player, hologram.entity));
                }
            }, "closePlayer");
        }
        return tracked;
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        try {
            if (playerId == null) {
                return;
            }
            closePlayer(playerId);
        } catch (Throwable failure) {
            logCleanupFailure("handlePlayerQuit", failure);
        }
    }

    @Override
    public int closeAll() {
        int removed = 0;
        for (UUID playerId : new ArrayList<>(scoreboards.keySet())) {
            ScoreboardEntry entry = scoreboards.remove(playerId);
            if (entry != null) {
                removed++;
                restoreScoreboardQuietly(playerId, entry);
            }
        }
        for (UUID playerId : new ArrayList<>(bossBars.keySet())) {
            BossBarEntry entry = bossBars.remove(playerId);
            if (entry != null) {
                removed++;
                detachBarQuietly(playerId, entry.bar);
            }
        }
        for (UUID hologramId : new ArrayList<>(holograms.keySet())) {
            HologramEntry entry = holograms.remove(hologramId);
            if (entry != null) {
                removed++;
                discardQuietly(entry.entity);
            }
        }
        for (var entry : new ArrayList<>(pendingHolograms.entrySet())) {
            if (cancelPendingHologram(entry.getKey(), entry.getValue())) {
                removed++;
            } else {
                HologramEntry spawned = holograms.remove(entry.getKey());
                if (spawned != null) {
                    removed++;
                    discardQuietly(spawned.entity);
                }
            }
        }
        return removed;
    }

    @Override
    public void shutdownService() {
        synchronized (lifecycleLock) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            for (InFlightDispatch dispatch : new ArrayList<>(inFlightDispatches)) {
                dispatch.cancel();
            }
        }
        closeAll();
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    private void restoreScoreboardQuietly(UUID playerId, ScoreboardEntry owned) {
        Player player = safeFindPlayer(playerId);
        if (player == null || owned == null) {
            return;
        }
        dispatchPlayerCleanup(player, () -> {
            if (player.getScoreboard() == owned.board) {
                player.setScoreboard(platform.mainScoreboard());
            }
        }, "restoreScoreboard");
    }

    private void spawnHologram(UUID hologramId, PendingHologram pending,
            AtomicReference<Throwable> failure) {
        synchronized (pending) {
            if (pending.cancelRequested || shutdown.get()
                    || pendingHolograms.get(hologramId) != pending) {
                pendingHolograms.remove(hologramId, pending);
                return;
            }
            try {
                Entity entity = platform.spawnHologram(pending.location, pending.text);
                holograms.put(hologramId,
                    new HologramEntry(entity, pending.location, pending.text));
            } catch (Throwable spawnFailure) {
                failure.set(spawnFailure);
            } finally {
                pendingHolograms.remove(hologramId, pending);
            }
        }
    }

    private boolean cancelPendingHologram(UUID hologramId, PendingHologram pending) {
        synchronized (pending) {
            if (!pendingHolograms.remove(hologramId, pending)) {
                return false;
            }
            pending.cancelRequested = true;
            return true;
        }
    }

    private void detachBarQuietly(UUID playerId, BossBar bar) {
        if (bar == null) {
            return;
        }
        Player player = safeFindPlayer(playerId);
        if (player == null) {
            return;
        }
        dispatchPlayerCleanup(player, () -> {
            runBestEffort("detachBossBar", () -> bar.removePlayer(player));
            runBestEffort("hideBossBar", () -> bar.setVisible(false));
        }, "detachBossBar");
    }

    private void discardQuietly(Entity entity) {
        if (entity == null) {
            return;
        }
        AtomicBoolean settled = new AtomicBoolean(false);
        pendingEntityCleanupCount.incrementAndGet();
        entityCleanupDispatchCount.incrementAndGet();
        Runnable settle = () -> {
            if (settled.compareAndSet(false, true)) {
                pendingEntityCleanupCount.decrementAndGet();
            }
        };
        try {
            boolean accepted = platform.runEntityCleanupInOwnerContext(owner, entity, () -> {
                try {
                    if (platform.isHologramAlive(entity)) {
                        platform.discardHologram(entity);
                    }
                } catch (Throwable failure) {
                    logCleanupFailure("removeHologram", failure);
                } finally {
                    settle.run();
                }
            }, settle);
            if (!accepted) {
                settle.run();
            }
        } catch (Throwable failure) {
            settle.run();
            logCleanupFailure("dispatchHologramRemoval", failure);
        }
    }

    private void dispatchPlayerCleanup(Player player, Runnable cleanup, String operation) {
        try {
            boolean accepted = platform.runPlayerCleanupInOwnerContext(owner, player, () -> {
                try {
                    cleanup.run();
                } catch (Throwable failure) {
                    logCleanupFailure(operation, failure);
                }
            }, () -> LOGGER.fine("[" + DisplayErrorCode.PLAYER_OFFLINE
                + "] display cleanup retired before execution (operation=" + operation + ")"));
            if (!accepted) {
                LOGGER.fine("[" + DisplayErrorCode.PLAYER_OFFLINE
                    + "] display cleanup not scheduled (operation=" + operation + ")");
            }
        } catch (Throwable failure) {
            logCleanupFailure(operation, failure);
        }
    }

    private static void runBestEffort(String operation, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            logCleanupFailure(operation, failure);
        }
    }

    private static void logCleanupFailure(String operation, Throwable failure) {
        LOGGER.log(Level.FINE, "[" + DisplayErrorCode.OPERATION_FAILED
            + "] display cleanup failed (operation=" + operation + ")", failure);
    }

    private ScheduledTask dispatch(Function<Runnable, ScheduledTask> submit, Runnable operation) {
        return dispatch(null, submit, operation);
    }

    private ScheduledTask dispatch(UUID playerId,
            Function<Runnable, ScheduledTask> submit, Runnable operation) {
        InFlightDispatch inFlight = new InFlightDispatch(playerId, operation);
        inFlightDispatches.add(inFlight);
        if (shutdown.get()) {
            inFlight.cancel();
        }
        try {
            ScheduledTask task = Objects.requireNonNull(submit.apply(inFlight), "scheduled task");
            inFlight.attach(task);
            if (task.isCancelled()) {
                inFlight.cancel();
            }
            return task;
        } catch (RuntimeException | Error failure) {
            inFlight.cancel();
            throw failure;
        }
    }

    private void cancelPlayerDispatches(UUID playerId) {
        synchronized (lifecycleLock) {
            for (InFlightDispatch dispatch : new ArrayList<>(inFlightDispatches)) {
                if (playerId.equals(dispatch.playerId)) {
                    dispatch.cancel();
                }
            }
        }
    }

    private DisplayResult dispatchFailure(ScheduledTask task, String rejectedCode,
            String rejectedDetail) {
        if (shutdown.get()) {
            return DisplayResult.failed(DisplayErrorCode.SHUTDOWN, "display service 已停用");
        }
        if (task.isCancelled()) {
            return DisplayResult.rejected(rejectedCode, rejectedDetail);
        }
        return null;
    }

    private Player safeFindPlayer(UUID playerId) {
        try {
            Player player = platform.findPlayer(playerId);
            return isOnline(player) ? player : null;
        } catch (Throwable failure) {
            LOGGER.log(Level.FINE, "[" + DisplayErrorCode.OPERATION_FAILED
                + "] player lookup failed during display cleanup", failure);
            return null;
        }
    }

    private List<HologramEntry> dropViewer(UUID playerId) {
        List<HologramEntry> visible = new ArrayList<>();
        for (HologramEntry entry : holograms.values()) {
            if (entry.visibleViewers.remove(playerId)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private static boolean isOnline(Player player) {
        try {
            return player != null && player.isOnline();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String checkProgress(double progress) {
        if (Double.isNaN(progress) || progress < 0.0 || progress > 1.0) {
            return "BossBar 進度必須在 [0.0, 1.0] 內，實際為 " + progress;
        }
        return null;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("[" + DisplayErrorCode.INVALID_INPUT
                + "] " + name + " must not be null");
        }
    }

    private final class InFlightDispatch implements Runnable {

        private final UUID playerId;
        private final Runnable operation;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile ScheduledTask scheduledTask;

        private InFlightDispatch(UUID playerId, Runnable operation) {
            this.playerId = playerId;
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        @Override
        public void run() {
            try {
                synchronized (lifecycleLock) {
                    if (!shutdown.get() && !cancelled.get()) {
                        operation.run();
                    }
                }
            } finally {
                finish();
            }
        }

        private void attach(ScheduledTask task) {
            scheduledTask = task;
            if (cancelled.get()) {
                cancelDelegate(task);
            }
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            ScheduledTask task = scheduledTask;
            if (task != null) {
                cancelDelegate(task);
            }
            inFlightDispatches.remove(this);
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                inFlightDispatches.remove(this);
            }
        }

        private void cancelDelegate(ScheduledTask task) {
            try {
                task.cancel();
            } catch (Throwable failure) {
                logCleanupFailure("cancelInFlightDisplay", failure);
            }
        }
    }

    // -----------------------------------------------------------------
    // 追蹤條目
    // -----------------------------------------------------------------

    private static final class ScoreboardEntry {
        final Scoreboard board;
        volatile Component title;
        volatile List<Component> lines;

        ScoreboardEntry(Scoreboard board) {
            this.board = board;
            this.title = Component.empty();
            this.lines = List.of();
        }
    }

    private static final class BossBarEntry {
        final BossBar bar;
        volatile Component title;
        volatile double progress;

        BossBarEntry(BossBar bar) {
            this.bar = bar;
            this.title = Component.empty();
            this.progress = 0.0;
        }
    }

    private static final class HologramEntry {
        final Entity entity;
        final Location location;
        volatile Component text;
        final Set<UUID> visibleViewers = ConcurrentHashMap.newKeySet();

        HologramEntry(Entity entity, Location location, Component text) {
            this.entity = entity;
            this.location = location;
            this.text = text;
        }
    }

    private static final class PendingHologram {
        final Location location;
        Component text;
        boolean cancelRequested;

        PendingHologram(Location location, Component text) {
            this.location = location;
            this.text = text;
        }
    }
}
