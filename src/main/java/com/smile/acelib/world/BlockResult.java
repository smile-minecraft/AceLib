package com.smile.acelib.world;

import java.util.Objects;

/**
 * 方塊操作結果（{@link WorldResult} 子類型）。
 *
 * <p>對應 {@link WorldService#readBlock(LocationSnapshot)} 與
 * {@link WorldService#writeBlock(LocationSnapshot, String)} 的回傳型別。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #blockKey} — 成功時為讀取 / 寫入的方塊 key（e.g. {@code "STONE"}）；
 *       失敗時可為 null</li>
 *   <li>{@link #location} — 操作的目標位置 snapshot；不可為 null</li>
 * </ul>
 *
 * @see WorldResult
 * @since 1.0.0
 */
public final class BlockResult extends WorldResult {

    private final String blockKey;
    private final LocationSnapshot location;

    private BlockResult(WorldState state,
                        String errorCode,
                        String detail,
                        String blockKey,
                        LocationSnapshot location) {
        super(state, errorCode, detail);
        this.blockKey = blockKey;
        this.location = Objects.requireNonNull(location, "location");
    }

    /**
     * 取得方塊 key（e.g. {@code "STONE"}）。
     *
     * @return 方塊 key；可能為 null（失敗時）
     */
    public String blockKey() {
        return blockKey;
    }

    /**
     * 取得目標位置。
     *
     * @return 對應的 {@link LocationSnapshot}；永遠不為 null
     */
    public LocationSnapshot location() {
        return location;
    }

    // ----- factory methods -----

    /**
     * 建立成功結果。
     *
     * @param location 目標位置；不可為 null
     * @param blockKey 方塊 key（如 {@code "STONE"}）
     * @return 成功結果（{@link WorldState#SUCCESS}，無錯誤碼）
     */
    public static BlockResult success(LocationSnapshot location, String blockKey) {
        return new BlockResult(WorldState.SUCCESS, null,
            "block operation succeeded (key=" + blockKey + ")", blockKey, location);
    }

    /**
     * 建立失敗結果（含錯誤代碼）。
     *
     * @param state     結果狀態；不可為 null
     * @param errorCode 錯誤代碼（{@code ACELIB-WORLD-*}）
     * @param detail    人類可讀訊息；不可為 null
     * @param location  目標位置；不可為 null
     * @return 失敗結果
     */
    public static BlockResult failure(WorldState state,
                                      String errorCode,
                                      String detail,
                                      LocationSnapshot location) {
        return new BlockResult(state, errorCode, detail, null, location);
    }
}
