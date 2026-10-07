package com.smile.acelib.display;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * 可編排的假顯示後端（顯示模組單元測試用）。
 *
 * <p>MockBukkit 4.113.1 尚未支援 {@code Score.customName} 與
 * {@code World.spawn(TextDisplay)}（皆拋 {@code UnimplementedOperationException}），
 * 因此實體與計分板渲染路徑改由此假後端覆蓋：全息字實體以 Mockito mock 承載，
 * 可用 {@link #kill(Entity)} 注入失效；計分板以帶狀態的 mock 承載，
 * 可用 {@link #scoreboardTitle(UUID)}／{@link #scoreboardScores(UUID)} 斷言。
 * 所有呼叫皆有計數，可觀察去重語意。</p>
 *
 * <p>注意 Bukkit 預設方法陷阱：3 參數 {@code registerNewObjective} 會委派到
 * 4 參數抽象方法，Mockito 只攔截實際被呼叫的 4 參數版本，兩種都 stub。</p>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 */
final class StubDisplayPlatform implements DisplayPlatform {

    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> playerBoards = new ConcurrentHashMap<>();
    private final Map<Scoreboard, BoardState> boardStates = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> playerBars = new ConcurrentHashMap<>();
    private final Map<BossBar, BarState> barStates = new ConcurrentHashMap<>();
    private final List<HologramRecord> holograms = new CopyOnWriteArrayList<>();
    private final Map<Entity, AtomicBoolean> aliveness = new ConcurrentHashMap<>();
    private final List<VisibilityChange> visibilityChanges = new CopyOnWriteArrayList<>();
    private volatile UUID lastLookup;

    /** 每次全息字生成的可觀察紀錄。 */
    record HologramRecord(Entity entity, Location location, Component text) {}

    /** 每次可見性變更的可觀察紀錄。 */
    record VisibilityChange(Player viewer, Entity entity, boolean visible) {}

    /** 計分板渲染狀態（測試斷言用）。 */
    static final class BoardState {
        volatile Objective objective;
        volatile Component title;
        final Map<String, Integer> scores = new LinkedHashMap<>();
        final Map<String, Component> customNames = new LinkedHashMap<>();
    }

    /** BossBar 狀態（測試斷言用）。 */
    static final class BarState {
        volatile Component title;
        volatile double progress;

        BarState(Component title, double progress) {
            this.title = title;
            this.progress = progress;
        }
    }

    /** 累計生成次數。 */
    int spawnCalls;
    /** 累計文字寫入次數。 */
    int writeCalls;
    /** 累計實體移除次數。 */
    int discardCalls;
    /** 累計計分板渲染次數。 */
    int scoreboardRenders;
    /** 累計 BossBar 建立次數。 */
    int bossBarCreates;
    /** 累計 BossBar 寫入次數。 */
    int bossBarWrites;
    /** 經玩家原生擁有者上下文 seam 派送的清理次數。 */
    int playerCleanupDispatches;
    /** 經實體原生擁有者上下文 seam 派送的清理次數。 */
    int entityCleanupDispatches;

    /** 註冊測試用玩家（{@link #findPlayer} 回此實例）。 */
    void addPlayer(Player player) {
        Objects.requireNonNull(player, "player");
        players.put(player.getUniqueId(), player);
    }

    /** 移除測試用玩家（後續 {@link #findPlayer} 回 null，模擬離線）。 */
    void removePlayer(UUID playerId) {
        players.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    /** @return 最近一次生成的全息字實體；從未生成時為 null */
    Entity lastSpawnedEntity() {
        if (holograms.isEmpty()) {
            return null;
        }
        return holograms.get(holograms.size() - 1).entity();
    }

    /** 標記實體失效（後續 {@link #isHologramAlive} 回 false）。 */
    void kill(Entity entity) {
        AtomicBoolean alive = aliveness.get(Objects.requireNonNull(entity, "entity"));
        if (alive != null) {
            alive.set(false);
        }
    }

    /** @return 指定實體的最新文字；未知實體時為 null */
    Component hologramText(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        for (HologramRecord record : holograms) {
            if (record.entity().equals(entity)) {
                return record.text();
            }
        }
        return null;
    }

    /** @return 指定玩家的計分板標題；從未渲染時為 null */
    Component scoreboardTitle(UUID playerId) {
        Scoreboard board = playerBoards.get(playerId);
        BoardState state = board == null ? null : boardStates.get(board);
        return state == null ? null : state.title;
    }

    /** @return 指定玩家的計分板分數（entry→分數，插入序）；從未渲染時為空 */
    Map<String, Integer> scoreboardScores(UUID playerId) {
        Scoreboard board = playerBoards.get(playerId);
        BoardState state = board == null ? null : boardStates.get(board);
        return state == null ? Map.of() : new LinkedHashMap<>(state.scores);
    }

    /** @return 指定玩家的 BossBar 狀態；從未建立時為 null */
    BarState bossBarState(UUID playerId) {
        BossBar bar = playerBars.get(playerId);
        return bar == null ? null : barStates.get(bar);
    }

    /** @return 可見性變更紀錄（依發生序）。 */
    List<VisibilityChange> visibilityChanges() {
        return new ArrayList<>(visibilityChanges);
    }

    // -----------------------------------------------------------------
    // DisplayPlatform
    // -----------------------------------------------------------------

    @Override
    public Player findPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lastLookup = playerId;
        return players.get(playerId);
    }

    @Override
    public Scoreboard createScoreboard() {
        Scoreboard board = mock(Scoreboard.class);
        if (lastLookup != null) {
            playerBoards.put(lastLookup, board);
        }
        BoardState state = new BoardState();
        boardStates.put(board, state);
        Objective objective = mock(Objective.class);
        when(board.getObjective(eq("acelib-display"))).thenAnswer(ignored -> state.objective);
        // Criteria.DUMMY 會初始化 Bukkit registry；測試替身以經典 dummy 名稱避開服務端初始化。
        when(board.registerNewObjective(eq("acelib-display"), anyString(),
                any(Component.class)))
            .thenAnswer(invocation -> registerObjective(state, objective, invocation.getArgument(2)));
        when(board.registerNewObjective(eq("acelib-display"), anyString(),
                any(Component.class), any(RenderType.class)))
            .thenAnswer(invocation -> registerObjective(state, objective, invocation.getArgument(2)));
        doAnswer(invocation -> {
            state.title = invocation.getArgument(0);
            return null;
        }).when(objective).displayName(any());
        doAnswer(invocation -> {
            scoreboardRenders++;
            return null;
        }).when(objective).setDisplaySlot(any(DisplaySlot.class));
        when(board.getEntries()).thenAnswer(ignored -> Set.copyOf(state.scores.keySet()));
        doAnswer(invocation -> {
            state.scores.remove(invocation.getArgument(0));
            return null;
        }).when(board).resetScores(anyString());
        when(objective.getScore(anyString())).thenAnswer(invocation -> {
            String entry = invocation.getArgument(0);
            Score score = mock(Score.class);
            doAnswer(set -> {
                state.scores.put(entry, set.getArgument(0));
                return null;
            }).when(score).setScore(anyInt());
            doAnswer(set -> {
                state.customNames.put(entry, set.getArgument(0));
                return null;
            }).when(score).customName(any());
            return score;
        });
        return board;
    }

    @Override
    public Scoreboard mainScoreboard() {
        return mock(Scoreboard.class);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void renderScoreboard(Scoreboard board, String objectiveName, Component title,
            List<Component> lines) {
        Objective objective = board.getObjective(objectiveName);
        if (objective == null) {
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
            String entry = "acelib-line-" + i;
            objective.getScore(entry).customName(lines.get(i));
            objective.getScore(entry).setScore(score--);
        }
    }

    @Override
    public BossBar createBossBar(Component title, BarColor color, BarStyle style) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(style, "style");
        bossBarCreates++;
        BossBar bar = mock(BossBar.class);
        if (lastLookup != null) {
            playerBars.put(lastLookup, bar);
        }
        barStates.put(bar, new BarState(title, 0.0));
        return bar;
    }

    @Override
    public void writeBossBar(BossBar bar, Component title, double progress) {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(title, "title");
        bossBarWrites++;
        BarState state = barStates.get(bar);
        if (state != null) {
            state.title = title;
            state.progress = progress;
        }
    }

    @Override
    public Entity spawnHologram(Location location, Component text) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(text, "text");
        spawnCalls++;
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        AtomicBoolean alive = new AtomicBoolean(true);
        aliveness.put(entity, alive);
        when(entity.isValid()).thenAnswer(ignored -> alive.get());
        holograms.add(new HologramRecord(entity, location.clone(), text));
        return entity;
    }

    @Override
    public void writeHologramText(Entity entity, Component text) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(text, "text");
        writeCalls++;
        for (int i = 0; i < holograms.size(); i++) {
            HologramRecord record = holograms.get(i);
            if (record.entity().equals(entity)) {
                holograms.set(i, new HologramRecord(entity, record.location(), text));
            }
        }
    }

    @Override
    public boolean isHologramAlive(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        AtomicBoolean alive = aliveness.get(entity);
        return alive != null && alive.get();
    }

    @Override
    public void discardHologram(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        discardCalls++;
        kill(entity);
    }

    @Override
    public boolean runPlayerCleanupInOwnerContext(JavaPlugin owner, Player player,
            Runnable cleanup, Runnable retired) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(player, "player");
        playerCleanupDispatches++;
        Objects.requireNonNull(cleanup, "cleanup").run();
        return true;
    }

    @Override
    public boolean runEntityCleanupInOwnerContext(JavaPlugin owner, Entity entity,
            Runnable cleanup, Runnable retired) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(entity, "entity");
        entityCleanupDispatches++;
        Objects.requireNonNull(cleanup, "cleanup").run();
        return true;
    }

    @Override
    public void concealFrom(Player viewer, Entity entity) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(entity, "entity");
        visibilityChanges.add(new VisibilityChange(viewer, entity, false));
    }

    @Override
    public void revealTo(Player viewer, Entity entity) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(entity, "entity");
        visibilityChanges.add(new VisibilityChange(viewer, entity, true));
    }

    private static Objective registerObjective(BoardState state, Objective objective,
            Component title) {
        state.title = title;
        state.objective = objective;
        return objective;
    }
}
