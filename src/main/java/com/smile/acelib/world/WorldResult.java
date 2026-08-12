package com.smile.acelib.world;

import java.util.Objects;

/**
 * 世界操作結果的共同基底（Plan §十九 Phase 10 共同契約）。
 *
 * <p>所有對外 {@link WorldService} 操作皆回傳 {@link WorldResult} 子類型。
 * 結果內含狀態（{@link WorldState}）、錯誤代碼（{@link WorldErrorCode} *）、
 * 以及人類可讀的訊息。任何非 {@link WorldState#SUCCESS} 狀態都必須攜帶
 * 對應的 {@code ACELIB-WORLD-*} 錯誤代碼。</p>
 *
 * <h2>設計</h2>
 * <ul>
 *   <li>不可變 record family — 執行緒安全</li>
 *   <li>錯誤代碼為 {@code String} 常數而非 enum，便於版本演進並與
 *       {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 對齊</li>
 *   <li>每個子類型再攜帶該 operation 類型特有的資料（blockKey / entityReference /
 *       teleportStage 等）</li>
 * </ul>
 *
 * @see BlockResult
 * @see EntityResult
 * @see TeleportResult
 * @see NearbyQueryResult
 * @since Phase 10 (Plan §十九)
 */
public abstract sealed class WorldResult
    permits BlockResult, EntityResult, TeleportResult, NearbyQueryResult {

    private final WorldState state;
    private final String errorCode;
    private final String detail;

    /**
     * 子類型共同基底 constructor。
     *
     * @param state     結果狀態；不可為 null
     * @param errorCode 錯誤代碼（{@code ACELIB-WORLD-*}）；於 {@link WorldState#SUCCESS} 必須為 null，
     *                  其他狀態為 null 表示「不分類錯誤」（INTERNAL 語意）
     * @param detail    人類可讀訊息；不可為 null
     */
    protected WorldResult(WorldState state, String errorCode, String detail) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (state == WorldState.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException(
                "SUCCESS state must not carry an error code, got: " + errorCode);
        }
        this.errorCode = errorCode;
    }

    /**
     * 結果狀態。
     *
     * @return 對應的 {@link WorldState}；永遠不為 null
     */
    public WorldState state() {
        return state;
    }

    /**
     * 錯誤代碼（{@code ACELIB-WORLD-*} 格式）。
     *
     * @return 錯誤代碼；可能為 null（SUCCESS 時）
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 人類可讀的詳細訊息。
     *
     * @return 詳細訊息；永遠不為 null（可能為空字串）
     */
    public String detail() {
        return detail;
    }

    /**
     * 便利方法：是否成功。
     */
    public boolean isSuccess() {
        return state == WorldState.SUCCESS;
    }

    /**
     * 便利方法：是否被拒絕（{@link WorldState#REJECTED} / {@link WorldState#CANCELLED}）。
     */
    public boolean isRejected() {
        return state == WorldState.REJECTED || state == WorldState.CANCELLED;
    }
}
