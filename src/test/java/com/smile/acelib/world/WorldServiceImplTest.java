package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.smile.acelib.platform.Platform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldServiceImpl lifecycle & operation tests with mocked WorldBackend.
 *
 * <p>對應 Evidence Pack §5 Red 2-7：以可控 in-memory backend 注入
 * {@link WorldServiceImpl}，驗證 shutdown 後拒絕新請求、block / entity /
 * teleport / cross-region partial 與平台矩陣契約。</p>
 */
@DisplayName("WorldServiceImpl 行為契約")
class WorldServiceImplTest {

    /** 用於所有測試的可注入 backend。 */
    static class FakeBackend implements WorldBackend {
        org.bukkit.Server server;
        org.bukkit.World world;
        /** 透過 Mockito 提供的 mock world；on demand 建立後給 resolveWorld 使用。 */
        org.bukkit.World mockWorld = null;
        /** 每個 Location 對應的 chunk 載入狀態。 */
        java.util.Map<String, Boolean> chunkLoadedMap = new java.util.HashMap<>();
        java.util.Map<UUID, org.bukkit.entity.Entity> entities = new java.util.HashMap<>();
        java.util.Map<UUID, org.bukkit.entity.Player> players = new java.util.HashMap<>();
        java.util.List<WriteRecord> writes = new ArrayList<>();
        java.util.List<TeleportRecord> teleports = new ArrayList<>();
        boolean chunkLoaded = true;
        Throwable teleportFailure = null;

        org.bukkit.World mockOrInitWorld(UUID wid) {
            if (mockWorld != null && mockWorld.getUID().equals(wid)) return mockWorld;
            org.bukkit.World w = org.mockito.Mockito.mock(org.bukkit.World.class);
            org.mockito.Mockito.when(w.getUID()).thenReturn(wid);
            org.mockito.Mockito.when(w.isChunkLoaded(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    int x = inv.getArgument(0);
                    int z = inv.getArgument(1);
                    String key = x + ":" + z;
                    return chunkLoadedMap.getOrDefault(key, chunkLoaded);
                });
            mockWorld = w;
            return w;
        }

        @Override public org.bukkit.Server server() { return server; }
        @Override public org.bukkit.World resolveWorld(UUID worldId) {
            return mockOrInitWorld(worldId);
        }
        @Override public org.bukkit.entity.Entity resolveEntity(UUID entityId) {
            return entities.get(entityId);
        }
        @Override public org.bukkit.entity.Player resolvePlayer(UUID playerId) {
            return players.get(playerId);
        }
        @Override public WorldBackendResult<String> readBlockAt(org.bukkit.Location location) {
            if (!chunkLoaded) return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED, "fake chunk unloaded");
            return WorldBackendResult.ok("STONE", "fake read STONE");
        }

        @Override public WorldBackendResult<Void> writeBlockAt(org.bukkit.Location location, String blockKey) {
            writes.add(new WriteRecord(location, blockKey));
            if (blockKey.equals("INVALID_BLOCK")) {
                return WorldBackendResult.failed(WorldErrorCode.BLOCK_OPERATION_FAILED, "unknown");
            }
            return WorldBackendResult.ok(null, "fake wrote " + blockKey);
        }
        // 拿掉 unused suppression 等

