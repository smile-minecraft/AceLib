package com.smile.acelib.scheduler;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.testing.contracts.SafeSchedulerContract;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 排程契約真實側：MockBukkit PAPER 路徑的 {@code SafeSchedulerImpl} 跑同一套契約。
 */
@DisplayName("排程契約 — 真實作")
class SafeSchedulerContractTest extends SafeSchedulerContract {

    private RealHarness current;

    @AfterEach
    void tearDown() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override
    protected Harness newHarness() {
        current = new RealHarness();
        return current;
    }

    private static final class RealHarness implements Harness {
        private final ServerMock server = MockBukkit.mock();
        private final AceLibPlugin plugin;
        private final SafeSchedulerImpl scheduler;

        RealHarness() {
            plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
            plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
            scheduler = new SafeSchedulerImpl(
                plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        }

        void close() {
            if (!scheduler.isDisabled()) {
                scheduler.onPluginDisable();
            }
            MockBukkit.unmock();
        }

        @Override
        public SafeScheduler scheduler() {
            return scheduler;
        }

        @Override
        public void runPending() {
            server.getScheduler().performTicks(30L);
        }

        @Override
        public Player onlinePlayer() {
            return server.addPlayer();
        }

        @Override
        public Player offlinePlayer() {
            org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
            player.disconnect();
            return player;
        }

        @Override
        public Entity liveEntity() {
            return server.addPlayer();
        }

        @Override
        public Entity retiredEntity() {
            Entity retired = Mockito.mock(Entity.class);
            Mockito.when(retired.isDead()).thenReturn(true);
            return retired;
        }

        @Override
        public Location loadedLocation() {
            World world = server.getWorlds().get(0);
            world.getChunkAt(0, 0);
            return new Location(world, 8.0, 64.0, 8.0);
        }

        @Override
        public Location unloadedLocation() {
            return new Location(null, 8.0, 64.0, 8.0);
        }

        @Override
        public void disable() {
            scheduler.onPluginDisable();
        }
    }
}
