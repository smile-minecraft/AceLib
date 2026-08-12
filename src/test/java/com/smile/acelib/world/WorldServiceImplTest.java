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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.anyInt;
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
}
