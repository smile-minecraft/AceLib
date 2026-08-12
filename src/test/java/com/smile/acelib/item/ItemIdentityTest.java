package com.smile.acelib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemIdentity} record 行為測試。
 *
 * <p>對應 Plan Phase 12 自訂物品核心：namespace / key / formatVersion
 * 是辨識物品唯一依據；不同 {@code ItemIdentity} 即使其中一欄相同也不可互相誤判。
 * </p>
 */
@DisplayName("ItemIdentity")
class ItemIdentityTest {

    @Test
    @DisplayName("record：三欄相等才算相同 identity")
    void recordEqualityRequiresAllThreeFields() {
        ItemIdentity base = new ItemIdentity("acelib", "sword", 1, 0);
        assertEquals(base, new ItemIdentity("acelib", "sword", 1, 0));
    }

    @Test
    @DisplayName("namespace 不同：identity 不相等")
    void differentNamespaceNotEqual() {
        ItemIdentity a = new ItemIdentity("acelib", "sword", 1, 0);
        ItemIdentity b = new ItemIdentity("otherplug", "sword", 1, 0);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("key 不同：identity 不相等")
    void differentKeyNotEqual() {
        ItemIdentity a = new ItemIdentity("acelib", "sword", 1, 0);
        ItemIdentity b = new ItemIdentity("acelib", "axe", 1, 0);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("formatVersion 不同：identity 不相等（major）")
    void differentMajorVersionNotEqual() {
        ItemIdentity v1 = new ItemIdentity("acelib", "sword", 1, 0);
        ItemIdentity v2 = new ItemIdentity("acelib", "sword", 2, 0);
        assertNotEquals(v1, v2);
    }

    @Test
    @DisplayName("formatVersion 不同：identity 不相等（minor）")
    void differentMinorVersionNotEqual() {
        ItemIdentity v1 = new ItemIdentity("acelib", "sword", 1, 0);
        ItemIdentity v2 = new ItemIdentity("acelib", "sword", 1, 1);
        assertNotEquals(v1, v2);
    }

    @Test
    @DisplayName("namespace 為 null / blank：拋 ItemException")
    void invalidNamespaceRejected() {
        assertThrows(ItemException.class,
            () -> new ItemIdentity(null, "k", 1, 0));
        assertThrows(ItemException.class,
            () -> new ItemIdentity("", "k", 1, 0));
        assertThrows(ItemException.class,
            () -> new ItemIdentity("   ", "k", 1, 0));
    }

    @Test
    @DisplayName("key 為 null / blank：拋 ItemException")
    void invalidKeyRejected() {
        assertThrows(ItemException.class,
            () -> new ItemIdentity("acelib", null, 1, 0));
        assertThrows(ItemException.class,
            () -> new ItemIdentity("acelib", "", 1, 0));
    }

    @Test
    @DisplayName("formatVersion 負數：拋 ItemException")
    void negativeFormatVersionRejected() {
        assertThrows(ItemException.class,
            () -> new ItemIdentity("acelib", "k", -1, 0));
        assertThrows(ItemException.class,
            () -> new ItemIdentity("acelib", "k", 0, -1));
    }

    @Test
    @DisplayName("toString 採 namespace:key@major.minor 形式")
    void toStringFormat() {
        ItemIdentity id = new ItemIdentity("acelib", "sword", 1, 0);
        assertEquals("acelib:sword@1.0", id.toString());
    }
}
