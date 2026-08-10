package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandException} 單元測試。
 *
 * <p>對應 Plan §十一驗收標準「錯誤代碼格式」與「可被玩家與管理員分別理解」。</p>
 */
@DisplayName("CommandException")
class CommandExceptionTest {

    @Test
    @DisplayName("標準 kind 自動對應預設 code")
    void standardKind_defaultCode() {
        CommandException ex = new CommandException(
            CommandErrorKind.MISSING_ARGUMENTS, "missing args");
        assertSame(CommandErrorKind.MISSING_ARGUMENTS, ex.getKind());
        assertEquals("ACELIB-CMD-001", ex.getCode());
        assertEquals("missing args", ex.getMessage());
    }

    @Test
    @DisplayName("所有 10 種 kind 都有對應的 ACELIB-CMD-NNN code")
    void allKinds_haveCodes() {
        assertEquals("ACELIB-CMD-001", CommandErrorKind.MISSING_ARGUMENTS.defaultCode());
        assertEquals("ACELIB-CMD-002", CommandErrorKind.UNKNOWN_SUBCOMMAND.defaultCode());
        assertEquals("ACELIB-CMD-003", CommandErrorKind.NO_PERMISSION.defaultCode());
        assertEquals("ACELIB-CMD-004", CommandErrorKind.CONSOLE_NOT_ALLOWED.defaultCode());
        assertEquals("ACELIB-CMD-005", CommandErrorKind.PLAYER_NOT_ALLOWED.defaultCode());
        assertEquals("ACELIB-CMD-006", CommandErrorKind.COOLDOWN_ACTIVE.defaultCode());
        assertEquals("ACELIB-CMD-007", CommandErrorKind.PLAYER_OFFLINE.defaultCode());
        assertEquals("ACELIB-CMD-008", CommandErrorKind.ASYNC_EXECUTION_FAILED.defaultCode());
        assertEquals("ACELIB-CMD-009", CommandErrorKind.REGISTRY_DISABLED.defaultCode());
        assertEquals("ACELIB-CMD-010", CommandErrorKind.CUSTOM.defaultCode());
    }

    @Test
    @DisplayName("kind=NO_PERMISSION 的 code 必須為 ACELIB-CMD-003")
    void noPermission_kindMapsToCmd003() {
        CommandException ex = new CommandException(
            CommandErrorKind.NO_PERMISSION, "no perm for my.sub",
            java.util.Map.of("permission", "my.sub", "sub", "sub"));
        assertEquals("ACELIB-CMD-003", ex.getCode());
        assertEquals("my.sub", ex.getVars().get("permission"));
    }

    @Test
    @DisplayName("vars 為 null 時自動視為空 map（不可變）")
    void nullVars_becomeEmpty() {
        CommandException ex = new CommandException(
            CommandErrorKind.UNKNOWN_SUBCOMMAND, "x", null);
        assertNotNull(ex.getVars());
        assertTrue(ex.getVars().isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> ex.getVars().put("a", "b"),
            "vars 必須為不可變 map");
    }

    @Test
    @DisplayName("vars 傳入 map 會被防禦性複製，不影響外部修改")
    void varsDefensivelyCopied() {
        java.util.Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("a", 1);
        CommandException ex = new CommandException(
            CommandErrorKind.COOLDOWN_ACTIVE, "x", mutable);
        mutable.put("b", 2);
        assertFalse(ex.getVars().containsKey("b"),
            "vars 修改後不應影響 exception 內部狀態");
    }

    @Test
    @DisplayName("custom(code, message) 帶有 CUSTOM kind 與自訂 code")
    void custom_carriesCustomCode() {
        CommandException ex = CommandException.custom("ACELIB-CMD-099", "my custom error");
        assertSame(CommandErrorKind.CUSTOM, ex.getKind());
        assertEquals("ACELIB-CMD-099", ex.getCode());
        assertEquals("my custom error", ex.getMessage());
    }

    @Test
    @DisplayName("custom 對 null code 拋 NPE")
    void custom_nullCode_throws() {
        assertThrows(NullPointerException.class,
            () -> CommandException.custom(null, "x"));
    }

    @Test
    @DisplayName("extends RuntimeException — 可不宣告 throws 直接拋出")
    void isRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(CommandException.class));
    }

    @Test
    @DisplayName("kind 為 null 拋 NPE")
    void nullKind_throws() {
        assertThrows(NullPointerException.class,
            () -> new CommandException(null, "x"));
    }

    @Test
    @DisplayName("message 為 null 拋 NPE")
    void nullMessage_throws() {
        assertThrows(NullPointerException.class,
            () -> new CommandException(CommandErrorKind.CUSTOM, null));
    }

    // -----------------------------------------------------------------
    // 反射測試（避免 getter 在 refactor 時被誤刪）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("CommandException 暴露 getKind / getCode / getVars 公開方法")
    void exposedAccessors() throws NoSuchMethodException {
        Method getKind = CommandException.class.getMethod("getKind");
        Method getCode = CommandException.class.getMethod("getCode");
        Method getVars = CommandException.class.getMethod("getVars");
        assertEquals(CommandErrorKind.class, getKind.getReturnType());
        assertEquals(String.class, getCode.getReturnType());
        assertEquals(java.util.Map.class, getVars.getReturnType());
    }
}