package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemaVersion} 排序、相等、序列化測試。
 *
 * <p>對應 Plan §十三 Phase 8「資料版本遷移」需求。</p>
 */
@DisplayName("SchemaVersion")
class SchemaVersionTest {

    @Test
    @DisplayName("Comparable：先比 major 後比 minor")
    void compareTo_ordersByMajorThenMinor() {
        SchemaVersion a = new SchemaVersion(1, 0);
        SchemaVersion b = new SchemaVersion(1, 1);
        SchemaVersion c = new SchemaVersion(2, 0);

        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
        assertTrue(c.compareTo(a) > 0);
        assertEquals(0, a.compareTo(new SchemaVersion(1, 0)));
    }

    @Test
    @DisplayName("toString 採 major.minor 格式")
    void toString_formatsAsMajorDotMinor() {
        assertEquals("1.0", new SchemaVersion(1, 0).toString());
        assertEquals("2.5", new SchemaVersion(2, 5).toString());
        assertEquals("0.9", new SchemaVersion(0, 9).toString());
    }

    @Test
    @DisplayName("record 自動生成 equals/hashCode")
    void equalsHashCode_recordsUseComponentSemantics() {
        SchemaVersion a = new SchemaVersion(3, 7);
        SchemaVersion b = new SchemaVersion(3, 7);
        SchemaVersion c = new SchemaVersion(3, 8);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(!a.equals(c));
    }

    @Test
    @DisplayName("V1_0 常數可用且等於 new SchemaVersion(1, 0)")
    void v1_0Constant() {
        assertEquals(new SchemaVersion(1, 0), SchemaVersion.V1_0);
    }
}