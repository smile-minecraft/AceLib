package com.smile.acelib.world;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可變位置快照。
 *
 * <p>本 record 為 {@link WorldService} 對外公開的位置表達方式，
 * <strong>不可</strong>將 {@link org.bukkit.Location} 或 {@link org.bukkit.World}
 * 暴露在 facade 介面上（避免保留 mutable reference 跨越執行緒或生命週期）。
 * 內部實作於收到 snapshot 後，於對應執行緒重新解析為當下的 Bukkit 物件。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #worldId} — 目標世界 UUID（識別而非 reference）</li>
 *   <li>{@link #blockX} / {@link #blockY} / {@link #blockZ} — 方塊座標（整數）</li>
 *   <li>{@link #yaw} / {@link #pitch} — 旋轉角度（degree）；二者皆可為 NaN 表示未指定</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * 不可變 record；所有欄位為 {@code final}，執行緒安全。
 *
 * @param worldId    目標世界 UUID；不可為 null
 * @param blockX     方塊 X 座標（整數）
 * @param blockY     方塊 Y 座標（整數）
 * @param blockZ     方塊 Z 座標（整數）
 * @param yaw        yaw 旋轉（degree；NaN 表示未指定）
 * @param pitch      pitch 旋轉（degree；NaN 表示未指定）
 * @see EntityReference
 * @since 1.0.0
 */
public record LocationSnapshot(
    UUID worldId,
    int blockX,
    int blockY,
    int blockZ,
    float yaw,
    float pitch
) {

    /**
     * Compact constructor：欄位驗證。
     *
     * @throws NullPointerException 當 {@code worldId} 為 null
     */
    public LocationSnapshot {
        Objects.requireNonNull(worldId, "worldId");
    }

    /**
     * 工廠方法：建構時不指定朝向（yaw / pitch 皆為 0）。
     *
     * @param worldId 目標世界 UUID；不可為 null
     * @param x       方塊 X 座標
     * @param y       方塊 Y 座標
     * @param z       方塊 Z 座標
     * @return 對應的 {@link LocationSnapshot}
     * @throws NullPointerException 當 {@code worldId} 為 null
     */
    public static LocationSnapshot of(UUID worldId, int x, int y, int z) {
        return new LocationSnapshot(worldId, x, y, z, 0.0f, 0.0f);
    }

    /**
     * 工廠方法：建構時指定完整朝向。
     *
     * @param worldId 目標世界 UUID；不可為 null
     * @param x       方塊 X 座標
     * @param y       方塊 Y 座標
     * @param z       方塊 Z 座標
     * @param yaw     yaw 旋轉（degree）
     * @param pitch   pitch 旋轉（degree）
     * @return 對應的 {@link LocationSnapshot}
     * @throws NullPointerException 當 {@code worldId} 為 null
     */
    public static LocationSnapshot of(UUID worldId, int x, int y, int z, float yaw, float pitch) {
        return new LocationSnapshot(worldId, x, y, z, yaw, pitch);
    }

    /**
     * 取得用於診斷的世界 UUID 字串形式。
     *
     * @return 對應 UUID 的 toString；永遠不為 null
     */
    public String worldIdString() {
        return worldId.toString();
    }
}
