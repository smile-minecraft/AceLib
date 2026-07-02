package com.smile.acelib.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PlatformCapability record 單元測試：驗證三個 platform value 的 capability 欄位正確，
 * 並覆蓋 null platform 邊界條件。
 *
 * 對應 Plan §六 Phase 1 驗收標準 #1（可區分 Folia / Paper / 不明平台）與 #4
 * （保守策略：UNKNOWN 全 false，不可假設功能可用）。
 */
@DisplayName("PlatformCapability")
class PlatformCapabilityTest {

    @Nested
    @DisplayName("FOLIA platform")
    class Folia {

        @Test
        @DisplayName("regionScheduling = true")
        void folia_regionScheduling_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.FOLIA).regionScheduling());
        }

        @Test
        @DisplayName("globalScheduler = true")
        void folia_globalScheduler_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.FOLIA).globalScheduler());
        }

        @Test
        @DisplayName("bukkitApi = true")
        void folia_bukkitApi_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.FOLIA).bukkitApi());
        }

        @Test
        @DisplayName("foliaThreadedRegionsApi = true")
        void folia_foliaThreadedRegionsApi_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.FOLIA).foliaThreadedRegionsApi());
        }
    }

    @Nested
    @DisplayName("PAPER platform")
    class Paper {

        @Test
        @DisplayName("regionScheduling = false")
        void paper_regionScheduling_false() {
            // Paper 全域 scheduler 仍可用，但不支援 regionized scheduling
            assertFalse(PlatformCapability.forPlatform(Platform.PAPER).regionScheduling());
        }

        @Test
        @DisplayName("globalScheduler = true")
        void paper_globalScheduler_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.PAPER).globalScheduler());
        }

        @Test
        @DisplayName("bukkitApi = true")
        void paper_bukkitApi_true() {
            assertTrue(PlatformCapability.forPlatform(Platform.PAPER).bukkitApi());
        }

        @Test
        @DisplayName("foliaThreadedRegionsApi = false")
        void paper_foliaThreadedRegionsApi_false() {
            assertFalse(PlatformCapability.forPlatform(Platform.PAPER).foliaThreadedRegionsApi());
        }
    }

    @Nested
    @DisplayName("UNKNOWN platform")
    class Unknown {

        @Test
        @DisplayName("所有 capability 欄位皆為 false")
        void unknown_allCapabilitiesFalse() {
            // 保守策略：不明平台不可宣稱任何能力，避免誤判導致呼叫失敗的 API
            PlatformCapability cap = PlatformCapability.forPlatform(Platform.UNKNOWN);
            assertNotNull(cap);
            assertFalse(cap.regionScheduling(), "UNKNOWN.regionScheduling 必須 false");
            assertFalse(cap.globalScheduler(), "UNKNOWN.globalScheduler 必須 false");
            assertFalse(cap.bukkitApi(), "UNKNOWN.bukkitApi 必須 false");
            assertFalse(cap.foliaThreadedRegionsApi(), "UNKNOWN.foliaThreadedRegionsApi 必須 false");
        }
    }

    @Test
    @DisplayName("邊界: null platform 必須拋 NullPointerException")
    void forPlatform_null_throws() {
        assertThrows(NullPointerException.class, () -> PlatformCapability.forPlatform(null));
    }

    @Test
    @DisplayName("同 platform 重複呼叫 forPlatform 回傳等價 record")
    void forPlatform_isIdempotent() {
        PlatformCapability a = PlatformCapability.forPlatform(Platform.FOLIA);
        PlatformCapability b = PlatformCapability.forPlatform(Platform.FOLIA);
        // record 的 equals 比較所有欄位
        assertEquals(a, b);
    }
}
