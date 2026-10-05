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

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;

/**
 * 預設的 {@link WorldBackend} 實作：直接呼叫 Bukkit/Paper API（Internal）。
 *
 * <p>所有方法皆 <strong>立即</strong> 在呼叫端執行緒執行（不跨 region 切換）；
 * region 切換由 facade 層透過既有 {@code SafeScheduler} 安排。</p>
 *
 * <p>{@link #teleportAsync} 委派給 Bukkit {@code Entity#teleportAsync(Location, boolean)}
 * （Paper 26.1 API），回傳的 future 完成時 true/false 直接對應 ACCEPT/REJECT。
 * 若執行環境不支援 {@code teleportAsync}（例如部分 Spigot 版本）—
 * 退而求其次 fallback 為 {@link Entity#teleport(Location)} 同步結果，包進
 * {@link CompletableFuture#completedFuture} 回傳。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；僅供
 * {@link WorldServiceImpl} 內部使用。</p>
 *
 * @since 1.0.0
 */
public final class BukkitWorldBackend implements WorldBackend {

    private final Server server;
    private final Platform platform;

    /**
     * 建立 backend 實作（平台以 classpath 偵測）。
     *
     * @param server 供解析用的 Bukkit {@link Server}；不可為 null
     */
    public BukkitWorldBackend(Server server) {
        this(server, new PlatformDetector(BukkitWorldBackend.class.getClassLoader()).detect());
    }

    /**
     * 建立 backend 實作（顯式指定平台，測試 seam）。
     *
     * <p>維持 package-private：僅同 package 的服務與測試經此指定平台；
     * 外部呼叫端一律經由公開的單參數建構子建立。</p>
     *
     * @param server   供解析用的 Bukkit {@link Server}；不可為 null
     * @param platform 執行平台；不可為 null
     */
    BukkitWorldBackend(Server server, Platform platform) {
        this.server = Objects.requireNonNull(server, "server");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * 查詢半徑邊界判定。
     *
     * <p>SPI 契約宣告半徑必須為正數；在此集中把關，避免 0 或負數被當成
     * 「合法但命中空集合」而回 ok，讓呼叫端誤以為查詢成功執行過。</p>
     *
     * @param radius 查詢半徑
     * @return 有限且大於 0 時為 true
     */
    private static boolean isValidRadius(double radius) {
        return radius > 0 && !Double.isNaN(radius) && !Double.isInfinite(radius);
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
        // 效果執行尚未真實實作；不得回 ok 宣告已播放，必須明確拒絕，
        // 讓 caller 能區分「已播放」與「功能不存在」。
        return WorldBackendResult.failed(WorldErrorCode.PLATFORM_UNSUPPORTED,
            "effect '" + effectKey + "' is not implemented on this backend; cannot confirm playback");
    }

    /**
     * 內建 bounded 查詢橋接（package-private，非公開 SPI）。
     *
     * <p>承載明確錯誤碼的查詢路徑：半徑不合法、world 不存在、Folia 無法證明
     * owner-safe、chunk 未載入時回 failed；Paper 上以 bounding-box 候選 +
     * 球形距離 + 類型篩選回 ok。服務層經由此橋接保留原始錯誤碼，而非經由
     * 會把拒絕坍縮成空清單的 legacy List 方法。</p>
     *
     * <p>不新增公開 API：呼叫端僅限同 package 的 {@link WorldServiceImpl}；
     * 外部未知實作不得依賴此方法。</p>
     */
    WorldBackendResult<List<Entity>> queryNearby(Location location, double radius, EntityType type) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        if (!isValidRadius(radius)) {
            return WorldBackendResult.failed(WorldErrorCode.INVALID_INPUT,
                "radius must be > 0 (was " + radius + ")");
        }
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (platform == Platform.FOLIA) {
            // Folia 無法在沒有 region owner 保證下做跨 region 掃描；
            // 明確拒絕，而非呼叫 getNearbyEntities / getEntities 猜測安全性。
            return WorldBackendResult.failed(WorldErrorCode.CONTEXT_UNSAFE,
                "findNearby cannot prove owner-safe region ownership on Folia");
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        List<Entity> hits = new ArrayList<>();
        double r2 = radius * radius;
        // bounding-box 候選：不掃全世界；球形距離 + 類型在此層濾掉誤差。
        for (Entity e : world.getNearbyEntities(location, radius, radius, radius)) {
            if (e.getType() != type) {
                continue;
            }
            // getLocation() 每次呼叫都會配置新物件，先取一次同時用於世界與距離比對。
            Location entityLocation = e.getLocation();
            if (entityLocation.getWorld() == world
                    && entityLocation.distanceSquared(location) <= r2) {
                hits.add(e);
            }
        }
        return WorldBackendResult.ok(hits, "found " + hits.size() + " " + type + " within " + radius);
    }

    /**
     * 內建 bounded 玩家查詢橋接（package-private，非公開 SPI）。
     *
     * <p>語意同 {@link #queryNearby}：Folia 無 owner 保證時拒絕且不掃描；
     * Paper 上以 bounded 候選篩 Player + 球形距離。</p>
     */
    WorldBackendResult<List<Player>> queryNearbyPlayers(Location location, double radius) {
        Objects.requireNonNull(location, "location");
        if (!isValidRadius(radius)) {
            return WorldBackendResult.failed(WorldErrorCode.INVALID_INPUT,
                "radius must be > 0 (was " + radius + ")");
        }
        World world = location.getWorld();
        if (world == null) {
            return WorldBackendResult.failed(WorldErrorCode.WORLD_NOT_FOUND,
                "world not found at location=" + location);
        }
        if (platform == Platform.FOLIA) {
            return WorldBackendResult.failed(WorldErrorCode.CONTEXT_UNSAFE,
                "findNearbyPlayers cannot prove owner-safe region ownership on Folia");
        }
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return WorldBackendResult.failed(WorldErrorCode.CHUNK_UNLOADED,
                "chunk not loaded at " + location.getBlockX() + "," + location.getBlockZ());
        }
        List<Player> hits = new ArrayList<>();
        double r2 = radius * radius;
        for (Entity e : world.getNearbyEntities(location, radius, radius, radius)) {
            if (!(e instanceof Player p)) {
                continue;
            }
            Location entityLocation = e.getLocation();
            if (entityLocation.getWorld() == world
                    && entityLocation.distanceSquared(location) <= r2) {
                hits.add(p);
            }
        }
        return WorldBackendResult.ok(hits, "found " + hits.size() + " players within " + radius);
    }

    @Override
    public List<Entity> findNearby(Location location, double radius, EntityType type) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        // legacy List 無法表達拒絕：把橋接的失敗坍縮成空清單（fail-closed）。
        // 橋接內已保證失敗時不掃描；需要錯誤碼的呼叫端請走 WorldService 結構化管道。
        WorldBackendResult<List<Entity>> r = queryNearby(location, radius, type);
        return r.isOk() ? r.value() : List.of();
    }

    @Override
    public List<Player> findNearbyPlayers(Location location, double radius) {
        Objects.requireNonNull(location, "location");
        WorldBackendResult<List<Player>> r = queryNearbyPlayers(location, radius);
        return r.isOk() ? r.value() : List.of();
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
