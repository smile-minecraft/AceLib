package com.smile.acelib.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ContextException} 測試。
 *
 * <p>對應 Plan §八 Phase 3：錯誤訊息包含功能區域、操作類型、目標資訊與分類代碼。
 * 例外應為 {@link RuntimeException} 子類別，可直接往外拋。</p>
 */
@DisplayName("ContextException")
class ContextExceptionTest {

    @Test
    @DisplayName("建構子必須保留 code、currentContext、operationType、targetInfo 與 message")
    void constructor_preservesFields() {
        ContextException ex = new ContextException(
            "ACELIB-CTX-001",
            ThreadContext.PAPER_ASYNC,
            OperationType.WORLD_MUTATE,
            "world=world_main, chunk=(0,0)",
            "mutating world from async thread"
        );
        assertEquals("ACELIB-CTX-001", ex.getCode());
        assertSame(ThreadContext.PAPER_ASYNC, ex.getCurrentContext());
        assertSame(OperationType.WORLD_MUTATE, ex.getOperationType());
        assertEquals("world=world_main, chunk=(0,0)", ex.getTargetInfo());
        assertEquals("mutating world from async thread", ex.getMessage());
    }

    @Test
    @DisplayName("ContextException 必須是 RuntimeException，可直接拋出")
    void isRuntimeException() {
        ContextException ex = new ContextException(
            "ACELIB-CTX-002",
            ThreadContext.FOLIA_ASYNC,
            OperationType.PLAYER_MUTATE,
            "player=smile",
            "async completion touching player"
        );
        assertTrue(ex instanceof RuntimeException);
        // 確認可以 throw 不需 throws 宣告（catch 端使用 ContextException 才能呼叫 getCode）
        try {
            throw ex;
        } catch (ContextException caught) {
            assertNotNull(caught);
            assertEquals("ACELIB-CTX-002", caught.getCode());
        }
    }

    @Test
    @DisplayName("getCode 必須對應 CONTRIBUTING.md §6 規範的 ACELIB-CTX-xxx 格式")
    void codeFormat_matchesSpec() {
        ContextException ex1 = new ContextException(
            "ACELIB-CTX-001",
            ThreadContext.PAPER_MAIN,
            OperationType.WORLD_MUTATE,
            "x",
            "y"
        );
        ContextException ex2 = new ContextException(
            "ACELIB-CTX-002",
            ThreadContext.PAPER_MAIN,
            OperationType.WORLD_MUTATE,
            "x",
            "y"
        );
        ContextException ex3 = new ContextException(
            "ACELIB-CTX-003",
            ThreadContext.PAPER_MAIN,
            OperationType.WORLD_MUTATE,
            "x",
            "y"
        );
        ContextException ex4 = new ContextException(
            "ACELIB-CTX-004",
            ThreadContext.PAPER_MAIN,
            OperationType.WORLD_MUTATE,
            "x",
            "y"
        );
        assertTrue(ex1.getCode().startsWith("ACELIB-CTX-"));
        assertTrue(ex2.getCode().startsWith("ACELIB-CTX-"));
        assertTrue(ex3.getCode().startsWith("ACELIB-CTX-"));
        assertTrue(ex4.getCode().startsWith("ACELIB-CTX-"));
    }

    @Test
    @DisplayName("支援攜帶 cause 例外鏈")
    void supportsCause() {
        Throwable cause = new IllegalStateException("upstream");
        ContextException ex = new ContextException(
            "ACELIB-CTX-001",
            ThreadContext.FOLIA_ASYNC,
            OperationType.ENTITY_MUTATE,
            "entity=ZOMBIE@loc(0,64,0)",
            "spawning entity from async",
            cause
        );
        assertSame(cause, ex.getCause());
        assertEquals("ACELIB-CTX-001", ex.getCode());
    }
}