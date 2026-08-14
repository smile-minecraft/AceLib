package com.smile.acelib.world;

import java.util.Objects;

/**
 * 實體操作結果（{@link WorldResult} 子類型）。
 *
 * <p>對應 {@link WorldService#spawnEntity(LocationSnapshot, String)}、
 * {@link WorldService#removeEntity(EntityReference)} 與
 * {@link WorldService#playEffect(LocationSnapshot, String)} 的回傳型別。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #reference} — 成功時為被建立 / 操作的 entity reference；可為 null（移除成功時）</li>
 *   <li>{@link #location} — 目標位置 snapshot（可能為 null，視呼叫類型）</li>
 * </ul>
 *
 * @see WorldResult
 * @since 1.0.0
 */
public final class EntityResult extends WorldResult {

    private final EntityReference reference;
    private final LocationSnapshot location;

    private EntityResult(WorldState state,
                         String errorCode,
                         String detail,
                         EntityReference reference,
                         LocationSnapshot location) {
        super(state, errorCode, detail);
        this.reference = reference;
        this.location = location;
    }

    /**
     * 取得 entity reference（如果適用）。
     *
     * @return 對應的 {@link EntityReference}；可能為 null（移除 / 效果操作）
     */
    public EntityReference reference() {
        return reference;
    }

    /**
     * 取得目標位置（如果適用）。
     *
     * @return 對應的 {@link LocationSnapshot}；可能為 null
     */
    public LocationSnapshot location() {
        return location;
    }

    // ----- factory methods -----

    /**
     * 建立成功結果（含被操作的 entity reference）。
     */
    public static EntityResult success(EntityReference reference, LocationSnapshot location) {
        return new EntityResult(WorldState.SUCCESS, null,
            "entity operation succeeded (entityId=" + reference.entityId() + ")",
            reference, location);
    }

    /**
     * 建立成功結果（無 reference，effect / remove 場景）。
     */
    public static EntityResult successWithoutReference(LocationSnapshot location) {
        return new EntityResult(WorldState.SUCCESS, null,
            "entity operation succeeded", null, location);
    }

    /**
     * 建立失敗結果（含錯誤代碼）。
     */
    public static EntityResult failure(WorldState state,
                                       String errorCode,
                                       String detail,
                                       LocationSnapshot location) {
        return new EntityResult(state, errorCode, detail, null, location);
    }
}
