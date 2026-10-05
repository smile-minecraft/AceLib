package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.smile.acelib.platform.Platform;

/**
 * BukkitWorldBackend 的 owner-safe 候選來源與 effect 失敗語意。
 *
 * <p>內建提供兩層：package-private result 橋接承載明確錯誤碼（服務層經由此保留
 * 原始錯誤），公開 legacy List 把失敗坍縮成空清單以維持 HEAD 二進位相容。
 * 兩層皆不得呼叫 {@code getEntities} / {@code getPlayers} 做跨 region 掃描；
 * Folia 上皆零掃描。</p>
 */
@DisplayName("BukkitWorldBackend owner-safe nearby / effect")
class BukkitWorldBackendOwnerSafeTest {

    private static World mockWorld() {
        World w = Mockito.mock(World.class);
        when(w.getUID()).thenReturn(UUID.randomUUID());
        when(w.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return w;
    }

    private static Entity entity(World w, EntityType type, double x, double z) {
        Entity e = Mockito.mock(Entity.class);
        when(e.getType()).thenReturn(type);
        when(e.getLocation()).thenReturn(new Location(w, x, 64, z));
        when(e.getUniqueId()).thenReturn(UUID.randomUUID());
        when(e.getWorld()).thenReturn(w);
        return e;
    }

    private Server mockServer() {
        Server s = Mockito.mock(Server.class);
        when(s.getName()).thenReturn("fake");
        return s;
    }

    @Test
    @DisplayName("Paper: 橋接只掃 bounding-box 候選並以球形半徑 + 類型篩選")
    void queryNearby_paper_usesBoundedCandidates() {
        World w = mockWorld();
        Entity nearZombie = entity(w, EntityType.ZOMBIE, 2, 0);
        Entity farZombie = entity(w, EntityType.ZOMBIE, 20, 0); // 在 bbox 外、一定不應命中
        Entity nearSkeleton = entity(w, EntityType.SKELETON, 1, 0);
        when(w.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(List.of(nearZombie, farZombie, nearSkeleton));

        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);
        Location center = new Location(w, 0, 64, 0);

        WorldBackendResult<List<Entity>> r = backend.queryNearby(center, 8.0, EntityType.ZOMBIE);

        assertTrue(r.isOk(), "Paper 應能回傳結果");
        assertEquals(1, r.value().size(), "只保留球形內且類型相符者");
        assertEquals(EntityType.ZOMBIE, r.value().get(0).getType());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
    }

    @Test
    @DisplayName("Paper: legacy List 同樣只掃 bounded 候選（與橋接一致）")
    void findNearby_paper_listUsesBoundedCandidates() {
        World w = mockWorld();
        Entity nearZombie = entity(w, EntityType.ZOMBIE, 2, 0);
        Entity farZombie = entity(w, EntityType.ZOMBIE, 20, 0);
        Entity nearSkeleton = entity(w, EntityType.SKELETON, 1, 0);
        when(w.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(List.of(nearZombie, farZombie, nearSkeleton));

        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);

        List<Entity> hits = backend.findNearby(new Location(w, 0, 64, 0), 8.0, EntityType.ZOMBIE);

        assertEquals(1, hits.size());
        assertEquals(EntityType.ZOMBIE, hits.get(0).getType());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
    }

    @Test
    @DisplayName("Paper: 橋接只從 bounded candidates 篩 Player + 球形")
    void queryNearbyPlayers_paper_usesBoundedCandidates() {
        World w = mockWorld();
        Player nearPlayer = Mockito.mock(Player.class);
        when(nearPlayer.getLocation()).thenReturn(new Location(w, 3, 64, 0));
        Player farPlayer = Mockito.mock(Player.class);
        when(farPlayer.getLocation()).thenReturn(new Location(w, 100, 64, 0));
        Entity bystander = entity(w, EntityType.ZOMBIE, 1, 0);
        when(w.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(List.of(nearPlayer, farPlayer, bystander));

        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);
        WorldBackendResult<List<Player>> r =
            backend.queryNearbyPlayers(new Location(w, 0, 64, 0), 8.0);

        assertTrue(r.isOk());
        assertEquals(1, r.value().size());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
    }

    @Test
    @DisplayName("Paper: legacy List 玩家查詢同樣 bounded（與橋接一致）")
    void findNearbyPlayers_paper_listUsesBoundedCandidates() {
        World w = mockWorld();
        Player nearPlayer = Mockito.mock(Player.class);
        when(nearPlayer.getLocation()).thenReturn(new Location(w, 3, 64, 0));
        Player farPlayer = Mockito.mock(Player.class);
        when(farPlayer.getLocation()).thenReturn(new Location(w, 100, 64, 0));
        Entity bystander = entity(w, EntityType.ZOMBIE, 1, 0);
        when(w.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(List.of(nearPlayer, farPlayer, bystander));

        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);

        List<Player> hits = backend.findNearbyPlayers(new Location(w, 0, 64, 0), 8.0);

        assertEquals(1, hits.size());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
    }

    @Test
    @DisplayName("Folia: 橋接無 owner 保證，拒絕且不做任何掃描")
    void queryNearby_folia_rejected() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.FOLIA);

