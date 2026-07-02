package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link ContextInspector} 規則表測試。
 *
 * <p>對應 Plan §八 Phase 3：Folia 環境下 region thread 才能 mutate；Paper 環境下
 * main thread 可 mutate 全域物件；UNKNOWN 環境全部拒絕；READ_ONLY 在所有環境安全。
 * 測試覆蓋 Folia × Paper × UNKNOWN × 各種 ThreadContext 的笛卡兒積核心案例。</p>
 */
@DisplayName("ContextInspector")
class ContextInspectorTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        // MockBukkit 環境下 Bukkit.isPrimaryThread() 才能正確回傳。
        // ContextInspectorTest 不需要完整 plugin 實例，只需 mock 一個 ServerMock。
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ContextCheckResult.allowed() 必須 safe=true 且 reason/code 為 null")
    void allowed_factory() {
        ContextCheckResult r = ContextCheckResult.allowed();
        assertTrue(r.safe());
        assertNotNull(r);
        assertEquals(null, r.code());
        assertEquals(null, r.reason());
    }

    @Test
    @DisplayName("ContextCheckResult.denied(code, reason) 必須 safe=false 並保留 code/reason")
    void denied_factory() {
        ContextCheckResult r = ContextCheckResult.denied("ACELIB-CTX-001", "test reason");
        assertFalse(r.safe());
        assertEquals("ACELIB-CTX-001", r.code());
        assertEquals("test reason", r.reason());
    }

    @Test
    @DisplayName("Paper + PAPER_MAIN 對所有 mutate 操作應為 allowed")
    void paperPaperMain_allowedForMutate() {
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.PAPER_MAIN, OperationType.WORLD_MUTATE, Platform.PAPER));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.PAPER_MAIN, OperationType.PLAYER_MUTATE, Platform.PAPER));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.PAPER_MAIN, OperationType.ENTITY_MUTATE, Platform.PAPER));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.PAPER_MAIN, OperationType.BLOCK_MUTATE, Platform.PAPER));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.PAPER_MAIN, OperationType.GUI_MUTATE, Platform.PAPER));
    }

    @Test
    @DisplayName("Paper + PAPER_ASYNC 對 mutate 操作應為 denied，READ_ONLY 應為 allowed")
    void paperPaperAsync_readOnlyAllowed() {
        ContextCheckResult deny = ContextInspector.check(
            ThreadContext.PAPER_ASYNC, OperationType.WORLD_MUTATE, Platform.PAPER);
        assertFalse(deny.safe());
        assertEquals("ACELIB-CTX-001", deny.code());

        ContextCheckResult ro = ContextInspector.check(
            ThreadContext.PAPER_ASYNC, OperationType.READ_ONLY, Platform.PAPER);
        assertTrue(ro.safe());
    }

    @Test
    @DisplayName("Folia + FOLIA_REGION 對 mutate 操作應為 allowed")
    void foliaRegion_allowedForMutate() {
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.FOLIA_REGION, OperationType.WORLD_MUTATE, Platform.FOLIA));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.FOLIA_REGION, OperationType.PLAYER_MUTATE, Platform.FOLIA));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.FOLIA_REGION, OperationType.ENTITY_MUTATE, Platform.FOLIA));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.FOLIA_REGION, OperationType.BLOCK_MUTATE, Platform.FOLIA));
        assertEquals(ContextCheckResult.allowed(),
            ContextInspector.check(ThreadContext.FOLIA_REGION, OperationType.GUI_MUTATE, Platform.FOLIA));
    }

    @Test
    @DisplayName("Folia + FOLIA_ASYNC 對 mutate 操作應為 denied，code 為 ACELIB-CTX-003，READ_ONLY 應為 allowed")
    void foliaAsync_readOnlyAllowed() {
        ContextCheckResult deny = ContextInspector.check(
            ThreadContext.FOLIA_ASYNC, OperationType.PLAYER_MUTATE, Platform.FOLIA);
        assertFalse(deny.safe());
        // Folia 環境下非 region thread 操作 region-bound 物件 → CTX-003
        assertEquals("ACELIB-CTX-003", deny.code(),
            "Folia 環境下 FOLIA_ASYNC mutate 屬於 region-bound 違規");

        ContextCheckResult ro = ContextInspector.check(
            ThreadContext.FOLIA_ASYNC, OperationType.READ_ONLY, Platform.FOLIA);
        assertTrue(ro.safe());
    }

    @Test
    @DisplayName("Folia + PAPER_MAIN（錯誤執行緒）對 mutate 應為 denied，code 為 ACELIB-CTX-003")
    void foliaWrongThread_deniedRegionBound() {
        ContextCheckResult deny = ContextInspector.check(
            ThreadContext.PAPER_MAIN, OperationType.PLAYER_MUTATE, Platform.FOLIA);
        assertFalse(deny.safe());
        assertEquals("ACELIB-CTX-003", deny.code(),
            "Folia 環境下非 region thread 操作 region-bound 物件應為 CTX-003");

        ContextCheckResult deny2 = ContextInspector.check(
            ThreadContext.GLOBAL, OperationType.WORLD_MUTATE, Platform.FOLIA);
        assertFalse(deny2.safe());
        assertEquals("ACELIB-CTX-003", deny2.code());
    }

    @Test
    @DisplayName("UNKNOWN 平台對 mutate 應為 denied，code 為 ACELIB-CTX-004")
    void unknownPlatform_denied() {
        ContextCheckResult deny = ContextInspector.check(
            ThreadContext.PAPER_MAIN, OperationType.WORLD_MUTATE, Platform.UNKNOWN);
        assertFalse(deny.safe());
        assertEquals("ACELIB-CTX-004", deny.code());

        ContextCheckResult deny2 = ContextInspector.check(
            ThreadContext.FOLIA_REGION, OperationType.PLAYER_MUTATE, Platform.UNKNOWN);
        assertFalse(deny2.safe());
        assertEquals("ACELIB-CTX-004", deny2.code());

        // 即使 READ_ONLY，UNKNOWN 仍視為不支援
        ContextCheckResult deny3 = ContextInspector.check(
            ThreadContext.PAPER_MAIN, OperationType.READ_ONLY, Platform.UNKNOWN);
        assertFalse(deny3.safe());
        assertEquals("ACELIB-CTX-004", deny3.code());
    }

    @Test
    @DisplayName("所有平台 × READ_ONLY + 合法執行緒 應為 allowed")
    void readOnly_alwaysAllowedForKnownPlatforms() {
        // Paper main
        assertTrue(ContextInspector.check(
            ThreadContext.PAPER_MAIN, OperationType.READ_ONLY, Platform.PAPER).safe());
        // Paper async
        assertTrue(ContextInspector.check(
            ThreadContext.PAPER_ASYNC, OperationType.READ_ONLY, Platform.PAPER).safe());
        // Folia region
        assertTrue(ContextInspector.check(
            ThreadContext.FOLIA_REGION, OperationType.READ_ONLY, Platform.FOLIA).safe());
        // Folia async
        assertTrue(ContextInspector.check(
            ThreadContext.FOLIA_ASYNC, OperationType.READ_ONLY, Platform.FOLIA).safe());
    }

    @Test
    @DisplayName("currentContext() 在主執行緒回傳 PAPER_MAIN")
    void currentContext_returnsPaperMainOnMainThread() {
        // setUp 已 mock ServerMock，Bukkit.isPrimaryThread() 可正確回傳
        ThreadContext ctx = ContextInspector.currentContext(Platform.PAPER);
        assertEquals(ThreadContext.PAPER_MAIN, ctx,
            "MockBukkit 主執行緒 + Paper 環境下應回傳 PAPER_MAIN");
    }

    @Test
    @DisplayName("currentContext() 在 Folia + 主執行緒回傳 FOLIA_REGION")
    void currentContext_foliaRegionOnMainThread() {
        ThreadContext ctx = ContextInspector.currentContext(Platform.FOLIA);
        // 在 MockBukkit 主執行緒下，Folia 環境的回傳是 FOLIA_REGION
        assertEquals(ThreadContext.FOLIA_REGION, ctx);
    }
}