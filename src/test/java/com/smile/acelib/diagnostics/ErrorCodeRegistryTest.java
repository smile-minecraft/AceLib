package com.smile.acelib.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ErrorCodeRegistry 單元測試。
 *
 * <p>對應 Plan §十九 Phase 14「錯誤代碼可映射分類」需求。
 * 純邏輯測試，不依賴 Bukkit / MockBukkit。</p>
 */
@DisplayName("ErrorCodeRegistry — 錯誤代碼分類映射")
class ErrorCodeRegistryTest {

    @Nested
    @DisplayName("已知 ACELIB-* 代碼分類")
    class KnownCodeMapping {

        @Test
        @DisplayName("ACELIB-SCHED-* 對應 SCHEDULER")
        void schedCode_mapsToScheduler() {
            assertSame(ErrorCategory.SCHEDULER,
                ErrorCodeRegistry.categorize("ACELIB-SCHED-001"));
            assertSame(ErrorCategory.SCHEDULER,
                ErrorCodeRegistry.categorize("ACELIB-SCHED-006"));
        }

        @Test
        @DisplayName("ACELIB-CFG-* 對應 CONFIG")
        void cfgCode_mapsToConfig() {
            assertSame(ErrorCategory.CONFIG,
                ErrorCodeRegistry.categorize("ACELIB-CFG-003"));
            assertSame(ErrorCategory.CONFIG,
                ErrorCodeRegistry.categorize("ACELIB-CFG-005"));
        }

        @Test
        @DisplayName("ACELIB-LANG-* 對應 LANGUAGE")
        void langCode_mapsToLanguage() {
            assertSame(ErrorCategory.LANGUAGE,
                ErrorCodeRegistry.categorize("ACELIB-LANG-001"));
            assertSame(ErrorCategory.LANGUAGE,
                ErrorCodeRegistry.categorize("ACELIB-LANG-002"));
        }

        @Test
        @DisplayName("ACELIB-PLAT-* 對應 PLATFORM")
        void platCode_mapsToPlatform() {
            assertSame(ErrorCategory.PLATFORM,
                ErrorCodeRegistry.categorize("ACELIB-PLAT-004"));
        }

        @Test
        @DisplayName("ACELIB-CTX-* 對應 CONTEXT")
        void ctxCode_mapsToContext() {
            assertSame(ErrorCategory.CONTEXT,
                ErrorCodeRegistry.categorize("ACELIB-CTX-002"));
        }

        @Test
        @DisplayName("ACELIB-DBG-* 對應 DEBUG")
        void dbgCode_mapsToDebug() {
            assertSame(ErrorCategory.DEBUG,
                ErrorCodeRegistry.categorize("ACELIB-DBG-001"));
        }

        @Test
        @DisplayName("ACELIB-MSG-* 對應 MESSAGE")
        void msgCode_mapsToMessage() {
            assertSame(ErrorCategory.MESSAGE,
                ErrorCodeRegistry.categorize("ACELIB-MSG-001"));
        }

        @Test
        @DisplayName("ACELIB-CMD-* 對應 COMMAND")
        void cmdCode_mapsToCommand() {
            assertSame(ErrorCategory.COMMAND,
                ErrorCodeRegistry.categorize("ACELIB-CMD-001"));
        }

        @Test
        @DisplayName("ACELIB-EVT-* 對應 EVENT")
        void evtCode_mapsToEvent() {
            assertSame(ErrorCategory.EVENT,
                ErrorCodeRegistry.categorize("ACELIB-EVT-001"));
        }

        @Test
        @DisplayName("ACELIB-EXT-* 對應 EXTERNAL")
        void extCode_mapsToExternal() {
            assertSame(ErrorCategory.EXTERNAL,
                ErrorCodeRegistry.categorize("ACELIB-EXT-001"));
        }

        @Test
        @DisplayName("ACELIB-DATA-* 對應 DATA")
        void dataCode_mapsToData() {
            assertSame(ErrorCategory.DATA,
                ErrorCodeRegistry.categorize("ACELIB-DATA-001"));
        }
    }

    @Nested
    @DisplayName("未知與邊界代碼處理")
    class UnknownAndEdgeCodes {