        WorldBackendResult<List<Entity>> r =
            backend.queryNearby(new Location(w, 0, 64, 0), 8.0, EntityType.ZOMBIE);

        assertFalse(r.isOk(), "Folia 無法證明跨 region 安全時必須拒絕");
        assertEquals(WorldErrorCode.CONTEXT_UNSAFE, r.errorCode());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
        verify(w, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Folia: legacy List 回空且不掃描（fail-closed，無法表達拒絕）")
    void findNearby_folia_listIsEmptyWithoutScan() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.FOLIA);

        List<Entity> hits = backend.findNearby(new Location(w, 0, 64, 0), 8.0, EntityType.ZOMBIE);
        List<Player> players = backend.findNearbyPlayers(new Location(w, 0, 64, 0), 8.0);

        assertTrue(hits.isEmpty(), "Folia 直接 List 必須 fail-closed 回空，不得掃描");
        assertTrue(players.isEmpty());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
        verify(w, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Folia: 玩家橋接同樣拒絕且不掃描")
    void queryNearbyPlayers_folia_rejected() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.FOLIA);

        WorldBackendResult<List<Player>> r =
            backend.queryNearbyPlayers(new Location(w, 0, 64, 0), 8.0);

        assertFalse(r.isOk());
        assertEquals(WorldErrorCode.CONTEXT_UNSAFE, r.errorCode());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
        verify(w, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("effect 無實作必須回明確錯誤，不能宣告 played（ground effectKey 字串皆同）")
    void playEffect_neverReportsPlayed() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);
        Location loc = new Location(w, 0, 64, 0);

        for (String key : new String[] {"EXPLOSION", "VILLAGER_HAPPY", "随便", "x", "SMOKE_LARGE"}) {
            WorldBackendResult<Void> r = backend.playEffect(loc, key);
            assertFalse(r.isOk(), "未實作不得回 ok: " + key);
            assertEquals(WorldErrorCode.PLATFORM_UNSUPPORTED, r.errorCode());
        }
    }

    @Test
    @DisplayName("world 不存在時橋接回 WORLD_NOT_FOUND，legacy List 回空")
    void queryNearby_worldNull_failedAndListEmpty() {
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);
        WorldBackendResult<List<Entity>> r =
            backend.queryNearby(new Location(null, 0, 64, 0), 8.0, EntityType.ZOMBIE);
        assertFalse(r.isOk());
        assertEquals(WorldErrorCode.WORLD_NOT_FOUND, r.errorCode());

        List<Entity> hits = backend.findNearby(new Location(null, 0, 64, 0), 8.0, EntityType.ZOMBIE);
        assertTrue(hits.isEmpty(), "world 不存在時 legacy List 維持空清單（HEAD 語意）");
    }

    @Test
    @DisplayName("半徑 0/負數/NaN/無限：橋接 INVALID_INPUT，List 回空且不掃描")
    void queryNearby_invalidRadius_rejectedBeforeScan() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.PAPER);

        for (double radius : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            WorldBackendResult<List<Entity>> r =
                backend.queryNearby(new Location(w, 0, 64, 0), radius, EntityType.ZOMBIE);
            assertFalse(r.isOk(), "半徑不合法不得回 ok: " + radius);
            assertEquals(WorldErrorCode.INVALID_INPUT, r.errorCode());

            WorldBackendResult<List<Player>> p =
                backend.queryNearbyPlayers(new Location(w, 0, 64, 0), radius);
            assertFalse(p.isOk(), "半徑不合法不得回 ok: " + radius);
            assertEquals(WorldErrorCode.INVALID_INPUT, p.errorCode());

            assertTrue(backend.findNearby(new Location(w, 0, 64, 0), radius, EntityType.ZOMBIE).isEmpty(),
                "半徑不合法時 legacy List 回空且不掃描: " + radius);
            assertTrue(backend.findNearbyPlayers(new Location(w, 0, 64, 0), radius).isEmpty());
        }
        verify(w, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        verify(w, never()).getEntities();
        verify(w, never()).getPlayers();
    }

    @Test
    @DisplayName("Folia 半徑不合法先判 INVALID_INPUT，不以 CONTEXT_UNSAFE 混淆輸入錯誤")
    void queryNearby_folia_invalidRadius_reportsInvalidInput() {
        World w = mockWorld();
        BukkitWorldBackend backend = new BukkitWorldBackend(mockServer(), Platform.FOLIA);

        WorldBackendResult<List<Entity>> r =
            backend.queryNearby(new Location(w, 0, 64, 0), 0.0, EntityType.ZOMBIE);

        assertFalse(r.isOk());
        assertEquals(WorldErrorCode.INVALID_INPUT, r.errorCode());
    }
}
