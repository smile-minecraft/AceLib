package com.smile.acelib.world;

import java.util.Objects;
import java.util.UUID;

/**
 * 傳送操作結果（{@link WorldResult} 子類型）。
 *
 * <p>對應 {@link WorldService#teleportPlayer(UUID, LocationSnapshot, boolean)} 與
 * {@link WorldService#teleportEntity(UUID, LocationSnapshot, boolean)} 的回傳型別。
 * 傳送為非同步操作，caller 應透過 {@link java.util.concurrent.CompletionStage} 等待
 * 實際完成（success / false / exception / cancelled / partial）。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #subjectId} — 被傳送的 player / entity UUID；不可為 null</li>
 *   <li>{@link #target} — 目標位置 snapshot；不可為 null</li>
 *   <li>{@link #keepPassengers} — 是否保留乘客（呼叫時傳入）</li>
 * </ul>
 *
 * @see WorldResult
 * @since 1.0.0
 */
public final class TeleportResult extends WorldResult {

    private final UUID subjectId;
    private final LocationSnapshot target;
    private final boolean keepPassengers;

    private TeleportResult(WorldState state,
                           String errorCode,
                           String detail,
                           UUID subjectId,
                           LocationSnapshot target,
                           boolean keepPassengers) {
        super(state, errorCode, detail);
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.target = Objects.requireNonNull(target, "target");
        this.keepPassengers = keepPassengers;
    }

    /**
     * 取得被傳送的 entity / player UUID。
     *
     * @return 對應 UUID；永遠不為 null
     */
    public UUID subjectId() {
        return subjectId;
    }

    /**
     * 取得目標位置。
     *
     * @return 對應的 {@link LocationSnapshot}；永遠不為 null
     */
    public LocationSnapshot target() {
        return target;
    }

    /**
     * 是否保留乘客。
     */
    public boolean keepPassengers() {
        return keepPassengers;
    }

    // ----- factory methods -----

    /**
     * 建立成功結果。
     */
    public static TeleportResult success(UUID subjectId,
                                          LocationSnapshot target,
                                          boolean keepPassengers) {
        return new TeleportResult(WorldState.SUCCESS, null,
            "teleport succeeded (subjectId=" + subjectId + ")",
            subjectId, target, keepPassengers);
    }

    /**
     * 建立失敗結果（含錯誤代碼）。
     */
    public static TeleportResult failure(WorldState state,
                                         String errorCode,
                                         String detail,
                                         UUID subjectId,
                                         LocationSnapshot target,
                                         boolean keepPassengers) {
        return new TeleportResult(state, errorCode, detail, subjectId, target, keepPassengers);
    }

    /**
     * 建立 cancelled 結果（用於 shutdown / in-flight cancellation）。
     */
    public static TeleportResult cancelled(UUID subjectId,
                                           LocationSnapshot target,
                                           boolean keepPassengers) {
        return new TeleportResult(WorldState.CANCELLED, WorldErrorCode.SHUTDOWN,
            "teleport cancelled because service was shut down",
            subjectId, target, keepPassengers);
    }
}