        @Override public WorldBackendResult<org.bukkit.entity.Entity> spawnAt(org.bukkit.Location location, String entityTypeKey) {
            if (!chunkLoaded) return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED, "fake chunk unloaded");
            return WorldBackendResult.failed(WorldErrorCode.INVALID_INPUT, "fake no entity impl");
        }
        @Override public WorldBackendResult<Void> removeEntity(org.bukkit.entity.Entity entity) {
            return WorldBackendResult.ok(null, "fake removed");
        }
        @Override public WorldBackendResult<Void> playEffect(org.bukkit.Location location, String effectKey) {
            if (!chunkLoaded) return WorldBackendResult.failed(WorldErrorCode.EFFECT_REJECTED, "fake chunk unloaded");
            return WorldBackendResult.ok(null, "fake effect");
        }
        @Override public List<org.bukkit.entity.Entity> findNearby(org.bukkit.Location location, double radius, org.bukkit.entity.EntityType type) {
            return new ArrayList<>();
        }
        @Override public List<org.bukkit.entity.Player> findNearbyPlayers(org.bukkit.Location location, double radius) {
            return new ArrayList<>();
        }
        @Override public CompletionStage<Boolean> teleportAsync(org.bukkit.entity.Entity subject, org.bukkit.Location target, boolean keepPassengers) {
            UUID subjectId = subject.getUniqueId();
            teleports.add(new TeleportRecord(subjectId, target, keepPassengers));
            if (teleportFailure != null) {
                return java.util.concurrent.CompletableFuture.failedFuture(teleportFailure);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
    }

    /** 寫入紀錄：便於測試驗證 backend 收到什麼請求。 */
    record WriteRecord(org.bukkit.Location location, String blockKey) {}
    record TeleportRecord(UUID subjectId, org.bukkit.Location target, boolean keepPassengers) {}

    // -----------------------------------------------------------------
    // Red 2: lifecycle shutdown
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("shutdown 後 read/write/spawn/remove/effect/query/teleport 全部回 SHUTDOWN")
        void shutdown_rejectsAllOperations() throws Exception {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);

            // 先確認 running 狀態下可呼叫
            assertEquals("READY", svc.getModuleStatus());

            // shutdown
            svc.shutdown();
            assertEquals("FAILED", svc.getModuleStatus());

            // shutdown 後所有方法回 SHUTDOWN
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            assertEquals(WorldState.REJECTED,
                svc.readBlock(snapshot).state());
            assertEquals(WorldErrorCode.SHUTDOWN,
                svc.readBlock(snapshot).errorCode());

            assertEquals(WorldState.REJECTED,
                svc.writeBlock(snapshot, "STONE").state());

            assertEquals(WorldState.REJECTED,
                svc.spawnEntity(snapshot, "ZOMBIE").state());

            assertEquals(WorldState.REJECTED,
                svc.removeEntity(EntityReference.of(UUID.randomUUID(), UUID.randomUUID(), "ZOMBIE"))
                    .state());

            assertEquals(WorldState.REJECTED,
                svc.playEffect(snapshot, "EXPLOSION").state());

            assertEquals(WorldState.REJECTED,
                svc.findNearbyEntities(snapshot, 16.0, "ZOMBIE").state());

            assertEquals(WorldState.REJECTED,
                svc.findNearbyPlayers(snapshot, 16.0).state());

            CompletionStage<TeleportResult> stage = svc.teleportPlayer(
                UUID.randomUUID(), snapshot, false);
            TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.SHUTDOWN, result.errorCode());

            stage = svc.teleportEntity(UUID.randomUUID(), snapshot, false);
            result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.SHUTDOWN, result.errorCode());
        }

        @Test
        @DisplayName("shutdown 為 idempotent：重複呼叫不丟例外")
        void shutdown_isIdempotent() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            svc.shutdown();
            svc.shutdown(); // 不丟例外
            assertEquals("FAILED", svc.getModuleStatus());
        }

        @Test
        @DisplayName("getInFlightCount 一開始為 0")
        void fresh_service_hasNoInFlight() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            assertEquals(0, svc.getInFlightCount());
        }
    }

    // -----------------------------------------------------------------
    // Red 3: block operations
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Block operations")
    class BlockTests {

        @Test
        @DisplayName("readBlock 在 world 有效 + chunk 已載入 → SUCCESS + STONE")
        void readBlock_success() {
            FakeBackend backend = new FakeBackend();
            backend.chunkLoaded = true;
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 1, 2, 3);
            BlockResult r = svc.readBlock(snapshot);
            assertEquals(WorldState.SUCCESS, r.state());
            assertEquals("STONE", r.blockKey());
            assertSame(snapshot, r.location());
        }

        @Test
        @DisplayName("readBlock 在 chunk 未載入 → REJECTED + CHUNK_UNLOADED")
        void readBlock_chunkUnloaded() {
            FakeBackend backend = new FakeBackend();
            backend.chunkLoaded = false;
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 1, 2, 3);
            BlockResult r = svc.readBlock(snapshot);
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.CHUNK_UNLOADED, r.errorCode());
        }

        @Test
        @DisplayName("writeBlock 將 blockKey 與 location 傳給 backend")
        void writeBlock_passesInputs() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 5, 64, 5);
            BlockResult r = svc.writeBlock(snapshot, "DIAMOND_BLOCK");
            assertEquals(WorldState.SUCCESS, r.state());
            assertEquals(1, backend.writes.size());
            // 寫入的 blockKey 為 upper-case per contract
            assertEquals("DIAMOND_BLOCK", backend.writes.get(0).blockKey());
        }

        @Test
        @DisplayName("writeBlock 對未知材質 → REJECTED + BLOCK_OPERATION_FAILED")
        void writeBlock_invalid() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0);
            BlockResult r = svc.writeBlock(snapshot, "INVALID_BLOCK");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.BLOCK_OPERATION_FAILED, r.errorCode());
        }
    }

    // -----------------------------------------------------------------
    // Red 4: entity operations
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Entity operations")
    class EntityTests {

        @Test
        @DisplayName("removeEntity 在 entity 不存在 → REJECTED + ENTITY_GONE")
        void remove_entity_gone() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            EntityReference ref = EntityReference.of(UUID.randomUUID(), UUID.randomUUID(), "ZOMBIE");
            EntityResult r = svc.removeEntity(ref);
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.ENTITY_GONE, r.errorCode());
        }

        @Test
        @DisplayName("removeEntity 在 backend.resolveEntity 回傳 entity 時 → SUCCESS")
        void remove_entity_success() {
            // 使用 Mockito 模擬一個 Bukkit Entity；FakeBackend.removeEntity 一律回 ok。
            org.bukkit.entity.Entity stub = Mockito.mock(org.bukkit.entity.Entity.class);
            FakeBackend backend = new FakeBackend() {
                @Override public org.bukkit.entity.Entity resolveEntity(UUID eid) {
                    return stub;
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            EntityReference ref = EntityReference.of(UUID.randomUUID(), UUID.randomUUID(), "ZOMBIE");
            EntityResult r = svc.removeEntity(ref);
            assertEquals(WorldState.SUCCESS, r.state());
        }

        @Test
        @DisplayName("playEffect 對 chunk 未載入 → REJECTED + EFFECT_REJECTED")
        void playEffect_chunkUnloaded() {
            FakeBackend backend = new FakeBackend();
            backend.chunkLoaded = false;
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            EntityResult r = svc.playEffect(snapshot, "EXPLOSION");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.EFFECT_REJECTED, r.errorCode());
        }

        @Test
        @DisplayName("findNearbyEntities 對未知 EntityType → REJECTED + INVALID_INPUT")
        void findNearby_invalidType() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            NearbyQueryResult r = svc.findNearbyEntities(snapshot, 16.0, "FAKE_TYPE");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.INVALID_INPUT, r.errorCode());
        }

        @Test
        @DisplayName("findNearbyEntities 接受有效 EntityType")
        void findNearby_validType() {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            NearbyQueryResult r = svc.findNearbyEntities(snapshot, 16.0, "ZOMBIE");
            assertEquals(WorldState.SUCCESS, r.state());
            assertNotNull(r.references());
            assertTrue(r.references().isEmpty(), "no zombies in fake world");
        }
    }

    // -----------------------------------------------------------------
    // Red 5: teleport
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Teleport")
    class TeleportTests {

        @Test
        @DisplayName("teleportPlayer 在 player 離線 → REJECTED + PLAYER_OFFLINE")
        void teleportPlayer_playerOffline() throws Exception {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            CompletionStage<TeleportResult> stage = svc.teleportPlayer(
                UUID.randomUUID(), target, false);
            TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.PLAYER_OFFLINE, result.errorCode());
        }

        @Test
        @DisplayName("teleportEntity 在 entity 不存在 → REJECTED + ENTITY_GONE")
        void teleportEntity_entityGone() throws Exception {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            CompletionStage<TeleportResult> stage = svc.teleportEntity(
                UUID.randomUUID(), target, false);
            TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.ENTITY_GONE, result.errorCode());
        }

        @Test
        @DisplayName("teleport 的 future 必定完成，不會留下未 resolve 的 promise")
        void teleport_future_completes() throws Exception {
            FakeBackend backend = new FakeBackend();
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            CompletionStage<TeleportResult> stage = svc.teleportPlayer(
                UUID.randomUUID(), target, false);
            // 限定時間內必定完成；caller 不可假設 future 留滯
            try {
                TeleportResult r = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertNotNull(r);
            } catch (java.util.concurrent.TimeoutException te) {
                fail("teleport future 必須完成；被 TimeoutException 表示 contract 違規");
            }
        }
    }

    // -----------------------------------------------------------------
    // Red 6: cross-region partial completion
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Cross-region state machine")
    class CrossRegionTests {

        @Test
        @DisplayName("server-side teleport 拋例外 → future 收到 TELEPORT_EXCEPTION")
        void teleport_serverThrew() throws Exception {
            org.bukkit.entity.Entity stub = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
            java.util.UUID subjectId = org.mockito.Mockito.when(stub.getUniqueId()).thenReturn(java.util.UUID.randomUUID()).getMock() == null
                ? null : null; // simulate call
            UUID subjectUid = UUID.randomUUID();
            org.mockito.Mockito.when(stub.getUniqueId()).thenReturn(subjectUid);
            FakeBackend backend = new FakeBackend() {
                @Override public org.bukkit.entity.Entity resolveEntity(UUID eid) {
                    return stub;
                }
            };
            backend.teleportFailure = new RuntimeException("server crash");
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            CompletionStage<TeleportResult> stage = svc.teleportEntity(
                subjectUid, target, false);
            TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.FAILED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_EXCEPTION, result.errorCode());
        }

        @Test
        @DisplayName("server-side teleport 回 false → future 收到 TELEPORT_REJECTED")
        void teleport_serverReturnedFalse() throws Exception {
            org.bukkit.entity.Entity stub = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
            UUID subjectUid = UUID.randomUUID();
            org.mockito.Mockito.when(stub.getUniqueId()).thenReturn(subjectUid);
            FakeBackend backend = new FakeBackend() {
                @Override public org.bukkit.entity.Entity resolveEntity(UUID eid) {
                    return stub;
                }
                @Override
                public java.util.concurrent.CompletionStage<Boolean> teleportAsync(
                        org.bukkit.entity.Entity subject, org.bukkit.Location target, boolean keepPassengers) {
                    teleports.add(new TeleportRecord(subject.getUniqueId(), target, keepPassengers));
                    return java.util.concurrent.CompletableFuture.completedFuture(Boolean.FALSE);
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            CompletionStage<TeleportResult> stage = svc.teleportEntity(
                subjectUid, target, false);
            TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(WorldState.REJECTED, result.state());
            assertEquals(WorldErrorCode.TELEPORT_REJECTED, result.errorCode());
        }
    }

    // -----------------------------------------------------------------
    // Red 7: ace lib plugin integration (smoke-only — covered by full ace lib plugin suite)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("null inputs 一律回 IllegalArgumentException 帶 INVALID_INPUT")
    void nullInputs_throw() {
        FakeBackend backend = new FakeBackend();
        WorldServiceImpl svc = new WorldServiceImpl(backend, null);
        try { svc.readBlock(null); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.writeBlock(null, "STONE"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.writeBlock(LocationSnapshot.of(UUID.randomUUID(), 0,0,0), null); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.writeBlock(LocationSnapshot.of(UUID.randomUUID(), 0,0,0), ""); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.spawnEntity(null, "ZOMBIE"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.removeEntity(null); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.playEffect(null, "EXPLOSION"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.findNearbyEntities(null, 16.0, "ZOMBIE"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.findNearbyEntities(LocationSnapshot.of(UUID.randomUUID(), 0,0,0), 0, "ZOMBIE"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.findNearbyEntities(LocationSnapshot.of(UUID.randomUUID(), 0,0,0), -1, "ZOMBIE"); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.findNearbyPlayers(null, 16.0); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
        try { svc.teleportPlayer(null, LocationSnapshot.of(UUID.randomUUID(),0,0,0), false); fail("expected"); }
        catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT));
        }
    }

    // keep compiler happy on unused imports in nested classes
    @SuppressWarnings("unused")
    private static void _unused() throws ExecutionException, InterruptedException {}

    @Nested
    @DisplayName("Effect / nearby error mapping")
    class EffectAndNearbyMappingTests {

        @Test
        @DisplayName("playEffect 無實作時必須回明確錯誤，不能 successWithoutReference 假成功；任意字串皆 fail closed")
        void playEffect_arbitraryKeys_neverSuccess() {
            FakeBackend backend = new FakeBackend() {
                @Override public WorldBackendResult<Void> playEffect(org.bukkit.Location location, String effectKey) {
                    return WorldBackendResult.failed(WorldErrorCode.PLATFORM_UNSUPPORTED,
                        "effect '" + effectKey + "' not implemented");
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            for (String key : new String[] {"EXPLOSION", "VILLAGER_HAPPY", "随便", "x"}) {
                EntityResult r = svc.playEffect(snapshot, key);
                assertTrue(r.state() != WorldState.SUCCESS, "不可回成功: " + key);
                assertEquals(WorldErrorCode.PLATFORM_UNSUPPORTED, r.errorCode());
            }
        }

        @Test
        @DisplayName("playEffect EFFECT_REJECTED 映為 REJECTED；其他 unknown code 不宣告 played")
        void playEffect_errorMapping() {
            FakeBackend backend = new FakeBackend() {
                @Override public WorldBackendResult<Void> playEffect(org.bukkit.Location location, String effectKey) {
                    return WorldBackendResult.failed(WorldErrorCode.EFFECT_REJECTED, "chunk gone");
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            EntityResult r = svc.playEffect(snapshot, "EXPLOSION");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.EFFECT_REJECTED, r.errorCode());
        }

        @Test
        @DisplayName("findNearby 內建 Folia 拒絕時轉譯為 REJECTED + 原錯誤碼，references 空")
        void findNearby_backendRejected_translated() {
            World mockWorld = Mockito.mock(World.class);
            UUID wid = UUID.randomUUID();
            when(mockWorld.getUID()).thenReturn(wid);
            when(mockWorld.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
            when(mockServer.getName()).thenReturn("fake");
            when(mockServer.getWorld(wid)).thenReturn(mockWorld);
            BukkitWorldBackend builtin = new BukkitWorldBackend(mockServer, Platform.FOLIA);
            WorldServiceImpl svc = new WorldServiceImpl(builtin, null);
            // 服務以 snapshot 的 worldId 解析：讓 resolveWorld 回傳 mockWorld
            // BukkitWorldBackend.resolveWorld 經由 server.getWorld，需先讓 server 認得 wid；
            // 此處 snapshot 的 wid 即為 mockWorld 的 UID，透過 server mock 取得。
            LocationSnapshot snapshot = LocationSnapshot.of(wid, 0, 64, 0);

            NearbyQueryResult r = svc.findNearbyEntities(snapshot, 16.0, "ZOMBIE");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.CONTEXT_UNSAFE, r.errorCode());
            assertTrue(r.references().isEmpty());

            NearbyQueryResult p = svc.findNearbyPlayers(snapshot, 16.0);
            assertEquals(WorldState.REJECTED, p.state());
            assertEquals(WorldErrorCode.CONTEXT_UNSAFE, p.errorCode());
            assertTrue(p.references().isEmpty());
            verify(mockWorld, never()).getEntities();
            verify(mockWorld, never()).getPlayers();
            verify(mockWorld, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("findNearby 內建回任意 error code 時直接保留（轉譯覆蓋任意字串）")
        void findNearby_arbitraryBackendCode_preserved() {
            BukkitWorldBackend builtin = Mockito.mock(BukkitWorldBackend.class);
            org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
            when(mockServer.getName()).thenReturn("fake");
            when(builtin.server()).thenReturn(mockServer);
            World mockWorld = Mockito.mock(World.class);
            UUID wid = UUID.randomUUID();
            when(mockWorld.getUID()).thenReturn(wid);
            when(builtin.resolveWorld(wid)).thenReturn(mockWorld);
            Location dummy = new Location(mockWorld, 0, 64, 0);
            when(builtin.queryNearby(any(Location.class), anyDouble(), any(EntityType.class)))
                .thenReturn(WorldBackendResult.failed("ACELIB-WORLD-999", "arbitrary"));
            when(builtin.queryNearbyPlayers(any(Location.class), anyDouble()))
                .thenReturn(WorldBackendResult.failed("ACELIB-WORLD-999", "arbitrary"));
            WorldServiceImpl svc = new WorldServiceImpl(builtin, null);
            LocationSnapshot snapshot = LocationSnapshot.of(wid, 0, 64, 0);

            // 未登錄的代碼保守降級為 FAILED：不可被誤標成「安全拒絕」，
            // 也不可回 SUCCESS 讓呼叫端以為真的查過。
            NearbyQueryResult r = svc.findNearbyEntities(snapshot, 16.0, "ZOMBIE");
            assertEquals(WorldState.FAILED, r.state());
            assertEquals("ACELIB-WORLD-999", r.errorCode());
            assertTrue(r.references().isEmpty());

            NearbyQueryResult p = svc.findNearbyPlayers(snapshot, 16.0);
            assertEquals(WorldState.FAILED, p.state());
            assertEquals("ACELIB-WORLD-999", p.errorCode());
        }

        @Test
        @DisplayName("playEffect 後端回未登錄代碼時為 FAILED 且不宣告已播放")
        void playEffect_unknownCode_failsClosed() {
            FakeBackend backend = new FakeBackend() {
                @Override public WorldBackendResult<Void> playEffect(org.bukkit.Location location, String effectKey) {
                    return WorldBackendResult.failed("ACELIB-WORLD-777", "boom");
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);

            EntityResult r = svc.playEffect(snapshot, "EXPLOSION");

            assertEquals(WorldState.FAILED, r.state());
            assertEquals("ACELIB-WORLD-777", r.errorCode());
        }

        @Test
        @DisplayName("playEffect 後端確實回 ok 才宣告成功（successWithoutReference 僅在真成功時）")
        void playEffect_backendOk_isSuccess() {
            FakeBackend backend = new FakeBackend(); // playEffect 回 ok
            WorldServiceImpl svc = new WorldServiceImpl(backend, null);
            LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);

            EntityResult r = svc.playEffect(snapshot, "EXPLOSION");

            assertEquals(WorldState.SUCCESS, r.state());
        }
    }

    @Nested
    @DisplayName("Legacy List 相容與內建分流")
    class LegacyCompatTests {

        private World mockWorldWith(UUID wid) {
            World w = Mockito.mock(World.class);
            when(w.getUID()).thenReturn(wid);
            when(w.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            return w;
        }

        @Test
        @DisplayName("legacy Paper 按原 List 委派並映射為 SUCCESS（不得宣稱已證 bounded）")
        void legacy_paper_delegatesList() {
            UUID wid = UUID.randomUUID();
            World w = mockWorldWith(wid);
            Entity zombie = Mockito.mock(Entity.class);
            UUID eid = UUID.randomUUID();
            when(zombie.getUniqueId()).thenReturn(eid);
            when(zombie.getWorld()).thenReturn(w);
            when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
            when(zombie.getLocation()).thenReturn(new Location(w, 1, 64, 0));
            when(w.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(zombie));

            org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
            when(mockServer.getName()).thenReturn("fake");
            when(mockServer.getWorld(wid)).thenReturn(w);
            // 用內建 Paper 走完整服務管道，驗證 bounded 映射；legacy 委派語意由下一個測試覆蓋。
            BukkitWorldBackend builtin = new BukkitWorldBackend(mockServer, Platform.PAPER);
            WorldServiceImpl svc = new WorldServiceImpl(builtin, null, Platform.PAPER);
            LocationSnapshot center = LocationSnapshot.of(wid, 0, 64, 0);

            NearbyQueryResult r = svc.findNearbyEntities(center, 16.0, "ZOMBIE");
            assertEquals(WorldState.SUCCESS, r.state());
            assertEquals(1, r.references().size());
            assertEquals(eid, r.references().get(0).entityId());
        }

        @Test
        @DisplayName("legacy Paper 外部實作 List 直接委派（服務不攔截）")
        void legacy_externalPaper_delegatesWithoutBoundedClaim() {
            UUID wid = UUID.randomUUID();
            World w = mockWorldWith(wid);
            Entity zombie = Mockito.mock(Entity.class);
            UUID eid = UUID.randomUUID();
            when(zombie.getUniqueId()).thenReturn(eid);
            when(zombie.getWorld()).thenReturn(w);
            when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
            FakeBackend backend = new FakeBackend() {
                @Override public World resolveWorld(UUID id) {
                    return w;
                }
                @Override public List<Entity> findNearby(Location loc, double radius, EntityType type) {
                    return List.of(zombie);
                }
                @Override public List<Player> findNearbyPlayers(Location loc, double radius) {
                    return List.of();
                }
            };
            WorldServiceImpl svc = new WorldServiceImpl(backend, null, Platform.PAPER);
            LocationSnapshot center = LocationSnapshot.of(wid, 0, 64, 0);

            NearbyQueryResult r = svc.findNearbyEntities(center, 16.0, "ZOMBIE");
            assertEquals(WorldState.SUCCESS, r.state());
            assertEquals(1, r.references().size());
            assertEquals(eid, r.references().get(0).entityId());
        }

        @Test
        @DisplayName("legacy Folia 不呼叫未知實作，直接 CONTEXT_UNSAFE")
        void legacy_folia_failClosedWithoutCalling() {
            WorldBackend backend = Mockito.mock(WorldBackend.class);
            org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
            when(mockServer.getName()).thenReturn("fake");
            when(backend.server()).thenReturn(mockServer);
            UUID wid = UUID.randomUUID();
            World w = mockWorldWith(wid);
            when(backend.resolveWorld(wid)).thenReturn(w);
            WorldServiceImpl svc = new WorldServiceImpl(backend, null, Platform.FOLIA);
            LocationSnapshot center = LocationSnapshot.of(wid, 0, 64, 0);

            NearbyQueryResult r = svc.findNearbyEntities(center, 16.0, "ZOMBIE");
            assertEquals(WorldState.REJECTED, r.state());
            assertEquals(WorldErrorCode.CONTEXT_UNSAFE, r.errorCode());
            assertTrue(r.references().isEmpty());

            NearbyQueryResult p = svc.findNearbyPlayers(center, 16.0);
            assertEquals(WorldState.REJECTED, p.state());
            assertEquals(WorldErrorCode.CONTEXT_UNSAFE, p.errorCode());

            verify(backend, never()).findNearby(any(Location.class), anyDouble(), any(EntityType.class));
            verify(backend, never()).findNearbyPlayers(any(Location.class), anyDouble());
        }

        @Test
        @DisplayName("shutdown 下 legacy 與內建一律 SHUTDOWN（優先於平台分流）")
        void legacy_shutdown_rejected() {
            FakeBackend legacy = new FakeBackend();
            WorldServiceImpl legacySvc = new WorldServiceImpl(legacy, null, Platform.FOLIA);
            legacySvc.shutdown();
            LocationSnapshot center = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
            assertEquals(WorldErrorCode.SHUTDOWN,
                legacySvc.findNearbyEntities(center, 16.0, "ZOMBIE").errorCode());
            assertEquals(WorldErrorCode.SHUTDOWN,
                legacySvc.findNearbyPlayers(center, 16.0).errorCode());

            org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
            when(mockServer.getName()).thenReturn("fake");
            BukkitWorldBackend builtin = new BukkitWorldBackend(mockServer, Platform.PAPER);
            WorldServiceImpl builtinSvc = new WorldServiceImpl(builtin, null, Platform.PAPER);
            builtinSvc.shutdown();
            assertEquals(WorldErrorCode.SHUTDOWN,
                builtinSvc.findNearbyEntities(center, 16.0, "ZOMBIE").errorCode());
        }
    }
}
