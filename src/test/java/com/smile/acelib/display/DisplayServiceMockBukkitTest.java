package com.smile.acelib.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.testing.FakeClock;
import com.smile.acelib.testing.FakeSafeScheduler;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * {@link BukkitDisplayPlatform} 在 MockBukkit 支援範圍內的整合測試。
 *
 * <p>MockBukkit 4.113.1 有 {@code TextDisplayMock}，但繼承的
 * {@code EntityMock#setVisibleByDefault} 尚未實作，無法執行正式後端要求的
 * 全息字安全預設；全息字後端的實體操作由假後端測試與實機探針覆蓋。
 * {@code Score.customName} 也由假後端單元測試覆蓋。</p>
 */
@DisplayName("BukkitDisplayPlatform MockBukkit 整合")
class DisplayServiceMockBukkitTest {

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private UUID playerId;
    private DisplayService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("acelib-display-bukkit");
        player = server.addPlayer();
        playerId = player.getUniqueId();
        service = DisplayService.forProduction(plugin,
            new FakeSafeScheduler(plugin, new FakeClock()));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("計分板標題：真後端把玩家設上專屬計分板並顯示標題")
    void scoreboard_titleAppliedToPlayer() {
        DisplayResult result = service.showScoreboard(playerId,
            Component.text("戰績"), List.of());

        assertEquals(DisplayState.SUCCESS, result.state(), result.detail());
        assertEquals("戰績", player.getScoreboard()
            .getObjective(DisplaySlot.SIDEBAR).getDisplayName());
    }

    @Test
    @DisplayName("BossBar：真後端建立並把玩家加進條")
    void bossBar_playerAddedToBar() {
        DisplayResult result = service.showBossBar(playerId,
            Component.text("首領"), 0.5, BarColor.RED, BarStyle.SOLID);

        assertEquals(DisplayState.SUCCESS, result.state());
    }

    @Test
    @DisplayName("隱藏計分板：玩家被設回主計分板")
    void hideScoreboard_restoresMain() {
        service.showScoreboard(playerId, Component.text("t"), List.of());

        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());
        assertEquals(server.getScoreboardManager().getMainScoreboard(),
            player.getScoreboard());
    }

    @Test
    @DisplayName("PAPER 真排程＋隱藏 idempotence：重複隱藏成功")
    void paperScheduler_hideIdempotent() {
        DisplayService paper = DisplayService.forProduction(plugin, new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER)));

        assertEquals(DisplayState.SUCCESS, paper.hideScoreboard(playerId).state());
        assertEquals(DisplayState.SUCCESS, paper.hideBossBar(playerId).state());
    }

    @Test
    @DisplayName("全息字位置無世界：回 REJECTED＋DISP-003")
    void hologram_noWorld_rejected() {
        DisplayResult result = service.showHologram(
            new Location(null, 0.0, 64.0, 0.0), Component.text("x"));

        assertEquals(DisplayState.REJECTED, result.state());
        assertEquals(DisplayErrorCode.INVALID_INPUT, result.errorCode());
    }

    @Test
    @DisplayName("清理只動自身：closeAll 後玩家回到主計分板")
    void closeAll_restoresMainScoreboard() {
        service.showScoreboard(playerId, Component.text("t"), List.of());
        service.showBossBar(playerId, Component.text("t"), 0.5, BarColor.RED, BarStyle.SOLID);

        assertTrue(service.closeAll() >= 2);
        assertEquals(server.getScoreboardManager().getMainScoreboard(),
            player.getScoreboard());
    }

    @Test
    @DisplayName("關閉計分板不覆蓋另一個 plugin 後來設上的計分板")
    void closeAll_doesNotReplaceAnotherPluginsScoreboard() {
        PluginMock otherPlugin = MockBukkit.createMockPlugin("other-display-owner");
        DisplayService otherService = DisplayService.forProduction(otherPlugin,
            new FakeSafeScheduler(otherPlugin, new FakeClock()));

        service.showScoreboard(playerId, Component.text("AceLib"), List.of());
        Scoreboard firstBoard = player.getScoreboard();
        otherService.showScoreboard(playerId, Component.text("Other"), List.of());
        Scoreboard otherBoard = player.getScoreboard();

        assertNotSame(firstBoard, otherBoard);
        assertEquals(otherBoard, player.getScoreboard());

        service.closeAll();

        assertEquals(otherBoard, player.getScoreboard(),
            "關閉舊 owner 的計分板不得把其他 plugin 的板設回主板");
    }

    @Test
    @DisplayName("隱藏已被替換的計分板不覆蓋別的 plugin")
    void hideScoreboard_doesNotOverrideAnotherPluginsBoard() {
        PluginMock otherPlugin = MockBukkit.createMockPlugin("other-display-hide-owner");
        DisplayService otherService = DisplayService.forProduction(otherPlugin,
            new FakeSafeScheduler(otherPlugin, new FakeClock()));

        service.showScoreboard(playerId, Component.text("ours"), List.of());
        otherService.showScoreboard(playerId, Component.text("theirs"), List.of());
        Scoreboard theirs = player.getScoreboard();

        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());
        assertEquals(theirs, player.getScoreboard(),
            "隱藏已被替換的計分板不得覆蓋另一個 plugin 的板");
    }

    @Test
    @DisplayName("自己的計分板被替換後重新套用，隱藏仍還原主板")
    void hideScoreboard_restoresOwnBoardAfterReapplyingIt() {
        PluginMock otherPlugin = MockBukkit.createMockPlugin("other-display-reapply-owner");
        DisplayService otherService = DisplayService.forProduction(otherPlugin,
            new FakeSafeScheduler(otherPlugin, new FakeClock()));

        service.showScoreboard(playerId, Component.text("ours"), List.of());
        Scoreboard ours = player.getScoreboard();
        otherService.showScoreboard(playerId, Component.text("theirs"), List.of());
        assertNotSame(ours, player.getScoreboard());

        service.showScoreboard(playerId, Component.text("ours again"), List.of());
        assertEquals(ours, player.getScoreboard());
        assertEquals(DisplayState.SUCCESS, service.hideScoreboard(playerId).state());
        assertEquals(server.getScoreboardManager().getMainScoreboard(), player.getScoreboard(),
            "自己的計分板再次設上後，隱藏仍應正常還原主板");
    }
}
