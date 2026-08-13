package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IntegrationRegistry 生命週期協調測試。
 *
 * <p>以測試內 {@link IntegrationAdapter} 手動實作（{@link ManualFakeAdapter}）隔離
 * registry 行為，不依賴任何具體外部插件 adapter。驗證 register / unregister /
 * initializeAll / shutdownAll / reload 的冪等、失敗隔離與「舊新不混用」契約。</p>
 */
@DisplayName("IntegrationRegistry")
class IntegrationRegistryTest {

    /** 手動實作冪等生命週期的測試 double；啟用失敗時拋 IllegalStateException。 */
    static final class ManualFakeAdapter implements IntegrationAdapter {
        private final String id;
        private final boolean failOnInitialize;
        private boolean active = false;
        private int initializeCalls = 0;
        private int shutdownCalls = 0;
        private IntegrationProbeResult status;

        ManualFakeAdapter(String id) {
            this(id, false);
        }

        ManualFakeAdapter(String id, boolean failOnInitialize) {
            this.id = id;
            this.failOnInitialize = failOnInitialize;
            this.status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' has not been initialized");
        }

        int initializeCalls() {
            return initializeCalls;
        }

        int shutdownCalls() {
            return shutdownCalls;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public IntegrationProbeResult getStatus() {
            return status;
        }

        @Override
        public void initialize() {
            if (active) {
                return;
            }
            initializeCalls++;
            if (failOnInitialize) {
                status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                    "adapter '" + id + "' failed to initialize");
                throw new IllegalStateException("init failed: " + id);
            }
            active = true;
            status = IntegrationProbeResult.of(IntegrationStatus.AVAILABLE,
                "adapter '" + id + "' initialized successfully");
        }

        @Override
        public void shutdown() {
            if (!active) {
                return;
            }
            shutdownCalls++;
            active = false;
            status = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' has been shut down");
        }
    }

    /** 將啟用 / 停用事件寫入共享順序列表，用於驗證 reload 的 shutdown-before-initialize。 */
    static final class OrderRecordingAdapter implements IntegrationAdapter {
        private final String id;
        private final List<String> order;
        private boolean active = false;

        OrderRecordingAdapter(String id, List<String> order) {
            this.id = id;
            this.order = order;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public IntegrationProbeResult getStatus() {
            return IntegrationProbeResult.of(IntegrationStatus.AVAILABLE, "ok: " + id);
        }

        @Override
        public void initialize() {
            if (active) {
                return;
            }
            order.add("init:" + id);
            active = true;
        }

        @Override
        public void shutdown() {
            if (!active) {
                return;
            }
            order.add("shutdown:" + id);
            active = false;
        }
    }

    // ----- register / unregister -----

