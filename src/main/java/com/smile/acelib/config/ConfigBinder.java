package com.smile.acelib.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 快照到 record／一般類別的綁定器。
 *
 * <p>補上 {@code schema 只檢查必填欄位在不在} 的缺口：綁定時驗證
 * 型別、數值範圍與列舉值，任一失敗拋 {@link ConfigBindingException}
 *（{@code ACELIB-CFG-007}），訊息帶完整欄位路徑。</p>
 *
 * <h2>支援的目標形狀</h2>
 * <ul>
 *   <li>record：走 canonical constructor；component 名即路徑，
 *       以 {@link ConfigKey} 覆寫路徑（含點分隔巢狀路徑）</li>
 *   <li>一般類別：無參建構＋欄位注入（含 private 欄位）；
 *       欄位名即路徑，以 {@link ConfigKey} 覆寫</li>
 * </ul>
 *
 * <h2>支援的欄位型別</h2>
 * <p>{@code String}、{@code int／Integer}、{@code long／Long}、
 * {@code double／Double}、{@code boolean／Boolean}、列舉、
 * {@code List<String>}、{@code Optional<T>}（缺失時為 empty，
 * T 限上述純量與列舉）與巢狀 record／POJO（該路徑須為 map）。</p>
 *
 * <h2>缺失與型別語意</h2>
 * <ul>
 *   <li>缺失的基本型別（{@code int} 等）報錯；缺失的參考型別
 *      （{@code String}／{@code Integer}／列舉／{@code List}／巢狀型別）
 *       為 null，由呼叫端決定是否接受</li>
 *   <li>{@code int}／{@code long} 嚴格轉換：小數、NaN／無限大、超出範圍
 *       一律報錯，不靜默截斷或溢位</li>
 *   <li>{@code List} 元素逐個轉字串，不做元素型別檢查；
 *       YAML 的 null 元素保留為 null</li>
 *   <li>列舉按名稱精確比對（大小寫敏感）；失敗訊息列出全部合法選項</li>
 * </ul>
 *
 * <h2>範例</h2>
 * <pre>{@code
 * public record ServerSettings(
 *     @ConfigBinder.ConfigKey("server.host") String host,
 *     @ConfigBinder.ConfigKey("server.port")
 *     @ConfigBinder.ConfigRange(min = 1, max = 65535) int port,
 *     @ConfigBinder.ConfigKey("server.mode") Mode mode) {}
 *
 * ServerSettings settings = ConfigBinder.bind(snapshot, ServerSettings.class);
 * }</pre>
 *
 * @since 1.4.0
 */
public final class ConfigBinder {

    private ConfigBinder() {
        // 靜態工具，不提供實例
    }

