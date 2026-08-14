package com.smile.acelib.event;

import java.util.Objects;
import org.bukkit.event.Event;

/**
 * 事件註冊 handle（immutable record）。
 *
 * <p>呼叫 {@link SafeEventRegistry#register} / {@link SafeEventRegistry#registerOneShot}
 * 後會回傳一個 {@code EventRegistration} handle，後續插件可持有此 handle
 * 呼叫 {@link SafeEventRegistry#unregister(EventRegistration)} 解除註冊。</p>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>{@code eventType}、{@code listener}、{@code identity} 不可為 null</li>
 *   <li>{@code registryId} 為 registry 內部的唯一識別（測試可透過
 *       {@link SafeEventRegistry#getTrackedRegistrations()} 觀察）</li>
 * </ul>
 *
 * <h2>辨識語意</h2>
 * <p>{@link #equals} 採「全部欄位 equals」語意；正常情況下兩個不同的
 * {@code EventRegistration} instance 不會 equals，但若 listener 透過
 * {@link SafeEventListener#identity()} override 自定義 key，可能出現
 * equals 的情況（這通常表示「同樣的 listener 重複註冊」）。</p>
 *
 * @param <E>       對應的 Bukkit Event 子型別
 * @param registryId registry 內部的唯一識別
 * @param eventType  對應的 Bukkit Event 型別；不可為 null
 * @param listener   對應的 listener；不可為 null
 * @param identity   listener 的識別鍵；不可為 null
 * @param oneShot    是否為一次性 listener
 * @see SafeEventRegistry
 * @see SafeEventListener
 * @since 1.0.0
 */
public record EventRegistration<E extends Event>(
    long registryId,
    Class<E> eventType,
    SafeEventListener<E> listener,
    Object identity,
    boolean oneShot
) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查。
     *
     * @throws NullPointerException 當 {@code eventType} / {@code listener} / {@code identity} 為 null
     */
    public EventRegistration {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(identity, "identity");
    }

    /**
     * 此註冊是否為一次性 listener。
     *
     * @return {@link SafeEventListener#isOneShot()} 的快照值
     */
    public boolean isOneShot() {
        return oneShot;
    }
}