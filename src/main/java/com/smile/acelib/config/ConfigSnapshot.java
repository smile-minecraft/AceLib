package com.smile.acelib.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 設定不可變快照。
 *
 * <p>{@link ConfigManager} 每次驗證整份設定通過後，只發布一次本快照；
 * 發布前在記憶體內完成補預設、遷移與版本收斂，發布後內容凍結。
 * 同一輪操作（{@code get}／{@code set}／{@code save}）固定使用同一個快照實例，
 * 只有 {@code load}／{@code reload}／{@code startup} 成功才換新實例。</p>
 *
 * <p>每次成功發布世代 +1（同內容也 +1），失敗不發布新世代；
 * 世代由 {@link #generation()} 讀取，不參與 {@link #equals}／{@link #hashCode}。</p>
 *
 * <p>快照內容深層不可變：{@link #asMap()} 回傳的頂層與巢狀 map／list
 * 皆為不可變視圖，任何修改都會拋 {@link UnsupportedOperationException}。
 * 下游拿到快照後無法改動它， routes 圖缺口「交給下游的設定物件可以被改動」即被封住。</p>
 *
 * <h2>路徑語法</h2>
 * <p>以 {@code .} 分隔的 YAML 路徑，例如 {@code "server.port"}。
 * 路徑不存在時各 getter 回傳呼叫端給的預設值，不拋例外。</p>
 *
 * @since 1.4.0
 */
public final class ConfigSnapshot {

    private final Map<String, Object> values;
    private final ConfigVersion version;
    private final long generation;

    /**
     * 以巢狀 map 建立快照（會深拷貝並凍結）。
     *
     * @param values 巢狀值（可為 {@code YamlConfiguration#getValues(false)} 的結果）；不可為 null
     * @throws NullPointerException 當 {@code values} 為 null
     */
    public ConfigSnapshot(Map<String, Object> values) {
        this(values, 0L);
    }

    /**
     * 以巢狀 map 與指定世代建立快照（會深拷貝並凍結）。
     *
     * <p>世代是獨立 metadata，不參與 {@link #equals}／{@link #hashCode}；
     * {@link ConfigManager} 每次成功發布（{@code load}／{@code reload}／
     * {@code startup}）世代 +1，失敗不發布新世代。</p>
     *
     * @param values 巢狀值（可為 {@code YamlConfiguration#getValues(false)} 的結果）；不可為 null
     * @param generation 世代；不得為負
     * @throws NullPointerException 當 {@code values} 為 null
     * @throws IllegalArgumentException 當 {@code generation} 為負
     * @since 1.5.0
     */
    public ConfigSnapshot(Map<String, Object> values, long generation) {
        Objects.requireNonNull(values, "values");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        Map<String, Object> frozen = deepFreeze(plainCopy(values));
        this.values = frozen;
        Object rawVersion = frozen.get(ConfigManager.VERSION_KEY);
        this.version = rawVersion == null ? null
            : MigrationChain.parseVersion(rawVersion.toString());
        this.generation = generation;
    }

    /**
     * 空快照（無任何值；{@link #version()} 為 null）。
     *
     * @return 空快照
     */
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(Map.of());
    }

    /**
     * 讀取指定路徑的原始值。
     *
     * @param path 點分隔路徑；不可為 null
     * @return 該路徑的值；路徑不存在回傳 null
     * @throws NullPointerException 當 {@code path} 為 null
     */
    public Object get(String path) {
        Objects.requireNonNull(path, "path");
        return lookup(values, path);
    }

    /**
     * 指定路徑是否包含值（null 值視為不存在）。
     *
     * @param path 點分隔路徑；不可為 null
     * @return 存在且非 null 回傳 true
     */
    public boolean contains(String path) {
        return get(path) != null;
    }

    /**
     * 以字串讀取指定路徑。
     *
     * <p>非字串值轉字串形式回傳（例如數字轉十進位字串）；不存在回傳預設值。</p>
     *
     * @param path 路徑；不可為 null
     * @param defaultValue 路徑不存在或值為 null 時的回傳；可為 null
     * @return 值的字串形式；不存在回傳 {@code defaultValue}
     */
    public String getString(String path, String defaultValue) {
        Object value = get(path);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 以 int 讀取指定路徑（嚴格：只接受整數值）。
     *
     * <p>自 1.5.0 起為嚴格轉換：小數、NaN／無限大、超出 int 範圍的
     * {@link Number} 一律拋 {@link ConfigBindingException}
     *（{@code ACELIB-CFG-007}，訊息含完整路徑），不再靜默截斷或溢位。
     * 路徑不存在、值為 null 或非 {@link Number} 時回傳預設值（行為不變）。</p>
     *
     * @param path 路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 整數值或 {@code defaultValue}
     * @throws ConfigBindingException 當值為小數、非有限或超出 int 範圍
     */
    public int getInt(String path, int defaultValue) {
        Object value = get(path);
        if (value == null || !(value instanceof Number)) {
            return defaultValue;
        }
        return strictInt(path, value);
    }

    /**
     * 以 long 讀取指定路徑（嚴格：只接受整數值）。
     *
     * <p>小數、NaN／無限大、超出 long 範圍的 {@link Number} 一律拋
     * {@link ConfigBindingException}（{@code ACELIB-CFG-007}，訊息含完整路徑）。
     * 路徑不存在、值為 null 或非 {@link Number} 時回傳預設值。</p>
     *
     * @param path 路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 長整數值或 {@code defaultValue}
     * @throws ConfigBindingException 當值為小數、非有限或超出 long 範圍
     * @since 1.5.0
     */
    public long getLong(String path, long defaultValue) {
        Object value = get(path);
        if (value == null || !(value instanceof Number)) {
            return defaultValue;
        }
        return strictLong(path, value);
    }

    /**
     * 以 double 讀取指定路徑。
     *
     * <p>非有限值（NaN／正負無限大，例如 YAML 的 {@code .nan}／
     * {@code .inf}）一律拋 {@link ConfigBindingException}
     *（{@code ACELIB-CFG-007}，訊息含完整路徑）。
     * 路徑不存在、值為 null 或非 {@link Number} 時回傳預設值。</p>
     *
     * @param path 路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 雙精度浮點值或 {@code defaultValue}
     * @throws ConfigBindingException 當值為非有限數
     * @since 1.5.0
     */
    public double getDouble(String path, double defaultValue) {
        Object value = get(path);
        if (value == null || !(value instanceof Number number)) {
            return defaultValue;
        }
        double numeric = number.doubleValue();
        if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
            throw new ConfigBindingException(path,
                "數值 " + value + " 非有限（NaN／無限大不允許）");
        }
        return numeric;
    }

    /**
     * 以 boolean 讀取指定路徑。
     *
     * @param path 路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 布林值或 {@code defaultValue}
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = get(path);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return defaultValue;
    }

    /**
     * 以字串清單讀取指定路徑。
     *
     * <p>元素逐個轉字串；YAML 的 null 元素（{@code ~}／空項）保留為 null，
     * 不跳過也不轉字串。</p>
     *
     * @param path 路徑；不可為 null
     * @return 不可變的字串清單；路徑不存在或非清單回傳空清單
     */
    public List<String> getStringList(String path) {
        Object value = get(path);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(item == null ? null : item.toString());
            }
            // 元素可能為 null：不用 List.copyOf（會 NPE），改 unmodifiable 包裝
            return Collections.unmodifiableList(out);
        }
        return List.of();
    }

    /**
     * 快照的版本（由 {@code version} 欄位解析；缺失或格式錯誤時為 null）。
     *
     * @return 版本；無版本資訊回傳 null
     */
    public ConfigVersion version() {
        return version;
    }

    /**
     * 快照的世代（獨立 metadata，不參與相等比較）。
     *
     * <p>{@link ConfigManager} 每次成功發布世代 +1（同內容也 +1），
     * 失敗不發布新世代；直接建構的快照世代為 0。</p>
     *
     * <p>例外：損壞啟動等後備路徑（最後成功副本、呼叫端指定的保守後備）
     * 產生的快照走預設建構、世代為 0；比較世代時以
     * {@link ConfigManager#snapshot()} 的發布快照為準。</p>
     *
     * @return 世代；不為負
     * @since 1.5.0
     */
    public long generation() {
        return generation;
    }

    /**
     * 快照內容的不可變視圖（深層凍結）。
     *
     * @return 不可變的巢狀 map；修改會拋 {@code UnsupportedOperationException}
     */
    public Map<String, Object> asMap() {
        return values;
    }

    /**
     * 內容相等：只比較深層內容，世代不參與比較。
     *
     * @param other 另一物件
     * @return 內容相同回傳 true
     * @since 1.5.0
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigSnapshot that)) {
            return false;
        }
        return values.equals(that.values);
    }

    /**
     * 內容雜湊：只取深層內容，與 {@link #equals} 一致（世代不參與）。
     *
     * @return 內容雜湊
     * @since 1.5.0
     */
    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /**
     * 嚴格轉 int：小數、無限大／NaN、超出範圍一律報錯，不靜默截斷或溢位。
     *
     * <p>邊界用 2 的冪比較（{@code -2^31}／{@code 2^31} 在 double 精確可表示），
     * 避免 {@code (double) Integer.MAX_VALUE} 舍入造成的邊界誤判；
     * 與 {@code ConfigBinder} 的 int 轉換同規則。</p>
     */
    private static int strictInt(String path, Object raw) {
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
        double numeric = ((Number) raw).doubleValue();
        if (Double.isNaN(numeric) || Double.isInfinite(numeric)
            || numeric < -2147483648.0 || numeric >= 2147483648.0
            || numeric != Math.rint(numeric)) {
            throw new ConfigBindingException(path,
                "期望 int 範圍內的整數，實際為 " + raw + "（" + describe(raw) + "）");
        }
        return (int) numeric;
    }

    /**
     * 嚴格轉 long：小數截斷與超出範圍一律報錯，不靜默處理；
     * 與 {@code ConfigBinder} 的 long 轉換同規則。
     */
    private static long strictLong(String path, Object raw) {
        if (raw instanceof Long value) {
            return value;
        }
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        double numeric = ((Number) raw).doubleValue();
        if (Double.isNaN(numeric) || Double.isInfinite(numeric)
            || numeric < -9223372036854775808.0 || numeric >= 9223372036854775808.0
            || numeric != Math.rint(numeric)) {
            throw new ConfigBindingException(path,
                "期望 long 範圍內的整數，實際為 " + raw + "（" + describe(raw) + "）");
        }
        return (long) numeric;
    }

    private static String describe(Object raw) {
        if (raw == null) {
            return "缺失";
        }
        return "型別 " + raw.getClass().getSimpleName();
    }

    private static Object lookup(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.", -1);
        Object cursor = root;
        for (String part : parts) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return null;
            }
            cursor = map.get(part);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    /**
     * 把 Bukkit 值轉成純 map／list 結構（套件內共用）。
     *
     * <p>{@code YamlConfiguration#getValues(false)} 的巢狀節點是
     * {@link ConfigurationSection}（不是 {@link Map}），直接遍歷會穿不過去；
     * 這裡先把 section 攤成 {@code LinkedHashMap}，清單內的 section 一併轉換。</p>
     *
     * @param source 來源；不可為 null
     * @return 純 map 結構的新 map
     */
    static Map<String, Object> plainCopy(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(entry.getKey(), plainValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object plainValue(Object value) {
        if (value instanceof ConfigurationSection section) {
            return plainCopy(section.getValues(false));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), plainValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(plainValue(item));
            }
            return out;
        }
        return value;
    }

    private static Map<String, Object> deepFreeze(Map<String, Object> source) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            frozen.put(entry.getKey(), freezeValue(entry.getValue()));
        }
        // 不用 Map.copyOf：YAML 清單／缺值可能含 null，copyOf 會 NPE；
        // unmodifiable 包裝同樣不可修改（修改拋 UnsupportedOperationException），且容許 null。
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(String.valueOf(entry.getKey()), freezeValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) {
                frozen.add(freezeValue(item));
            }
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }
}
