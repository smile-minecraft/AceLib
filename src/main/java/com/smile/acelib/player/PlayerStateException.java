package com.smile.acelib.player;

import java.util.Objects;

/**
 * 玩家狀態模組例外（extends {@link RuntimeException}）。
 *
 * <p>所有對外拋出的玩家狀態例外必須攜帶 {@code ACELIB-PLAYER-<CODE>} 格式
 * 分類代碼，讓 {@link com.smile.acelib.diagnostics.ErrorCodeRegistry} 能正確歸類。</p>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-PLAYER-001}：資料尚未就緒（caller 在 LOADING 階段讀取）</li>
 *   <li>{@code ACELIB-PLAYER-002}：資料載入失敗（I/O 或反序列化錯誤）</li>
 *   <li>{@code ACELIB-PLAYER-003}：資料保存失敗（I/O 或序列化錯誤）</li>
 *   <li>{@code ACELIB-PLAYER-004}：session 重複登入（同一 UUID 已有 active session）</li>
 *   <li>{@code ACELIB-PLAYER-005}：session 未找到（caller 對未登入 UUID 操作）</li>
 *   <li>{@code ACELIB-PLAYER-006}：DataStore 未初始化</li>
 *   <li>{@code ACELIB-PLAYER-007}：服務已關閉（disable/shutdown 後呼叫 join/quit）</li>
 * </ul>
 *
 * <h2>設計約定</h2>
 * <ul>
 *   <li>{@link #getCode()} 永遠不為 null</li>
 *   <li>{@link #getCause()} 保留底層例外（不安靜吞錯）</li>
 *   <li>錯誤訊息須含「失敗位置 + 失敗原因」，避免只回傳 {@code ex.getMessage()}</li>
 * </ul>
 *
 * @see PlayerDataService
 * @since 1.0.0
 */
public class PlayerStateException extends RuntimeException {

    private final String code;

    /**
     * 主要建構子（無 cause）。
     *
     * @param code    錯誤代碼（{@code ACELIB-PLAYER-<CODE>}）；不可為 null
     * @param message 詳細訊息；不可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public PlayerStateException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 完整建構子（含 cause）。
     *
     * @param code    錯誤代碼；不可為 null
     * @param message 詳細訊息；不可為 null
     * @param cause   底層例外；可為 null
     * @throws NullPointerException 當 {@code code} 或 {@code message} 為 null
     */
    public PlayerStateException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 取得錯誤分類代碼。
     *
     * @return {@code ACELIB-PLAYER-<CODE>} 格式字串；永遠不為 null
     */
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        // 覆寫 toString 讓 log 與 stack trace 都帶有 ACELIB-PLAYER-* 代碼
        //（RuntimeException 預設 toString 只回傳 className + message）。
        return "[" + code + "] " + super.toString();
    }
}
