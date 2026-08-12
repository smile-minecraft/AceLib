package com.smile.acelib.world;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可變實體參考（Plan §十九 Phase 10 共同契約）。
 *
 * <p>對外識別 entity 的最小資訊集合 — <strong>不可</strong>將
 * {@link org.bukkit.entity.Entity} 或 {@link org.bukkit.entity.Player}
 * 暴露在 facade 介面上（避免跨執行緒保留 mutable reference）。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #entityId} — entity 唯一 UUID（識別）</li>
 *   <li>{@link #worldId} — 所屬世界 UUID（用於在 lookup 時縮小範圍）</li>
 *   <li>{@link #entityTypeKey} — Bukkit EntityType 列舉名（e.g. {@code "ZOMBIE"}）</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * 不可變 record；執行緒安全。
 *
 * @see LocationSnapshot
 * @since Phase 10 (Plan §十九)
 */
public record EntityReference(
    UUID entityId,
    UUID worldId,
    String entityTypeKey
) {

    /**
     * Compact constructor：欄位驗證。
     *
     * @throws NullPointerException 任一欄位為 null
     */
    public EntityReference {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(entityTypeKey, "entityTypeKey");
    }

    /**
     * 工廠方法。
     *
     * @param entityId      entity UUID；不可為 null
     * @param worldId       所屬世界 UUID；不可為 null
     * @param entityTypeKey Bukkit EntityType 列舉名；不可為 null
     * @return 對應的 {@link EntityReference}
     * @throws NullPointerException 任一參數為 null
     */
    public static EntityReference of(UUID entityId, UUID worldId, String entityTypeKey) {
        return new EntityReference(entityId, worldId, entityTypeKey);
    }
}
