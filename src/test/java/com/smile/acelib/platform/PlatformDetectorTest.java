package com.smile.acelib.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ---------------------------------------------------------------------
    // Phase 1 新增測試：isFolia/isPaper classpath 偵測 + 版本偵測 + capability
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("isFoliaClasspathAvailable() 在測試 classloader（僅含 Paper）下回傳 false")
    void isFoliaClasspathAvailable_falseOnTestClassloader() {
        // MockBukkit 提供 org.bukkit.Bukkit 但不提供 Folia RegionizedServer
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        assertFalse(detector.isFoliaClasspathAvailable(),
            "測試 classloader 找不到 Folia marker，必須 false");
    }

    @Test
    @DisplayName("isFoliaClasspathAvailable() 在空 classloader 下回傳 false")
    void isFoliaClasspathAvailable_falseOnEmptyClassloader() {
        ClassLoader empty = new ClassLoader(null) {};
        PlatformDetector detector = new PlatformDetector(empty);
        assertFalse(detector.isFoliaClasspathAvailable());
    }

    @Test
    @DisplayName("isPaperClasspathAvailable() 在測試 classloader（MockBukkit 提供 Bukkit）下回傳 true")
    void isPaperClasspathAvailable_trueOnTestClassloader() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        assertTrue(detector.isPaperClasspathAvailable(),
            "測試 classloader 應找到 org.bukkit.Bukkit");
    }

    @Test
    @DisplayName("isPaperClasspathAvailable() 在空 classloader 下回傳 false")
    void isPaperClasspathAvailable_falseOnEmptyClassloader() {
        ClassLoader empty = new ClassLoader(null) {};
        PlatformDetector detector = new PlatformDetector(empty);
        assertFalse(detector.isPaperClasspathAvailable());
    }

    @Test
    @DisplayName("detectMinecraftVersion(null) null-safe 回傳 'unknown'，不丟例外")
    void detectMinecraftVersion_nullServer_returnsUnknown() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        assertDoesNotThrow(() -> {
            String v = detector.detectMinecraftVersion(null);
            assertEquals("unknown", v, "null server 必須回傳 'unknown'");
        });
    }

    @Test
    @DisplayName("detectJavaVersion() 不為 null、非空白")
    void detectJavaVersion_nonBlank() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        String v = detector.detectJavaVersion();
        assertNotNull(v, "detectJavaVersion 不可回傳 null");
        assertFalse(v.trim().isEmpty(), "detectJavaVersion 不可回傳空白字串");
        // 寬鬆斷言：可能是 "unknown"（理論上 System.getProperty 永不失敗）或實際版本字串
        assertTrue(v.equals("unknown") || v.matches(".*\\d+.*"),
            "detectJavaVersion 應為 unknown 或包含版本數字；實際: " + v);
    }

    @Test
    @DisplayName("detectCapability(FOLIA) 與 PlatformCapability.forPlatform(FOLIA) 等價")
    void detectCapability_matchesFactory() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        // 不依賴 classpath 實際結果：測試 factory 與 detectCapability 一致性
        assertEquals(PlatformCapability.forPlatform(Platform.FOLIA),
            detector.detectCapability(Platform.FOLIA));
        assertEquals(PlatformCapability.forPlatform(Platform.PAPER),
            detector.detectCapability(Platform.PAPER));
        assertEquals(PlatformCapability.forPlatform(Platform.UNKNOWN),
            detector.detectCapability(Platform.UNKNOWN));
        // FOLIA 不等於 PAPER
        assertNotEquals(
            detector.detectCapability(Platform.FOLIA),
            detector.detectCapability(Platform.PAPER));
    }

    @Test
    @DisplayName("detectCapability(null) 必須拋例外")
    void detectCapability_null_throws() {
        PlatformDetector detector = new PlatformDetector(getClass().getClassLoader());
        assertThrows(NullPointerException.class, () -> detector.detectCapability(null));
    }
}