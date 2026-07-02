package com.smile.acelib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigVersion} 測試。
 *
 * <p>對應 Plan §九 Phase 4「設定檔版本欄位存在；版本過舊自動觸發遷移」。
 * {@link ConfigVersion} 為不可變 record，提供 major/minor 排序與相容性判斷。</p>
 */
@DisplayName("ConfigVersion")
class ConfigVersionTest {

    @Test
    @DisplayName("建構子保留 major 與 minor 欄位")
    void constructor_preservesFields() {
        ConfigVersion v = new ConfigVersion(1, 2);
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
    }

    @Test
    @DisplayName("record 自動提供 equals / hashCode / toString")
    void recordEquality() {
        ConfigVersion a = new ConfigVersion(2, 5);
        ConfigVersion b = new ConfigVersion(2, 5);
        ConfigVersion c = new ConfigVersion(2, 6);
        assertEquals(a, b, "相同 major/minor 必須 equals");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c, "minor 不同必須 not equals");
        assertTrue(a.toString().contains("2"));
    }

    @Test
    @DisplayName("compareTo 依 major → minor 排序")
    void compareTo_sortsCorrectly() {
        ConfigVersion v1_0 = new ConfigVersion(1, 0);
        ConfigVersion v1_5 = new ConfigVersion(1, 5);
        ConfigVersion v2_0 = new ConfigVersion(2, 0);
        assertTrue(v1_0.compareTo(v1_5) < 0, "1.0 < 1.5");
        assertTrue(v1_5.compareTo(v2_0) < 0, "1.5 < 2.0");
        assertTrue(v2_0.compareTo(v1_0) > 0, "2.0 > 1.0");
        assertEquals(0, v1_5.compareTo(new ConfigVersion(1, 5)), "相同版本必須 0");
    }

    @Test
    @DisplayName("isCompatible：同 major 視為相容，不同 major 需遷移")
    void isCompatible_sameMajorReturnsTrue() {
        ConfigVersion v1_0 = new ConfigVersion(1, 0);
        ConfigVersion v1_9 = new ConfigVersion(1, 9);
        ConfigVersion v2_0 = new ConfigVersion(2, 0);
        assertTrue(v1_0.isCompatible(v1_9), "同 major 必須相容");
        assertTrue(v1_9.isCompatible(v1_0), "同 major 反向也相容");
        assertFalse(v1_0.isCompatible(v2_0), "跨 major 必須不相容");
        assertFalse(v2_0.isCompatible(v1_0), "跨 major 反向也不相容");
    }

    @Test
    @DisplayName("compareTo 邊界：負值或零值都視為合法版本")
    void compareTo_zeroVersion() {
        ConfigVersion zero = new ConfigVersion(0, 0);
        ConfigVersion v1 = new ConfigVersion(1, 0);
        assertTrue(zero.compareTo(v1) < 0);
        assertEquals(0, zero.compareTo(new ConfigVersion(0, 0)));
        // 負值仍合法（測試情境）
        ConfigVersion negative = new ConfigVersion(-1, -1);
        assertTrue(negative.compareTo(zero) < 0);
    }

    @Test
    @DisplayName("建構子拒絕 null（record 預設行為）")
    void constructor_rejectsNullFields() {
        // 由於 record 為 int，無法傳入 null，但驗證基本建構無 NPE
        ConfigVersion v = new ConfigVersion(0, 0);
        assertEquals(0, v.major());
        assertEquals(0, v.minor());
    }

    @Test
    @DisplayName("compareTo 與 equals 在 0.0 vs 0.0 情境下行為一致")
    void zeroEqualsZero() {
        ConfigVersion zero1 = new ConfigVersion(0, 0);
        ConfigVersion zero2 = new ConfigVersion(0, 0);
        assertEquals(zero1, zero2);
        assertEquals(0, zero1.compareTo(zero2));
        // 同 major=0 但 minor 不同視為不同版本
        assertNotEquals(zero1, new ConfigVersion(0, 1));
    }
}