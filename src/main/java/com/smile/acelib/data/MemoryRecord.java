package com.smile.acelib.data;

import java.util.Collections;
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
 *   <li>null 值條目保留：{@code has} 視為不存在、{@code get} 回 null、
 *       {@code set(path, null)} 等同移除、{@code snapshot} 保留並由
 *       {@link JsonCodec} 以 JSON {@code null} 落盤；不支援的型別拋
 *       {@code ACELIB-DATA-006}</li>
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
     * <p>null 策略：底層 map 中的 null 值條目會保留於快照（{@link JsonCodec}
     * 以 JSON {@code null} 落盤），不因 {@code Map.copyOf} 而拋 NPE；
     * {@link #has(String)}／{@link #get(String)} 對該條目視為不存在／null。</p>
     *
     * @return 不可變的 {@link Map}
     */
    public Map<String, Object> snapshot() {
        // 不用 Map.copyOf：其不接受 null 值，會對含 JSON null 的 record 拋 NPE
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    @Override
    public Record copy() {
        Map<String, Object> copied = new LinkedHashMap<>(data);
        return new MemoryRecord(key, copied);
    }

    /**
     * 深拷貝隔離視圖（僅供 {@link MigrationChain} 在單步內局部使用）。
     *
     * <p>與公開 {@link #copy()} 的淺拷貝不同：此方法遞迴複製巢狀
     * {@code Map}/{@code List} 結構，讓 migration 對巢狀節點的
     * {@code set}/{@code remove} 只影響寫入視圖，不污染讀取視圖。
     * 基本型別（{@link String}、{@link Number}、{@link Boolean}）與
     * {@code null} 為不可變，直接共用參考；容器一律重建為可變的
     * {@link LinkedHashMap}／{@link java.util.ArrayList}，不經 JSON
     * 序列化，因此不會改變合法值的實際型別（例如 {@link Integer} 不會
     * 變成 {@link Long}）。</p>
     *
     * @return 與本視圖資料相同但巢狀容器已隔離的新視圖
     */
    MemoryRecord copyIsolated() {
        return new MemoryRecord(key, deepCopyMap(data));
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source.size() + 1);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    private static java.util.List<Object> deepCopyList(java.util.List<?> source) {
        java.util.List<Object> copy = new java.util.ArrayList<>(source.size() + 1);
        for (Object item : source) {
            copy.add(deepCopyValue(item));
        }
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(map.size() + 1);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put((String) entry.getKey(), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof java.util.List<?> list) {
            return deepCopyList(list);
        }
        return value;
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
        return pr.parent.remove(pr.leaf) != null;
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
        requireValidPath(path);
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
     * 走訪 path，回傳對應的值與其「直接父層」map。
     *
     * <p>{@code parent} 恆為包含最終段 key 的那層 map（單段路徑時為根視圖的
     * {@code data}），讓 {@link #remove(String)} 能直接對父層操作。
     * 中間段缺失或型別非 Map 時，value 為 null、parent 為最後成功取得的那層。</p>
     *
     * @param path 點分隔路徑
     * @return 走訪結果
     */
    private PathResult resolvePathSegments(String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i];
            if (i == parts.length - 1) {
                return new PathResult(current, segment, current.get(segment));
            }
            Object value = current.get(segment);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                current = typed;
            } else {
                // 路徑中段不是 map：視為不存在
                return new PathResult(current, segment, null);
            }
        }
        return new PathResult(data, "", null);
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