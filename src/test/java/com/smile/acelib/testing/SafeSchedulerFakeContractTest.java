package com.smile.acelib.testing;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.testing.contracts.SafeSchedulerContract;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

/**
 * 排程契約假側：{@code FakeSafeScheduler} 跑同一套契約。
 */
@DisplayName("排程契約 — 假實作")
class SafeSchedulerFakeContractTest extends SafeSchedulerContract {

    @Override
    protected Harness newHarness() {
        return new FakeHarness();
    }

    private static final class FakeHarness implements Harness {
        private final FakeClock clock = new FakeClock();
        private final FakeSafeScheduler scheduler =
            new FakeSafeScheduler(Mockito.mock(JavaPlugin.class), clock);

        @Override
        public SafeScheduler scheduler() {
            return scheduler;
        }

        @Override
        public void runPending() {
            scheduler.advanceTicks(30L);
        }

        @Override
        public Player onlinePlayer() {
            Player player = Mockito.mock(Player.class);
            Mockito.when(player.isOnline()).thenReturn(true);
            Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            return player;
        }

        @Override
        public Player offlinePlayer() {
            Player player = Mockito.mock(Player.class);
            Mockito.when(player.isOnline()).thenReturn(false);
            Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            return player;
        }

        @Override
        public Entity liveEntity() {
            Entity entity = Mockito.mock(Entity.class);
            Mockito.when(entity.isDead()).thenReturn(false);
            Mockito.when(entity.isValid()).thenReturn(true);
            Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            return entity;
        }

        @Override
        public Entity retiredEntity() {
            Entity entity = Mockito.mock(Entity.class);
            Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            scheduler.retire(entity);
            return entity;
        }

        @Override
        public Location loadedLocation() {
            Location location = new Location(null, 8.0, 64.0, 8.0);
            scheduler.setChunkLoaded(location, true);
            return location;
        }

        @Override
        public Location unloadedLocation() {
            return new Location(null, 80.0, 64.0, 80.0);
        }

        @Override
        public void disable() {
            scheduler.disable();
        }
    }
}
