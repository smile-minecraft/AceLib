package com.smile.acelib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JsonCodecImpl} encode / decode 行為測試。
 *
 * <p>對應 Plan §十三 Phase 8「資料表 / 資料格式初始化」與「round-trip」需求。
 * 重點：白名單型別支援、不支援型別報 {@code ACELIB-DATA-006}、損壞 JSON
 * 報 {@code ACELIB-DATA-002}。</p>
 */
@DisplayName("JsonCodecImpl")
class JsonCodecTest {

    private final JsonCodec codec = new JsonCodecImpl();

    @Test
    @DisplayName("encode + decode round-trip 支援基本型別")
    void roundtrip_basicTypes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("s", "hello");
        map.put("i", 42);
        map.put("l", 9999999999L);
        map.put("d", 3.14);
        map.put("b", true);
        map.put("n", null);

        String text = codec.encode(map);
        Map<String, Object> back = codec.decode(text);

        assertEquals("hello", back.get("s"));
        assertEquals(42, back.get("i"));
        assertEquals(9999999999L, back.get("l"));
        assertEquals(3.14, back.get("d"));
        assertEquals(Boolean.TRUE, back.get("b"));
        assertNull(back.get("n"));
    }

    @Test
    @DisplayName("encode + decode 支援巢狀 Map 與 List")
    void roundtrip_nestedMapAndList() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", "v");
        inner.put("arr", List.of(1, 2, 3));
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("nested", inner);
        outer.put("list", List.of("a", "b", "c"));

        Map<String, Object> back = codec.decode(codec.encode(outer));
        assertNotNull(back.get("nested"));
        assertTrue(back.get("nested") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedBack = (Map<String, Object>) back.get("nested");
        assertEquals("v", nestedBack.get("k"));
        assertEquals(List.of(1, 2, 3), nestedBack.get("arr"));
        assertEquals(List.of("a", "b", "c"), back.get("list"));
    }

    @Test
    @DisplayName("encode 不支援型別拋 ACELIB-DATA-006")
    void encode_unsupportedTypeThrows() {
        Object bad = new Object();
        DataStoreException ex = assertThrows(DataStoreException.class,
            () -> codec.encode(bad));
        assertEquals("ACELIB-DATA-006", ex.getCode());
    }

    @Test
    @DisplayName("encode 不接受 non-String map key 拋 ACELIB-DATA-006")
    void encode_nonStringKeyThrows() {
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(42, "value");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("bad", bad);
        DataStoreException ex = assertThrows(DataStoreException.class,
            () -> codec.encode(outer));
        assertEquals("ACELIB-DATA-006", ex.getCode());
    }

    @Test
    @DisplayName("decode 損壞 JSON 拋 ACELIB-DATA-002")
    void decode_corruptThrows() {
        DataStoreException ex = assertThrows(DataStoreException.class,
            () -> codec.decode("{not valid json"));
        assertEquals("ACELIB-DATA-002", ex.getCode());
    }

    @Test
    @DisplayName("decode 空字串回傳空 map")
    void decode_emptyReturnsEmptyMap() {
        assertEquals(Map.of(), codec.decode(""));
        assertEquals(Map.of(), codec.decode("   "));
    }

    @Test
    @DisplayName("decode 根節點不是 object 拋 ACELIB-DATA-002")
    void decode_nonObjectRootThrows() {
        DataStoreException ex = assertThrows(DataStoreException.class,
            () -> codec.decode("[1,2,3]"));
        assertEquals("ACELIB-DATA-002", ex.getCode());
    }

    @Test
    @DisplayName("encodeVersion + decodeVersion round-trip")
    void version_roundtrip() {
        SchemaVersion v = new SchemaVersion(2, 7);
        assertEquals(v, codec.decodeVersion(codec.encodeVersion(v)));
    }

    @Test
    @DisplayName("decodeVersion 不合法格式拋 ACELIB-DATA-002")
    void version_invalidThrows() {
        assertThrows(DataStoreException.class, () -> codec.decodeVersion("abc"));
        assertThrows(DataStoreException.class, () -> codec.decodeVersion(""));
        assertThrows(DataStoreException.class, () -> codec.decodeVersion("1"));
        assertThrows(DataStoreException.class, () -> codec.decodeVersion("."));
    }

    @Test
    @DisplayName("encode 字串支援跳脫字元")
    void encode_stringEscapes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("s", "a\"b\\c\nd");
        String text = codec.encode(map);
        assertTrue(text.contains("\\\""), "should escape double quote");
        assertTrue(text.contains("\\\\"), "should escape backslash");
        assertTrue(text.contains("\\n"), "should escape newline");

        Map<String, Object> back = codec.decode(text);
        assertEquals("a\"b\\c\nd", back.get("s"));
    }
}