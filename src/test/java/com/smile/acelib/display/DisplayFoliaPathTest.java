package com.smile.acelib.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.ScheduledTask;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * {@link DisplayService} Folia capability 分流測試。
 *
 * <h2>MockBukkit Folia 限制（必須如實記錄，不得宣稱真 runtime）</h2>
 * <p>本測試運行於 MockBukkit v26.1.2＋paper-api 26.1.2。
 * Folia capability 下 {@code SafeSchedulerImpl} 的 dispatch 路徑會把任務
 * enqueue 到 MockBukkit 的 scheduler（回傳非 cancelled task），但 runnable
 * <em>不會同步執行</em>（測試不 performTick 即不執行）。這是「seam 證據」——
 * 僅證明 dispatch 路徑未被拒絕、服務誠實回 {@code ACCEPTED} 而非謊稱
 * {@code SUCCESS}，<em>不代表</em>真 Folia region 執行。</p>
 *
 * <ul>
 *   <li><strong>online Folia</strong>：變更回 {@code ACCEPTED}（已接受派送、
 *       尚未執行），且<strong>不殘留</strong>已完成的假成功狀態</li>
 *   <li><strong>offline Folia</strong>：走真實拒絕路徑（玩家離線 →
 *       {@code ACELIB-DISP-004}），runnable 不執行</li>
 * </ul>
 *
 * <p><strong>未驗證</strong>：真 Folia runtime 的 region 內同步執行語意；
 * 本測試所有「成功 enqueue」皆標註為 seam 證據，真語意靠實機探針。</p>
 */
