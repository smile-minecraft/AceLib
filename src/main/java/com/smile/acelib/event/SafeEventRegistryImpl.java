package com.smile.acelib.event;

import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * <h2>父類派送與 HandlerList 解析</h2>
 * <p>Bukkit 的註冊目標不是「呼叫端給的 event class」，而是由
 * {@code getRegistrationClass} 解析出的<strong>宣告 static
 * {@code getHandlerList()} 的最近類別</strong>：從 event class 自身往上找，
 * 遇到 {@link Event} 就停；找不到就擲 {@code IllegalPluginAccessException}。
 * 派送則只看 {@code event.getHandlers()}。本實作以相同規則運作，因此：</p>
 * <ul>
 *   <li>子類自帶 {@code getHandlerList()} → 獨立 HandlerList；父類 listener
 *       不會收到子類事件</li>
 *   <li>子類<strong>未</strong>自帶 {@code getHandlerList()}（常見的共用基底
 *       event 寫法）→ 與父類共用同一 HandlerList；父類 listener 會收到子類
 *       事件，且兩者的 bridge 必須合併為一個，否則同一事件會被派送兩次</li>
 *   <li>無法解析 HandlerList（含 interface、以及一路到 {@link Event} 都沒有
 *       static {@code getHandlerList()} 者）→ 以 {@code ACELIB-EVT-002}
 *       fail closed，不追蹤、不註冊</li>
 * </ul>
 *
 * <h2>同一 HandlerList 內的型別篩選</h2>
 * <p>共用 HandlerList 不等於同一 event class：共用基底 HandlerList 的兩組兄弟子類
 * 會被放進同一份 listener list。因此 dispatch 在呼叫前以
 * {@link Class#isInstance}（見 {@link #isApplicableTo}）逐一篩選：</p>
 *
 * <ul>
 *   <li>註冊父類 → 子類實例<strong>會</strong>命中（保留 Bukkit 的父類派送語意，
 *       不是精確等號比對）</li>
 *   <li>註冊兄弟子類 / 不相干型別 → <strong>不會</strong>被呼叫。不篩就會把事件餵給
 *       泛型擦除後才 cast 的 listener，在 listener 內部炸出
 *       {@link ClassCastException}（被記成 {@code ACELIB-EVT-001}），而且會把
 *       一次性 listener 誤消耗掉</li>
 * </ul>
 * <p>型別不符是共用 HandlerList 的正常結果（不代表錯誤），因此不記錄錯誤。</p>
 *
 * <p>abstract 與否<strong>不</strong>影響可否註冊：Bukkit 只看 static
 * {@code getHandlerList()}，因此自帶 HandlerList 的 abstract 基底 event 是
 * 合法且可用的註冊目標。</p>
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
 * {@link #byRegistrationClass} 與 {@link #bridgedRegistrationClasses} 採
 * {@link ConcurrentHashMap}。bridge 只向 Bukkit 註冊一次這件事由
 * {@link ConcurrentHashMap#newKeySet()} 的單次 {@code add} 原子決定，
 * 不依賴任何「per-list 已註冊旗標」——兩份狀態分開維護會在並行註冊時
 * 互相蓋掉而漏註冊。</p>
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
     * Key: Bukkit 解析出的註冊目標 class（最近宣告 static
     * {@code getHandlerList()} 的類別）。Value: 該 HandlerList 的 listener list。
     *
     * <p>刻意<strong>不以</strong>呼叫端給的 event class 當 key：多個 event
     * class 可能共用同一個 HandlerList（子類不自帶 {@code getHandlerList()}），
     * 那些 listener 在 Bukkit 裡本來就是同一個派送桶。共用同一 key 才能讓
     * bridge 只註冊一次。</p>
     *
     * <p>listener 全被移除後<strong>保留</strong>空 list（不從 map 移除），
     * 讓 bridge 狀態與 listener 生命週期解耦：dispatch 在 entries 為空時早退，
     * 重複註冊同一 HandlerList 則由 {@link #bridgedRegistrationClasses} 擋下。</p>
     */
    private final ConcurrentMap<Class<? extends Event>, RegistrationList> byRegistrationClass =
        new ConcurrentHashMap<>();
    /**
     * 已嘗試向 Bukkit PluginManager 註冊過 bridge 的註冊目標 class。
     *
     * <p>{@code add} 的回傳值就是「這一次要不要真的註冊」的唯一判準，因此
     * 多執行緒同時對同一 event type 註冊時只會有一個執行緒真的呼叫
     * {@code registerEvent}。</p>
     *
     * <p>與 {@link #byRegistrationClass} 分離：listener 清空不會讓此 set 失真，
     * 重複註冊不會再次把 bridge 塞進同一個 HandlerList。僅在
     * {@link #unregisterAll()} / {@link #onPluginDisable()}（兩者都真的呼叫
     * {@link HandlerList#unregisterAll(Listener)}）才清空。</p>
     */
    private final Set<Class<? extends Event>> bridgedRegistrationClasses =
        ConcurrentHashMap.newKeySet();
    /**
     * event class → 解析結果快取，避免每個事件都做一次反射。
     *
     * <p>值的可能形狀：解析到的註冊目標 class（{@link Class}）、或
     * {@link #UNRESOLVABLE} 哨兵。類別結構在執行期不會變動，所以負向結果也
     * 可以快取。成長上限是見過的 event class 數，與 registry 生命週期同壽命。</p>
     */
    private final ConcurrentMap<Class<? extends Event>, Object> registrationClassCache =
        new ConcurrentHashMap<>();
    /**
     * {@link #registrationClassCache} 的負向結果哨兵：無法解析 HandlerList。
     */
    private static final Object UNRESOLVABLE = new Object();
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

        Class<? extends Event> registrationClass = resolveRegistrationClass(eventType);
        if (registrationClass == null) {
            // HandlerList 無法解析 → fail closed：不追蹤、不向 Bukkit 註冊。
            // 仍回傳 handle，契約與 disabled / host-not-enabled 路徑一致
            // （handle 可用，但 listener 不會被 dispatch）。
            recorder.record(EventErrorRecord.cancelled(eventType, ERR_DISPATCH_FAILURE,
                "no Bukkit-registrable handler list for " + eventType.getName()
                    + ": no static HandlerList getHandlerList() declared on the type"
                    + " itself or its superclasses up to Event; listener will not be invoked"));
            return new EventRegistration<>(
                nextRegistryId.getAndIncrement(),
                eventType, listener, identity, listener.isOneShot());
        }
        RegistrationList list = byRegistrationClass.computeIfAbsent(
            registrationClass, RegistrationList::new);

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

        // 為此 HandlerList 註冊到 PluginManager（僅首次）
        ensureBridgeRegistered(eventType, registrationClass);
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
        RegistrationList list = lookupList(registration.eventType());
        if (list == null) {
            return;
        }
        // 以 registryId 識別移除（避免 listener identity 在 mutation 後改變）
        list.entries.removeIf(e -> e.reg.registryId() == registration.registryId());
        // 刻意不移除 RegistrationList、也不解除 Bukkit bridge：listener 清空後
        // dispatch 會在 entries.isEmpty() 時早退；bridge 留著才不會在移除全部
        // listener 後又把同一個 bridge 塞回同一 HandlerList（重複註冊 → 同一
        // 事件被派送多次）。真正解除 bridge 的時點是 unregisterAll() 與
        // onPluginDisable()，那兩者都會呼叫 HandlerList.unregisterAll。
    }

    @Override
    public void unregisterAll() {
        // 清空所有 listener list，但保留「bridge 已註冊」狀態以避免重複註冊；
        // 下面真的把 bridge 從 Bukkit 移除，兩者一起做才一致。
        for (RegistrationList list : byRegistrationClass.values()) {
            list.entries.clear();
        }
        byRegistrationClass.clear();
        bridgedRegistrationClasses.clear();
        registrationClassCache.clear();
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
        for (RegistrationList list : byRegistrationClass.values()) {
            sum += list.entries.size();
        }
        return sum;
    }

    @Override
    public List<EventRegistration<? extends Event>> getTrackedRegistrations() {
        List<EventRegistration<? extends Event>> snapshot = new ArrayList<>();
        for (RegistrationList list : byRegistrationClass.values()) {
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
        for (RegistrationList list : byRegistrationClass.values()) {
            list.entries.clear();
        }
        byRegistrationClass.clear();
        bridgedRegistrationClasses.clear();
        registrationClassCache.clear();
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
     * 取得已向 Bukkit PluginManager 註冊 bridge 的 HandlerList 數量（測試與診斷用）。
     *
     * <p>以「註冊目標」而非「呼叫端 event class」計數：共用同一 HandlerList
     * 的多個 event class 只算一個，數字才等於實際塞進 Bukkit 的 bridge 數。</p>
     *
     * @return 已註冊 bridge 的 HandlerList 數量
     */
    public int getRegisteredEventTypeCount() {
        return bridgedRegistrationClasses.size();
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
        RegistrationList list = lookupList(event.getClass());
        if (list == null || list.entries.isEmpty()) {
            return;
        }

        // CopyOnWriteArrayList 對迭代期間 mutation 安全；先 snapshot 一次避免重入時改變集合
        List<RegistrationEntry> snapshot = new ArrayList<>(list.entries);
        List<RegistrationEntry> toRemove = null;
        for (RegistrationEntry entry : snapshot) {
            EventRegistration<?> reg = entry.reg;
            SafeEventListener<?> listener = reg.listener();

            // 事件型別篩選：這份 list 是「同一 HandlerList」共用，不是「同一
            // event class」共用。共用 HandlerList 的兄弟子類會被放進同一份
            // list，若不逐一比對就直接呼叫，兄弟子類事件會餵給不相干的 listener，
            // 在 listener 內部以泛型擦除後的 cast 炸出 ClassCastException。
            //
            // 用 isInstance 而不是 equals：註冊父類的 listener 應命中子類實例
            // （Bukkit 的父類派送語意），只有「不是註冊型別或其子類」才跳過。
            if (!isApplicableTo(reg, event)) {
                continue;
            }

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
        }
    }

    /**
     * 取得 event type 對應的既有 {@link RegistrationList}；不建立新的。
     *
     * <p>dispatch 與 unregister 走這裡：dispatch 對「從未註冊過的 event
     * class」不建立空 list，避免長期運行時 map 隨見過的事件類別無界成長；
     * 同時也不記錄錯誤 —— 無法解析 HandlerList 的事件連註冊都不可能發生，
     * 在每次 dispatch 重複記錄只會淹沒真正需要看的紀錄。</p>
     *
     * @param eventType 事件實例的實際 class；不可為 null
     * @return 既有 list；沒有或無法解析時為 null
     */
    private RegistrationList lookupList(Class<? extends Event> eventType) {
        Class<? extends Event> registrationClass = resolveRegistrationClass(eventType);
        return registrationClass == null ? null : byRegistrationClass.get(registrationClass);
    }

    /**
     * 以與 Bukkit 相同規則解析 event type 的註冊目標 class。
     *
     * <p>對應 Bukkit {@code PluginManager#getRegistrationClass}：從自身往上找
     * 最近<strong>宣告</strong> static {@code getHandlerList()} 的類別，遇到
     * {@link Event} 就停。差異只有兩處，都以 fail closed 收斂（結果與 Bukkit
     * 擲 {@code IllegalPluginAccessException} 相同）：</p>
     * <ul>
     *   <li>Bukkit 不檢查 method 是否 static、也不檢查回傳型別；這裡要求
     *       static 且回傳 {@link HandlerList}，否則 Bukkit 會在
     *       {@code invoke} / cast 時失敗，與直接擲例外等價</li>
     * </ul>
     *
     * <p>結果（含負向結果）寫入 {@link #registrationClassCache}。</p>
     *
     * @param eventType 待解析的 event class；不可為 null
     * @return 註冊目標 class；無法解析時為 null
     */
    @SuppressWarnings("unchecked")
    private Class<? extends Event> resolveRegistrationClass(Class<? extends Event> eventType) {
        Object cached = registrationClassCache.get(eventType);
        if (cached != null) {
            return cached == UNRESOLVABLE ? null : (Class<? extends Event>) cached;
        }
        Class<? extends Event> resolved = lookUpRegistrationClass(eventType);
        registrationClassCache.put(eventType, resolved != null ? resolved : UNRESOLVABLE);
        return resolved;
    }

    private static Class<? extends Event> lookUpRegistrationClass(
            Class<? extends Event> eventType) {
        Class<?> current = eventType;
        while (current != null && current != Event.class) {
            try {
                Method declared = current.getDeclaredMethod("getHandlerList");
                boolean usable = Modifier.isStatic(declared.getModifiers())
                    && HandlerList.class.isAssignableFrom(declared.getReturnType());
                return usable ? asEventClass(current) : null;
            } catch (NoSuchMethodException notDeclaredHere) {
                // 自身沒有就往父類找，與 Bukkit 的向上搜尋一致
                current = current.getSuperclass();
            }
        }
        // 走到 Event 仍找不到：interface 的 getSuperclass() 為 null，
        // 也會在迴圈條件結束後落到這裡，兩者都對應 Bukkit 擲例外。
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> asEventClass(Class<?> candidate) {
        return (Class<? extends Event>) candidate;
    }

    /**
     * 為指定 HandlerList 註冊 bridge 到 Bukkit PluginManager；每個註冊目標只
     * 真正註冊一次。
     *
     * <p>「要不要註冊」只由 {@link #bridgedRegistrationClasses} 的單次
     * {@code add} 決定，因此並行註冊同一 event type 時只有一個執行緒會進來，
     * 不存在兩份狀態互相蓋掉而漏註冊的窗口。</p>
     *
     * <h2>Host plugin lifecycle 契約</h2>
     * <p>此方法<strong>不會</strong>主動呼叫 {@link PluginManager#enablePlugin}
     * 啟用 host plugin。Library 屬於被動元件，必須由 caller（後續插件）透過
     * 標準 Bukkit plugin lifecycle 自行管理 enabled 狀態；若 host plugin 尚未
     * enabled，本方法記錄 {@code ACELIB-EVT-006} 並跳過實際 registerEvent，
     * listener 不會被 Bukkit dispatch，但 {@link #register} 仍回傳 handle
     * （與既有 disable 路徑一致：handle 仍可用，但 listener 不會觸發）。</p>
     */
    private void ensureBridgeRegistered(Class<? extends Event> eventType,
                                        Class<? extends Event> registrationClass) {
        if (!bridgedRegistrationClasses.add(registrationClass)) {
            // 此 HandlerList 的 bridge 已經註冊過（或已嘗試過），不重複註冊，
            // 避免同一 bridgeListener 被加入同一 HandlerList 兩次而重複派送。
            return;
        }
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

    /**
     * 判斷某個 registration 是否適用於目前派送的事件實例。
     *
     * <p>註冊目標是共用同一 HandlerList 的 event class；判定用
     * {@link Class#isInstance}，保留「註冊父類 → 子類實例也命中」的 Bukkit
     * 語意，同時擋掉「兄弟子類 / 不相干型別」的誤呼叫。</p>
     *
     * <p>這裡不能只比對「註冊 class 完全相等」：共用基底 HandlerList 的典型寫法
     * 就是子類不自帶 {@code getHandlerList()}，父類 listener 應該收到子類事件
     * （既有測試 {@code subclassSharingParentHandlerList_hitsParentListener} 凍結
     * 這個語意）。</p>
     *
     * @param reg   registration handle
     * @param event 目前派送的事件實例
     * @return true 表示該 listener 應該收到這個事件
     */
    private static boolean isApplicableTo(EventRegistration<?> reg, Event event) {
        Class<? extends Event> declared = reg.eventType();
        return declared != null && declared.isInstance(event);
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
     * 單一 HandlerList 的 listener list。
     *
     * <p>{@link #registrationClass} 是產生這份 list 的 Bukkit 註冊目標，
     * 讓呼叫端不必重新解析就能把它交給 {@code ensureBridgeRegistered}。</p>
     */
    private static final class RegistrationList {
        final CopyOnWriteArrayList<RegistrationEntry> entries = new CopyOnWriteArrayList<>();
        final Class<? extends Event> registrationClass;

        RegistrationList(Class<? extends Event> registrationClass) {
            this.registrationClass = registrationClass;
        }
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