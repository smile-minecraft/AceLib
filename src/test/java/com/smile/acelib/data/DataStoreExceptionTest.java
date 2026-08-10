package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DataStoreException} 合約測試。
 *
 * <p>對應 Plan §十三 Phase 8「資料操作錯誤回報」與 §二十三 DoD「錯誤分類代碼」：
 * 所有對外拋出的例外都必須攜帶 {@code ACELIB-DATA-<CODE>} 格式代碼，
 * 且保留底層 cause 以利診斷。</p>
 */
@DisplayName("DataStoreException")
class DataStoreExceptionTest {

    @Test
    @DisplayName("code 與 message 必填")
    void constructor_requiresNonNull() {
        assertThrows(NullPointerException.class,
            () -> new DataStoreException(null, "msg"));
        assertThrows(NullPointerException.class,
            () -> new DataStoreException("ACELIB-DATA-001", null));
        assertThrows(NullPointerException.class,
            () -> new DataStoreException(null, "msg", new RuntimeException()));
    }

    @Test
    @DisplayName("getCode 回傳當初傳入的代碼")
    void getCode_returnsConstructorArg() {
        DataStoreException ex = new DataStoreException("ACELIB-DATA-002", "bad json");
        assertEquals("ACELIB-DATA-002", ex.getCode());
        assertEquals("bad json", ex.getMessage());
        assertSame(ex.getCause(), null);
    }

    @Test
    @DisplayName("3 參數建構子保留 cause")
    void constructor_preservesCause() {
        RuntimeException root = new RuntimeException("root");
        DataStoreException ex = new DataStoreException("ACELIB-DATA-001", "io fail", root);
        assertEquals("ACELIB-DATA-001", ex.getCode());
        assertSame(root, ex.getCause());
        assertNotNull(ex.getMessage());
    }
}