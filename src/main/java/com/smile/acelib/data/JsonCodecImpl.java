package com.smile.acelib.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 極簡 JSON 編解碼器（內建實作）。
 *
 * <p>對應 Plan §十三 Phase 8「本地輕量資料儲存」與「資料表 / 資料格式初始化」需求。
 * 刻意不依賴 Gson / Jackson 等外部函式庫，僅使用 Java 標準 API；目的：</p>
 * <ul>
 *   <li>避免引入大型序列化依賴（與 Plan §三 (2) 「不引入複雜 ORM」一致）</li>
 *   <li>支援型別嚴格白名單：基本型別、{@code null}、{@link Map}/{@link List}</li>
 *   <li>JSON 結構可控，方便 round-trip 測試</li>
 * </ul>
 *
 * <h2>支援型別</h2>
 * <ul>
 *   <li>{@link String}、{@link Integer}、{@link Long}、{@link Double}、{@link Boolean}</li>
 *   <li>{@code null}</li>
 *   <li>{@code Map<String, Object>}</li>
 *   <li>{@code List<Object>}</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-DATA-002}：JSON 解析失敗</li>
 *   <li>{@code ACELIB-DATA-006}：不支援的型別（round-trip 白名單外）</li>
 * </ul>
 *
 * @since Phase 8 (Plan §十三)
 */
public final class JsonCodecImpl implements JsonCodec {

    /** 允許的型別白名單（給 {@link #validateValue(Object)} 使用）。 */
    private static final Class<?>[] ALLOWED_TYPES = {
        String.class,
        Integer.class,
        Long.class,
        Double.class,
        Boolean.class,
        Map.class,
        List.class,
    };

    @Override
    public String encodeVersion(SchemaVersion version) {
        Objects.requireNonNull(version, "version");
        return version.toString();
    }

    @Override
    public SchemaVersion decodeVersion(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new DataStoreException("ACELIB-DATA-002",
                "version string is empty");
        }
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            throw new DataStoreException("ACELIB-DATA-002",
                "invalid version format: " + text);
        }
        try {
            int major = Integer.parseInt(trimmed.substring(0, dot).trim());
            int minor = Integer.parseInt(trimmed.substring(dot + 1).trim());
            return new SchemaVersion(major, minor);
        } catch (NumberFormatException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "invalid version format: " + text, ex);
        }
    }

    @Override
    public String encode(Object value) {
        validateValue(value);
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @Override
    public Map<String, Object> decode(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Parser p = new Parser(trimmed);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.hasMore()) {
            throw new DataStoreException("ACELIB-DATA-002",
                "unexpected trailing characters at position " + p.pos());
        }
        if (!(value instanceof Map)) {
            throw new DataStoreException("ACELIB-DATA-002",
                "root JSON value must be object, got " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    // -----------------------------------------------------------------
    // Encoder
    // -----------------------------------------------------------------

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Integer i) {
            sb.append(i.intValue());
        } else if (value instanceof Long l) {
            sb.append(l.longValue());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new DataStoreException("ACELIB-DATA-006",
                    "NaN/Infinity not supported in JSON");
            }
            sb.append(d.doubleValue());
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            throw new DataStoreException("ACELIB-DATA-006",
                "unsupported type: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Object key = e.getKey();
            if (!(key instanceof String s)) {
                throw new DataStoreException("ACELIB-DATA-006",
                    "map key must be String, got " + (key == null ? "null" : key.getClass().getName()));
            }
            writeString(sb, s);
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // -----------------------------------------------------------------
    // Decoder
    // -----------------------------------------------------------------

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        int pos() {
            return pos;
        }

        boolean hasMore() {
            return pos < src.length();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new DataStoreException("ACELIB-DATA-002",
                    "unexpected end of input");
            }
            char c = src.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBoolean();
            }
            if (c == 'n') {
                return readNull();
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                Object key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put((String) key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new DataStoreException("ACELIB-DATA-002",
                    "expected ',' or '}' at position " + pos);
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new DataStoreException("ACELIB-DATA-002",
                    "expected ',' or ']' at position " + pos);
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new DataStoreException("ACELIB-DATA-002",
                            "unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new DataStoreException("ACELIB-DATA-002",
                                    "incomplete \\u escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new DataStoreException("ACELIB-DATA-002",
                                    "invalid \\u escape: " + hex, ex);
                            }
                        }
                        default -> throw new DataStoreException("ACELIB-DATA-002",
                            "invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new DataStoreException("ACELIB-DATA-002",
                "unterminated string");
        }

        private Boolean readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new DataStoreException("ACELIB-DATA-002",
                "expected boolean at position " + pos);
        }

        private Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new DataStoreException("ACELIB-DATA-002",
                "expected null at position " + pos);
        }

        private Number readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isFloat = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else if (Character.isDigit(c)) {
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            if (num.isEmpty()) {
                throw new DataStoreException("ACELIB-DATA-002",
                    "expected number at position " + start);
            }
            try {
                if (isFloat) {
                    return Double.parseDouble(num);
                }
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException ex) {
                throw new DataStoreException("ACELIB-DATA-002",
                    "invalid number: " + num, ex);
            }
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new DataStoreException("ACELIB-DATA-002",
                    "expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        private char peek() {
            if (pos >= src.length()) {
                return '\0';
            }
            return src.charAt(pos);
        }
    }

    // -----------------------------------------------------------------
    // 型別驗證
    // -----------------------------------------------------------------

    private static void validateValue(Object value) {
        if (value == null) {
            return;
        }
        for (Class<?> allowed : ALLOWED_TYPES) {
            if (allowed.isInstance(value)) {
                if (value instanceof Map<?, ?> map) {
                    for (Object k : map.keySet()) {
                        if (!(k instanceof String)) {
                            throw new DataStoreException("ACELIB-DATA-006",
                                "map key must be String, got "
                                    + (k == null ? "null" : k.getClass().getName()));
                        }
                    }
                    for (Object v : map.values()) {
                        validateValue(v);
                    }
                } else if (value instanceof List<?> list) {
                    for (Object item : list) {
                        validateValue(item);
                    }
                }
                return;
            }
        }
        throw new DataStoreException("ACELIB-DATA-006",
            "unsupported type: " + value.getClass().getName());
    }
}