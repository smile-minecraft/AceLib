package com.smile.acelib.display;

import java.util.Objects;
import java.util.UUID;

/**
 * 顯示操作的對外結果（Supported）。
 *
 * <p>所有 {@link DisplayService} 操作皆回傳本型別，不因領域失敗丟例外
 * （null 參數除外，見 {@link DisplayService} 的預檢約定）。
 * 不可變 record 風格：執行緒安全，可跨 region 傳遞。</p>
 *
 * <ul>
 *   <li>{@link DisplayState#SUCCESS}／{@link DisplayState#ACCEPTED} 不攜帶
 *       errorCode；全息字生成成功時另攜帶 {@link #hologramId()}</li>
 *   <li>{@link DisplayState#REJECTED}／{@link DisplayState#FAILED} 必須攜帶
 *       {@code ACELIB-DISP-*} 代碼</li>
 *   <li>{@link #deduped()} 為 true 表示內容與上次相同、未實際派送
 *       （同值不重送，可觀察的去重語意）</li>
 * </ul>
 *
 * @since 1.4.0
 */
public final class DisplayResult {

    private final DisplayState state;
    private final String errorCode;
    private final String detail;
    private final UUID hologramId;
    private final boolean deduped;

    private DisplayResult(DisplayState state, String errorCode, String detail,
            UUID hologramId, boolean deduped) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = detail == null ? "" : detail;
        this.hologramId = hologramId;
        this.deduped = deduped;
        if ((state == DisplayState.REJECTED || state == DisplayState.FAILED)
                && errorCode == null) {
            throw new IllegalArgumentException(
                "[" + DisplayErrorCode.INVALID_INPUT + "] " + state
                    + " state 必須攜帶 errorCode");
        }
        this.errorCode = errorCode;
    }

    /**
     * 建立成功結果。
     *
     * @param detail 人類可讀訊息；可為 null（正規化為空字串）
     * @return 不可為 null 的結果
     */
    public static DisplayResult success(String detail) {
        return new DisplayResult(DisplayState.SUCCESS, null, detail, null, false);
    }

    /**
     * 建立成功結果（攜帶全息字 id）。
     *
     * @param hologramId 追蹤中的全息字 id；不可為 null
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果
     */
    public static DisplayResult success(UUID hologramId, String detail) {
        return new DisplayResult(DisplayState.SUCCESS, null, detail,
            Objects.requireNonNull(hologramId, "hologramId"), false);
    }

    /**
     * 建立去重成功結果（內容與上次相同，未實際派送）。
     *
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果（{@link #deduped()} 為 true）
     */
    public static DisplayResult deduped(String detail) {
        return new DisplayResult(DisplayState.SUCCESS, null, detail, null, true);
    }

    /**
     * 建立派送已接受結果（全息字生成等非同步路徑）。
     *
     * @param hologramId 追蹤中的全息字 id；不可為 null
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果
     */
    public static DisplayResult accepted(UUID hologramId, String detail) {
        return new DisplayResult(DisplayState.ACCEPTED, null, detail,
            Objects.requireNonNull(hologramId, "hologramId"), false);
    }

    /**
     * 建立派送已接受結果（不攜帶全息字 id 的玩家操作路徑）。
     *
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果
     */
    public static DisplayResult accepted(String detail) {
        return new DisplayResult(DisplayState.ACCEPTED, null, detail, null, false);
    }

    /**
     * 建立可修正的拒絕結果。
     *
     * @param errorCode {@code ACELIB-DISP-*} 代碼；不可為 null
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果
     */
    public static DisplayResult rejected(String errorCode, String detail) {
        return new DisplayResult(DisplayState.REJECTED,
            Objects.requireNonNull(errorCode, "errorCode"), detail, null, false);
    }

    /**
     * 建立失敗結果。
     *
     * @param errorCode {@code ACELIB-DISP-*} 代碼；不可為 null
     * @param detail 人類可讀訊息；可為 null
     * @return 不可為 null 的結果
     */
    public static DisplayResult failed(String errorCode, String detail) {
        return new DisplayResult(DisplayState.FAILED,
            Objects.requireNonNull(errorCode, "errorCode"), detail, null, false);
    }

    /** @return 結果狀態；永不為 null */
    public DisplayState state() {
        return state;
    }

    /** @return 錯誤代碼；成功／接受時為 null */
    public String errorCode() {
        return errorCode;
    }

    /** @return 人類可讀訊息；永不為 null */
    public String detail() {
        return detail;
    }

    /** @return 全息字 id；僅生成成功／接受時非 null */
    public UUID hologramId() {
        return hologramId;
    }

    /** @return 是否因內容相同而略過派送 */
    public boolean deduped() {
        return deduped;
    }

    /** @return 是否為成功（含去重成功） */
    public boolean isSuccess() {
        return state == DisplayState.SUCCESS;
    }

    @Override
    public String toString() {
        return "DisplayResult{state=" + state + ", errorCode=" + errorCode
            + ", detail=" + detail + ", hologramId=" + hologramId
            + ", deduped=" + deduped + '}';
    }
}
