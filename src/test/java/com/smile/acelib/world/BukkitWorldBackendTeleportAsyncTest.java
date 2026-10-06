package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

import io.papermc.paper.entity.TeleportFlag;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link BukkitWorldBackend#teleportAsync} 平台呼叫契約。
 *
 * <p>鎖定實機暴露的根因：舊實作以反射尋找不存在的
 * {@code teleportAsync(Location, boolean)} overload，永遠掉進同步
 * {@code teleport} fallback；Folia region 執行緒上同步傳送直接拋
 * {@code UnsupportedOperationException: Must use teleportAsync while in
 * region threading}，延後傳送遂以 {@code ACELIB-WORLD-015} 失敗。</p>
 *
 * <p>假實體模擬 Folia 行為：同步 {@code teleport} 一律拋錯，只有真正的
 * {@code teleportAsync} 可用；後端必須走非同步路徑，不得再碰同步方法。</p>
 */
@DisplayName("BukkitWorldBackend 非同步傳送平台呼叫")
class BukkitWorldBackendTeleportAsyncTest {

    /** 模擬 Folia region 執行緒上的實體：同步傳送被拒，只有非同步可用。 */
    private static Entity foliaLikeEntity() {
        Entity subject = Mockito.mock(Entity.class);
        UUID id = UUID.randomUUID();
        Mockito.when(subject.getUniqueId()).thenReturn(id);
        Mockito.when(subject.teleport(any(Location.class)))
            .thenThrow(new UnsupportedOperationException(
                "Must use teleportAsync while in region threading"));
        Mockito.when(subject.teleport(any(Location.class), any(TeleportCause.class)))
            .thenThrow(new UnsupportedOperationException(
                "Must use teleportAsync while in region threading"));
        Mockito.when(subject.teleportAsync(
                any(Location.class), any(TeleportCause.class), any(TeleportFlag[].class)))
            .thenAnswer(invocation ->
                CompletableFuture.completedFuture(Boolean.TRUE));
        return subject;
    }

    private static BukkitWorldBackend backend() {
        org.bukkit.Server server = Mockito.mock(org.bukkit.Server.class);
        Mockito.when(server.getName()).thenReturn("fake");
        return new BukkitWorldBackend(server,
            com.smile.acelib.platform.Platform.FOLIA);
    }

    private static Location target() {
        World world = Mockito.mock(World.class);
        return new Location(world, 30, 66, 30);
    }

    @Test
    @DisplayName("同步傳送被拒時仍走非同步路徑並成功，不碰同步方法")
    void asyncPath_usedEvenWhenSyncRejected() throws Exception {
        Entity subject = foliaLikeEntity();

        CompletionStage<Boolean> stage =
            backend().teleportAsync(subject, target(), false);

        assertEquals(Boolean.TRUE, stage.toCompletableFuture().get(5L, TimeUnit.SECONDS));
        Mockito.verify(subject, Mockito.never()).teleport(any(Location.class));
        Mockito.verify(subject, Mockito.never())
            .teleport(any(Location.class), any(TeleportCause.class));
        Mockito.verify(subject, Mockito.times(1)).teleportAsync(
            any(Location.class), any(TeleportCause.class), any(TeleportFlag[].class));
    }

    @Test
    @DisplayName("keepPassengers=true 映射為 RETAIN_PASSENGERS 旗標")
    void keepPassengers_mapsToRetainFlag() throws Exception {
        Entity subject = foliaLikeEntity();
        ArgumentCaptor<TeleportFlag[]> flags = ArgumentCaptor.forClass(TeleportFlag[].class);

        Boolean ok = backend().teleportAsync(subject, target(), true)
            .toCompletableFuture().get(5L, TimeUnit.SECONDS);

        assertEquals(Boolean.TRUE, ok);
        Mockito.verify(subject).teleportAsync(
            any(Location.class), any(TeleportCause.class), flags.capture());
        assertTrue(java.util.Arrays.asList(flags.getValue())
            .contains(TeleportFlag.EntityState.RETAIN_PASSENGERS),
            "keepPassengers=true 必須攜帶 RETAIN_PASSENGERS");
    }

    @Test
    @DisplayName("keepPassengers=false 不攜帶任何旗標")
    void noPassengers_noFlags() throws Exception {
        Entity subject = foliaLikeEntity();
        ArgumentCaptor<TeleportFlag[]> flags = ArgumentCaptor.forClass(TeleportFlag[].class);

        backend().teleportAsync(subject, target(), false)
            .toCompletableFuture().get(5L, TimeUnit.SECONDS);

        Mockito.verify(subject).teleportAsync(
            any(Location.class), any(TeleportCause.class), flags.capture());
        assertEquals(0, flags.getValue().length, "不保留乘客時不得攜帶旗標");
    }

    @Test
    @DisplayName("平台回 false 原樣透出（服務層仍映為 WORLD-014）")
    void platformFalse_passthrough() throws Exception {
        Entity subject = Mockito.mock(Entity.class);
        Mockito.when(subject.teleportAsync(
                any(Location.class), any(TeleportCause.class), any(TeleportFlag[].class)))
            .thenReturn(CompletableFuture.completedFuture(Boolean.FALSE));

        assertEquals(Boolean.FALSE, backend().teleportAsync(subject, target(), false)
            .toCompletableFuture().get(5L, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("平台拋錯以異常 future 透出（服務層仍映為 WORLD-015）")
    void platformThrows_propagates() {
        Entity subject = Mockito.mock(Entity.class);
        RuntimeException boom = new RuntimeException("region gone");
        Mockito.when(subject.teleportAsync(
                any(Location.class), any(TeleportCause.class), any(TeleportFlag[].class)))
            .thenReturn(CompletableFuture.failedFuture(boom));

        try {
            backend().teleportAsync(subject, target(), false)
                .toCompletableFuture().get(5L, TimeUnit.SECONDS);
            fail("平台拋錯必須以異常 future 透出");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() == boom, "必須保留原始例外");
        } catch (InterruptedException | java.util.concurrent.TimeoutException failure) {
            fail("不應被中斷或逾時：" + failure);
        }
    }
}
