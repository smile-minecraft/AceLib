package com.smile.acelib.player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家 session registry（Plan §十四 Phase 9）。
 *
 * <p>以 {@link UUID} 為唯一識別 key 維護 active session 清單；提供
 * start / get / end 三種 lifecycle 操作與 size / clear 觀察/管理方法。</p>
 *
 * <h2>不變量</h2>
 * <ul>
 *   <li>同一 UUID 不可同時存在多個 session（{@link #startSession} 會拒絕重複）</li>
 *   <li>Session 一旦 {@link #endSession} 即從 registry 移除；不會保留失效 session</li>
 *   <li>所有方法 thread-safe（使用 {@link ConcurrentHashMap}）</li>
 * </ul>
 *
 * <h2>與 Player 名稱變更的關係</h2>
 * <p>Registry 不感知名稱變化；識別完全依 UUID。同 UUID 不同名稱重新登入時，
 * 舊 session 必須先 {@link #endSession(UUID)}；新 session 透過
 * {@link #startSession(UUID, String)} 以新 name snapshot 建立。允許
 * 「alice」與「AliceRenamed」（不同 UUID）並存於 registry。</p>
 *
 * @see PlayerSession
 * @since Phase 9 (Plan §十四)
 */
public final class PlayerSessionRegistry {

    private final ConcurrentMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    /**
     * 為指定 UUID 建立新 session（state=LOADING）。
     *
     * @param uuid 玩家 UUID；不可為 null
     * @param name 建構時的顯示名稱快照；不可為 null
     * @return 不可為 null 的新 session
     * @throws NullPointerException 任何參數為 null
     * @throws PlayerStateException 當 {@code uuid} 已有 active session（{@code ACELIB-PLAYER-004}）
     */
    public PlayerSession startSession(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        PlayerSession candidate = new PlayerSession(uuid, name, PlayerSessionState.LOADING);
        PlayerSession existing = sessions.putIfAbsent(uuid, candidate);
        if (existing != null) {
            throw new PlayerStateException("ACELIB-PLAYER-004",
                "session already active for uuid=" + uuid
                    + " (existing name=" + existing.getName()
                    + ", state=" + existing.getState() + ")");
        }
        return candidate;
    }

    /**
     * 取得指定 UUID 的 session（若存在）。
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 對應 session；若不存在回傳 {@link Optional#empty()}
     * @throws NullPointerException 當 {@code uuid} 為 null
     */
    public Optional<PlayerSession> getSession(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return Optional.ofNullable(sessions.get(uuid));
    }

    /**
     * 結束並移除指定 UUID 的 session。
     *
     * <p>注意：本方法僅移除 registry 中的 entry；session 物件的 state
     * 仍維持呼叫前的值（通常由 {@link PlayerDataService} 透過
     * {@link PlayerSession#transitionTo(PlayerSessionState)} 同步轉換）。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 被移除的 session；若無對應 session 回傳 null
     * @throws NullPointerException 當 {@code uuid} 為 null
     */
    public PlayerSession endSession(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return sessions.remove(uuid);
    }

    /**
     * 取得當前 active session 數。
     *
     * @return active session 數；0 表示無任何 session
     */
    public int size() {
        return sessions.size();
    }

    /**
     * 直接放入既有 session（package-private test seam）。
     *
     * <p>僅供 {@link PlayerDataService} 內部或測試用 — 通常不應由外部直接呼叫。
     * 若該 UUID 已有 session 將被覆寫而不拋例外（測試場景）。</p>
     *
     * @param session 要放入的 session；不可為 null
     */
    void putSession(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        sessions.put(session.getUniqueId(), session);
    }

    /**
     * 清除所有 session（reload / disable 使用）。
     *
     * <p>冪等：重複呼叫不丟例外。被移除的 session 由 caller 自行管理
     * （本方法不主動呼叫 transitionTo(ENDED)）。</p>
     */
    public void clear() {
        sessions.clear();
    }
}
