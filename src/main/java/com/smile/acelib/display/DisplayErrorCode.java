package com.smile.acelib.display;

/**
 * 顯示模組錯誤代碼（Supported）。
 *
 * <p>新分類區 {@code DISP}，自 {@code ACELIB-DISP-001} 起編號。
 * 所有對外拋出或記錄的顯示錯誤都攜帶下列代碼，格式遵循
 * {@code ACELIB-&lt;AREA&gt;-&lt;CODE&gt;}（見
 * {@code docs/reference/error-codes.md}）。</p>
 *
 * @since 1.4.0
 */
public final class DisplayErrorCode {

    /** 顯示服務尚未啟用（unavailable facade 的拒絕）。 */
    public static final String NOT_READY = "ACELIB-DISP-001";

    /** 顯示服務已停用（reload／disable 後的拒絕）。 */
    public static final String SHUTDOWN = "ACELIB-DISP-002";

    /** 輸入不合法（null 參數、進度超出範圍、計分板行數超限等）。 */
    public static final String INVALID_INPUT = "ACELIB-DISP-003";

    /** 目標玩家已離線（派送前或派送當下判定）。 */
    public static final String PLAYER_OFFLINE = "ACELIB-DISP-004";

    /** 目標全息字實體已失效（退休／死亡／被移除）。 */
    public static final String ENTITY_RETIRED = "ACELIB-DISP-005";

    /** 目標位置所在 chunk 尚未載入（全息字生成被拒）。 */
    public static final String CHUNK_NOT_LOADED = "ACELIB-DISP-006";

    /** 擁有者上下文內執行失敗（底層平台操作拋錯，已記錄不吞錯）。 */
    public static final String OPERATION_FAILED = "ACELIB-DISP-007";

    private DisplayErrorCode() {
        // 常數表，不提供實例
    }
}
