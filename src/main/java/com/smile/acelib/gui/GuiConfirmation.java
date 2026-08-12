package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可變的確認 action 合約（Phase 11 延伸第二切片：confirmation/cancellation）。
 *
 * <p>後續插件透過 {@link GuiService#createConfirmation} 取得本物件，並將其
 * {@link #actionToken()} 綁定回 {@link GuiService#confirm} / {@link GuiService#cancel}。
 * 一個 confirmation 一旦被 confirm 或 cancel，即一次性失效，重複呼叫會被拒絕。</p>
 *
 * <h2>綁定保證</h2>
 * <ul>
 *   <li>綁定玩家 {@link UUID} 與 session {@link #generation()} —
 *       錯誤 generation 或已關閉 session 的 confirm/cancel 會被拒絕</li>
 *   <li>{@link #actionToken()} 為服務產生的不透明唯一 token —
 *       呼叫端必須持有本物件才能取得 token，無法猜測</li>
 *   <li>{@link #actionId()} 為語意識別（例如 "delete-item-42"），供診斷</li>
 * </ul>
 *
 * <h2>不可變</h2>
 * <p>所有欄位為 {@code final}；本物件不持有 {@link org.bukkit.entity.Player}
 * reference。</p>
 *
 * @see GuiService
 * @since Phase 11 延伸（Plan §十六 確認與取消流程）
 */
public final class GuiConfirmation {

    /**
     * 確認 action 的生命週期狀態。順序凍結，不得更動（序列化相容）。
     *
     * <p>服務內部以 {@code PENDING → CONFIRMED / CANCELLED} 表示一次性解析；
     * session 關閉 / shutdown 時 action 直接失效（confirm/cancel 回
     * {@code UNKNOWN_ACTION}），不進入額外狀態。</p>
     */
    public enum State {
        PENDING,
        CONFIRMED,
        CANCELLED
    }

    private final UUID playerUuid;
    private final long generation;
    private final String actionId;
    private final String actionToken;
    private final State state;

    GuiConfirmation(UUID playerUuid, long generation, String actionId,
                   String actionToken, State state) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] generation 必須 > 0；實際: " + generation);
        }
        this.generation = generation;
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionToken = Objects.requireNonNull(actionToken, "actionToken");
        this.state = Objects.requireNonNull(state, "state");
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public long generation() {
        return generation;
    }

    public String actionId() {
        return actionId;
    }

    public String actionToken() {
        return actionToken;
    }

    public State state() {
        return state;
    }

    @Override
    public String toString() {
        return "GuiConfirmation{playerUuid=" + playerUuid
            + ", generation=" + generation
            + ", actionId=" + actionId
            + ", actionToken=" + actionToken
            + ", state=" + state + "}";
    }
}
