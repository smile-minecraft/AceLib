package com.smile.acelib.player;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家 session 物件。
 *
 * <p>不持有 {@code org.bukkit.entity.Player} reference — 僅以
 * {@link UUID} + 建構時 name snapshot 表達 session，避免長期持有已失效
 * Player 物件。當 session 被移除（END 狀態）時，
 * 唯一被釋放的是 session 物件本身；外部 caller 必須自行保證不保留任何
 * Player reference。</p>
 *
 * <h2>識別保證</h2>
 * <ul>
 *   <li>以 {@link UUID} 為唯一識別 key；{@link #getName()} 為建構時快照</li>
 *   <li>玩家更名（換 name 但 UUID 相同）→ 既有 session 與資料不變；
 *       重啟 session（join）時以新 name snapshot 取代</li>
 *   <li>{@link #equals(Object)} 與 {@link #hashCode()} 以 UUID 為唯一基準 —
 *       同 UUID 不同 name 仍視為同一 session</li>
 * </ul>
 *
 * <h2>狀態</h2>
 * <p>可變狀態僅限 {@link PlayerSessionState}；其餘欄位於建構後唯讀。
 * 狀態轉換受限於 {@link PlayerSessionState#canTransitionTo(PlayerSessionState)}
 * 規範，非法轉換拋 {@link IllegalStateException}。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>狀態變更使用 {@code volatile} 保證可見性；不支援並發 transition —
 * 預期由單一 owner thread（{@link PlayerDataService}）驅動轉換。</p>
 *
 * @see PlayerSessionState
 * @see PlayerSessionRegistry
 * @since 1.0.0
 */
public final class PlayerSession {

    private final UUID uniqueId;
    private final String name;
    private volatile PlayerSessionState state;

    /**
     * 主要建構子。
     *
     * @param uniqueId 玩家 UUID；不可為 null
     * @param name     建構時的顯示名稱快照；不可為 null
     * @param state    初始狀態；不可為 null
     * @throws NullPointerException 任何參數為 null
     */
    public PlayerSession(UUID uniqueId, String name, PlayerSessionState state) {
        this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
        this.name = Objects.requireNonNull(name, "name");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * 取得玩家 UUID（穩定識別）。
     *
     * @return 永不為 null 的玩家 UUID
     */
    public UUID getUniqueId() {
        return uniqueId;
    }

    /**
     * 取得建構時的顯示名稱快照。
     *
     * <p>此為建構當下的快照；玩家後續若改名（透過其他 server 機制），
     * 不會反映在此 session 物件上。session 重啟時（重新 join）會以新
     * snapshot 取代。</p>
     *
     * @return 永不為 null 的玩家名稱
     */
    public String getName() {
        return name;
    }

    /**
     * 取得當前 session 狀態。
     *
     * @return 當前狀態；永遠不為 null
     */
    public PlayerSessionState getState() {
        return state;
    }

    /**
     * 判斷是否處於「資料已就緒」狀態。
     *
     * @return true 表示 {@link PlayerSessionState#isReady()}
     */
    public boolean isReady() {
        return state.isReady();
    }

    /**
     * 將 session 轉換到新狀態。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>當前狀態為 {@link PlayerSessionState#ENDED} → 拋 IllegalStateException</li>
     *   <li>當前狀態不可直接跳至目標狀態 → 拋 IllegalStateException</li>
     *   <li>合法轉換成功後更新 state</li>
     * </ul>
     *
     * @param target 目標狀態；不可為 null
     * @throws NullPointerException 當 {@code target} 為 null
     * @throws IllegalStateException 當轉換非法
     */
    public void transitionTo(PlayerSessionState target) {
        Objects.requireNonNull(target, "target");
        PlayerSessionState current = this.state;
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(
                "illegal session state transition: " + current + " -> " + target
                    + " (uuid=" + uniqueId + ", name=" + name + ")");
        }
        this.state = target;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlayerSession that)) {
            return false;
        }
        return uniqueId.equals(that.uniqueId);
    }

    @Override
    public int hashCode() {
        return uniqueId.hashCode();
    }

    @Override
    public String toString() {
        return "PlayerSession{uuid=" + uniqueId
            + ", name=" + name
            + ", state=" + state
            + "}";
    }
}
