package com.smile.acelib.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * {@code List<T>}、{@code Set<T>}、{@code Map<String, T>}、
 * {@code Optional<T>}（缺失時為 empty，T 限上述純量、列舉與集合）與
 * 巢狀 record／POJO（該路徑須為 map）。集合的元素／值型別 {@code T}
 * 取自宣告的泛型參數，逐元素驗證。</p>
 *
 * <h2>缺失與型別語意</h2>
 * <ul>
 *   <li>缺失的基本型別（{@code int} 等）報錯；缺失的參考型別
 *      （{@code String}／{@code Integer}／列舉／集合／巢狀型別）
 *       為 null，由呼叫端決定是否接受</li>
 *   <li>{@code int}／{@code long} 嚴格轉換：小數、NaN／無限大、超出範圍
 *       一律報錯，不靜默截斷或溢位</li>
 *   <li>{@code double} 非有限值（NaN／無限大）一律報錯，不論有無範圍約束</li>
 *   <li>集合元素逐個依宣告型別驗證：{@code List<String>}、
 *       元素為 {@code Object} 與未指定泛型的 {@code List}
 *       維持既有行為（元素逐個轉字串，null 保留）；其他清單元素型別
 *       （數值、列舉、巢狀型別）走與純量欄位相同的嚴格規則；
 *       YAML 的 null 元素保留為 null</li>
 *   <li>{@code Map} 只支援 {@code Map<String, T>}（鍵為 YAML 鍵名）；
 *       {@code Set} 由 YAML 清單建構，依 equals 去重並保留首次出現順序；
 *       兩者的元素一律嚴格驗證（{@code String} 只接受字串，
 *       {@code Object} 原值保留），不走清單的相容轉換</li>
 *   <li>萬用字元／型別變數推斷不出驗證規則時明確報錯，
 *       不默默轉字串產生錯型別集合</li>
 *   <li>集合內的數值元素同樣受欄位上的 {@link ConfigRange} 約束</li>
 *   <li>集合元素／巢狀元素內的錯誤帶完整元素路徑：Map 項目為
 *       {@code <path>.<key>}、Set 與清單元素為 {@code <path>[i]}、
 *       巢狀元素內欄位為 {@code <path>[i].<field>}；
 *       集合元素內的絕對路徑（{@code ConfigKey} 含點）仍從根解析</li>
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

    /**
     * 綁定視野：查詢路徑與錯誤路徑分開追蹤。
     *
     * <p>根綁定時兩份快照皆為來源快照、兩個前綴皆為空；
     * 巢狀綁定沿用同一視野、只延伸路徑；集合元素綁定時
     * {@code view} 換成元素子快照、查詢前綴歸零、錯誤前綴記為元素路徑
     * （例如 {@code endpoints[0]}），查詢與報錯才不會互相拖累。
     * 含點的絕對路徑一律在 {@code root} 解析，不受視野影響。</p>
     *
     * @param root   根快照（絕對路徑的解析對象）
     * @param view   相對路徑的解析對象（根綁定與巢狀綁定時即 root）
     * @param lookup 在 view 內的查詢前綴（元素內為空字串）
     * @param error  錯誤顯示前綴（元素為元素路徑）
     */
    private record BindScope(ConfigSnapshot root, ConfigSnapshot view, String lookup, String error) {
    }

    private static <T> T bindRecord(BindScope scope, Class<T> type) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            args[i] = convert(scope, configKeyOf(component), component.getType(),
                rangeOf(component), component.getGenericType());
        }
        try {
            Constructor<T> canonical = type.getDeclaredConstructor(paramTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            String where = scope.error().isEmpty() ? "(root)" : scope.error();
            throw new ConfigBindingException(where,
                "無法建立 " + type.getSimpleName() + "：" + ex.getMessage());
        }
    }

    private static <T> T bindRecord(ConfigSnapshot snapshot, Class<T> type, String prefix) {
        return bindRecord(new BindScope(snapshot, snapshot, prefix, prefix), type);
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

    private static <T> T bindPojo(BindScope scope, Class<T> type) {
        Constructor<T> noArg;
        try {
            noArg = type.getDeclaredConstructor();
            noArg.setAccessible(true);
        } catch (NoSuchMethodException ex) {
            String where = scope.error().isEmpty() ? "(root)" : scope.error();
            throw new ConfigBindingException(where,
                "一般類別 " + type.getSimpleName() + " 必須有無參建構子才能綁定");
        }
        T instance;
        try {
            instance = noArg.newInstance();
        } catch (ReflectiveOperationException ex) {
            String where = scope.error().isEmpty() ? "(root)" : scope.error();
            throw new ConfigBindingException(where,
                "無法建立 " + type.getSimpleName() + "：" + ex.getMessage());
        }
        for (Field field : allFields(type)) {
            ConfigKey key = field.getAnnotation(ConfigKey.class);
            if (key == null) {
                continue;
            }
            Object value = convert(scope, key.value(), field.getType(),
                field.getAnnotation(ConfigRange.class), field.getGenericType());
            try {
                field.setAccessible(true);
                field.set(instance, value);
            } catch (ReflectiveOperationException ex) {
                throw new ConfigBindingException(errorPathOf(scope, key.value()),
                    "無法寫入欄位 " + field.getName() + "：" + ex.getMessage());
            }
        }
        return instance;
    }

    private static <T> T bindPojo(ConfigSnapshot snapshot, Class<T> type, String prefix) {
        return bindPojo(new BindScope(snapshot, snapshot, prefix, prefix), type);
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
    private static Object convert(BindScope scope, String key,
                                  Class<?> target, ConfigRange range, Type generic) {
        // 絕對路徑（含點的 ConfigKey）在根解析；相對路徑在視野內解析。
        boolean absolute = key.contains(".");
        String lookupPath = absolute ? key : join(scope.lookup(), key);
        String errorPath = absolute ? key : join(scope.error(), key);
        ConfigSnapshot source = absolute ? scope.root() : scope.view();
        // Optional<T>：缺失時為 empty
        if (target == Optional.class) {
            Type innerType = firstTypeArgument(generic);
            Class<?> inner = innerType == null ? Object.class : resolvedClass(innerType);
            if (inner == null) {
                throw new ConfigBindingException(errorPath,
                    "Optional 的元素型別無法推斷（萬用字元／型別變數不支援），請改用具體型別宣告");
            }
            Object raw = source.get(lookupPath);
            if (raw == null) {
                return Optional.empty();
            }
            if (isNestedType(inner)) {
                BindScope child = new BindScope(scope.root(), source, lookupPath, errorPath);
                return Optional.of(inner.isRecord()
                    ? bindRecord(child, (Class) inner) : bindPojo(child, (Class) inner));
            }
            return Optional.of(convertNonNested(scope, errorPath, raw, inner, range, innerType));
        }
        Object raw = source.get(lookupPath);
        if (raw == null) {
            if (target.isPrimitive()) {
                throw new ConfigBindingException(errorPath,
                    "缺少必填值，目標為基本型別 " + target.getSimpleName());
            }
            // 巢狀 record／POJO 缺失時回傳 null（由呼叫端決定是否接受）
            if (isNestedType(target)) {
                return null;
            }
            if (target == String.class || target == List.class || target == Set.class
                || target == Map.class || target.isEnum()) {
                return null;
            }
            return null;
        }
        return convertValue(scope, lookupPath, errorPath, source, raw, target, range, generic);
    }

    private static boolean isNestedType(Class<?> target) {
        return target.isRecord() || (!isSimple(target) && !target.isEnum() && target != List.class
            && target != Set.class && target != Map.class
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
    private static Object convertValue(BindScope scope, String lookupPath, String errorPath,
                                       ConfigSnapshot source, Object raw,
                                       Class<?> target, ConfigRange range, Type generic) {
        // 巢狀 record／POJO：查詢視野沿用 scope，錯誤前綴已由呼叫端換算；
        // 內層的絕對路徑（含點的 ConfigKey）直接命中根快照，相對名則補上前綴。
        if (target.isRecord() || isNestedType(target)) {
            if (!(raw instanceof java.util.Map)) {
                throw bindingTypeError(errorPath, "節點（map）", raw);
            }
            BindScope child = new BindScope(scope.root(), source, lookupPath, errorPath);
            if (target.isRecord()) {
                return bindRecord(child, (Class) target);
            }
            return bindPojo(child, (Class) target);
        }
        return convertNonNested(scope, errorPath, raw, target, range, generic);
    }

    private static Object convertNonNested(BindScope scope, String errorPath, Object raw,
                                           Class<?> target, ConfigRange range, Type generic) {
        if (target == String.class) {
            if (raw instanceof String text) {
                return text;
            }
            throw bindingTypeError(errorPath, "字串", raw);
        }
        if (target == int.class || target == Integer.class) {
            int value = asInt(errorPath, raw);
            checkRange(errorPath, value, range);
            return value;
        }
        if (target == long.class || target == Long.class) {
            long value = asLong(errorPath, raw);
            checkRange(errorPath, (double) value, range);
            return value;
        }
        if (target == double.class || target == Double.class) {
            double numeric = asNumber(errorPath, raw, "數值");
            checkRange(errorPath, numeric, range);
            return numeric;
        }
        if (target == boolean.class || target == Boolean.class) {
            if (raw instanceof Boolean flag) {
                return flag;
            }
            throw bindingTypeError(errorPath, "布林", raw);
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
            throw new ConfigBindingException(errorPath,
                "列舉值非法 " + name + "，允許：" + String.join("、", allowed));
        }
        if (target == List.class) {
            return convertList(scope, errorPath, raw, range, generic);
        }
        if (target == Set.class) {
            return convertSet(scope, errorPath, raw, range, generic);
        }
        if (target == Map.class) {
            return convertMap(scope, errorPath, raw, range, generic);
        }
        throw new ConfigBindingException(errorPath,
            isUnresolvable(generic)
                ? "綁定型別無法推斷（萬用字元／型別變數不支援），請改用具體型別宣告"
                : "不支援的綁定型別 " + target.getSimpleName() + "（實際值：" + describe(raw) + "）");
    }

    // -----------------------------------------------------------------
    // 集合：Map／Set／型別清單
    // -----------------------------------------------------------------

    /**
     * 清單綁定：元素型別取自宣告的泛型參數。
     *
     * <p>{@code List<String>}、元素為 {@code Object} 與未指定泛型的 {@code List}
     * 維持既有行為（元素逐個轉字串，null 保留）；其他元素型別逐元素嚴格驗證，
     * 錯誤帶 {@code <path>[i]}（巢狀元素內欄位再補 {@code .field}）。
     * 萬用字元／型別變數無法推斷驗證規則，一律明確拒絕。</p>
     */
    private static Object convertList(BindScope scope, String errorPath, Object raw,
                                      ConfigRange range, Type generic) {
        if (!(raw instanceof List<?> list)) {
            throw bindingTypeError(errorPath, "清單", raw);
        }
        Type elementType = firstTypeArgument(generic);
        Class<?> element = elementType == null ? Object.class : resolvedClass(elementType);
        if (element == null) {
            throw new ConfigBindingException(errorPath,
                "List 的元素型別無法推斷（萬用字元／型別變數不支援），請改用具體型別宣告");
        }
        // 相容路徑只留給既有語意（未指定泛型／String／Object 逐個轉字串）；
        // 其餘一律嚴格驗證，不默默轉字串。
        boolean lenient = elementType == null
            || element == String.class || element == Object.class;
        List<Object> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String itemPath = elementPath(errorPath, i);
            out.add(lenient
                ? lenientStringItem(list.get(i))
                : strictCollectionElement(scope, itemPath,
                    list.get(i), element, elementType, range));
        }
        // 元素可能為 null：不用 List.copyOf（會 NPE），改 unmodifiable 包裝
        return Collections.unmodifiableList(out);
    }

    /**
     * 集合綁定：由 YAML 清單建構，依 equals 去重並保留首次出現順序。
     *
     * <p>未宣告元素型別、或元素型別無法推斷（萬用字元／型別變數）的
     * {@code Set} 直接報錯（過去會掉進巢狀分支或默默轉字串，
     * 皆非預期行為，不屬於可沿用的語意）。元素一律嚴格驗證，
     * 與清單的相容轉換分開。</p>
     */
    private static Object convertSet(BindScope scope, String errorPath, Object raw,
                                     ConfigRange range, Type generic) {
        if (!(raw instanceof List<?> list)) {
            throw bindingTypeError(errorPath, "清單", raw);
        }
        Type elementType = firstTypeArgument(generic);
        if (elementType == null) {
            throw new ConfigBindingException(errorPath,
                "Set 需要宣告元素型別（例如 Set<String>），實際值：" + describe(raw));
        }
        Class<?> element = resolvedClass(elementType);
        if (element == null) {
            throw new ConfigBindingException(errorPath,
                "Set 的元素型別無法推斷（萬用字元／型別變數不支援），請改用具體型別宣告");
        }
        Set<Object> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(strictCollectionElement(scope, elementPath(errorPath, i),
                list.get(i), element, elementType, range));
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * 對映綁定：只支援 {@code Map<String, T>}，鍵為 YAML 鍵名，
     * 值依 {@code T} 逐筆嚴格驗證，錯誤帶 {@code <path>.<key>}。
     * 未宣告泛型、鍵非 {@code String}、或值型別無法推斷（萬用字元／
     * 型別變數）時明確拒絕；與清單的相容轉換分開，不默默轉字串。
     */
    private static Object convertMap(BindScope scope, String errorPath, Object raw,
                                     ConfigRange range, Type generic) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw bindingTypeError(errorPath, "節點（map）", raw);
        }
        Type[] args = generic instanceof ParameterizedType parameterized
            ? parameterized.getActualTypeArguments() : null;
        if (args == null || args.length != 2) {
            throw new ConfigBindingException(errorPath,
                "Map 需要宣告泛型參數（例如 Map<String, Integer>），實際值：" + describe(raw));
        }
        Class<?> keyType = resolvedClass(args[0]);
        if (keyType == null || keyType != String.class) {
            throw new ConfigBindingException(errorPath,
                "只支援 Map<String, T>（鍵為 YAML 鍵名），實際鍵型別：" + args[0].getTypeName());
        }
        Class<?> valueType = resolvedClass(args[1]);
        if (valueType == null) {
            throw new ConfigBindingException(errorPath,
                "Map 的值型別無法推斷（萬用字元／型別變數不支援），請改用具體型別宣告");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            out.put(key, strictCollectionElement(scope, errorPath + "." + key,
                entry.getValue(), valueType, args[1], range));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 清單相容路徑：沿用既有的逐個轉字串（未指定泛型／String／Object），
     * null 保留。只給清單用；Map／Set 走嚴格路徑。
     */
    private static Object lenientStringItem(Object item) {
        return item == null ? null : item.toString();
    }

    /**
     * Map／Set 嚴格路徑：null 保留；{@code Object} 接受一切且不轉換；
     * {@code String} 只接受字串（非字串報錯，不轉字串）；
     * 其餘沿用純量／集合／巢狀規則。
     */
    private static Object strictCollectionElement(BindScope scope, String errorPath, Object item,
                                                  Class<?> element, Type elementType,
                                                  ConfigRange range) {
        if (item == null) {
            return null;
        }
        if (element == Object.class) {
            return item;
        }
        if (element == String.class) {
            if (item instanceof String text) {
                return text;
            }
            throw bindingTypeError(errorPath, "字串", item);
        }
        return convertElement(scope, errorPath, item, element, elementType, range);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertElement(BindScope scope, String errorPath, Object item,
                                         Class<?> target, Type generic, ConfigRange range) {
        if (item == null) {
            return null;
        }
        if (isNestedType(target)) {
            if (!(item instanceof Map)) {
                throw bindingTypeError(errorPath, "節點（map）", item);
            }
            // 元素子快照：相對路徑在元素內解析，錯誤前綴記為元素路徑；
            // 元素內的絕對路徑仍由 BindScope 回到根解析。
            BindScope child = new BindScope(scope.root(),
                new ConfigSnapshot((Map<String, Object>) item), "", errorPath);
            if (target.isRecord()) {
                return bindRecord(child, (Class) target);
            }
            return bindPojo(child, (Class) target);
        }
        return convertNonNested(scope, errorPath, item, target, range, generic);
    }

    private static String errorPathOf(BindScope scope, String key) {
        return key.contains(".") ? key : join(scope.error(), key);
    }

    private static String elementPath(String errorPath, int index) {
        return errorPath + "[" + index + "]";
    }

    private static Type firstTypeArgument(Type generic) {
        if (generic instanceof ParameterizedType parameterized
            && parameterized.getActualTypeArguments().length >= 1) {
            return parameterized.getActualTypeArguments()[0];
        }
        return null;
    }

    /**
     * 把泛型取為執行期類別；萬用字元、型別變數等推斷不出的回傳 null，
     * 由呼叫端拋 {@code ACELIB-CFG-007} 明說原因，不默默退回轉字串。
     */
    private static Class<?> resolvedClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private static boolean isUnresolvable(Type type) {
        return type instanceof java.lang.reflect.WildcardType
            || type instanceof java.lang.reflect.TypeVariable;
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

    /**
     * 範圍與有限值檢查：NaN／無限大一律拒絕（不論有無範圍），
     * 因為 NaN 的大小比較恆為 false，單靠範圍比較會放行。
     */
    private static void checkRange(String path, double value, ConfigRange range) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            if (range != null) {
                throw new ConfigBindingException(path,
                    "數值 " + value + " 非有限（NaN／無限大不允許），允許範圍 ["
                        + range.min() + ", " + range.max() + "]");
            }
            throw new ConfigBindingException(path,
                "數值 " + value + " 非有限（NaN／無限大不允許）");
        }
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
