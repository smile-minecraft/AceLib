package com.smile.acelib.event;

import java.util.Objects;
import org.bukkit.event.Event;

/**
 * 後續插件的事件處理器介面。
 *
 * <p>對應 Plan §十二 Phase 7「事件註冊／解除」的需求；{@link SafeEventRegistry}
 * 透過泛型 {@code E} 綁定事件型別，避免後續插件在 listener 內部自行
 * {@code instanceof} 判斷造成的 boilerplate。</p>
 *
 * <h2>設計約束</h2>
 * <ul>
 *   <li>listener 內部拋出任何例外都會被 registry 攔截並記錄
 *       {@code ACELIB-EVT-001}，不會影響其他 listener 或導致 AceLib 癱瘓</li>
 *   <li>{@link #eventType()} 必須在實作時固定；registry 依此決定註冊到
 *       Bukkit {@code PluginManager} 的哪一個 event class</li>
 *   <li>{@link #policy()} 預設為 {@link ListenerPolicy#UNCONSTRAINED}；
 *       後續插件可 override 為 {@link ListenerPolicy#REQUIRES_REGION} 以要求
 *       Folia region thread context</li>
 *   <li>{@link #isOneShot()} 預設為 false；true 表示 listener 觸發一次後自動解除註冊</li>
 * </ul>
 *
 * <h2>識別語意</h2>
 * <p>兩個 listener 是否「同一個」以 {@link #identity()} 預設實作的
 * {@code == this} 為準（後續插件可 override 提供自定義 key，例如同一個 class
 * 的多個 listener instance 仍視為不同）。</p>
 *
 * @param <E> 對應的 Bukkit Event 子型別
 * @see SafeEventRegistry
 * @see ListenerPolicy
 * @since Phase 7 (Plan §十二)
 */
@FunctionalInterface
public interface SafeEventListener<E extends Event> {

    /**
     * 處理 Bukkit Event。
     *
     * <p>任何拋出的例外都會被 {@link SafeEventRegistry} 攔截並記錄
     * {@code ACELIB-EVT-001}；listener 內不需要 try-catch。</p>
     *
     * @param event Bukkit 觸發的事件；不可為 null
     * @throws Exception listener 內部錯誤（將被 registry 捕獲）
     */
    void onEvent(E event) throws Exception;

    /**
     * 此 listener 對應的 Bukkit Event 型別。
     *
     * <p>registry 依此決定要註冊到 Bukkit {@code PluginManager} 的哪一個
     * event class。每個 listener 只能對應一個型別；若需要處理多個 event，
     * 請建立多個 listener 實作。</p>
     *
     * @return Bukkit Event 子型別的 {@link Class} 物件；不可為 null
     */
    default Class<E> eventType() {
        throw new UnsupportedOperationException(
            "SafeEventListener.eventType() 必須由實作者 override；"
                + "若使用 lambda 形式，請改用具名 class 或 override 此方法");
    }

    /**
     * 此 listener 的執行策略。
     *
     * @return 預設 {@link ListenerPolicy#UNCONSTRAINED}；後續插件可 override
     */
    default ListenerPolicy policy() {
        return ListenerPolicy.UNCONSTRAINED;
    }

    /**
     * 是否為一次性 listener。
     *
     * <p>若為 true，listener 在首次被 dispatch 後會自動從 registry 移除。
     * 適用於「只想處理下一次事件」「初始化單次任務」等情境。</p>
     *
     * @return 預設 false；後續插件可 override 為 true
     */
    default boolean isOneShot() {
        return false;
    }

    /**
     * 此 listener 的識別鍵（用於重複註冊偵測）。
     *
     * <p>預設回傳 {@code this}（以 {@code ==} 比對）；若後續插件希望以
     * 同一個 class 的多個 instance 視為「同一個 listener」，可 override
     * 回傳 {@code getClass()} 或其他穩定的 key。</p>
     *
     * @return 不可為 null 的識別鍵
     */
    default Object identity() {
        return this;
    }

    /**
     * Null-safe 物件檢查便利方法（for lambda use）。
     *
     * @param obj   要檢查的物件
     * @param name  欄位名稱（用於錯誤訊息）
     * @param <T>   物件型別
     * @return 非 null 的 {@code obj}
     * @throws NullPointerException 當 {@code obj} 為 null
     */
    static <T> T requireNonNull(T obj, String name) {
        return Objects.requireNonNull(obj, name);
    }
}