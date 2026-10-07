package com.smile.acelib.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.testing.FakeClock;
import com.smile.acelib.testing.FakeSafeScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DisplayService} 核心行為測試（假排程＋假後端，全 hermetic）。
 *
 * <p>涵蓋三種顯示的正常／非法／空內容路徑，以及同值不重送的去重語意。
 * 去重可觀察：假後端的呼叫計數在第二次同值更新時不再增加。</p>
 */
@DisplayName("DisplayService 核心行為")
class DisplayServiceTest {

    private Player player;
    private UUID playerId;
    private World world;
    private FakeSafeScheduler scheduler;
    private StubDisplayPlatform platform;
    private DisplayService service;

    @BeforeEach
    void setUp() {
        JavaPlugin owner = mock(JavaPlugin.class);
        scheduler = new FakeSafeScheduler(owner, new FakeClock());
        platform = new StubDisplayPlatform();
        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        platform.addPlayer(player);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        service = new DisplayServiceImpl(owner, scheduler, platform);
    }

    private Location spot() {
        Location location = new Location(world, 0.5, 64.0, 0.5);
        scheduler.setChunkLoaded(location, true);
        return location;
    }

    // -----------------------------------------------------------------
    // 計分板
    // -----------------------------------------------------------------

    @Test
    @DisplayName("計分板正常路徑：標題＋多行成功，渲染內容與分數正確")
    void scoreboard_normal_showsTitleAndLines() {
        DisplayResult result = service.showScoreboard(playerId,
            Component.text("戰績"), List.of(Component.text("擊殺 3"), Component.text("死亡 1")));

        assertEquals(DisplayState.SUCCESS, result.state());
        assertEquals(Component.text("戰績"), platform.scoreboardTitle(playerId));
        assertEquals(Map.of("acelib-line-0", 2, "acelib-line-1", 1),
            platform.scoreboardScores(playerId));
    }

    @Test
    @DisplayName("計分板空行表：清空分數，只留標題")
    void scoreboard_emptyLines_clearsScoresKeepsTitle() {
        service.showScoreboard(playerId, Component.text("戰績"),
            List.of(Component.text("擊殺 3")));
        DisplayResult result = service.showScoreboard(playerId,
            Component.text("戰績"), List.of());

        assertEquals(DisplayState.SUCCESS, result.state());
        assertTrue(platform.scoreboardScores(playerId).isEmpty());
        assertEquals(Component.text("戰績"), platform.scoreboardTitle(playerId));
    }

    @Test
    @DisplayName("計分板非法輸入：null 參數丟 IllegalArgumentException")
    void scoreboard_nullInput_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> service.showScoreboard(null, Component.text("t"), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> service.showScoreboard(playerId, null, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> service.showScoreboard(playerId, Component.text("t"), null));
    }

    @Test
    @DisplayName("計分板非法輸入：超出行數上限回 REJECTED＋DISP-003")
    void scoreboard_tooManyLines_rejected() {
        List<Component> lines = IntStream.range(0, 16)
            .mapToObj(i -> (Component) Component.text("行" + i)).toList();

        DisplayResult result = service.showScoreboard(playerId, Component.text("t"), lines);

        assertEquals(DisplayState.REJECTED, result.state());
        assertEquals(DisplayErrorCode.INVALID_INPUT, result.errorCode());
    }

    @Test
    @DisplayName("計分板行包含 null 時回 REJECTED＋DISP-003")
    void scoreboard_nullLine_rejected() {
        List<Component> lines = new ArrayList<>();
        lines.add(null);

        DisplayResult result = service.showScoreboard(playerId, Component.text("title"), lines);

        assertEquals(DisplayState.REJECTED, result.state());
        assertEquals(DisplayErrorCode.INVALID_INPUT, result.errorCode());
    }

