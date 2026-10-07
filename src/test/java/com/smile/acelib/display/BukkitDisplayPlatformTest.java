package com.smile.acelib.display;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BukkitDisplayPlatform 後端")
class BukkitDisplayPlatformTest {

    @Test
    @DisplayName("生成 TextDisplay 時維持隱藏與不持久化安全預設")
    void spawnHologram_appliesVisibilityAndPersistenceDefaults() {
        Plugin owner = mock(Plugin.class);
        World world = mock(World.class, CALLS_REAL_METHODS);
        Location location = new Location(world, 1.5, 64.0, 1.5);
        TextDisplay display = mock(TextDisplay.class);
        Component text = Component.text("探針文字");
        doAnswer(invocation -> {
            Consumer<? super TextDisplay> consumer = invocation.getArgument(2);
            consumer.accept(display);
            return display;
        }).when(world).spawn(eq(location), eq(TextDisplay.class),
            org.mockito.ArgumentMatchers.<Consumer<? super TextDisplay>>any(),
            eq(SpawnReason.CUSTOM));

        Entity created = new BukkitDisplayPlatform(owner).spawnHologram(location, text);

        assertSame(display, created);
        verify(display).setPersistent(false);
        verify(display).setVisibleByDefault(false);
        verify(display).text(text);
    }

    @Test
    @DisplayName("實體清理離開擁有者 region 時走原生 EntityScheduler 並帶 retired callback")
    void entityCleanup_dispatchesThroughNativeEntityScheduler() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Entity entity = mock(Entity.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        Runnable retired = mock(Runnable.class);
        AtomicBoolean ran = new AtomicBoolean();
        when(entity.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(scheduler).execute(eq(owner), any(Runnable.class), eq(retired), eq(1L));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(false);
            boolean accepted = new BukkitDisplayPlatform(owner).runEntityCleanupInOwnerContext(
                owner, entity, () -> ran.set(true), retired);

            assertTrue(accepted);
            assertTrue(ran.get());
            verify(scheduler).execute(eq(owner), any(Runnable.class), eq(retired), eq(1L));
        }
    }

    @Test
    @DisplayName("玩家清理離開擁有者 region 時走該玩家原生 scheduler")
    void playerCleanup_dispatchesThroughNativePlayerScheduler() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        Runnable retired = mock(Runnable.class);
        AtomicBoolean ran = new AtomicBoolean();
        when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(scheduler).execute(eq(owner), any(Runnable.class), eq(retired), eq(1L));

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
            boolean accepted = new BukkitDisplayPlatform(owner).runPlayerCleanupInOwnerContext(
                owner, player, () -> ran.set(true), retired);

            assertTrue(accepted);
            assertTrue(ran.get());
            verify(scheduler).execute(eq(owner), any(Runnable.class), eq(retired), eq(1L));
        }
    }
}
