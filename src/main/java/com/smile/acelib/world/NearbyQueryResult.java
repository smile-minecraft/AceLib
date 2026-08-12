package com.smile.acelib.world;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 鄰近查詢結果（{@link WorldResult} 子類型；Plan §十九 Phase 10 共同契約）。
 *
 * <p>對應 {@link WorldService#findNearbyEntities(LocationSnapshot, double, String)} 與
 * {@link WorldService#findNearbyPlayers(LocationSnapshot, double)} 的回傳型別。
 * 攜帶不可變的 {@link EntityReference} 清單；SUCCESS 時清單可為空集合（半徑內無命中）
 * 或含 N 筆命中；FAILURE 時清單一律為不可變的空集合。</p>
 *
 * @see WorldResult
 * @since Phase 10 (Plan §十九)
 */
public final class NearbyQueryResult extends WorldResult {

    private final List<EntityReference> references;
    private final LocationSnapshot center;

    private NearbyQueryResult(WorldState state,
                              String errorCode,
                              String detail,
                              List<EntityReference> references,
                              LocationSnapshot center) {
        super(state, errorCode, detail);
        Objects.requireNonNull(references, "references");
        this.references = List.copyOf(references);
        this.center = center;
    }

    /**
     * 命中清單。SUCCESS 時可能為空（半徑內無命中），其他狀態一律為空。
     *
     * @return 不可變清單；永遠不為 null
     */
    public List<EntityReference> references() {
        return references;
    }

    /**
     * 查詢中心；可能為 null（FAILURE 等無位置語意場景）。
     */
    public LocationSnapshot center() {
        return center;
    }

    // ----- factory methods -----

    /**
     * 成功 + 命中清單（清單為空表示無命中）。
     */
    public static NearbyQueryResult success(LocationSnapshot center,
                                           List<EntityReference> hits) {
        return new NearbyQueryResult(
            WorldState.SUCCESS, null,
            "nearby query succeeded (hits=" + hits.size() + ")",
            hits, center);
    }

    /**
     * 失敗（含錯誤代碼）。失敗時無位置語意，references 一律為空。
     */
    public static NearbyQueryResult failure(WorldState state,
                                            String errorCode,
                                            String detail,
                                            LocationSnapshot center) {
        return new NearbyQueryResult(
            state, errorCode, detail,
            Collections.emptyList(), center);
    }
}
