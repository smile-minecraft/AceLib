package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IntegrationAdapter 冪等生命週期測試（以 package-private
 * {@link AbstractIntegrationAdapter} 基底實作驗證）。
 *
 * <p>驗證 initialize / shutdown / isActive 的冪等契約、啟用失敗時不得保持 active
 * 且失敗原因可經由 {@link #getStatus()} 取得、以及未初始化狀態回傳非 null 的
 * INIT_FAILED 結果。</p>
 */
@DisplayName("IntegrationAdapter lifecycle")
class IntegrationAdapterTest {

    /** 具體 adapter 測試 double：可設定啟用失敗。 */
    static final class LifecycleFake extends AbstractIntegrationAdapter {
        private final boolean fail;
        int initCalls = 0;
        int shutdownCalls = 0;

        LifecycleFake(String id) {
            this(id, false);
        }

        LifecycleFake(String id, boolean fail) {
            super(id);
            this.fail = fail;
        }

        @Override
        protected IntegrationProbeResult doInitialize() throws Exception {
            initCalls++;
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return IntegrationProbeResult.of(IntegrationStatus.AVAILABLE,
                "lifecycle fake initialized: " + getId());
        }

        @Override
        protected void doShutdown() {
            shutdownCalls++;
        }
    }

    @Test
    @DisplayName("正常 initialize 後 isActive 為 true 且狀態為 AVAILABLE")
    void normalInitialize_activeAndAvailable() {
        LifecycleFake adapter = new LifecycleFake("vault");
        adapter.initialize();
        assertTrue(adapter.isActive());
        assertEquals(IntegrationStatus.AVAILABLE, adapter.getStatus().status());
        assertNotNull(adapter.getStatus().reason());
    }

    @Test
    @DisplayName("重複 initialize 為冪等：doInitialize 只執行一次")
    void initializeTwice_idempotent() {
        LifecycleFake adapter = new LifecycleFake("vault");
        adapter.initialize();
        adapter.initialize();
        assertEquals(1, adapter.initCalls, "重複 initialize 不應再次執行 doInitialize");
        assertTrue(adapter.isActive());
    }

    @Test
    @DisplayName("shutdown 後 isActive 為 false")
    void shutdown_deactivates() {
        LifecycleFake adapter = new LifecycleFake("vault");
        adapter.initialize();
        assertTrue(adapter.isActive());
        adapter.shutdown();
        assertFalse(adapter.isActive());
    }

    @Test
    @DisplayName("重複 shutdown 為冪等：doShutdown 只執行一次")
    void shutdownTwice_idempotent() {
        LifecycleFake adapter = new LifecycleFake("vault");
        adapter.initialize();
        adapter.shutdown();
        adapter.shutdown();
        assertEquals(1, adapter.shutdownCalls, "重複 shutdown 不應再次執行 doShutdown");
        assertFalse(adapter.isActive());
    }

    @Test
    @DisplayName("shutdown 後再次 initialize 可重新啟用（doInitialize 第二次執行）")
    void initializeAfterShutdown_reinitializes() {
        LifecycleFake adapter = new LifecycleFake("vault");
        adapter.initialize();
        adapter.shutdown();
        assertFalse(adapter.isActive());
        adapter.initialize();
        assertEquals(2, adapter.initCalls);
        assertTrue(adapter.isActive());
    }

    @Test
    @DisplayName("initialize 失敗：拋例外、不保持 active、getStatus 為非 null INIT_FAILED")
    void initializeFailure_throwsAndNotActive_andStatusInitFailed() {
        LifecycleFake adapter = new LifecycleFake("vault", true);
        assertThrows(IntegrationLifecycleException.class, adapter::initialize);
        assertFalse(adapter.isActive(), "失敗 adapter 不得保持 active");
        IntegrationProbeResult result = adapter.getStatus();
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("boom"),
            "失敗原因應包含原始例外訊息: " + result.reason());
    }

    @Test
    @DisplayName("未初始化時 getStatus 回傳非 null 的 INIT_FAILED 結果")
    void getStatusBeforeInit_nonNullInitFailed() {
        LifecycleFake adapter = new LifecycleFake("vault");
        assertFalse(adapter.isActive());
        IntegrationProbeResult result = adapter.getStatus();
        assertNotNull(result);
        assertEquals(IntegrationStatus.INIT_FAILED, result.status());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("AbstractIntegrationAdapter 建構子：null id 必須拋 IllegalArgumentException")
    void nullIdConstructor_throws() {
        assertThrows(IllegalArgumentException.class, () -> new LifecycleFake(null));
    }
}
