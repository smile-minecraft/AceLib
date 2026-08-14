package com.smile.acelib.event;

import java.util.List;
import org.bukkit.event.Event;

/**
 * 安全事件註冊器介面。
 *
 * <p>安全註冊、解除、追蹤事件，避免 reload / disable 後殘留 listener。
 * 本介面對外承諾：</p>
 *
 * <ul>
 *   <li>註冊後 listener 會被呼叫；解除後不再被呼叫</li>
 *   <li>重複註冊同一個 listener 會回傳原 registration 並記錄 {@code ACELIB-EVT-003}</li>
 *   <li>listener 內部拋錯會被攔截、記錄 {@code ACELIB-EVT-001}，不影響其他 listener</li>
 *   <li>disable 後所有 listener 不再被呼叫、無殘留</li>
 *   <li>Plugin disable 後任何註冊操作不丟例外但記錄 {@code ACELIB-EVT-004}</li>
 * </ul>
 *
 * <h2>Folia 安全邊界</h2>
 * <p>listener 透過 {@link SafeEventListener#policy()} 標記是否需要
 * region-bound context；Folia 環境下非 region thread 呼叫 REQUIRES_REGION
 * listener 會被略過並記錄 {@code ACELIB-EVT-005}。Paper / UNKNOWN 環境下
 * REQUIRES_REGION 等同 UNCONSTRAINED。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆為 thread-safe，可在多 region 並行環境下使用。</p>
 *
 * @see SafeEventListener
 * @see EventRegistration
 * @see EventErrorRecorder
 * @since 1.0.0
 */
public interface SafeEventRegistry {

    /**
     * 註冊一個 listener（非一次性）。
     *
     * <p>等同 {@code register(eventType, listener, false)}。</p>
     *
     * @param eventType Bukkit Event 型別；不可為 null
     * @param listener  要註冊的 listener；不可為 null
     * @param <E>       事件型別
     * @return 對應的 {@link EventRegistration} handle；若 listener 已註冊，
     *         回傳先前 registration 並記錄 {@code ACELIB-EVT-003}
     * @throws NullPointerException 當 {@code eventType} 或 {@code listener} 為 null
     */
    <E extends Event> EventRegistration<E> register(Class<E> eventType,
                                                     SafeEventListener<E> listener);

    /**
     * 註冊一個一次性 listener。
     *
     * <p>listener 在首次被 dispatch 後會自動從 registry 移除。</p>
     *
     * @param eventType Bukkit Event 型別；不可為 null
     * @param listener  要註冊的 listener；不可為 null
     * @param <E>       事件型別
     * @return 對應的 {@link EventRegistration} handle（含 {@code oneShot=true}）
     * @throws NullPointerException 當 {@code eventType} 或 {@code listener} 為 null
     */
    <E extends Event> EventRegistration<E> registerOneShot(Class<E> eventType,
                                                            SafeEventListener<E> listener);

    /**
     * 解除一個 listener 註冊。
     *
     * <p>若 {@code registration} 不在 registry 中（例如已經被 disable 清理或
     * 是一次性 listener 已觸發），呼叫為 no-op，不丟例外。</p>
     *
     * @param registration 之前 {@link #register} 回傳的 handle；不可為 null
     * @throws NullPointerException 當 {@code registration} 為 null
     */
    void unregister(EventRegistration<? extends Event> registration);

    /**
     * 解除此 registry 內所有 listener 註冊。
     *
     * <p>呼叫後 listener 不再被 dispatch；PluginManager 註冊的 bridge
     * listener 也會被解除。常用於 reload / disable 流程。
     * 重複呼叫不丟例外。</p>
     */
    void unregisterAll();

    /**
     * 取得最近的 N 筆錯誤紀錄。
     *
     * @param max 最多回傳幾筆（&lt;=0 回傳空清單）
     * @return 不可變的「時間由舊到新」紀錄清單
     */
    List<EventErrorRecord> getRecentErrors(int max);

    /**
     * 取得目前已追蹤的 listener 數量（測試與診斷用）。
     *
     * @return active listener 數量
     */
    int getTrackedRegistrationCount();

    /**
     * 取得目前已追蹤的所有 registration（測試與診斷用）。
     *
     * <p>回傳的清單為不可變快照；caller 不應修改。</p>
     *
     * @return 不可變的 registration 清單
     */
    List<EventRegistration<? extends Event>> getTrackedRegistrations();

    /**
     * 判斷此 registry 是否已被標記為 disabled（{@link #onPluginDisable()} 已呼叫）。
     *
     * @return true 表示已停用
     */
    boolean isDisabled();

    /**
     * 通知此 registry 對應的 plugin 已停用：解除所有 listener 並標記為 disabled。
     *
     * <p>呼叫後：</p>
     * <ul>
     *   <li>所有後續 {@link #register} / {@link #registerOneShot} 仍回傳新 handle
     *       但 listener 不會被 dispatch（dispatch 入口直接拒絕）並記錄
     *       {@code ACELIB-EVT-004}</li>
     *   <li>Bukkit {@link org.bukkit.event.HandlerList HandlerList} 上的 bridge
     *       listener 會被解除（透過
     *       {@link org.bukkit.event.HandlerList#unregisterAll(org.bukkit.event.Listener)}），
     *       listener 不會被 Bukkit 觸發</li>
     *   <li>所有 tracked registration 清空</li>
     *   <li>重複呼叫不丟例外（idempotent）</li>
     * </ul>
     *
     * <p>disable 路徑不依賴 dispatch 入口檢查
     * 作為唯一保護；{@link org.bukkit.event.HandlerList} 上的 bridge listener
     * 會被明確解除，避免下一輪 onEnable / 重新註冊時 listener 重複觸發或
     * 重複加入 HandlerList。</p>
     */
    void onPluginDisable();
}