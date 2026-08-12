package com.smile.acelib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.AceLibApi;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WorldService facade lookup contract.
 *
 * <p>對應 Evidence Pack §5 Red 1：確認 {@code AceLibApi.getWorldService()}
 * 永不為 null，且未啟用（uninitialized）狀態下每次呼叫皆回 REJECTED + ACELIB-WORLD-001。
 * 後續插件應能在 onEnable 之前就查得 facade 物件，無需 null 判斷。</p>
 */
@DisplayName("WorldService facade lookup")
class WorldServiceFacadeLookupTest {

    @Test
    @DisplayName("AceLibApi.uninitialized().getWorldService() 不可為 null")
    void uninitializedApi_facadeIsNonNull() {
        AceLibApi api = AceLibApi.uninitialized();
        WorldService svc = api.getWorldService();
        assertNotNull(svc, "getWorldService 必須永遠不為 null");
    }

    @Test
    @DisplayName("未啟用時 readBlock 應回 REJECTED + ACELIB-WORLD-001")
    void uninitializedApi_readBlock_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0);
        BlockResult result = svc.readBlock(snapshot);
        assertNotNull(result);
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 writeBlock 應回 REJECTED + ACELIB-WORLD-001")
    void uninitializedApi_writeBlock_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0);
        BlockResult result = svc.writeBlock(snapshot, "STONE");
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 spawnEntity 應回 REJECTED + ACELIB-WORLD-001")
    void uninitializedApi_spawnEntity_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
        EntityResult result = svc.spawnEntity(snapshot, "ZOMBIE");
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 removeEntity 應回 REJECTED + ACELIB-WORLD-001")
    void uninitializedApi_removeEntity_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        EntityReference ref = EntityReference.of(UUID.randomUUID(), UUID.randomUUID(), "ZOMBIE");
        EntityResult result = svc.removeEntity(ref);
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 playEffect 應回 REJECTED + ACELIB-WORLD-001")
    void uninitializedApi_playEffect_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
        EntityResult result = svc.playEffect(snapshot, "EXPLOSION");
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 teleportPlayer 應回 REJECTED + ACELIB-WORLD-001 + future 完成")
    void uninitializedApi_teleportPlayer_isRejected() throws Exception {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
        CompletionStage<TeleportResult> stage = svc.teleportPlayer(
            UUID.randomUUID(), target, false);
        TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 teleportEntity 應回 REJECTED + ACELIB-WORLD-001 + future 完成")
    void uninitializedApi_teleportEntity_isRejected() throws Exception {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot target = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
        CompletionStage<TeleportResult> stage = svc.teleportEntity(
            UUID.randomUUID(), target, false);
        TeleportResult result = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
    }

    @Test
    @DisplayName("未啟用時 findNearbyEntities 應回 REJECTED + ACELIB-WORLD-001 + empty list")
    void uninitializedApi_findNearby_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        LocationSnapshot snapshot = LocationSnapshot.of(UUID.randomUUID(), 0, 64, 0);
        NearbyQueryResult result = svc.findNearbyEntities(snapshot, 16.0, "ZOMBIE");
        assertEquals(WorldState.REJECTED, result.state());
        assertEquals(WorldErrorCode.NOT_READY, result.errorCode());
        assertNotNull(result.references());
        assertTrue(result.references().isEmpty());
    }

    @Test
    @DisplayName("未啟用時 readBlock 傳入 null 應回 REJECTED + ACELIB-WORLD-007 INVALID_INPUT")
    void uninitializedApi_nullInput_isRejected() {
        WorldService svc = AceLibApi.uninitialized().getWorldService();
        try {
            svc.readBlock(null);
            fail("expected IllegalArgumentException for null input");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains(WorldErrorCode.INVALID_INPUT),
                "error code must be present in message: " + ex.getMessage());
        }
    }
}