    @Test
    @DisplayName("register 後 isRegistered 為 true 且 size 為 1")
    void register_storesAdapter_andIsRegistered() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new ManualFakeAdapter("vault"));
        assertTrue(registry.isRegistered("vault"));
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("register 重複 id 必須拋 IllegalArgumentException")
    void register_duplicateId_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new ManualFakeAdapter("vault"));
        assertThrows(IllegalArgumentException.class,
            () -> registry.register(new ManualFakeAdapter("vault")));
    }

    @Test
    @DisplayName("register(null) 必須拋 IllegalArgumentException")
    void register_null_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> registry.register(null));
    }

    @Test
    @DisplayName("unregister 移除 adapter；若已啟用則先停用")
    void unregister_removesAndShutsDownActive() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter adapter = new ManualFakeAdapter("vault");
        registry.register(adapter);
        registry.initializeAll();
        assertTrue(adapter.isActive());

        IntegrationAdapter removed = registry.unregister("vault");
        assertEquals(adapter, removed);
        assertFalse(registry.isRegistered("vault"));
        assertFalse(adapter.isActive(), "unregister 應先停用活躍 adapter");
        assertEquals(1, adapter.shutdownCalls());
    }

    @Test
    @DisplayName("unregister 未註冊 id 必須拋 IllegalArgumentException")
    void unregister_unknown_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> registry.unregister("missing"));
    }

    @Test
    @DisplayName("unregister(null) 必須拋 IllegalArgumentException")
    void unregister_null_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> registry.unregister(null));
    }

    // ----- initializeAll / shutdownAll -----

    @Test
    @DisplayName("initializeAll 啟用所有已註冊 adapter")
    void initializeAll_activatesAll() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter a = new ManualFakeAdapter("a");
        ManualFakeAdapter b = new ManualFakeAdapter("b");
        registry.register(a);
        registry.register(b);

        registry.initializeAll();

        assertTrue(a.isActive());
        assertTrue(b.isActive());
    }

    @Test
    @DisplayName("initializeAll 單一失敗隔離：失敗 adapter 不 active，其他仍 active")
    void initializeAll_failureIsolation_keepsOthersActive() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter ok = new ManualFakeAdapter("ok");
        ManualFakeAdapter bad = new ManualFakeAdapter("bad", true);
        registry.register(ok);
        registry.register(bad);

        registry.initializeAll();

        assertFalse(bad.isActive(), "失敗 adapter 不得保持 active");
        assertTrue(ok.isActive(), "其他 adapter 仍應啟用");
        assertEquals(IntegrationStatus.INIT_FAILED, bad.getStatus().status());
        assertNotNull(bad.getStatus().reason());
    }

    @Test
    @DisplayName("shutdownAll 停用所有啟用中的 adapter")
    void shutdownAll_deactivatesAll() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter a = new ManualFakeAdapter("a");
        ManualFakeAdapter b = new ManualFakeAdapter("b");
        registry.register(a);
        registry.register(b);
        registry.initializeAll();
        assertTrue(a.isActive());
        assertTrue(b.isActive());

        registry.shutdownAll();

        assertFalse(a.isActive());
        assertFalse(b.isActive());
    }

    // ----- reload -----

    @Test
    @DisplayName("reload 先 shutdown 舊 adapter 再 initialize 新 adapter（順序正確）")
    void reload_shutsDownOldBeforeInitializingNew() {
        IntegrationRegistry registry = new IntegrationRegistry();
        List<String> order = new ArrayList<>();
        OrderRecordingAdapter old = new OrderRecordingAdapter("old", order);
        registry.register(old);
        registry.initializeAll();
        assertTrue(old.isActive());

        OrderRecordingAdapter n1 = new OrderRecordingAdapter("new1", order);
        OrderRecordingAdapter n2 = new OrderRecordingAdapter("new2", order);
        registry.reload(List.of(n1, n2));

        assertEquals(List.of("init:old", "shutdown:old", "init:new1", "init:new2"), order,
            "reload 必須先 shutdown 舊、再 initialize 新（init:old 來自 reload 前的 initializeAll）");
        assertFalse(old.isActive(), "舊 adapter 不應仍啟用");
        assertTrue(n1.isActive());
        assertTrue(n2.isActive());
    }

    @Test
    @DisplayName("reload 失敗時：舊 registry 已清空、新成功者 active、新失敗者不 active（不混用）")
    void reload_failure_doesNotMixOldAndNew() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter old = new ManualFakeAdapter("old");
        registry.register(old);
        registry.initializeAll();
        assertTrue(old.isActive());

        ManualFakeAdapter newOk = new ManualFakeAdapter("newOk");
        ManualFakeAdapter newBad = new ManualFakeAdapter("newBad", true);
        registry.reload(List.of(newOk, newBad));

        assertFalse(registry.isRegistered("old"), "舊 adapter 不應仍存在於 registry");
        assertTrue(newOk.isActive(), "新成功 adapter 應啟用");
        assertFalse(newBad.isActive(), "新失敗 adapter 不得保持 active");
        assertEquals(IntegrationStatus.INIT_FAILED, newBad.getStatus().status());
        assertNotNull(newBad.getStatus().reason());
    }

    // ----- empty registry / boundaries -----

    @Test
    @DisplayName("空 registry 的 initializeAll 不拋出")
    void emptyRegistry_initializeAll_noThrow() {
        IntegrationRegistry registry = new IntegrationRegistry();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(registry::initializeAll);
    }

    @Test
    @DisplayName("空 registry 的 shutdownAll 不拋出")
    void emptyRegistry_shutdownAll_noThrow() {
        IntegrationRegistry registry = new IntegrationRegistry();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(registry::shutdownAll);
    }

    @Test
    @DisplayName("reload 空集合不拋出且清空 registry")
    void reload_empty_noThrow_andClears() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new ManualFakeAdapter("vault"));
        registry.initializeAll();
        assertTrue(registry.isRegistered("vault"));

        registry.reload(List.of());

        assertEquals(0, registry.size());
        assertFalse(registry.isRegistered("vault"));
    }

    @Test
    @DisplayName("getStatus 對未初始化 adapter 回傳非 null 的 INIT_FAILED 結果")
    void getStatus_uninitialized_returnsNonNullInitFailed() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new ManualFakeAdapter("vault"));
        IntegrationProbeResult result = registry.getStatus("vault");
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("getStatus 未知 id 必須拋 IllegalArgumentException")
    void getStatus_unknown_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.getStatus("missing"));
    }

    @Test
    @DisplayName("getStatus(null) 必須拋 IllegalArgumentException")
    void getStatus_null_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.getStatus(null));
    }

    @Test
    @DisplayName("isActive 未知 id 必須拋 IllegalArgumentException")
    void isActive_unknown_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.isActive("missing"));
    }

    @Test
    @DisplayName("getRegisteredIds 回傳目前註冊 id 的快照")
    void getRegisteredIds_returnsSnapshot() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.register(new ManualFakeAdapter("a"));
        registry.register(new ManualFakeAdapter("b"));
        Set<String> ids = registry.getRegisteredIds();
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertEquals(2, ids.size());
    }

    @Test
    @DisplayName("reload(null) 必須拋 IllegalArgumentException")
    void reload_null_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.reload(null));
    }

    @Test
    @DisplayName("reload 新集合內重複 id 必須拋 IllegalArgumentException")
    void reload_duplicateIds_throws() {
        IntegrationRegistry registry = new IntegrationRegistry();
        ManualFakeAdapter a1 = new ManualFakeAdapter("dup");
        ManualFakeAdapter a2 = new ManualFakeAdapter("dup");
        assertThrows(IllegalArgumentException.class, () -> registry.reload(List.of(a1, a2)));
    }
}