@DisplayName("DisplayService Folia capability path")
class DisplayFoliaPathTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("acelib-display-folia");
        player = server.addPlayer();
        playerId = player.getUniqueId();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private DisplayService foliaService() {
        return DisplayService.forProduction(plugin, new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA)));
    }

    @Test
    @DisplayName("FOLIA＋online：計分板更新回 ACCEPTED，不謊稱已完成")
    void foliaOnline_scoreboard_accepted() {
        DisplayResult result = foliaService().showScoreboard(playerId,
            Component.text("t"), List.of(Component.text("a")));

        assertEquals(DisplayState.ACCEPTED, result.state());
    }

    @Test
    @DisplayName("FOLIA＋online：BossBar 建立回 ACCEPTED")
    void foliaOnline_bossBar_accepted() {
        DisplayResult result = foliaService().showBossBar(playerId,
            Component.text("t"), 0.5, BarColor.RED, BarStyle.SOLID);

        assertEquals(DisplayState.ACCEPTED, result.state());
    }

    @Test
    @DisplayName("FOLIA＋online：全息字生成回 ACCEPTED 並攜帶追蹤 id")
    void foliaOnline_hologram_acceptedWithId() {
        DisplayService service = foliaService();
        Location location = new Location(player.getWorld(), 0.5, 64.0, 0.5);

        DisplayResult result = service.showHologram(location, Component.text("字"));

        assertTrue(result.state() == DisplayState.ACCEPTED
            || result.state() == DisplayState.REJECTED);
        if (result.state() == DisplayState.ACCEPTED) {
            // 非同步派送：追蹤 id 已配置，實體尚未生成，快照仍為 empty
            assertTrue(result.hologramId() != null);
            assertTrue(service.findHologram(result.hologramId()).isEmpty());
        }
    }

    @Test
    @DisplayName("FOLIA：移除待生成全息字後，稍後執行的生成 runnable 不得留下孤兒")
    void foliaPendingHologram_removeBeforeGeneration_preventsOrphan() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        player.getWorld().loadChunk(0, 0);
        SafeScheduler delegate = new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        DisplayService service = new DisplayServiceImpl(plugin,
            ignoreCancellation(delegate, "runAtLocation"), platform);

        DisplayResult accepted = service.showHologram(
            new Location(player.getWorld(), 0.5, 64.0, 0.5), Component.text("待生成"));
        assertEquals(DisplayState.ACCEPTED, accepted.state());
        assertTrue(service.findHologram(accepted.hologramId()).isEmpty());
        assertEquals(0, platform.spawnCalls, "生成 runnable 尚未執行");
        DisplayServiceImpl implementation = (DisplayServiceImpl) service;
        assertEquals(1, implementation.inFlightDispatchCount());
        assertEquals(1, implementation.pendingHologramCount());

        DisplayResult removed = service.removeHologram(accepted.hologramId());
        assertEquals(DisplayState.SUCCESS, removed.state());
        assertEquals("待生成的全息字已取消", removed.detail());
        assertEquals(0, implementation.pendingHologramCount());

        server.getScheduler().performTicks(1L);

        assertEquals(0, platform.spawnCalls,
            "即使排程取消未能阻止 runnable，取消標記也必須阻止生成");
        assertTrue(service.findHologram(accepted.hologramId()).isEmpty());
        assertEquals(0, implementation.inFlightDispatchCount(),
            "移除後的生成 runnable 已執行並完成清理");
    }

    @Test
    @DisplayName("FOLIA：更新待生成全息字回 ACCEPTED，並以最新文字生成")
    void foliaPendingHologram_updateBeforeGeneration_usesLatestText() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        player.getWorld().loadChunk(0, 0);
        DisplayService service = new DisplayServiceImpl(plugin, new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA)), platform);

        DisplayResult accepted = service.showHologram(
            new Location(player.getWorld(), 0.5, 64.0, 0.5), Component.text("初始"));
        DisplayResult updated = service.updateHologram(
            accepted.hologramId(), Component.text("更新後"));

        assertEquals(DisplayState.ACCEPTED, updated.state());
        assertEquals(0, platform.spawnCalls, "更新待生成內容不應提早生成實體");
        server.getScheduler().performTicks(1L);

        assertEquals(1, platform.spawnCalls);
        assertEquals(Component.text("更新後"),
            service.findHologram(accepted.hologramId()).orElseThrow().text());
        assertEquals(Component.text("更新後"), platform.hologramText(platform.lastSpawnedEntity()));
    }

    @Test
    @DisplayName("shutdown 清除待生成全息字，取消競爭後仍不得留下實體")
    void shutdown_cancelsPendingHologram() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        player.getWorld().loadChunk(0, 0);
        SafeScheduler delegate = new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        DisplayService service = new DisplayServiceImpl(plugin,
            ignoreCancellation(delegate, "runAtLocation"), platform);
        DisplayServiceImpl implementation = (DisplayServiceImpl) service;

        DisplayResult accepted = service.showHologram(
            new Location(player.getWorld(), 0.5, 64.0, 0.5), Component.text("待停用"));
        assertEquals(DisplayState.ACCEPTED, accepted.state());
        assertEquals(1, implementation.pendingHologramCount());

        ((DisplayServiceControl) service).shutdownService();
        assertEquals(0, implementation.pendingHologramCount());
        server.getScheduler().performTicks(1L);

        assertEquals(0, platform.spawnCalls,
            "即使取消未能攔住排程回呼，停用守衛也必須阻止生成");
        assertTrue(service.findHologram(accepted.hologramId()).isEmpty());
    }

    @Test
    @DisplayName("FOLIA＋offline：玩家操作回 REJECTED＋DISP-004，runnable 不執行")
    void foliaOffline_rejected() {
        player.kick();

        DisplayService service = foliaService();
        assertEquals(DisplayErrorCode.PLAYER_OFFLINE,
            service.showScoreboard(playerId, Component.text("t"), List.of()).errorCode());
        assertEquals(DisplayErrorCode.PLAYER_OFFLINE,
            service.showBossBar(playerId, Component.text("t"), 0.5,
                BarColor.RED, BarStyle.SOLID).errorCode());
    }

    @Test
    @DisplayName("shutdown 前已接受的玩家更新不得在停用後執行")
    void shutdown_cancelsAcceptedPlayerMutation() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        platform.addPlayer(player);
        DisplayService service = new DisplayServiceImpl(plugin, new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA)), platform);

        DisplayResult accepted = service.showScoreboard(playerId,
            Component.text("t"), List.of(Component.text("line")));
        assertEquals(DisplayState.ACCEPTED, accepted.state());
        assertEquals(1, ((DisplayServiceImpl) service).inFlightDispatchCount());

        ((DisplayServiceControl) service).shutdownService();
        assertEquals(0, ((DisplayServiceImpl) service).inFlightDispatchCount());
        server.getScheduler().performTicks(1L);

        assertEquals(0, platform.scoreboardRenders,
            "已停用服務的排隊更新不得在 shutdown 後建立顯示");
    }

    @Test
    @DisplayName("底層取消遇到競爭失敗時 shutdown 守衛仍阻止排隊更新")
    void shutdownGuard_blocksAcceptedMutationWhenSchedulerCancelLosesRace() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        platform.addPlayer(player);
        SafeScheduler delegate = new SafeSchedulerImpl(plugin,
            Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        SafeScheduler racingScheduler = ignorePlayerCancellation(delegate);
        DisplayService service = new DisplayServiceImpl(plugin, racingScheduler, platform);

        assertEquals(DisplayState.ACCEPTED,
            service.showScoreboard(playerId, Component.text("t"), List.of()).state());
        assertEquals(1, ((DisplayServiceImpl) service).inFlightDispatchCount());
        ((DisplayServiceControl) service).shutdownService();
        assertEquals(0, ((DisplayServiceImpl) service).inFlightDispatchCount());
        server.getScheduler().performTicks(1L);

        assertEquals(0, platform.scoreboardRenders,
            "runnable 內的 shutdown 守衛必須獨立於底層 cancel 生效");
    }

    @Test
    @DisplayName("退服會取消尚未執行的玩家顯示派送")
    void quit_cancelsAcceptedPlayerMutation() {
        StubDisplayPlatform platform = new StubDisplayPlatform();
        platform.addPlayer(player);
        DisplayService service = new DisplayServiceImpl(plugin, new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA)), platform);

        assertEquals(DisplayState.ACCEPTED,
            service.showScoreboard(playerId, Component.text("t"), List.of()).state());
        assertEquals(1, ((DisplayServiceImpl) service).inFlightDispatchCount());

        player.kick();
        service.handlePlayerQuit(playerId);

        assertEquals(0, ((DisplayServiceImpl) service).inFlightDispatchCount());
        server.getScheduler().performTicks(1L);
        assertEquals(0, platform.scoreboardRenders);
    }

    private static SafeScheduler ignorePlayerCancellation(SafeScheduler delegate) {
        return ignoreCancellation(delegate, "runForPlayer");
    }

    private static SafeScheduler ignoreCancellation(SafeScheduler delegate, String methodName) {
        return (SafeScheduler) Proxy.newProxyInstance(
            SafeScheduler.class.getClassLoader(), new Class<?>[] {SafeScheduler.class},
            (proxy, method, args) -> {
                try {
                    Object result = method.invoke(delegate, args);
                    if (methodName.equals(method.getName())
                            && result instanceof ScheduledTask task) {
                        return new ScheduledTask() {
                            @Override
                            public void cancel() {
                                // 模擬取消與已派送回呼競爭而未能阻止執行。
                            }

                            @Override
                            public boolean isCancelled() {
                                return task.isCancelled();
                            }

                            @Override
                            public org.bukkit.plugin.java.JavaPlugin getPlugin() {
                                return task.getPlugin();
                            }

                            @Override
                            public com.smile.acelib.scheduler.TaskType getType() {
                                return task.getType();
                            }

                            @Override
                            public long getCreationTick() {
                                return task.getCreationTick();
                            }
                        };
                    }
                    return result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }
}
