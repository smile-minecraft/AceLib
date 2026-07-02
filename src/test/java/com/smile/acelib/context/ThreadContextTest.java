package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ThreadContext} 列舉測試。
 *
 * <p>對應 Plan §八 Phase 3：6 種執行緒/區域上下文分類 + 預設 isSafeFor 規則。
 * 屬於無外部依賴的純單元測試，可在任何環境下執行。</p>
 */
@DisplayName("ThreadContext")
class ThreadContextTest {

    @Test
    @DisplayName("列舉值應包含 6 種上下文：GLOBAL / FOLIA_REGION / FOLIA_ASYNC / PAPER_MAIN / PAPER_ASYNC / UNKNOWN")
    void enumValues_complete() {
        ThreadContext[] values = ThreadContext.values();
        assertEquals(6, values.length, "必須有 6 種 ThreadContext 列舉值");
        assertNotNull(ThreadContext.valueOf("GLOBAL"));
        assertNotNull(ThreadContext.valueOf("FOLIA_REGION"));
        assertNotNull(ThreadContext.valueOf("FOLIA_ASYNC"));
        assertNotNull(ThreadContext.valueOf("PAPER_MAIN"));
        assertNotNull(ThreadContext.valueOf("PAPER_ASYNC"));
        assertNotNull(ThreadContext.valueOf("UNKNOWN"));
    }

    @Test
    @DisplayName("預設規則：FOLIA_REGION 對所有 mutate 操作都應為 safe")
    void foliaRegion_safeForAllMutates() {
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.WORLD_MUTATE));
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.PLAYER_MUTATE));
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.ENTITY_MUTATE));
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.BLOCK_MUTATE));
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.GUI_MUTATE));
        assertTrue(ThreadContext.FOLIA_REGION.isSafeFor(OperationType.READ_ONLY));
    }

    @Test
    @DisplayName("預設規則：PAPER_MAIN 對所有 mutate 操作都應為 safe")
    void paperMain_safeForAllMutates() {
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.WORLD_MUTATE));
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.PLAYER_MUTATE));
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.ENTITY_MUTATE));
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.BLOCK_MUTATE));
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.GUI_MUTATE));
        assertTrue(ThreadContext.PAPER_MAIN.isSafeFor(OperationType.READ_ONLY));
    }

    @Test
    @DisplayName("預設規則：FOLIA_ASYNC / PAPER_ASYNC / GLOBAL 對 mutate 操作不安全，僅 READ_ONLY 安全")
    void asyncContexts_onlyReadOnly() {
        assertFalse(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.WORLD_MUTATE));
        assertFalse(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.PLAYER_MUTATE));
        assertFalse(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.ENTITY_MUTATE));
        assertFalse(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.BLOCK_MUTATE));
        assertFalse(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.GUI_MUTATE));
        assertTrue(ThreadContext.FOLIA_ASYNC.isSafeFor(OperationType.READ_ONLY));

        assertFalse(ThreadContext.PAPER_ASYNC.isSafeFor(OperationType.WORLD_MUTATE));
        assertFalse(ThreadContext.PAPER_ASYNC.isSafeFor(OperationType.PLAYER_MUTATE));
        assertTrue(ThreadContext.PAPER_ASYNC.isSafeFor(OperationType.READ_ONLY));

        assertFalse(ThreadContext.GLOBAL.isSafeFor(OperationType.WORLD_MUTATE));
        assertFalse(ThreadContext.GLOBAL.isSafeFor(OperationType.PLAYER_MUTATE));
        assertTrue(ThreadContext.GLOBAL.isSafeFor(OperationType.READ_ONLY));
    }

    @Test
    @DisplayName("預設規則：UNKNOWN 對所有操作都不安全（保守降級）")
    void unknown_neverSafe() {
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.WORLD_MUTATE));
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.PLAYER_MUTATE));
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.ENTITY_MUTATE));
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.BLOCK_MUTATE));
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.GUI_MUTATE));
        // 即使是 READ_ONLY，未知平台仍視為不安全
        assertFalse(ThreadContext.UNKNOWN.isSafeFor(OperationType.READ_ONLY));
    }
}