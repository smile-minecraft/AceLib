package com.smile.acelib.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Phase 7（M-7-01 P0）整合測試：驗證 {@link AceLibEvents} 與
 * {@link AceLibPlugin} lifecycle 整合契約。
 *
 * <h2>覆蓋矩陣</h2>
 * <ul>
 *   <li>{@link AceLibEvents#bind(AceLibPlugin, SafeEventRegistry)} —
 *       綁定後 {@code boundTo} 回傳該 registry</li>
 *   <li>{@link AceLibEvents#unbind(AceLibPlugin)} — 解除綁定並呼叫
 *       bound registry 的 {@link SafeEventRegistry#onPluginDisable()}，
 *       HandlerList 真清理，重複 unbind idempotent</li>
 *   <li>{@code bind} 覆蓋既有 binding 時，舊 registry 必須被 disable
 *       （避免 listener leak）</li>
 *   <li>{@link AceLibPlugin#onDisable()} 自動呼叫
 *       {@link AceLibEvents#unbind(AceLibPlugin)}，確保 lifecycle 真正清理</li>
 *   <li>{@link AceLibPlugin#onDisable()} 重複呼叫 idempotent</li>
 *   <li>未 bind 的 plugin 呼叫 {@code unbind} 為 no-op</li>
 * </ul>
 *
 * <h2>AceLibEvents.Bindings 是 static 的注意事項</h2>
 * <p>{@link AceLibEvents.Bindings} 為 process-local static map，多個測試
 * class 共用；本測試在 {@code @AfterEach} 透過 reflection 呼叫
 * {@code Bindings.clear()} 以避免測試間污染既有測試的 binding 狀態。</p>
 *
 * @since Phase 7 (Plan §十二, M-7-01 P0)
 */
@DisplayName("Phase 7 Lifecycle Integration (AceLibEvents + AceLibPlugin)")
class Phase7LifecycleIntegrationTest {

    /**
     * 測試用 Event 子型別：MockBukkit 透過 reflection 探測 static
     * {@code getHandlerList()}。
     */
    public static final class ProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static final class NamedListener implements SafeEventListener<ProbeEvent> {
        private final String name;
        private final CopyOnWriteArrayList<String> received;

        NamedListener(String name, CopyOnWriteArrayList<String> received) {
            this.name = name;
            this.received = received;
        }

        @Override
        public Class<ProbeEvent> eventType() {
            return ProbeEvent.class;
        }

        @Override
        public void onEvent(ProbeEvent event) {
            received.add(name);
        }
    }

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeEventRegistryImpl registry;

    @BeforeEach
    void setUp() {
        clearBindingsStatic();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        server.getPluginManager().enablePlugin(plugin);
        registry = new SafeEventRegistryImpl(
            plugin,
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER)
        );
    }

    @AfterEach
    void tearDown() {
        // 解除綁定並清空 Bindings，避免污染其他測試 class
        try {
            AceLibEvents.unbind(plugin);
        } catch (Throwable ignore) {
            // unbind on unknown plugin 為 no-op，不應拋例外
        }
        clearBindingsStatic();
        if (registry != null && !registry.isDisabled()) {
            registry.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    /**
     * 透過 reflection 呼叫 package-private {@code AceLibEvents.Bindings.clear()}
     * 清空 process-local static bindings map；測試隔離必備。
     */
    private static void clearBindingsStatic() {
        try {
            Class<?> cls = Class.forName("com.smile.acelib.event.AceLibEvents$Bindings");
            java.lang.reflect.Method m = cls.getDeclaredMethod("clear");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Bindings.clear reflection failed: " + e, e);
        }
    }

    // =====================================================================
    // AceLibEvents bind/unbind 獨立契約
    // =====================================================================

    @Nested
    @DisplayName("AceLibEvents bind/unbind")
    class AceLibEventsBindUnbind {

        @Test
        @DisplayName("bind 後 boundTo 回傳同一 registry")
        void bind_setsBinding() {
            AceLibEvents.bind(plugin, registry);
            assertSame(registry, AceLibEvents.boundTo(plugin));
        }

        @Test
        @DisplayName("unbind 後 boundTo 回傳 null 且 registry 被 disable")
        void unbind_removesBindingAndDisablesRegistry() {
            AceLibEvents.bind(plugin, registry);
            assertSame(registry, AceLibEvents.boundTo(plugin));

            AceLibEvents.unbind(plugin);

            assertNull(AceLibEvents.boundTo(plugin),
                "unbind 後 boundTo 必須回傳 null");
            assertTrue(registry.isDisabled(),
                "unbind 必須呼叫 SafeEventRegistry.onPluginDisable() 標記 disabled");
        }

        @Test
        @DisplayName("unbind 後 HandlerList 上 listener 真被解除（M-7-01 P0）")
        void unbind_clearsHandlerList() {
            registry.register(ProbeEvent.class,
                new NamedListener("L1", new CopyOnWriteArrayList<>()));
            HandlerList hl = ProbeEvent.getHandlerList();
            assertTrue(hl.getRegisteredListeners().length > 0,
                "register 後 HandlerList 應有 listener");

            AceLibEvents.bind(plugin, registry);
            AceLibEvents.unbind(plugin);

            assertEquals(0, hl.getRegisteredListeners().length,
                "unbind 必須解除 HandlerList 上所有 bridge listener，"
                    + "確保 disable 後無重複觸發");
        }

        @Test
        @DisplayName("unbind 對未 bind 的 plugin 為 no-op,不丟例外")
        void unbind_unknownPlugin_isNoop() {
            assertDoesNotThrow(() -> AceLibEvents.unbind(plugin),
                "未 bind 的 plugin unbind 必須為 no-op,不丟例外");
            assertNull(AceLibEvents.boundTo(plugin));
        }

        @Test
        @DisplayName("unbind 重複呼叫 idempotent")
        void unbind_idempotent() {
            AceLibEvents.bind(plugin, registry);
            AceLibEvents.unbind(plugin);
            assertDoesNotThrow(() -> AceLibEvents.unbind(plugin),
                "重複 unbind 必須 idempotent,不丟例外");
            assertNull(AceLibEvents.boundTo(plugin));
            assertTrue(registry.isDisabled());
        }

        @Test
        @DisplayName("bind 覆蓋既有 binding：舊 registry 必須被 disable")
        void bind_overwritesPreviousAndDisablesOld() {
            SafeEventRegistryImpl old = new SafeEventRegistryImpl(
                plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
            SafeEventRegistryImpl next = new SafeEventRegistryImpl(
                plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
            try {
                AceLibEvents.bind(plugin, old);
                assertSame(old, AceLibEvents.boundTo(plugin));

                AceLibEvents.bind(plugin, next);

                assertSame(next, AceLibEvents.boundTo(plugin),
                    "第二次 bind 必須覆蓋舊 binding");
                assertTrue(old.isDisabled(),
                    "覆蓋既有 binding 時，舊 registry 必須被 disable"
                        + "（避免 listener leak）");
            } finally {
                // cleanup
                try { old.onPluginDisable(); } catch (Throwable ignore) {}
                try { next.onPluginDisable(); } catch (Throwable ignore) {}
            }
        }

        @Test
        @DisplayName("bind 同 plugin 同一 registry 連續兩次為 no-op,不重複 disable")
        void bind_sameInstance_isIdempotent() {
            AceLibEvents.bind(plugin, registry);
            // 第二次 bind 同一 instance：不應觸發 disable（previous == registry）
            AceLibEvents.bind(plugin, registry);
            assertSame(registry, AceLibEvents.boundTo(plugin));
            assertFalse(registry.isDisabled(),
                "bind 同一 instance 不應 disable registry");
        }

        @Test
        @DisplayName("null 參數契約：bind(plugin, null) 與 unbind(null) 必須丟 NPE")
        void bindAndUnbind_nullArgs_throwNPE() {
            assertNotNull(plugin);
            // bind plugin, null
            org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> AceLibEvents.bind(plugin, null));
            // bind null, registry
            org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> AceLibEvents.bind(null, registry));
            // unbind(null)
            org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> AceLibEvents.unbind(null));
            // boundTo(null)
            org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> AceLibEvents.boundTo(null));
        }
    }

    // =====================================================================
    // AceLibEvents.create(plugin) 自動 binding ownership
    // （推薦用法不再要求 caller 手動 bind）
    // =====================================================================

    @Nested
    @DisplayName("create(plugin) 自動納入 binding ownership")
    class CreatePluginAutoBinding {

        @Test
        @DisplayName("create(plugin) 後 boundTo 立刻回傳新建的 registry, 不需手動 bind")
        void create_returnsAutoBoundRegistry() {
            SafeEventRegistry created = AceLibEvents.create(plugin);

            assertNotNull(created);
            assertSame(created, AceLibEvents.boundTo(plugin),
                "create(plugin) 必須自動 binding 到 plugin 的 lifecycle,"
                    + "caller 不需額外呼叫 bind(plugin, registry)");
        }

        @Test
        @DisplayName("create(plugin) → register listener → onDisable 自動清理 create 路徑資源")
        void create_thenOnDisable_cleansListenerAndBinding() {
            SafeEventRegistry created = AceLibEvents.create(plugin);
            created.register(ProbeEvent.class,
                new NamedListener("L1", new CopyOnWriteArrayList<>()));
            HandlerList hl = ProbeEvent.getHandlerList();
            assertTrue(hl.getRegisteredListeners().length > 0,
                "前置:create(plugin) 後 register 應讓 HandlerList 有 listener");

            plugin.onDisable();

            assertNull(AceLibEvents.boundTo(plugin),
                "create(plugin) 路徑下 onDisable 必須自動呼叫 unbind");
            assertTrue(((SafeEventRegistryImpl) created).isDisabled(),
                "create(plugin) 路徑下 onDisable 必須 disable 對應 registry");
            assertEquals(0, hl.getRegisteredListeners().length,
                "create(plugin) 路徑下 HandlerList 必須被清理,不留 listener 殘留");
            assertFalse(plugin.isReady(),
                "onDisable 後 plugin.isReady() 必須為 false");
        }

        @Test
        @DisplayName("create(plugin) 連續呼叫 → 舊 registry 必須 disable,避免 listener leak")
        void create_twice_replacesBindingAndDisablesPrevious() {
            SafeEventRegistry first = AceLibEvents.create(plugin);
            first.register(ProbeEvent.class,
                new NamedListener("L1", new CopyOnWriteArrayList<>()));
            HandlerList hl = ProbeEvent.getHandlerList();
            assertTrue(hl.getRegisteredListeners().length > 0,
                "前置:first.register 後 HandlerList 應有 listener");

            SafeEventRegistry second = AceLibEvents.create(plugin);

            assertSame(second, AceLibEvents.boundTo(plugin),
                "第二次 create(plugin) 必須覆蓋既有 binding");
            assertNotSame(first, second,
                "create(plugin) 必須建立新 instance(而非重用舊 reference)");
            assertTrue(((SafeEventRegistryImpl) first).isDisabled(),
                "覆蓋既有 binding 時, 舊 registry 必須被 disable,"
                    + "避免 Bukkit HandlerList 留下 listener 殘留");
            assertEquals(0, hl.getRegisteredListeners().length,
                "覆蓋時舊 registry 對應的 HandlerList 必須一併清理");
            assertFalse(((SafeEventRegistryImpl) second).isDisabled(),
                "新建立的 registry 不應預設為 disabled");
        }

        @Test
        @DisplayName("create(plugin) → 手動 bind 同 instance → 為 no-op,不重複 disable")
        void create_thenManualBindSameInstance_isIdempotent() {
            SafeEventRegistry created = AceLibEvents.create(plugin);
            assertSame(created, AceLibEvents.boundTo(plugin));

            // 第二個 bind 對應同一 instance:previous == registry, bind 必須 no-op
            AceLibEvents.bind(plugin, created);

            assertSame(created, AceLibEvents.boundTo(plugin));
            assertFalse(((SafeEventRegistryImpl) created).isDisabled(),
                "create 後手動 bind 同 instance 不應 disable 已建立的 registry");
        }

        @Test
        @DisplayName("create(plugin) → unbind → 再 create(plugin):第二次仍正確綁定新 registry")
        void create_unbind_create_swapIsClean() {
            SafeEventRegistry first = AceLibEvents.create(plugin);
            assertSame(first, AceLibEvents.boundTo(plugin));

            AceLibEvents.unbind(plugin);
            assertNull(AceLibEvents.boundTo(plugin),
                "unbind 後 boundTo 必須為 null");
            assertTrue(((SafeEventRegistryImpl) first).isDisabled(),
                "unbind 後對應 registry 必須被 disable");

            SafeEventRegistry second = AceLibEvents.create(plugin);
            assertSame(second, AceLibEvents.boundTo(plugin),
                "再 create 後, boundTo 必須指向新建的 registry");
            assertNotSame(first, second);
            assertFalse(((SafeEventRegistryImpl) second).isDisabled(),
                "新建立的 registry 不應預設為 disabled");
        }

        @Test
        @DisplayName("create(plugin) + onDisable + 再 create(plugin):自動重新建立乾淨 lifecycle")
        void create_onDisable_recreate_cycleIsSafe() {
            SafeEventRegistry first = AceLibEvents.create(plugin);
            first.register(ProbeEvent.class,
                new NamedListener("OLD", new CopyOnWriteArrayList<>()));
            assertSame(first, AceLibEvents.boundTo(plugin));

            plugin.onDisable();
            assertNull(AceLibEvents.boundTo(plugin));
            assertTrue(((SafeEventRegistryImpl) first).isDisabled());

            // 模擬 reload 後:onEnable + create(plugin) 應建立乾淨 registry
            plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
            server.getPluginManager().enablePlugin(plugin);
            SafeEventRegistry second = AceLibEvents.create(plugin);

            assertSame(second, AceLibEvents.boundTo(plugin));
            assertNotSame(first, second);
            assertFalse(((SafeEventRegistryImpl) second).isDisabled(),
                "reload 後新建的 registry 必須未 disabled");

            // 再 disable 一次仍安全
            plugin.onDisable();
            assertTrue(((SafeEventRegistryImpl) second).isDisabled());
        }
    }

    // =====================================================================
    // AceLibPlugin lifecycle integration
    // =====================================================================

    @Nested
    @DisplayName("AceLibPlugin onDisable 自動呼叫 unbind")
    class AceLibPluginLifecycleIntegration {

        @Test
        @DisplayName("onDisable 解除 binding + 標記 registry disabled + HandlerList 清理")
        void onDisable_unbindsAndDisablesRegistry() {
            registry.register(ProbeEvent.class,
                new NamedListener("L1", new CopyOnWriteArrayList<>()));
            HandlerList hl = ProbeEvent.getHandlerList();
            assertTrue(hl.getRegisteredListeners().length > 0,
                "register 後 HandlerList 應有 listener");

            AceLibEvents.bind(plugin, registry);
            assertSame(registry, AceLibEvents.boundTo(plugin));

            plugin.onDisable();

            assertNull(AceLibEvents.boundTo(plugin),
                "onDisable 必須自動呼叫 AceLibEvents.unbind(this)，boundTo 回傳 null");
            assertTrue(registry.isDisabled(),
                "onDisable 必須傳遞到 SafeEventRegistry.onPluginDisable()");
            assertEquals(0, hl.getRegisteredListeners().length,
                "onDisable 必須清理 HandlerList 上所有 bridge listener（M-7-01 P0）");
            assertFalse(plugin.isReady(),
                "onDisable 後 plugin.isReady() 必須為 false");
        }

        @Test
        @DisplayName("onDisable 在未 bind 任何 registry 時仍安全")
        void onDisable_withoutBinding_isSafe() {
            // 沒有 bind 任何 registry
            assertDoesNotThrow(() -> plugin.onDisable(),
                "未 bind 的 plugin 呼叫 onDisable 必須不丟例外");
            assertFalse(plugin.isReady());
        }

        @Test
        @DisplayName("onDisable 重複呼叫 idempotent + 雙重 unbind 安全")
        void onDisable_repeated_isIdempotent() {
            AceLibEvents.bind(plugin, registry);
            plugin.onDisable();
            assertDoesNotThrow(() -> plugin.onDisable(),
                "重複 onDisable 必須 idempotent,不丟例外");
            // unbind 已 idempotent：boundTo 仍為 null
            assertNull(AceLibEvents.boundTo(plugin));
            assertTrue(registry.isDisabled());
        }

        @Test
        @DisplayName("AceLibPlugin 與 AceLibEvents.unbind 整合：bind → onDisable → unbind 二次呼叫 idempotent")
        void fullLifecycle_bindThenDisableThenUnbind() {
            // 1. bind
            AceLibEvents.bind(plugin, registry);
            assertSame(registry, AceLibEvents.boundTo(plugin));

            // 2. plugin disable 自動 unbind
            plugin.onDisable();
            assertNull(AceLibEvents.boundTo(plugin));
            assertTrue(registry.isDisabled());

            // 3. 再 unbind 一次（idempotent）
            assertDoesNotThrow(() -> AceLibEvents.unbind(plugin));
            assertNull(AceLibEvents.boundTo(plugin));
        }
    }
}
