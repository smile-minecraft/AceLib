package com.smile.acelib.event;

/**
 * 事件監聽器的執行策略列舉。
 *
 * <p>後續插件實作 {@link SafeEventListener} 時，可依 listener 是否需要
 * region-bound 物件 mutate 來標記；{@link SafeEventRegistry} 在 dispatch
 * 階段會依 {@link ListenerPolicy} 與當前 {@link com.smile.acelib.platform.Platform 平台}
 * 決定是否略過此次呼叫並留下 {@code ACELIB-EVT-005} 紀錄。</p>
 *
 * <h2>語意對照</h2>
 * <ul>
 *   <li>{@link #UNCONSTRAINED}（預設）— listener 接受在任何 thread/context 被呼叫；
 *       Folia 環境下 listener 內部若需要 region-bound mutate，須由 listener 作者
 *       透過 {@link com.smile.acelib.context.SafeExecutor} 自行路由。</li>
 *   <li>{@link #REQUIRES_REGION} — listener 需要 region-bound 物件 mutate。
 *       Folia 環境下若當前 context 不是 region thread，registry 將略過此次呼叫並
 *       記錄 {@code ACELIB-EVT-005}；Paper / UNKNOWN 環境下等同 UNCONSTRAINED。</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * 列舉常數順序凍結，不得更動。
 *
 * @see SafeEventListener
 * @since 1.0.0
 */
public enum ListenerPolicy {

    /** 任意 thread/context 皆可執行。 */
    UNCONSTRAINED,

    /** Folia 環境下必須在 region thread，否則略過並記錄 EVT-005。 */
    REQUIRES_REGION
}