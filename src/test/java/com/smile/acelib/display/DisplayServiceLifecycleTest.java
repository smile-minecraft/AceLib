package com.smile.acelib.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.testing.FakeClock;
import com.smile.acelib.testing.FakeSafeScheduler;
import java.util.List;
import java.util.UUID;
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
 * {@link DisplayService} 生命週期與邊界測試（假排程＋假後端）。
 *
 * <p>涵蓋跨區重派送、離線／退休無孤兒、重複關閉冪等、
 * reload／disable 清理不影響其他服務實例。</p>
 */
@DisplayName("DisplayService 生命週期與邊界")
class DisplayServiceLifecycleTest {

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

    @Test
    @DisplayName("跨區：玩家移動後更新仍派送給同一玩家（每次呼叫重查線上玩家）")
    void crossRegion_updateAfterMove_dispatchesToSamePlayer() {
        service.showScoreboard(playerId, Component.text("t"), List.of(Component.text("a")));
        // 模擬跨區移動：同 UUID 的新玩家物件（新 region 上下文）
        Player moved = mock(Player.class);
        when(moved.getUniqueId()).thenReturn(playerId);
        when(moved.isOnline()).thenReturn(true);
        platform.addPlayer(moved);

        DisplayResult result = service.showScoreboard(playerId,
            Component.text("t"), List.of(Component.text("b")));

        assertEquals(DisplayState.SUCCESS, result.state());
        assertEquals(Component.text("t"), platform.scoreboardTitle(playerId));
        assertEquals(1, platform.scoreboardScores(playerId).size());
    }

    @Test
    @DisplayName("離線：派送被拒且無孤兒任務、無平台副作用")
    void offline_rejectedWithoutOrphans() {
        platform.removePlayer(playerId);
        scheduler.markPlayerOffline(playerId);

        DisplayResult score = service.showScoreboard(playerId,
            Component.text("t"), List.of(Component.text("a")));
        DisplayResult bar = service.showBossBar(playerId,
            Component.text("t"), 0.5, BarColor.RED, BarStyle.SOLID);

        assertEquals(DisplayErrorCode.PLAYER_OFFLINE, score.errorCode());
        assertEquals(DisplayErrorCode.PLAYER_OFFLINE, bar.errorCode());
        assertEquals(0, scheduler.pendingTaskCount());
        assertFalse(service.closePlayer(playerId));
        assertEquals(0, platform.scoreboardRenders);
        assertEquals(0, platform.bossBarCreates);
    }

    @Test
    @DisplayName("退服：handlePlayerQuit 清除該玩家追蹤且永不拋例外（含 null）")
    void quit_clearsTrackingNeverThrows() {
        service.showScoreboard(playerId, Component.text("t"), List.of(Component.text("a")));
        service.showBossBar(playerId, Component.text("t"), 0.5, BarColor.RED, BarStyle.SOLID);
        UUID holo = service.showHologram(spot(), Component.text("字")).hologramId();
        service.setHologramVisible(holo, playerId, false);

        service.handlePlayerQuit(playerId);
        service.handlePlayerQuit(null);

        assertFalse(service.closePlayer(playerId));
    }

    @Test
    @DisplayName("重複 close 冪等：第二次回 false／0，不影響其他服務實例的追蹤")
    void close_idempotentAndIsolated() {
        JavaPlugin other = mock(JavaPlugin.class);
        DisplayService second = new DisplayServiceImpl(other, scheduler, platform);
        second.showScoreboard(playerId, Component.text("other"), List.of());
        service.showScoreboard(playerId, Component.text("mine"), List.of(Component.text("a")));

        assertTrue(service.closePlayer(playerId));
        assertFalse(service.closePlayer(playerId));
        assertEquals(0, service.closeAll());
        // 另一個服務實例的追蹤不受影響：它的關閉仍有東西可清
        assertTrue(second.closePlayer(playerId));
    }

