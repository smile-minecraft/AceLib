package com.smile.acelib.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * M-7-01 P1 補強：獨立測試 host plugin 尚未 enabled 時
 * {@link SafeEventRegistryImpl} 的 registerToBukkit 必須走
 * {@code ACELIB-EVT-006} 短路，不主動呼叫 {@code pm.enablePlugin(plugin)}。
 *
 * <p>獨立 class 的原因：本測試需要在 setUp 階段建立一個「未 enabled」的
 * AceLibPlugin instance 作為 host；MockBukkit 的
 * {@link org.mockbukkit.mockbukkit.PluginManagerMock#loadPlugin(Class)}
 * 不會自動呼叫 {@code enablePlugin}，因此
 * {@code plugin.isEnabled() == false}。既有
 * {@link SafeEventRegistryImplTest} 的 {@code @BeforeEach} 已將 plugin
 * 設為 enabled，無法在同一 class 內重設又不破壞
 * AceLibPlugin.ready 旗標，故獨立。</p>
 *
 * <h2>為何不使用 reflection 改 JavaPlugin 內部 enabled 欄位</h2>
 * <p>MockBukkit 4.113.1 的 {@code JavaPlugin} 內部欄位名稱並非 {@code enabled}，
 * 反射注入脆弱且不可移植；改用「loadPlugin 不 enable」建立 fixture 更可靠，
 * 也更貼近 production 真實路徑（後續插件於 onEnable 中 register 時 host
 * plugin 必然已 enabled）。</p>
 *
 * @since Phase 7 (Plan §十二, M-7-01 P1)
 */
@DisplayName("HostPluginNotEnabled (M-7-01 P1)")
class HostPluginNotEnabledTest {

    /**
     * 測試用 Event 子型別：MockBukkit 4.113.1 的
     * {@code PluginManagerMock.registerEvent} 透過 reflection 探測
     * {@code getHandlerList()}（static）以取得 HandlerList；缺少此方法會
     * 導致 registerEvent 拋 IllegalPluginAccessException。
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

    /**
     * 簡單 listener：記錄被 dispatch 次數。
     */
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

        @Override
        public String toString() {
            return "NamedListener{" + name + "}";
        }
    }

    private ServerMock server;
    private AceLibPlugin plugin;
    private SafeEventRegistryImpl registry;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // MockBukkit loadPlugin 不會 enable plugin；production registerToBukkit
        // 必須走 EVT-006 短路，不可自動呼叫 pm.enablePlugin(plugin)。
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertFalse(plugin.isEnabled(),
            "前置：loadPlugin 後 host plugin 必須尚未 enabled");
        registry = new SafeEventRegistryImpl(
            plugin,
            Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER)
        );
    }

    @AfterEach
    void tearDown() {
        if (registry != null && !registry.isDisabled()) {
            registry.onPluginDisable();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("register 仍回 handle, fire 後 listener 不被 dispatch, 記錄 EVT-006")
    void register_recordsEvt006_andSkipsDispatch() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        EventRegistration<ProbeEvent> reg = registry.register(
            ProbeEvent.class, new NamedListener("L1", received));
        assertNotNull(reg,
            "M-7-01 P1：register 仍回 handle（與 disable 路�契約一致）");

        // HandlerList 上不應出現 bridge listener（因 registerToBukkit 已提早 return）
        HandlerList hl = ProbeEvent.getHandlerList();
        assertEquals(0, hl.getRegisteredListeners().length,
            "host-not-enabled 路徑不得向 Bukkit 註冊 listener");

        // fire 後 listener 不被 dispatch
        server.getPluginManager().callEvent(new ProbeEvent());
        assertTrue(received.isEmpty(),
            "host plugin 未 enabled 時 listener 不應被 dispatch");

        // 必須記錄 EVT-006，不可記錄 EVT-002（dispatch failure）
        assertTrue(registry.getRecorder().contains("ACELIB-EVT-006"),
            "host plugin 未 enabled 必須記錄 ACELIB-EVT-006");
        assertFalse(registry.getRecorder().contains("ACELIB-EVT-002"),
            "host-not-enabled 必須走 EVT-006 短路，不得記錄 EVT-002");
    }

    @Test
    @DisplayName("同 eventType 重複 register 仍走 EVT-006, HandlerList 不留殘留")
    void duplicateRegister_sameEventType_doesNotPolluteHandlerList() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        registry.register(ProbeEvent.class, new NamedListener("L1", received));
        registry.register(ProbeEvent.class, new NamedListener("L2", received));

        HandlerList hl = ProbeEvent.getHandlerList();
        assertEquals(0, hl.getRegisteredListeners().length,
            "host-not-enabled 短路必須 idempotent：同 eventType 重複 register 不得累積 listener");

        // EVT-006 至少記錄一次（第二次 register 走相同短路，但因 list.registeredToBukkit
        // 已是 true，會在 registerToBukkit 入口提早 return，不再記錄新 EVT-006；
        // 但第一次 register 已記錄 EVT-006，recorder 仍 contains 該 code）
        assertTrue(registry.getRecorder().contains("ACELIB-EVT-006"),
            "第一次 register 必須記錄 EVT-006");
    }

    @Test
    @DisplayName("host-not-enabled 短路後 disable registry 仍安全（不丟例外）")
    void disableAfterHostNotEnabled_isSafe() {
        registry.register(ProbeEvent.class, new NamedListener("L1", new CopyOnWriteArrayList<>()));
        // disable 必須 idempotent 且不丟例外，即使 host 從未 enabled
        registry.onPluginDisable();
        assertTrue(registry.isDisabled());
        // 第二次 disable 仍 idempotent
        registry.onPluginDisable();
        assertTrue(registry.isDisabled());
    }
}
