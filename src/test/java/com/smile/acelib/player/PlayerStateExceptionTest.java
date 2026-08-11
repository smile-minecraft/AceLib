package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerStateException} 行為測試。
 *
 * <p>驗證錯誤代碼格式與 cause 保留（不安靜吞錯）。</p>
 */
@DisplayName("PlayerStateException")
class PlayerStateExceptionTest {

    @Test
    @DisplayName("constructor：保留 code 與 message；無 cause")
    void constructor_noCause() {
        PlayerStateException ex = new PlayerStateException("ACELIB-PLAYER-001",
            "data not loaded");
        assertEquals("ACELIB-PLAYER-001", ex.getCode());
        assertEquals("data not loaded", ex.getMessage());
    }

    @Test
    @DisplayName("constructor：保留 code 與 message + cause")
    void constructor_withCause() {
        Throwable cause = new RuntimeException("underlying");
        PlayerStateException ex = new PlayerStateException("ACELIB-PLAYER-002",
            "load failed", cause);
        assertEquals("ACELIB-PLAYER-002", ex.getCode());
        assertEquals("load failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("constructor：null code 拋 NPE")
    void constructor_nullCode_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new PlayerStateException(null, "msg"));
    }

    @Test
    @DisplayName("constructor：null message 拋 NPE")
    void constructor_nullMessage_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new PlayerStateException("ACELIB-PLAYER-001", null));
    }

    @Test
    @DisplayName("toString 包含 code 與 message，方便 log 識別")
    void toString_includesCodeAndMessage() {
        PlayerStateException ex = new PlayerStateException("ACELIB-PLAYER-001",
            "data not loaded");
        String str = ex.toString();
        assertNotNull(str);
        org.junit.jupiter.api.Assertions.assertTrue(str.contains("ACELIB-PLAYER-001"));
        org.junit.jupiter.api.Assertions.assertTrue(str.contains("data not loaded"));
    }
}