    /**
     * 覆寫綁定路徑（預設為 component／欄位名）。
     *
     * <p>含點的值視為從根起的絕對路徑（例如 {@code "server.port"}）；
     * 不含點的值在巢狀綁定時會補上外層前綴。</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
    public @interface ConfigKey {
        /**
         * @return 點分隔路徑（例如 {@code "server.port"}）
         */
        String value();
    }

    /**
     * 數值範圍約束（套用於數值型 component／欄位，含邊界）。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
    public @interface ConfigRange {
        /**
         * @return 最小值（含）
         */
        double min();

        /**
         * @return 最大值（含）
         */
        double max();
    }

    /**
     * 把快照綁定到 record 或一般類別。
     *
     * @param snapshot 來源快照；不可為 null
     * @param type     目標型別；不可為 null
     * @param <T>      目標型別
     * @return 綁定完成的實例
     * @throws NullPointerException   當任一參數為 null
     * @throws ConfigBindingException 當型別、範圍、列舉或缺失檢查失敗（ACELIB-CFG-007）
     */
    public static <T> T bind(ConfigSnapshot snapshot, Class<T> type) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(type, "type");
        if (type.isRecord()) {
            return bindRecord(snapshot, type, "");
        }
        return bindPojo(snapshot, type, "");
    }

    // -----------------------------------------------------------------
    // record
    // -----------------------------------------------------------------

    private static <T> T bindRecord(ConfigSnapshot snapshot, Class<T> type, String prefix) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            String path = join(prefix, configKeyOf(component));
            args[i] = convert(snapshot, path, component.getType(),
                rangeOf(component), component.getGenericType());
        }
        try {
            Constructor<T> canonical = type.getDeclaredConstructor(paramTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new ConfigBindingException(prefix.isEmpty() ? "(root)" : prefix,
                "無法建立 " + type.getSimpleName() + "：" + ex.getMessage());
        }
    }

    private static String configKeyOf(RecordComponent component) {
        // 宣告在 component 上的註解會依 @Target 自動傳播到欄位／存取子／建構參數，
        // 因此 component 本身一定找得到；存取子只當退路。
        ConfigKey key = component.getAnnotation(ConfigKey.class);
        if (key == null) {
            try {
                key = component.getAccessor().getAnnotation(ConfigKey.class);
            } catch (SecurityException ignored) {
                // 取不到存取子註解時退回 component 名
            }
        }
        return key == null ? component.getName() : key.value();
    }

    private static ConfigRange rangeOf(RecordComponent component) {
        ConfigRange range = component.getAnnotation(ConfigRange.class);
        if (range == null) {
            try {
                range = component.getAccessor().getAnnotation(ConfigRange.class);
            } catch (SecurityException ignored) {
                // 無範圍約束
            }
        }
        return range;
    }

    // -----------------------------------------------------------------
    // 一般類別
    // -----------------------------------------------------------------

    private static <T> T bindPojo(ConfigSnapshot snapshot, Class<T> type, String prefix) {
        Constructor<T> noArg;
        try {
            noArg = type.getDeclaredConstructor();
            noArg.setAccessible(true);
        } catch (NoSuchMethodException ex) {
            throw new ConfigBindingException(prefix.isEmpty() ? "(root)" : prefix,
                "一般類別 " + type.getSimpleName() + " 必須有無參建構子才能綁定");
        }
        T instance;
        try {
            instance = noArg.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new ConfigBindingException(prefix.isEmpty() ? "(root)" : prefix,
                "無法建立 " + type.getSimpleName() + "：" + ex.getMessage());
        }
        for (Field field : allFields(type)) {
            ConfigKey key = field.getAnnotation(ConfigKey.class);
            if (key == null) {
                continue;
            }
            String path = join(prefix, key.value());
            Object value = convert(snapshot, path, field.getType(),
                field.getAnnotation(ConfigRange.class), field.getGenericType());
            try {
                field.setAccessible(true);
                field.set(instance, value);
            } catch (ReflectiveOperationException ex) {
                throw new ConfigBindingException(path,
                    "無法寫入欄位 " + field.getName() + "：" + ex.getMessage());
            }
        }
        return instance;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
             cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    // -----------------------------------------------------------------
    // 值轉換
    // -----------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(ConfigSnapshot snapshot, String path, Class<?> target,
                                  ConfigRange range, java.lang.reflect.Type generic) {
        // Optional<T>：缺失時為 empty
        if (target == Optional.class) {
            Class<?> inner = Object.class;
            if (generic instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> innerClass) {
                inner = innerClass;
            }
            Object raw = snapshot.get(path);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(convertValue(snapshot, path, raw, inner, range));
        }
        Object raw = snapshot.get(path);
        if (raw == null) {
            if (target.isPrimitive()) {
                throw new ConfigBindingException(path,
                    "缺少必填值，目標為基本型別 " + target.getSimpleName());
            }
            // 巢狀 record／POJO 缺失時回傳 null（由呼叫端決定是否接受）
            if (isNestedType(target)) {
                return null;
            }
            if (target == String.class || target == List.class || target.isEnum()) {
                return null;
            }
            return null;
        }
        return convertValue(snapshot, path, raw, target, range);
    }

    private static boolean isNestedType(Class<?> target) {
        return target.isRecord() || (!isSimple(target) && !target.isEnum() && target != List.class
            && target != Object.class && !target.isPrimitive()
            && !Number.class.isAssignableFrom(target) && target != String.class
            && target != Boolean.class && target != Optional.class);
    }

    private static boolean isSimple(Class<?> target) {
        return target == String.class || target == int.class || target == Integer.class
            || target == long.class || target == Long.class
            || target == double.class || target == Double.class
            || target == boolean.class || target == Boolean.class;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertValue(ConfigSnapshot snapshot, String path, Object raw,
                                       Class<?> target, ConfigRange range) {
        if (target == String.class) {
            if (raw instanceof String text) {
                return text;
            }
            throw bindingTypeError(path, "字串", raw);
        }
        if (target == int.class || target == Integer.class) {
            int value = asInt(path, raw);
            checkRange(path, value, range);
            return value;
        }
        if (target == long.class || target == Long.class) {
            long value = asLong(path, raw);
            checkRange(path, (double) value, range);
            return value;
        }
        if (target == double.class || target == Double.class) {
            double numeric = asNumber(path, raw, "數值");
            checkRange(path, numeric, range);
            return numeric;
        }
        if (target == boolean.class || target == Boolean.class) {
            if (raw instanceof Boolean flag) {
                return flag;
            }
            throw bindingTypeError(path, "布林", raw);
        }
        if (target.isEnum()) {
            String name = raw.toString();
            for (Object constant : target.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
            List<String> allowed = new ArrayList<>();
            for (Object constant : target.getEnumConstants()) {
                allowed.add(((Enum<?>) constant).name());
            }
            throw new ConfigBindingException(path,
                "列舉值非法 " + name + "，允許：" + String.join("、", allowed));
        }
        if (target == List.class) {
            if (raw instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(item == null ? null : item.toString());
                }
                // 元素可能為 null：不用 List.copyOf（會 NPE），改 unmodifiable 包裝
                return java.util.Collections.unmodifiableList(out);
            }
            throw bindingTypeError(path, "清單", raw);
        }
        // 巢狀 record／POJO：沿用同一快照、以前綴遞迴，內層的絕對路徑
        //（含點的 ConfigKey）直接命中根快照，相對名則補上前綴。
        if (target.isRecord() || isNestedType(target)) {
            if (!(raw instanceof java.util.Map)) {
                throw bindingTypeError(path, "節點（map）", raw);
            }
            if (target.isRecord()) {
                return bindRecord(snapshot, target, path);
            }
            return bindPojo(snapshot, target, path);
        }
        throw new ConfigBindingException(path,
            "不支援的綁定型別 " + target.getSimpleName() + "（實際值：" + describe(raw) + "）");
    }

    private static double asNumber(String path, Object raw, String expected) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        throw bindingTypeError(path, expected, raw);
    }

    /**
     * 嚴格轉 int：小數、無限大／NaN、超出範圍一律報錯，不靜默截斷或溢位。
     *
     * <p>邊界用 2 的冪比較（{@code -2^31}／{@code 2^31} 在 double 精確可表示），
     * 避免 {@code (double) Integer.MAX_VALUE} 舍入造成的邊界誤判。</p>
     */
    private static int asInt(String path, Object raw) {
        if (raw instanceof Integer value) {
            return value;
        }
        if (raw instanceof Long value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new ConfigBindingException(path,
                    "超出 int 範圍 " + raw + "（" + describe(raw) + "）");
            }
            return value.intValue();
        }
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)
                || numeric < -2147483648.0 || numeric >= 2147483648.0
                || numeric != Math.rint(numeric)) {
                throw new ConfigBindingException(path,
                    "期望 int 範圍內的整數，實際為 " + raw + "（" + describe(raw) + "）");
            }
            return (int) numeric;
        }
        throw bindingTypeError(path, "整數", raw);
    }

    /**
     * 嚴格轉 long：小數截斷與超出範圍一律報錯，不靜默處理。
     */
    private static long asLong(String path, Object raw) {
        if (raw instanceof Long value) {
            return value;
        }
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)
                || numeric < -9223372036854775808.0 || numeric >= 9223372036854775808.0
                || numeric != Math.rint(numeric)) {
                throw new ConfigBindingException(path,
                    "期望 long 範圍內的整數，實際為 " + raw + "（" + describe(raw) + "）");
            }
            return (long) numeric;
        }
        throw bindingTypeError(path, "長整數", raw);
    }

    private static void checkRange(String path, double value, ConfigRange range) {
        if (range != null && (value < range.min() || value > range.max())) {
            throw new ConfigBindingException(path,
                "數值 " + value + " 超出範圍 [" + range.min() + ", " + range.max() + "]");
        }
    }

    private static ConfigBindingException bindingTypeError(String path, String expected, Object raw) {
        return new ConfigBindingException(path,
            "期望" + expected + "，實際為 " + raw + "（" + describe(raw) + "）");
    }

    private static String describe(Object raw) {
        if (raw == null) {
            return "缺失";
        }
        return "型別 " + raw.getClass().getSimpleName();
    }

    private static String join(String prefix, String path) {
        if (prefix == null || prefix.isEmpty()) {
            return path;
        }
        // ConfigKey 本來就可能帶完整路徑（含點）：前綴只在相對路徑時補上
        if (path.contains(".")) {
            return path;
        }
        return prefix + "." + path;
    }
}
