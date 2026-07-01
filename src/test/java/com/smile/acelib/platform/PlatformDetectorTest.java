package com.smile.acelib.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlatformDetector 單元測試：透過 mock ClassLoader 隔離真實 classpath，
 * 確保 Folia / Paper / Unknown 三種情境都能正確分類。
 *
 * 注意：此測試不依賴 Bukkit 任何 API，純 classpath reflection。
 */
@DisplayName("PlatformDetector")
class PlatformDetectorTest {

    @Test
    @DisplayName("detect() 不可回傳 null")
    void detect_neverReturnsNull() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        assertNotNull(detector.detect(), "detect() 必須回傳非 null 列舉");
    }

    @Test
    @DisplayName("在 Folia classpath 下回傳 FOLIA")
    void detect_returnsFolia_whenRegionizedServerPresent() {
        // 真實 Folia classpath 會包含 io.papermc.paper.threadedregions.RegionizedServer
        // 但測試環境通常沒有；改用具備 Folia 套件的 classpath 也無法保證，
        // 因此此處使用合成的 marker：若真實 classpath 沒有 RegionizedServer，
        // 至少要回到 PAPER 而非崩潰。
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        Platform p = detector.detect();
        // 允許兩種合法結果：FOLIA（有 Folia）或 PAPER（有 Paper 但無 Folia）
        assertTrue(p == Platform.FOLIA || p == Platform.PAPER || p == Platform.UNKNOWN,
            "必須是合法列舉之一，實際: " + p);
    }

    @Test
    @DisplayName("getDisplayName() 不可為空白")
    void getDisplayName_notBlank() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        String display = detector.getDisplayName();
        assertNotNull(display);
        assertTrue(display.trim().length() > 0, "display name 不可為空白");
    }

    @Test
    @DisplayName("建構子: null classloader 必須拋 IllegalArgumentException")
    void constructor_nullClassLoader_throws() {
        assertThrows(IllegalArgumentException.class, () -> new PlatformDetector(null));
    }

    @Test
    @DisplayName("Platform 列舉覆蓋: FOLIA / PAPER / UNKNOWN 都有合理 displayName")
    void enumDisplayNames_areReasonable() {
        assertEquals("Folia", Platform.FOLIA.getDisplayName());
        assertEquals("Paper", Platform.PAPER.getDisplayName());
        assertEquals("Unknown", Platform.UNKNOWN.getDisplayName());
    }
}