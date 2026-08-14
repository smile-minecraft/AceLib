package com.smile.acelib.event;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link SafeEventRegistry} 的標準實作。
 *
 * <p>內含：</p>
 *
 * <ul>
 *   <li>註冊 / 解除 listener，綁定到 Bukkit {@link PluginManager}</li>
 *   <li>一次性 listener：首次 dispatch 後自動解除</li>
 *   <li>重複註冊（同 {@link SafeEventListener#identity()}）→ 回傳原 registration
 *       並記錄 {@code ACELIB-EVT-003}</li>
 *   <li>handler 內部拋錯以 {@code ACELIB-EVT-001} 紀錄，但不影響其他 listener</li>
 *   <li>{@link #onPluginDisable()} 解除所有 listener 並呼叫
 *       {@link HandlerList#unregisterAll(Listener)} 解除 Bukkit 註冊，
 *       同時標記 disabled；後續註冊走 no-op 並記錄 {@code ACELIB-EVT-004}</li>
 *   <li>Folia 環境下 {@link ListenerPolicy#REQUIRES_REGION} listener 若不在
 *       region thread → 略過並記錄 {@code ACELIB-EVT-005}</li>
 *   <li>Host plugin 尚未 enabled 時，跳過
 *       {@link PluginManager#registerEvent} 並記錄 {@code ACELIB-EVT-006}；
 *       library <strong>不會</strong>主動呼叫
 *       {@link PluginManager#enablePlugin} 啟用 host plugin</li>
 * </ul>
 *
 * <h2>錯誤代碼一覽</h2>
 * <ul>
 *   <li>{@code ACELIB-EVT-001} — listener handler 內部拋 exception</li>
 *   <li>{@code ACELIB-EVT-002} — Event class 註冊到 PluginManager 失敗（dispatch 失敗）</li>
 *   <li>{@code ACELIB-EVT-003} — 重複註冊（已存在的 identity）</li>
 *   <li>{@code ACELIB-EVT-004} — 插件停用（disabled 後 register / dispatch）</li>
 *   <li>{@code ACELIB-EVT-005} — Folia 環境下 REQUIRES_REGION listener 在錯誤 context</li>
 *   <li>{@code ACELIB-EVT-006} — Host plugin 尚未 enabled</li>
 * </ul>
 *
 * <h2>與 Bukkit PluginManager 整合</h2>
 * <p>本實作建立一個內部 {@link BridgeListener}（implements {@link Listener}
 * 但無任何 {@code @EventHandler} 方法），並透過
 * {@link PluginManager#registerEvent(Class, Listener, EventPriority, EventExecutor, org.bukkit.plugin.Plugin)}
 * 為每個 eventType 註冊一次 executor；executor 委派給本實作的
 * {@link #dispatch(Event)} 方法，內部依 listener list 逐一呼叫。</p>
 *
 * <h2>Host plugin lifecycle 邊界</h2>
 * <p>本實作<strong>不會</strong>呼叫 {@link PluginManager#enablePlugin} 啟用
 * host plugin。Library 屬於被動元件；host plugin 的 enabled 狀態由 caller
 * 透過標準 Bukkit plugin lifecycle 管理。若 host plugin 尚未 enabled，
 * {@link #register} 仍回傳 handle（契約與 disabled 路徑一致）但 listener
 * 不會被 dispatch，並記錄 {@code ACELIB-EVT-006}。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆可在多 region 並行環境下使用。listener list
 * 採 {@link CopyOnWriteArrayList}（dispatch 期間 mutation-safe）；
 * byEventType map 採 {@link ConcurrentHashMap}。</p>
 *
 * @see SafeEventRegistry
 * @since 1.0.0
 */
public final class SafeEventRegistryImpl implements SafeEventRegistry {

    // 錯誤代碼
    static final String ERR_HANDLER_EXCEPTION = "ACELIB-EVT-001";
    static final String ERR_DISPATCH_FAILURE = "ACELIB-EVT-002";
    static final String ERR_DUPLICATE_REGISTRATION = "ACELIB-EVT-003";
    static final String ERR_PLUGIN_DISABLED = "ACELIB-EVT-004";
    static final String ERR_POLICY_UNSAFE = "ACELIB-EVT-005";
    /**
     * Host plugin 尚未 enabled 時的拒絕代碼。
     *
     * <p>{@link #registerToBukkit} 為了避免 library 主動啟用 host plugin
     * （{@code pm.enablePlugin(plugin)} 是 plugin lifecycle 副作用，library
     * 不可繞過），當 {@link JavaPlugin#isEnabled()} 回傳 false 時記錄此代碼
     * 並跳過實際的 {@link PluginManager#registerEvent} 呼叫。</p>
     */
    static final String ERR_HOST_PLUGIN_NOT_ENABLED = "ACELIB-EVT-006";

    private final JavaPlugin plugin;
    private final Platform platform;
    private final PlatformCapability capability;
    private final EventErrorRecorder recorder = new EventErrorRecorder();
    private final BridgeListener bridgeListener = new BridgeListener();
    /**
     * Key: eventType class. Value: per-eventType listener list + 已註冊到
     * Bukkit PluginManager 的標記。
     */
    private final ConcurrentMap<Class<? extends Event>, RegistrationList> byEventType =
        new ConcurrentHashMap<>();
    private final AtomicLong nextRegistryId = new AtomicLong(1L);
    private volatile boolean disabled = false;

    /**
     * 建構子。
     *
     * @param plugin     listener owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @throws NullPointerException 當任一參數為 null
     */
    public SafeEventRegistryImpl(JavaPlugin plugin,
                                 Platform platform,
                                 PlatformCapability capability) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    // -----------------------------------------------------------------
    // SafeEventRegistry 方法
    // -----------------------------------------------------------------

    @Override
    public <E extends Event> EventRegistration<E> register(Class<E> eventType,
                                                            SafeEventListener<E> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        Object identity = listener.identity();
        Objects.requireNonNull(identity, "listener.identity()");

        if (disabled) {
            recorder.record(EventErrorRecord.cancelled(eventType, ERR_PLUGIN_DISABLED,
                "register called on disabled registry; listener will not be invoked"));
            // 仍回傳 handle 但 listener 不會被 dispatch（dispatch 入口檢查）
            return new EventRegistration<>(
                nextRegistryId.getAndIncrement(),
                eventType, listener, identity, listener.isOneShot());
        }

        RegistrationList list = byEventType.computeIfAbsent(eventType, k -> new RegistrationList());

        // 重複註冊偵測（以 identity() equals 為準）
        for (RegistrationEntry e : list.entries) {
            if (e.reg.identity().equals(identity)) {
                recorder.record(EventErrorRecord.cancelled(eventType, ERR_DUPLICATE_REGISTRATION,
                    "duplicate listener registration: identity=" + identity
                        + ", eventType=" + eventType.getName()));
                @SuppressWarnings("unchecked")
                EventRegistration<E> existing = (EventRegistration<E>) e.reg;
                return existing;
            }
        }

        EventRegistration<E> reg = new EventRegistration<>(
            nextRegistryId.getAndIncrement(),
            eventType, listener, identity, listener.isOneShot());
        list.entries.add(new RegistrationEntry(reg));

        // 為此 eventType 註冊到 PluginManager（僅首次）
        registerToBukkit(eventType, list);
        return reg;
    }

    @Override
    public <E extends Event> EventRegistration<E> registerOneShot(Class<E> eventType,
                                                                   SafeEventListener<E> listener) {
        // 包裝 listener：強制 isOneShot = true，且不改變 listener 本身（避免 mutate user object）
        SafeEventListener<E> wrapped = new OneShotWrapper<>(listener);
        return register(eventType, wrapped);
    }

    @Override
    public void unregister(EventRegistration<? extends Event> registration) {
        Objects.requireNonNull(registration, "registration");
        RegistrationList list = byEventType.get(registration.eventType());
        if (list == null) {
            return;
        }
        // 以 registryId 識別移除（避免 listener identity 在 mutation 後改變）
        boolean removed = list.entries.removeIf(e -> e.reg.registryId() == registration.registryId());
        if (removed && list.entries.isEmpty()) {
            byEventType.remove(registration.eventType(), list);
            // 不主動 unregister from Bukkit PluginManager；讓 dispatcher 走 no-op
        }
    }

    @Override
    public void unregisterAll() {
        // 清空所有 listener list，但保留「已註冊到 Bukkit」的狀態以避免重複註冊
        for (RegistrationList list : byEventType.values()) {
            list.entries.clear();
        }
        byEventType.clear();
        // 主動解除 Bukkit PluginManager 上的 bridgeListener
        HandlerList.unregisterAll(bridgeListener);
    }

    @Override
    public List<EventErrorRecord> getRecentErrors(int max) {
        return recorder.getRecentErrors(max);
    }

    @Override
    public int getTrackedRegistrationCount() {
        int sum = 0;
        for (RegistrationList list : byEventType.values()) {
            sum += list.entries.size();
        }
        return sum;
    }

    @Override
    public List<EventRegistration<? extends Event>> getTrackedRegistrations() {
        List<EventRegistration<? extends Event>> snapshot = new ArrayList<>();
        for (RegistrationList list : byEventType.values()) {
            for (RegistrationEntry e : list.entries) {
                snapshot.add(e.reg);
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public void onPluginDisable() {
        if (disabled) {
            return; // idempotent
        }
        disabled = true;
        // onPluginDisable 必須真正解除 Bukkit HandlerList 上的
        // bridgeListener，不能只靠 dispatch 入口拒絕觸發 + EVT-004。
        //
        // 動機：
        // 1. 若保留 bridgeListener，下一輪 onEnable / 新一輪 listener 重新
        //    register 到相同 eventType 時，BridgeListener 會被重複加入
        //    HandlerList，導致 dispatch 被呼叫兩次；同時 Bukkit 內部對
        //    同一 listener instance 重複註冊可能丟 IllegalStateException。
        // 2. 「disable / reload 後無重複觸發」要求 listener
        //    與 Bukkit HandlerList 完全解除（不留殘留）。
        //
        // 行為分離：
        // - 清空 listener list（byEventType）：確保 listener 不再被觸發
        // - HandlerList.unregisterAll(bridgeListener)：解除 Bukkit 註冊
        // - 入口 disabled 檢查保留為防禦性雙保險（即使 HandlerList 還殘留，
        //   dispatch 入口仍會早退並記錄 EVT-004）
        for (RegistrationList list : byEventType.values()) {
            list.entries.clear();
        }
        byEventType.clear();
        HandlerList.unregisterAll(bridgeListener);
        // recorder.clear() — 保留紀錄供診斷使用；不主動清空避免遮蓋跨 disable 的錯誤
    }

    // -----------------------------------------------------------------
    // 對外診斷輔助
    // -----------------------------------------------------------------

    /**
     * 取得內部錯誤紀錄器（供進階診斷使用）。
     *
     * @return 內部 {@link EventErrorRecorder}；永遠不為 null
     */
    public EventErrorRecorder getRecorder() {
        return recorder;
    }

    /**
     * 取得偵測到的平台（建構時傳入）。
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * 取得當前使用的 capability profile。
     */
    public PlatformCapability getCapability() {
        return capability;
    }

    /**
     * 取得已註冊到 Bukkit PluginManager 的 event type 數量（測試與診斷用）。
     */
    public int getRegisteredEventTypeCount() {
        int sum = 0;
        for (RegistrationList list : byEventType.values()) {
            if (list.registeredToBukkit) {
                sum++;
            }
        }
        return sum;
    }

    // -----------------------------------------------------------------
    // 內部 dispatch 邏輯
    // -----------------------------------------------------------------

    /**
     * 由 {@link EventExecutor} 委派呼叫的統一 dispatch 入口。
     *
     * @param event Bukkit 觸發的事件；不可為 null
     */
    void dispatch(Event event) {
        Objects.requireNonNull(event, "event");
        if (disabled) {
            recorder.record(EventErrorRecord.cancelled(event.getClass(), ERR_PLUGIN_DISABLED,
                "dispatch called on disabled registry for " + event.getClass().getName()));
            return;
        }
        RegistrationList list = byEventType.get(event.getClass());
        if (list == null || list.entries.isEmpty()) {
            return;
        }

        // CopyOnWriteArrayList 對迭代期間 mutation 安全；先 snapshot 一次避免重入時改變集合
        List<RegistrationEntry> snapshot = new ArrayList<>(list.entries);
        List<RegistrationEntry> toRemove = null;
        for (RegistrationEntry entry : snapshot) {
            EventRegistration<?> reg = entry.reg;
            SafeEventListener<?> listener = reg.listener();

            // Folia 上下文安全檢查
            if (shouldSkipForPolicy(event.getClass(), reg)) {
                recorder.record(EventErrorRecord.cancelled(event.getClass(), ERR_POLICY_UNSAFE,
                    "listener " + listenerClassName(listener) + " requires region thread"
                        + " but current context is not FOLIA_REGION; platform=" + platform));
                continue;
            }

            // 呼叫 listener；錯誤被捕獲
            try {
                invokeListener(listener, event);
            } catch (Throwable t) {
                recorder.record(EventErrorRecord.threw(event.getClass(), ERR_HANDLER_EXCEPTION,
                    "listener " + listenerClassName(listener)
                        + " threw exception: " + safeMessage(t), t));
                // 不丟出，避免影響 Bukkit 其他 listener（同一 event 可能有多個 plugin 註冊）
            }

            // 一次性 listener 標記為待移除
            if (reg.oneShot()) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(entry);
            }
        }

        // 一次性 listener 移除（從原始 list；用 registryId 比對避免 race）
        if (toRemove != null && !toRemove.isEmpty()) {
            for (RegistrationEntry r : toRemove) {
                list.entries.removeIf(e -> e.reg.registryId() == r.reg.registryId());
            }
            if (list.entries.isEmpty()) {
                byEventType.remove(event.getClass(), list);
            }
        }
    }

    /**
     * 為指定 eventType 註冊到 Bukkit PluginManager；僅首次註冊會真正呼叫
     * {@link PluginManager#registerEvent}，後續呼叫為 no-op。
     *
     * <h2>Host plugin lifecycle 契約</h2>
     * <p>此方法<strong>不會</strong>主動呼叫 {@link PluginManager#enablePlugin}
     * 啟用 host plugin。Library 屬於被動元件，必須由 caller（後續插件）透過
     * 標準 Bukkit plugin lifecycle 自行管理 enabled 狀態；若 host plugin 尚未
     * enabled，本方法記錄 {@code ACELIB-EVT-006} 並跳過實際 registerEvent，
     * listener 不會被 Bukkit dispatch，但 {@link #register} 仍回傳 handle
     * （與既有 disable 路徑一致：handle 仍可用，但 listener 不會觸發）。</p>
     */
    private void registerToBukkit(Class<? extends Event> eventType, RegistrationList list) {
        if (list.registeredToBukkit) {
            return;
        }
        synchronized (list) {
            if (list.registeredToBukkit) {
                return;
            }
            // 標記為已嘗試註冊，避免 host-not-enabled 路徑重複記錄 EVT-006
            list.registeredToBukkit = true;
            // 不主動呼叫 pm.enablePlugin(plugin) 啟用 host plugin：library 不可
            // 繞過 Bukkit plugin lifecycle；若 host plugin
            // 尚未 enabled，記錄 EVT-006 並跳過實際的 pm.registerEvent 呼叫。
            if (!plugin.isEnabled()) {
                recorder.record(EventErrorRecord.cancelled(eventType, ERR_HOST_PLUGIN_NOT_ENABLED,
                    "registerEvent skipped: host plugin " + plugin.getName()
                        + " is not enabled; library will not auto-enable it"));
                return;
            }
            try {
                PluginManager pm = plugin.getServer().getPluginManager();
                // EventExecutor 介面在現代 Paper 仍是 functional interface；
                // 但為了相容不同 Bukkit/Paper 變體，採明確 inner class 而非 lambda。
                EventExecutor executor = new EventExecutor() {
                    @Override
                    public void execute(Listener listener, Event event) throws EventException {
                        try {
                            dispatch(event);
                        } catch (Throwable t) {
                            throw new EventException(t);
                        }
                    }
                };
                pm.registerEvent(eventType, bridgeListener, EventPriority.NORMAL, executor, plugin);
            } catch (Throwable t) {
                // PluginManager 註冊失敗（Folia API 不存在、class 不在 event bus 等）
                recorder.record(EventErrorRecord.threw(eventType, ERR_DISPATCH_FAILURE,
                    "registerEvent failed for " + eventType.getName() + ": " + safeMessage(t), t));
                // 不丟出，避免後續插件無法繼續運作；listener 不會被 dispatch 但 handle 仍回傳
            }
        }
    }

    /**
     * Folia 環境下檢查 listener 是否應在當前 context 被略過。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>listener policy = UNCONSTRAINED：永遠不被略過</li>
     *   <li>listener policy = REQUIRES_REGION 且 platform != FOLIA：等同 UNCONSTRAINED</li>
     *   <li>listener policy = REQUIRES_REGION 且 platform = FOLIA：
     *       透過 {@link com.smile.acelib.context.ContextInspector} 推導當前 context；
     *       若為 FOLIA_REGION 允許，否則略過</li>
     * </ul>
     */
    private boolean shouldSkipForPolicy(Class<? extends Event> eventType,
                                        EventRegistration<?> reg) {
        if (reg.listener().policy() != ListenerPolicy.REQUIRES_REGION) {
            return false;
        }
        if (platform != Platform.FOLIA) {
            return false;
        }
        com.smile.acelib.context.ThreadContext ctx =
            com.smile.acelib.context.ContextInspector.currentContext(platform);
        return ctx != com.smile.acelib.context.ThreadContext.FOLIA_REGION;
    }

    /**
     * Raw-type 呼叫 listener 以繞過泛型 erasure（SafeEventListener 介面契約保證
     * onEvent 接受其泛型 E 的 event）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void invokeListener(SafeEventListener listener, Event event) throws Exception {
        listener.onEvent(event);
    }

    private static String listenerClassName(SafeEventListener<?> listener) {
        // lambda 沒有 className；以 "lambda" 表示；具名 class 取 simpleName
        Class<?> c = listener.getClass();
        String simple = c.getSimpleName();
        if (simple.isEmpty() || simple.contains("$")) {
            return "lambda<" + c.getName() + ">";
        }
        return simple;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "(null throwable)";
        }
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /**
     * 統一 logger 介面（保留以供未來擴展，目前透過 recorder 紀錄；不使用 JUL 避免污染測試）。
     */

    // -----------------------------------------------------------------
    // 內部類型
    // -----------------------------------------------------------------

    /**
     * 內部 Listener，用於註冊到 Bukkit PluginManager；本身不宣告任何
     * {@code @EventHandler} 方法，所有 dispatch 透過 {@link EventExecutor} 委派。
     */
    private static final class BridgeListener implements Listener {
        // intentionally empty
    }

    /**
     * per-eventType 的 listener list + 已註冊標記。
     */
    private static final class RegistrationList {
        final CopyOnWriteArrayList<RegistrationEntry> entries = new CopyOnWriteArrayList<>();
        volatile boolean registeredToBukkit = false;
    }

    /**
     * 一個 registry entry 的內部封裝。
     */
    private static final class RegistrationEntry {
        final EventRegistration<?> reg;

        RegistrationEntry(EventRegistration<?> reg) {
            this.reg = reg;
        }
    }

    /**
     * 一次性 listener 包裝：保留原 listener reference，但 {@link #isOneShot()} 強制 true。
     *
     * <p>不修改原 listener 物件（避免 mutate user-supplied instance），僅在
     * registry 內部以 wrapper 形式運作；{@link EventRegistration#identity()}
     * 仍透傳原 listener 的 identity 以維持重複偵測語意。</p>
     */
    private static final class OneShotWrapper<E extends Event> implements SafeEventListener<E> {

        private final SafeEventListener<E> delegate;

        OneShotWrapper(SafeEventListener<E> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void onEvent(E event) throws Exception {
            delegate.onEvent(event);
        }

        @Override
        public Class<E> eventType() {
            return delegate.eventType();
        }

        @Override
        public ListenerPolicy policy() {
            return delegate.policy();
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public Object identity() {
            return delegate.identity();
        }
    }
}