    @Test
    @DisplayName("實體失效：更新時清除追蹤、回 ENTITY_RETIRED，不留孤兒顯示")
    void retiredEntity_purgesTracking() {
        UUID id = service.showHologram(spot(), Component.text("字")).hologramId();
        service.setHologramVisible(id, playerId, true);
        Entity entity = platform.lastSpawnedEntity();
        platform.kill(entity);

        DisplayResult result = service.updateHologram(id, Component.text("新"));

        assertEquals(DisplayErrorCode.ENTITY_RETIRED, result.errorCode());
        assertTrue(service.findHologram(id).isEmpty());
    }

    @Test
    @DisplayName("實體失效後移除：追蹤已清，不重複派送移除")
    void retiredEntity_removeAfterDeath_noResend() {
        UUID id = service.showHologram(spot(), Component.text("字")).hologramId();
        Entity entity = platform.lastSpawnedEntity();
        platform.kill(entity);
        int discards = platform.discardCalls;

        DisplayResult result = service.removeHologram(id);

        assertEquals(DisplayErrorCode.ENTITY_RETIRED, result.errorCode());
        assertEquals(discards, platform.discardCalls);
    }

    @Test
    @DisplayName("reload／disable：shutdown 後操作一律 FAILED＋SHUTDOWN，且只清自身")
    void shutdown_rejectsAndClearsOwn() {
        service.showScoreboard(playerId, Component.text("t"), List.of(Component.text("a")));
        DisplayServiceControl control = (DisplayServiceControl) service;

        control.shutdownService();
        control.shutdownService();

        assertEquals(DisplayErrorCode.SHUTDOWN,
            service.showScoreboard(playerId, Component.text("t"), List.of()).errorCode());
        assertEquals(DisplayErrorCode.SHUTDOWN,
            service.showHologram(spot(), Component.text("x")).errorCode());
        assertFalse(service.closePlayer(playerId));
        assertEquals(0, service.closeAll());
    }

    @Test
    @DisplayName("closeAll：一次清除全部追蹤並回數量，第二次為 0")
    void closeAll_clearsEverythingOnce() {
        service.showScoreboard(playerId, Component.text("t"), List.of(Component.text("a")));
        service.showBossBar(playerId, Component.text("t"), 0.5, BarColor.RED, BarStyle.SOLID);
        service.showHologram(spot(), Component.text("字"));

        int removed = service.closeAll();

        assertEquals(3, removed);
        assertEquals(0, service.closeAll());
    }

    @Test
    @DisplayName("停用排程器：派送被拒不留孤兒，追蹤保留可重試")
    void disabledScheduler_rejectedWithoutOrphans() {
        scheduler.disable();

        DisplayResult result = service.showScoreboard(playerId,
            Component.text("t"), List.of(Component.text("a")));

        assertEquals(DisplayErrorCode.PLAYER_OFFLINE, result.errorCode());
        assertEquals(0, platform.scoreboardRenders);
    }

    @Test
    @DisplayName("排程器停用後 shutdown 仍以實體擁有者上下文移除全息字")
    void shutdownAfterSchedulerDisabled_removesHologramWithoutSafeScheduler() {
        service.showScoreboard(playerId, Component.text("board"), List.of());
        service.showBossBar(playerId, Component.text("bar"), 0.5,
            BarColor.BLUE, BarStyle.SOLID);
        UUID hologramId = service.showHologram(spot(), Component.text("字")).hologramId();
        Entity entity = platform.lastSpawnedEntity();
        scheduler.disable();

        ((DisplayServiceControl) service).shutdownService();

        assertTrue(service.findHologram(hologramId).isEmpty());
        DisplayServiceImpl implementation = (DisplayServiceImpl) service;
        assertEquals(1, platform.entityCleanupDispatches,
            "shutdown 必須走實體擁有者上下文 seam，而非 SafeScheduler");
        assertEquals(2, platform.playerCleanupDispatches,
            "玩家顯示拆除也必須走玩家擁有者上下文 seam");
        assertEquals(1, implementation.entityCleanupDispatchCount());
        assertEquals(0, implementation.pendingEntityCleanupCount());
        assertFalse(platform.isHologramAlive(entity),
            "SafeScheduler 已停用時仍要經 DisplayPlatform 的實體擁有者上下文清除實體");
    }
}