    @Test
    @DisplayName("計分板去重：同值更新不重送，回 deduped 成功且渲染計數不變")
    void scoreboard_sameContent_deduped() {
        Component title = Component.text("戰績");
        List<Component> lines = List.of(Component.text("擊殺 3"));
        service.showScoreboard(playerId, title, lines);
        int renders = platform.scoreboardRenders;

        DisplayResult second = service.showScoreboard(playerId, title, lines);

        assertEquals(DisplayState.SUCCESS, second.state());
        assertTrue(second.deduped());
        assertEquals(renders, platform.scoreboardRenders);
    }

    @Test
    @DisplayName("計分板隱藏：無追蹤時成功且冪等；有追蹤時清除")
    void scoreboard_hide_idempotent() {
        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());

        service.showScoreboard(playerId, Component.text("t"),
            List.of(Component.text("x")));

        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());
        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());
    }

    // -----------------------------------------------------------------
    // BossBar
    // -----------------------------------------------------------------

    @Test
    @DisplayName("BossBar 正常路徑：建立並可更新標題與進度")
    void bossBar_normal_createAndUpdate() {
        DisplayResult created = service.showBossBar(playerId,
            Component.text("首領"), 0.5, BarColor.RED, BarStyle.SOLID);

        assertEquals(DisplayState.SUCCESS, created.state());
        assertEquals(1, platform.bossBarCreates);

        DisplayResult updated = service.updateBossBar(playerId, Component.text("首領·殘血"), 0.2);

        assertEquals(DisplayState.SUCCESS, updated.state());
        assertFalse(updated.deduped());
        StubDisplayPlatform.BarState state = platform.bossBarState(playerId);
        assertEquals(Component.text("首領·殘血"), state.title);
        assertEquals(0.2, state.progress);
    }

    @Test
    @DisplayName("BossBar 非法進度：NaN／越界不做靜默 clamp，回 REJECTED＋DISP-003")
    void bossBar_badProgress_rejected() {
        assertEquals(DisplayErrorCode.INVALID_INPUT,
            service.showBossBar(playerId, Component.text("t"), -0.1,
                BarColor.RED, BarStyle.SOLID).errorCode());
        assertEquals(DisplayErrorCode.INVALID_INPUT,
            service.showBossBar(playerId, Component.text("t"), 1.1,
                BarColor.RED, BarStyle.SOLID).errorCode());
        assertEquals(DisplayErrorCode.INVALID_INPUT,
            service.showBossBar(playerId, Component.text("t"), Double.NaN,
                BarColor.RED, BarStyle.SOLID).errorCode());
    }

    @Test
    @DisplayName("BossBar 更新不存在的條：回 REJECTED＋DISP-003")
    void bossBar_updateMissing_rejected() {
        DisplayResult result = service.updateBossBar(playerId, Component.text("t"), 0.5);

        assertEquals(DisplayState.REJECTED, result.state());
        assertEquals(DisplayErrorCode.INVALID_INPUT, result.errorCode());
    }

    @Test
    @DisplayName("BossBar 去重：同值更新回 deduped 成功且寫入計數不變")
    void bossBar_sameContent_deduped() {
        service.showBossBar(playerId, Component.text("t"), 0.5, BarColor.BLUE, BarStyle.SOLID);
        int writes = platform.bossBarWrites;

        DisplayResult second = service.updateBossBar(playerId, Component.text("t"), 0.5);

        assertTrue(second.isSuccess());
        assertTrue(second.deduped());
        assertEquals(writes, platform.bossBarWrites);
    }

    @Test
    @DisplayName("BossBar 隱藏：無追蹤時成功且冪等")
    void bossBar_hide_idempotent() {
        service.showBossBar(playerId, Component.text("t"), 0.5, BarColor.BLUE, BarStyle.SOLID);

        assertEquals(DisplayState.SUCCESS, service.hideBossBar(playerId).state());
        assertEquals(DisplayState.SUCCESS, service.hideBossBar(playerId).state());
    }

    // -----------------------------------------------------------------
    // 全息字
    // -----------------------------------------------------------------

    @Test
    @DisplayName("全息字正常路徑：生成成功並攜帶 id，可查詢快照")
    void hologram_normal_spawnsAndFindable() {
        DisplayResult result = service.showHologram(spot(), Component.text("你好"));

        assertEquals(DisplayState.SUCCESS, result.state());
        assertEquals(1, platform.spawnCalls);
        assertEquals(Component.text("你好"),
            service.findHologram(result.hologramId()).orElseThrow().text());
    }

    @Test
    @DisplayName("全息字更新：文字變更成功；同值回 deduped 且寫入計數不變")
    void hologram_update_changesTextAndDedupes() {
        UUID id = service.showHologram(spot(), Component.text("舊")).hologramId();
        Entity entity = platform.lastSpawnedEntity();

        DisplayResult updated = service.updateHologram(id, Component.text("新"));

        assertEquals(DisplayState.SUCCESS, updated.state());
        assertEquals(Component.text("新"), platform.hologramText(entity));
        int writes = platform.writeCalls;
        assertTrue(service.updateHologram(id, Component.text("新")).deduped());
        assertEquals(writes, platform.writeCalls);
    }

    @Test
    @DisplayName("全息字未知 id：更新回 REJECTED，移除具冪等成功")
    void hologram_unknownId_rejectedOrIdempotent() {
        UUID unknown = UUID.randomUUID();

        assertEquals(DisplayErrorCode.INVALID_INPUT,
            service.updateHologram(unknown, Component.text("x")).errorCode());
        assertEquals(DisplayState.SUCCESS, service.removeHologram(unknown).state());
    }

    @Test
    @DisplayName("全息字非法輸入：null 參數丟 IllegalArgumentException")
    void hologram_nullInput_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> service.showHologram(null, Component.text("x")));
        assertThrows(IllegalArgumentException.class,
            () -> service.showHologram(spot(), null));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateHologram(null, Component.text("x")));
    }

    @Test
    @DisplayName("全息字 chunk 未載入：回 REJECTED＋DISP-006，不留追蹤")
    void hologram_chunkNotLoaded_rejected() {
        Location remote = new Location(world, 8000.5, 64.0, 8000.5);
        scheduler.setChunkLoaded(remote, false);

        DisplayResult result = service.showHologram(remote, Component.text("遠方"));

        assertEquals(DisplayState.REJECTED, result.state());
        assertEquals(DisplayErrorCode.CHUNK_NOT_LOADED, result.errorCode());
        assertEquals(0, platform.spawnCalls);
    }

    @Test
    @DisplayName("全息字可見性：指定觀看者可顯示與隱藏，紀錄可觀察")
    void hologram_visibility_toggleSucceeds() {
        UUID id = service.showHologram(spot(), Component.text("看板")).hologramId();
        UUID viewerId = UUID.randomUUID();
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.isOnline()).thenReturn(true);
        platform.addPlayer(viewer);
        Entity entity = platform.lastSpawnedEntity();

        assertEquals(DisplayState.SUCCESS,
            service.setHologramVisible(id, viewerId, true).state());
        assertEquals(DisplayState.SUCCESS,
            service.setHologramVisible(id, viewerId, false).state());
        assertEquals(List.of(new StubDisplayPlatform.VisibilityChange(viewer, entity, true),
                new StubDisplayPlatform.VisibilityChange(viewer, entity, false)),
            platform.visibilityChanges());
    }

    // -----------------------------------------------------------------
    // 不可用 facade
    // -----------------------------------------------------------------

    @Test
    @DisplayName("unavailable facade：變更一律 FAILED＋建構代碼，清理為無操作")
    void unavailable_allMutationsFailedCleanupNoop() {
        DisplayService unavailable =
            DisplayService.forUnavailable(DisplayErrorCode.NOT_READY);

        assertEquals(DisplayErrorCode.NOT_READY,
            unavailable.showScoreboard(playerId, Component.text("t"), List.of()).errorCode());
        assertEquals(DisplayErrorCode.NOT_READY,
            unavailable.showHologram(spot(), Component.text("x")).errorCode());
        assertTrue(unavailable.findHologram(UUID.randomUUID()).isEmpty());
        assertFalse(unavailable.closePlayer(playerId));
        assertEquals(0, unavailable.closeAll());
    }
}
