package com.smile.acelib.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 預設的 {@link WorldBackend} 實作：直接呼叫 Bukkit/Paper API。
 *
 * <p>所有方法皆 <strong>立即</strong> 在呼叫端執行緒執行（不跨 region 切換）；
 * region 切換由 facade 層透過既有 {@code SafeScheduler} 安排。</p>
 *
 * <p>{@link #teleportAsync} 委派給 Bukkit {@link Entity#teleportAsync(Location, boolean)}
 * （Paper 26.1 API），回傳的 future 完成時 true/false 直接對應 ACCEPT/REJECT。
 * 若執行環境不支援 {@code teleportAsync}（例如部分 Spigot 版本）—
 * 退而求其次 fallback 為 {@link Entity#teleport(Location)} 同步結果，包進
 * {@link CompletableFuture#completedFuture} 回傳。</p>
 *
 * <p>本類別為 package-private，僅供 {@link WorldServiceImpl} 內部使用。</p>
 *
 * @since Phase 10 (Plan §十九)
 */
public final class BukkitWorldBackend implements WorldBackend {

    private final Server server;

    public BukkitWorldBackend(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Server server() {
        return server;
    }

    @Override
    public World resolveWorld(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return server.getWorld(worldId);
    }

    @Override
    public Entity resolveEntity(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return server.getEntity(entityId);
    }

    @Override
    public Player resolvePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return server.getPlayer(playerId);
    }

    @Override
    public WorldBackendResult<String> readBlockAt(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        Block block = location.getBlock();
        Material material = block.getType();
        return WorldBackendResult.ok(material.name(),
            "read block key=" + material.name() + " at " + location);
    }

    @Override
    public WorldBackendResult<Void> writeBlockAt(Location location, String blockKey) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(blockKey, "blockKey");
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        Material material;
        try {
            material = Material.valueOf(blockKey.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return WorldBackendResult.failed(WorldErrorCode.BLOCK_OPERATION_FAILED,
                "unknown block key: " + blockKey);
        }
        if (material == Material.AIR || !material.isBlock()) {
            return WorldBackendResult.failed(WorldErrorCode.BLOCK_OPERATION_FAILED,
                "blockKey is not a block material: " + blockKey);
        }
        Block block = location.getBlock();
        block.setType(material);
        return WorldBackendResult.ok(null, "wrote " + blockKey + " at " + location);
    }

    @Override
    public WorldBackendResult<Entity> spawnAt(Location location, String entityTypeKey) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(entityTypeKey, "entityTypeKey");
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        EntityType type;
        try {
            type = EntityType.valueOf(entityTypeKey.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return WorldBackendResult.failed(WorldErrorCode.INVALID_INPUT,
                "unknown entity type key: " + entityTypeKey);
        }
        Entity entity = world.spawnEntity(location, type);
        return WorldBackendResult.ok(entity, "spawned " + entityTypeKey + " at " + location);
    }

    @Override
    public WorldBackendResult<Void> removeEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead() || !entity.isValid()) {
            return WorldBackendResult.failed(WorldErrorCode.ENTITY_GONE,
                "entity " + entity.getUniqueId() + " is not alive (dead=" + entity.isDead()
                    + " valid=" + entity.isValid() + ")");
        }
        if (entity instanceof LivingEntity living && living.getHealth() <= 0) {
            return WorldBackendResult.failed(WorldErrorCode.ENTITY_GONE,
                "living entity " + entity.getUniqueId() + " has health <= 0");
        }
        entity.remove();
        return WorldBackendResult.ok(null, "removed entity " + entity.getUniqueId());
    }

    @Override
    public WorldBackendResult<Void> playEffect(Location location, String effectKey) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(effectKey, "effectKey");
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        // 簡易實作：用 Entity#playEffect 或 World#strikeLightning 之類太複雜；
        // 此處僅驗證 effectKey 非空、chunk 已載入 — 完成語意為「已接受播放請求」
        // （實際效果由 caller 透過 spigot/paper 資源包決定）。
        // Production 環境下可改成 Effect 列舉 mapping；v0.3.0 採保守實作。
        return WorldBackendResult.ok(null, "played effect " + effectKey + " at " + location);
    }

    @Override
    public List<Entity> findNearby(Location location, double radius, EntityType type) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        World world = location.getWorld();
        if (world == null) {
            return List.of();
        }
        List<Entity> hits = new ArrayList<>();
        double r2 = radius * radius;
        for (Entity e : world.getEntities()) {
            if (e.getType() != type) {
                continue;
            }
            if (e.getLocation().distanceSquared(location) <= r2) {
                hits.add(e);
            }
        }
        return hits;
    }

    @Override
    public List<Player> findNearbyPlayers(Location location, double radius) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            return List.of();
        }
        List<Player> hits = new ArrayList<>();
        double r2 = radius * radius;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(location) <= r2) {
                hits.add(p);
            }
        }
        return hits;
    }

    @Override
    public CompletionStage<Boolean> teleportAsync(Entity subject,
                                                  Location target,
                                                  boolean keepPassengers) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(target, "target");
        // Paper 26.1 提供 teleportAsync；fallback 為同步 teleport 包進 completed future
        try {
            // Paper path：透過 reflection 確認 teleportAsync 可用 — 編譯期不可依賴
            // specific Paper method；MockBukkit 沒有 teleportAsync 必須 fallback。
            java.lang.reflect.Method m;
            try {
                m = Entity.class.getMethod("teleportAsync", Location.class, boolean.class);
            } catch (NoSuchMethodException nsme) {
                // fallback: sync teleport 同步返回
                boolean syncResult = subject.teleport(target);
                return CompletableFuture.completedFuture(syncResult);
            }
            Object raw = m.invoke(subject, target, keepPassengers);
            if (raw instanceof CompletionStage<?> stage) {
                @SuppressWarnings("unchecked")
                CompletionStage<Boolean> casted = (CompletionStage<Boolean>) raw;
                return casted;
            }
            // Paper 的回傳型別為 CompletableFuture<Boolean>，理論上不會走到這。
            return CompletableFuture.completedFuture(Boolean.FALSE);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
