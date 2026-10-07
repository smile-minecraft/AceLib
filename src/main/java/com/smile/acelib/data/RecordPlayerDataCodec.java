package com.smile.acelib.data;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以 Java record component 對映頂層欄位的預設 {@link PlayerDataCodec}（public API）。
 *
 * <p>每個 record component 對應一個同名的頂層欄位；巢狀 record 會遞迴展開成巢狀
 * {@code Map}，因此儲存格式是巢狀 JSON 而非扁平化字串。</p>
 *
 * <h2>支援的 component 型別</h2>
 * <ul>
 *   <li>基本型別與其包裝類別：{@code long/int/double/boolean/short/byte/float}、
 *       {@code Long/Integer/Double/Boolean/Short/Byte/Float/String}</li>
 *   <li>{@link Enum}（以名稱存放）</li>
 *   <li>{@link Optional}（缺失時為 {@code empty}）</li>
 *   <li>{@code List<T>}（元素為上述支援型別）</li>
 *   <li>{@code Map<String, Object>}</li>
 *   <li>巢狀 record（遞迴）</li>
 * </ul>
 *
 * <p>其他型別在建構時即拒絕（{@link IllegalArgumentException}），不在執行期才失敗。
 * 這是刻意的邊界：型別轉換規則一旦放寬就難以收斂，下游需要更寬鬆的映射時可自行
 * 實作 {@link PlayerDataCodec}。</p>
 *
 * <h2>缺欄位與非法資料</h2>
 * <p>欄位不存在時取 component 型別的預設值（primitive 零值、參考型別 {@code null}）。
 * 欄位存在但型別不符則拋 {@code ACELIB-DATA-002}，訊息帶欄位路徑，不做猜測式轉換。</p>
 *
 * <h2>執行緒</h2>
 * <p>本類無狀態，可安全共用於多執行緒。</p>
 *
 * @param <T> record 資料模型型別
 * @see PlayerDataCodec
 * @since 1.4.0
 */
public final class RecordPlayerDataCodec<T> implements PlayerDataCodec<T> {

    private final Class<T> type;
    private final List<RecordComponent> components;
    private final Constructor<T> canonical;

