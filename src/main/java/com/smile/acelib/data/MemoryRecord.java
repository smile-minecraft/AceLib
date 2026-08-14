package com.smile.acelib.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link Record} 的標準 in-memory 實作。
 *
 * <p>以 {@link LinkedHashMap} 表達階層式鍵值結構，支援基本型別 + 巢狀
 * {@code Map} + {@code List}。</p>
 *
 * <h2>設計約定</h2>
 * <ul>
 *   <li>所有 {@code getXxx} 對缺失 path 回傳對應 default，不丟例外</li>
 *   <li>所有 {@code set} 對 null path 拋 {@link DataStoreException}（{@code ACELIB-DATA-003}）</li>
 *   <li>型別不符時回傳 default，不丟例外（避免 migration 期間因單一欄位崩潰）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class MemoryRecord implements Record {

    private final String key;
    private final Map<String, Object> data;

    /**
     * 建構一個根視圖。
     */
    public MemoryRecord() {
        this("", new LinkedHashMap<>());
    }

    /**
     * 建構一個子視圖（自訂 key 與既有 data 引用）。
     *
     * @param key  此視圖的 key；可為空字串（根）
     * @param data 對應的底層 map；不可為 null
     */
    public MemoryRecord(String key, Map<String, Object> data) {
        this.key = Objects.requireNonNull(key, "key");
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public String key() {
        return key;
    }

    /**
     * 取得底層 map 的不可變快照（僅供診斷／序列化使用；外部請勿修改）。
     *
     * @return 不可變的 {@link Map}
     */
    public Map<String, Object> snapshot() {
        return Map.copyOf(data);
    }

    @Override
    public Record copy() {
        Map<String, Object> copied = new LinkedHashMap<>(data);
        return new MemoryRecord(key, copied);
    }

    /**
     * Replace this record's underlying contents with the given entries (in-place).
     *
     * <p>Intended for {@link MigrationChain} to commit the migrated final state
     * back into the caller-provided {@code readView} so the caller observes the
     * merged result through the same {@link Record} reference. Package-private
     * because this is an internal migration-coordination contract; not part of
     * the public {@link Record} API.</p>
     *
     * @param entries the new entries; must not be {@code null}
     */
    void replaceContents(Map<String, Object> entries) {
        Objects.requireNonNull(entries, "entries");
        data.clear();
        data.putAll(entries);
    }

    @Override
    public boolean has(String path) {
        requireValidPath(path);
        return resolvePathSegments(path).value != null;
    }

    @Override
    public Object get(String path) {
        requireValidPath(path);
        PathResult pr = resolvePathSegments(path);
        return pr.value;
    }

    @Override
    public Object set(String path, Object value) {
        requireValidPath(path);
        validateValue(value);
        String[] parts = path.split("\\.");
        // 找出（或建立）最終段的 parent map
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            String segment = parts[i];
            Object existing = current.get(segment);
            if (existing instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                current = typed;
            } else if (existing == null) {
                // 中間段不存在：建立新 Map 並掛上
                LinkedHashMap<String, Object> fresh = new LinkedHashMap<>();
                current.put(segment, fresh);
                current = fresh;
            } else {
                // 中間段既不是 Map 也不是 null：覆寫為新 Map（保留舊值丟失）
                LinkedHashMap<String, Object> fresh = new LinkedHashMap<>();
                current.put(segment, fresh);
                current = fresh;
            }
        }
        String leaf = parts[parts.length - 1];
        Object previous = current.get(leaf);
        if (value == null) {
            current.remove(leaf);
        } else {
            current.put(leaf, value);
        }
        return previous;
    }

    @Override
    public boolean remove(String path) {
        requireValidPath(path);
        PathResult pr = resolvePathSegments(path);
        if (pr.value == null) {
            return false;
        }
        if (pr.parent == null) {
            data.remove(pr.leaf);
        } else {
            Map<String, Object> parentMap = asMap(pr.parent.get(pr.leaf));
            if (parentMap != null) {
                parentMap.remove(pr.leaf);
            }
        }
        return true;
    }

    @Override
    public Set<String> keys() {
        return Set.copyOf(data.keySet());
    }

    @Override
    public String getString(String path, String defaultValue) {
        Object value = get(path);
        if (value instanceof String s) {
            return s;
        }
        return defaultValue;
    }

    @Override
    public int getInt(String path, int defaultValue) {
        Object value = get(path);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public long getLong(String path, long defaultValue) {
        Object value = get(path);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        Object value = get(path);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = get(path);
        if (value instanceof Boolean b) {
            return b;
        }
        return defaultValue;
    }

    @Override
    public Record getRecord(String path, Record defaultValue) {
        PathResult pr = resolvePathSegments(path);
        if (pr.value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return new MemoryRecord(pr.leaf, typed);
        }
        return defaultValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String path, Class<T> type, T defaultValue) {
        Objects.requireNonNull(type, "type");
        Object value = get(path);
        if (value == null) {
            return defaultValue;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        // 型別轉換（基本型別）
        if (type == String.class) {
            if (value instanceof Number n) {
                return (T) n.toString();
            }
            if (value instanceof Boolean b) {
                return (T) Boolean.toString(b);
            }
            if (value instanceof String s) {
                return (T) s;
            }
        }
        if ((type == Integer.class || type == int.class) && value instanceof Number n) {
            return (T) Integer.valueOf(n.intValue());
        }
        if ((type == Long.class || type == long.class) && value instanceof Number n) {
            return (T) Long.valueOf(n.longValue());
        }
        if ((type == Double.class || type == double.class) && value instanceof Number n) {
            return (T) Double.valueOf(n.doubleValue());
        }
        if ((type == Boolean.class || type == boolean.class) && value instanceof Boolean b) {
            return (T) b;
        }
        return defaultValue;
    }

    // -----------------------------------------------------------------
    // Internal: path navigation
    // -----------------------------------------------------------------

    /**
     * 走訪 path，回傳對應的值與其父節點 map（root 視圖時 parent = null）。
     *
     * @param path 點分隔路徑
     * @return 走訪結果
     */
    private PathResult resolvePathSegments(String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;
        Map<String, Object> parent = null;
        Object value = null;
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i];
            value = current == null ? null : current.get(segment);
            if (i == parts.length - 1) {
                return new PathResult(parent, segment, value);
            }
            if (value instanceof Map<?, ?> map) {
                parent = current;
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                current = typed;
            } else {
                // 路徑中段不是 map：視為不存在
                return new PathResult(parent, segment, null);
            }
        }
        return new PathResult(null, "", null);
    }

    /**
     * 確保 {@code parent[leaf]} 是一個 {@code Map<String, Object>}，必要時建立空 map。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureMap(Map<String, Object> parent, String leaf) {
        Object existing = parent.get(leaf);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        LinkedHashMap<String, Object> fresh = new LinkedHashMap<>();
        parent.put(leaf, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static void requireValidPath(String path) {
        if (path == null) {
            throw new DataStoreException("ACELIB-DATA-003",
                "path must not be null");
        }
        if (path.isBlank()) {
            throw new DataStoreException("ACELIB-DATA-003",
                "path must not be blank");
        }
    }

    /**
     * 驗證 value 是否在允許型別白名單內（遞迴檢查 Map/List）。
     */
    private static void validateValue(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return;
        }
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
            return;
        }
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                validateValue(item);
            }
            return;
        }
        throw new DataStoreException("ACELIB-DATA-006",
            "unsupported type: " + value.getClass().getName());
    }

    /**
     * 路徑走訪的內部結果：父節點 + 最終段 + 對應值。
     */
    private static final class PathResult {
        final Map<String, Object> parent;
        final String leaf;
        final Object value;

        PathResult(Map<String, Object> parent, String leaf, Object value) {
            this.parent = parent;
            this.leaf = leaf;
            this.value = value;
        }
    }
}