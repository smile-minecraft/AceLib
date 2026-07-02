package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigException} 測試。
 *
 * <p>對應 Plan §九 Phase 4：所有對外拋出的設定檔／語言檔例外必須攜帶
 * {@code ACELIB-<AREA>-<CODE>} 格式代碼，並支援例外鏈。
 * 對應錯誤代碼：
 * <ul>
 *   <li>{@code ACELIB-CFG-001} ~ {@code 005}</li>
 *   <li>{@code ACELIB-LANG-001} ~ {@code 002}</li>
 * </ul>
 */
@DisplayName("ConfigException")
class ConfigExceptionTest {

    @Test
    @DisplayName("建構子保留 code 與 message")
    void constructor_preservesFields() {
        ConfigException ex = new ConfigException(
            "ACELIB-CFG-001",
            "config.yml 不存在且無法生成"
        );
        assertEquals("ACELIB-CFG-001", ex.getCode());
        assertEquals("config.yml 不存在且無法生成", ex.getMessage());
    }

    @Test
    @DisplayName("ConfigException 必須是 RuntimeException，可直接拋出")
    void isRuntimeException() {
        ConfigException ex = new ConfigException("ACELIB-CFG-003", "fallback failed");
        assertTrue(ex instanceof RuntimeException,
            "ConfigException 必須 extends RuntimeException");
        // 確認可以 throw 不需 throws 宣告
        try {
            throw ex;
        } catch (ConfigException caught) {
            assertSame(ex, caught);
            assertNotNull(caught.getCode());
        }
    }

    @Test
    @DisplayName("支援攜帶 cause 例外鏈")
    void supportsCause() {
        Throwable cause = new IllegalStateException("upstream YAML parser error");
        ConfigException ex = new ConfigException(
            "ACELIB-CFG-002",
            "YAML 解析失敗",
            cause
        );
        assertSame(cause, ex.getCause());
        assertEquals("ACELIB-CFG-002", ex.getCode());
    }

    @Test
    @DisplayName("code 格式必須符合 ACELIB-CFG-xxx 或 ACELIB-LANG-xxx 規範")
    void codeFormat_matchesSpec() {
        // 驗證兩類錯誤代碼前綴
        String[] cfgCodes = {
            "ACELIB-CFG-001", "ACELIB-CFG-002", "ACELIB-CFG-003",
            "ACELIB-CFG-004", "ACELIB-CFG-005"
        };
        String[] langCodes = {
            "ACELIB-LANG-001", "ACELIB-LANG-002"
        };
        for (String code : cfgCodes) {
            ConfigException ex = new ConfigException(code, "x");
            assertTrue(ex.getCode().startsWith("ACELIB-CFG-"),
                "設定檔錯誤代碼前綴不符：" + code);
            assertTrue(ex.getCode().matches("ACELIB-CFG-\\d{3}"),
                "設定檔錯誤代碼格式不符： " + code);
        }
        for (String code : langCodes) {
            ConfigException ex = new ConfigException(code, "x");
            assertTrue(ex.getCode().startsWith("ACELIB-LANG-"),
                "語言檔錯誤代碼前綴不符：" + code);
            assertTrue(ex.getCode().matches("ACELIB-LANG-\\d{3}"),
                "語言檔錯誤代碼格式不符：" + code);
        }
    }
}