    /**
     * 建立以 {@code type} 為資料模型的 codec。
     *
     * @param type record 型別；不可為 null，且必須是 record 且所有 component 型別受支援
     * @throws NullPointerException     當 {@code type} 為 null
     * @throws IllegalArgumentException 當 {@code type} 不是 record，或含不支援的 component 型別
     */
    public RecordPlayerDataCodec(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                "資料模型必須是 record：" + type.getName());
        }
        this.components = List.of(type.getRecordComponents());
        for (RecordComponent component : components) {
            if (!isSupported(component.getType())) {
                throw new IllegalArgumentException(
                    "record component '" + component.getName() + "' 的型別 "
                        + component.getType().getName() + " 不受支援；"
                        + "需要更寬鬆的映射請自行實作 PlayerDataCodec");
            }
        }
        // canonical 建構子的參數型別必須逐一對應 component 的宣告型別，
        // 不能用無參建構子（record 沒有），也不能只傳空型別陣列。
        Class<?>[] paramTypes = new Class<?>[components.size()];
        for (int i = 0; i < components.size(); i++) {
            paramTypes[i] = components.get(i).getType();
        }
        try {
            this.canonical = type.getDeclaredConstructor(paramTypes);
            this.canonical.setAccessible(true);
        } catch (NoSuchMethodException | RuntimeException ex) {
            throw new IllegalArgumentException(
                "record " + type.getName() + " 沒有可用的 canonical 建構子：" + ex.getMessage(),
                ex);
        }
    }

    /**
     * 取得此 codec 對應的資料模型型別。
     *
     * @return 不可為 null 的型別
     */
    public Class<T> type() {
        return type;
    }

    @Override
    public Record encode(T value) {
        Objects.requireNonNull(value, "value");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (RecordComponent component : components) {
            Object raw;
            try {
                raw = component.getAccessor().invoke(value);
            } catch (ReflectiveOperationException ex) {
                throw new DataStoreException("ACELIB-DATA-006",
                    "failed to read record component '" + component.getName()
                        + "' of " + type.getName() + ": " + ex.getMessage(), ex);
            }
            Object encoded = encodeValue(component.getName(), raw);
            if (encoded != null) {
                fields.put(component.getName(), encoded);
            }
        }
        return new MemoryRecord("", fields);
    }

    @Override
    public T decode(Record record) {
        Objects.requireNonNull(record, "record");
        Object[] args = new Object[components.size()];
        for (int i = 0; i < components.size(); i++) {
            RecordComponent component = components.get(i);
            args[i] = decodeValue(component.getName(), component.getType(),
                record.get(component.getName()));
        }
        try {
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new DataStoreException("ACELIB-DATA-002",
                "failed to construct " + type.getName() + ": " + ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------
    // encode
    // -----------------------------------------------------------------

    private Object encodeValue(String path, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Optional<?> optional) {
            return optional.isEmpty() ? null : encodeValue(path, optional.get());
        }
        if (raw instanceof String || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof Number number) {
            return normalizeNumber(number);
        }
        if (raw instanceof Enum<?> constant) {
            return constant.name();
        }
        if (raw instanceof List<?> list) {
            List<Object> encoded = new ArrayList<>(list.size());
            for (Object item : list) {
                // List 元素允許 null（JSON null 有意義），不跳過
                encoded.add(item == null ? null : encodeValue(path, item));
            }
            return encoded;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new DataStoreException("ACELIB-DATA-006",
                        "field '" + path + "' 的 Map 鍵必須是 String，實際為 "
                            + describe(entry.getKey()));
                }
                encoded.put(key, entry.getValue() == null
                    ? null
                    : encodeValue(path + "." + key, entry.getValue()));
            }
            return encoded;
        }
        Class<?> rawClass = raw.getClass();
        if (rawClass.isRecord() && isSupported(rawClass)) {
            return encodeRecord(path, raw);
        }
        throw new DataStoreException("ACELIB-DATA-006",
            "field '" + path + "' 的值型別 " + rawClass.getName() + " 不受支援");
    }

    /** 巢狀 record 的編碼（避免泛型擦除造成 unchecked cast）。 */
    private Map<String, Object> encodeRecord(String path, Object instance) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (RecordComponent component : instance.getClass().getRecordComponents()) {
            Object componentValue;
            try {
                componentValue = component.getAccessor().invoke(instance);
            } catch (ReflectiveOperationException ex) {
                throw new DataStoreException("ACELIB-DATA-006",
                    "field '" + path + "' 的巢狀 record component '"
                        + component.getName() + "' 無法讀取：" + ex.getMessage(), ex);
            }
            Object encoded = encodeValue(path + "." + component.getName(), componentValue);
            if (encoded != null) {
                fields.put(component.getName(), encoded);
            }
        }
        return fields;
    }

    /**
     * 把 {@link Number} 收斂為 {@code Long} 或 {@code Double}。
     *
     * <p>避免 {@code Integer}／{@code Float} 等型別在 JSON 來回後變成別的型別，
     * 讓 round-trip 的型別穩定。</p>
     */
    private static Object normalizeNumber(Number number) {
        if (number instanceof Long || number instanceof Double) {
            return number;
        }
        if (number instanceof Float) {
            return number.doubleValue();
        }
        return number.longValue();
    }

    // -----------------------------------------------------------------
    // decode
    // -----------------------------------------------------------------

    private Object decodeValue(String path, Class<?> target, Object raw) {
        if (target == Optional.class) {
            return raw == null ? Optional.empty() : Optional.of(raw);
        }
        if (raw == null) {
            return defaultValue(target);
        }
        if (target == String.class) {
            if (raw instanceof String text) {
                return text;
            }
            throw typeMismatch(path, "String", raw);
        }
        if (target == boolean.class || target == Boolean.class) {
            if (raw instanceof Boolean flag) {
                return flag;
            }
            throw typeMismatch(path, "boolean", raw);
        }
        if (target == long.class || target == Long.class) {
            return toLong(path, raw);
        }
        if (target == int.class || target == Integer.class) {
            return (int) toLong(path, raw);
        }
        if (target == short.class || target == Short.class) {
            return (short) toLong(path, raw);
        }
        if (target == byte.class || target == Byte.class) {
            return (byte) toLong(path, raw);
        }
        if (target == double.class || target == Double.class) {
            return toDouble(path, raw);
        }
        if (target == float.class || target == Float.class) {
            return (float) toDouble(path, raw);
        }
        if (target.isEnum()) {
            if (raw instanceof String name) {
                for (Object constant : target.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(name)) {
                        return constant;
                    }
                }
                throw typeMismatch(path, "enum " + target.getSimpleName()
                    + "（允許：" + enumNames(target) + "）", raw);
            }
            throw typeMismatch(path, "enum " + target.getSimpleName(), raw);
        }
        if (target == List.class) {
            if (raw instanceof List<?> list) {
                return List.copyOf(list);
            }
            throw typeMismatch(path, "List", raw);
        }
        if (target == Map.class) {
            if (raw instanceof Map<?, ?> map) {
                return Map.copyOf(map);
            }
            throw typeMismatch(path, "Map", raw);
        }
        if (target.isRecord()) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw typeMismatch(path, "nested record " + target.getSimpleName(), raw);
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    fields.put(key, entry.getValue());
                }
            }
            return decodeNestedRecord(target, fields);
        }
        throw new DataStoreException("ACELIB-DATA-002",
            "field '" + path + "' 的目標型別 " + target.getName() + " 不受支援");
    }

    /** 巢狀 record 的解碼入口（避免泛型擦除造成 unchecked cast）。 */
    private static Object decodeNestedRecord(Class<?> target, Map<String, Object> fields) {
        RecordPlayerDataCodec<?> nested = new RecordPlayerDataCodec<>(target);
        return nested.decode(new MemoryRecord("", fields));
    }

    private static Object defaultValue(Class<?> target) {
        if (target == long.class) {
            return 0L;
        }
        if (target == int.class) {
            return 0;
        }
        if (target == short.class) {
            return (short) 0;
        }
        if (target == byte.class) {
            return (byte) 0;
        }
        if (target == double.class) {
            return 0.0d;
        }
        if (target == float.class) {
            return 0.0f;
        }
        if (target == boolean.class) {
            return Boolean.FALSE;
        }
        // String / enum / 參考型別皆為 null：欄位不存在不等於資料損壞
        return null;
    }

    private static long toLong(String path, Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        throw typeMismatch(path, "整數", raw);
    }

    private static double toDouble(String path, Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        throw typeMismatch(path, "數值", raw);
    }

    private static DataStoreException typeMismatch(String path, String expected, Object raw) {
        return new DataStoreException("ACELIB-DATA-002",
            "field '" + path + "' expects " + expected + " but stored value is "
                + describe(raw) + "; fix the stored data or supply a compatible codec");
    }

    private static String describe(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Map<?, ?>) {
            return "object";
        }
        if (raw instanceof List<?>) {
            return "array";
        }
        if (raw instanceof String) {
            return "string";
        }
        if (raw instanceof Boolean) {
            return "boolean";
        }
        if (raw instanceof Number) {
            return "number";
        }
        return raw.getClass().getName();
    }

    private static String enumNames(Class<?> target) {
        StringBuilder names = new StringBuilder();
        Object[] constants = target.getEnumConstants();
        for (int i = 0; i < constants.length; i++) {
            if (i > 0) {
                names.append('、');
            }
            names.append(((Enum<?>) constants[i]).name());
        }
        return names.toString();
    }

    private static boolean isSupported(Class<?> target) {
        if (target.isPrimitive()) {
            return target != char.class && target != void.class;
        }
        if (target.isEnum()) {
            return true;
        }
        if (target == String.class || target == Boolean.class
                || target == Long.class || target == Integer.class
                || target == Short.class || target == Byte.class
                || target == Double.class || target == Float.class
                || target == Object.class
                || target == List.class || target == Map.class
                || target == Optional.class) {
            return true;
        }
        if (target.isArray()) {
            return false;
        }
        return target.isRecord() && isSupportedComponents(target);
    }

    private static boolean isSupportedComponents(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            if (!isSupported(component.getType())) {
                return false;
            }
        }
        return true;
    }
}