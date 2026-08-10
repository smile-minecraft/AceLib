package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryRecord} 行為測試。
 *
 * <p>對應 Plan §十三 Phase 8「資料讀寫 round-trip」與「資料不存在回傳合理預設」需求。
 * 重點：點分隔 path 走訪、巢狀 Map、型別化 getter 預設值、copy() 隔離、null/blank path
 * 拋 {@code ACELIB-DATA-003}。</p>
 */
@DisplayName("MemoryRecord")
class MemoryRecordTest {

    @Test
    @DisplayName("set + get 基本型別 round-trip")
    void roundtrip_basicTypes() {
        MemoryRecord r = new MemoryRecord();
        r.set("s", "hello");
        r.set("i", 42);
        r.set("l", 9999999999L);
        r.set("d", 3.14);
        r.set("b", true);

        assertEquals("hello", r.get("s"));
        assertEquals(42, r.get("i"));
        assertEquals(9999999999L, r.get("l"));
        assertEquals(3.14, r.get("d"));
        assertEquals(Boolean.TRUE, r.get("b"));
    }

    @Test
    @DisplayName("get 缺失 path 回傳 null")
    void get_missingReturnsNull() {
        MemoryRecord r = new MemoryRecord();
        assertNull(r.get("does.not.exist"));
    }

    @Test
    @DisplayName("has 對應 set/未 set 行為")
    void has_reflectsSetPresence() {
        MemoryRecord r = new MemoryRecord();
        assertFalse(r.has("k"));
        r.set("k", "v");
        assertTrue(r.has("k"));
        assertFalse(r.has("missing"));
    }

    @Test
    @DisplayName("型別化 getter：缺失 / 型別不符回傳 default")
    void typedGetters_returnDefaults() {
        MemoryRecord r = new MemoryRecord();
        r.set("i", 42);
        assertEquals(42, r.getInt("i", -1));
        assertEquals(-1, r.getInt("missing", -1));
        // 型別不符 → 預設值
        assertEquals("d", r.getString("i", "d"));
        // 字串可解析為 int
        r.set("numStr", "123");
        assertEquals(123, r.getInt("numStr", -1));
        // 字串無法解析 → 預設值
        r.set("bad", "abc");
        assertEquals(-1, r.getInt("bad", -1));
    }

    @Test
    @DisplayName("型別化 getter：Long / Double / Boolean")
    void typedGetters_longDoubleBoolean() {
        MemoryRecord r = new MemoryRecord();
        r.set("l", 9999999999L);
        r.set("d", 1.5);
        r.set("b", true);

        assertEquals(9999999999L, r.getLong("l", -1L));
        assertEquals(1.5, r.getDouble("d", 0.0));
        assertTrue(r.getBoolean("b", false));
        assertFalse(r.getBoolean("missing", false));
    }

    @Test
    @DisplayName("null / blank path 拋 ACELIB-DATA-003")
    void pathNullOrBlank_throws() {
        MemoryRecord r = new MemoryRecord();
        DataStoreException nullEx = assertThrows(DataStoreException.class,
            () -> r.get(null));
        assertEquals("ACELIB-DATA-003", nullEx.getCode());
        DataStoreException blankEx = assertThrows(DataStoreException.class,
            () -> r.get(""));
        assertEquals("ACELIB-DATA-003", blankEx.getCode());
        DataStoreException wsEx = assertThrows(DataStoreException.class,
            () -> r.get("   "));
        assertEquals("ACELIB-DATA-003", wsEx.getCode());
    }

    @Test
    @DisplayName("set 不支援型別拋 ACELIB-DATA-006")
    void set_unsupportedType_throws() {
        MemoryRecord r = new MemoryRecord();
        Object bad = new Object();
        DataStoreException ex = assertThrows(DataStoreException.class,
            () -> r.set("k", bad));
        assertEquals("ACELIB-DATA-006", ex.getCode());
    }

    @Test
    @DisplayName("dot-path 走訪：巢狀 Map 自動建立")
    void nestedPath_autoCreateMaps() {
        MemoryRecord r = new MemoryRecord();
        r.set("user.balance", 100);
        r.set("user.name", "alice");

        assertEquals(100, r.get("user.balance"));
        assertEquals("alice", r.get("user.name"));

        Object userObj = r.get("user");
        assertInstanceOf(Map.class, userObj);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) userObj;
        assertEquals(100, user.get("balance"));
        assertEquals("alice", user.get("name"));
    }

    @Test
    @DisplayName("getRecord 回傳子視圖；set 透過子視圖回寫根")
    void getRecord_returnsSubView() {
        MemoryRecord root = new MemoryRecord();
        root.set("user.balance", 200);
        Record userView = root.getRecord("user", null);
        assertNotSame(root, userView);
        // 子視圖寫入應回寫根
        userView.set("balance", 300);
        assertEquals(300, root.get("user.balance"));
        // 子視圖讀取可見
        assertEquals(300, userView.get("balance"));
    }

    @Test
    @DisplayName("getRecord 路徑不存在回傳 default")
    void getRecord_missingPathReturnsDefault() {
        MemoryRecord r = new MemoryRecord();
        Record sentinel = new MemoryRecord();
        assertSame(sentinel, r.getRecord("missing", sentinel));
        // 不是 Map 也回 default
        r.set("scalar", 5);
        assertSame(sentinel, r.getRecord("scalar", sentinel));
    }

    @Test
    @DisplayName("remove 回傳 true / false 對應實際移除行為")
    void remove_returnsActualRemoval() {
        MemoryRecord r = new MemoryRecord();
        r.set("k", "v");
        assertTrue(r.remove("k"));
        assertFalse(r.has("k"));
        assertFalse(r.remove("k"));
    }

    @Test
    @DisplayName("copy() 回傳新實例；修改副本不影響原")
    void copy_isolates() {
        MemoryRecord r = new MemoryRecord();
        r.set("k", "v");
        Record copy = r.copy();
        copy.set("k", "v2");
        assertEquals("v", r.get("k"));
        assertEquals("v2", copy.get("k"));
    }

    @Test
    @DisplayName("keys() 回傳不可變集合")
    void keys_isImmutable() {
        MemoryRecord r = new MemoryRecord();
        r.set("a", 1);
        r.set("b", 2);
        java.util.Set<String> keys = r.keys();
        assertEquals(2, keys.size());
        assertThrows(UnsupportedOperationException.class, () -> keys.add("c"));
    }

    @Test
    @DisplayName("set 同 path 回傳先前值")
    void set_returnsPreviousValue() {
        MemoryRecord r = new MemoryRecord();
        assertNull(r.set("k", "first"));
        assertEquals("first", r.set("k", "second"));
        assertEquals("second", r.get("k"));
    }

    @Test
    @DisplayName("getObject 型別轉換")
    void getObject_typeConversion() {
        MemoryRecord r = new MemoryRecord();
        r.set("n", 42);
        // 同型別直接回傳
        assertEquals(42, r.getObject("n", Integer.class, 0));
        // 自動從 Number 轉為 String（基本型別轉換）
        assertEquals("42", r.getObject("n", String.class, ""));
        // 缺失回 default
        assertEquals("d", r.getObject("missing", String.class, "d"));
    }

    @Test
    @DisplayName("巢狀 Map 內含 List；getString/getInt 對 List 不視為字串/數字")
    void nestedMapWithList() {
        MemoryRecord r = new MemoryRecord();
        r.set("data.list", List.of("a", "b", "c"));
        Object listObj = r.get("data.list");
        assertInstanceOf(List.class, listObj);
        assertEquals(List.of("a", "b", "c"), listObj);
    }
}