        @Test
        @DisplayName("非 ACELIB 前綴的代碼 → UNKNOWN")
        void nonAcelibPrefix_mapsToUnknown() {
            assertSame(ErrorCategory.UNKNOWN,
                ErrorCodeRegistry.categorize("PLUGIN-001"));
            assertSame(ErrorCategory.UNKNOWN,
                ErrorCodeRegistry.categorize("foo"));
        }

        @Test
        @DisplayName("空字串 → UNKNOWN")
        void emptyString_mapsToUnknown() {
            assertSame(ErrorCategory.UNKNOWN,
                ErrorCodeRegistry.categorize(""));
        }

        @Test
        @DisplayName("null 代碼拋 NullPointerException（避免吞錯）")
        void nullCode_throws() {
            assertThrows(NullPointerException.class,
                () -> ErrorCodeRegistry.categorize(null));
        }

        @Test
        @DisplayName("大小寫差異的 ACELIB-* 仍能識別（小寫 area 視為 UNKNOWN）")
        void caseSensitivity_respected() {
            // 規範化視為 ACELIB-SCHED-*，小寫'acelib-sched-001'不應被歸類為 SCHEDULER
            assertSame(ErrorCategory.UNKNOWN,
                ErrorCodeRegistry.categorize("acelib-sched-001"));
        }

        @Test
        @DisplayName("未在預設表內的 ACELIB-AREA-* 也依 area 解析")
        void unknownArea_mapsToUnknown() {
            // 規範：未知 area 也應被歸類到 UNKNOWN，不可丟例外
            assertSame(ErrorCategory.UNKNOWN,
                ErrorCodeRegistry.categorize("ACELIB-NEWAREA-001"));
        }
    }

    @Nested
    @DisplayName("ErrorCodeInfo 查詢")
    class ErrorCodeInfoLookup {

        @Test
        @DisplayName("查詢已知代碼回傳非 null 含 category 與 description")
        void lookupKnown_returnsInfo() {
            ErrorCodeInfo info = ErrorCodeRegistry.lookup("ACELIB-SCHED-001");
            assertNotNull(info);
            assertSame(ErrorCategory.SCHEDULER, info.category());
            assertNotNull(info.description());
            assertTrue(info.description().length() > 0,
                "description 必須為非空字串");
        }

        @Test
        @DisplayName("查詢未知代碼回傳 null（不丟例外）")
        void lookupUnknown_returnsNull() {
            assertNull(ErrorCodeRegistry.lookup("ACELIB-NEWAREA-001"));
            assertNull(ErrorCodeRegistry.lookup("random"));
        }

        @Test
        @DisplayName("查詢 null 拋 NullPointerException")
        void lookupNull_throws() {
            assertThrows(NullPointerException.class,
                () -> ErrorCodeRegistry.lookup(null));
        }
    }

    @Nested
    @DisplayName("ErrorCategory 列舉完整性")
    class CategoryEnum {

        @Test
        @DisplayName("ErrorCategory 至少包含 16 種分類（PLAT/SCHED/CTX/CFG/MSG/LANG/CMD/EVT/DATA/PLAYER/WORLD/GUI/ITEM/EXT/DEBUG/UNKNOWN）")
        void category_hasAllPlanAreas() {
            // 至少有 16 個 enum 值
            assertTrue(ErrorCategory.values().length >= 16,
                "ErrorCategory 必須包含 Plan §七定義的所有 area + DEBUG + UNKNOWN");
        }

        @Test
        @DisplayName("ErrorCategory.values() 對應 ACELIB-<AREA> 字串前綴")
        void category_toAreaPrefix_consistent() {
            // 每個 category 都應能反查回 area 前綴；用於報告輸出
            for (ErrorCategory c : ErrorCategory.values()) {
                String prefix = c.areaPrefix();
                assertNotNull(prefix, "category " + c + " 必須有 area prefix");
                assertTrue(prefix.length() > 0,
                    "area prefix 不可為空");
            }
            assertEquals("SCHED", ErrorCategory.SCHEDULER.areaPrefix());
            assertEquals("CFG", ErrorCategory.CONFIG.areaPrefix());
            assertEquals("PLAT", ErrorCategory.PLATFORM.areaPrefix());
            assertEquals("DBG", ErrorCategory.DEBUG.areaPrefix());
            assertEquals("UNKNOWN", ErrorCategory.UNKNOWN.areaPrefix());
        }
    }
}
