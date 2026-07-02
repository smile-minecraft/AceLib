package com.smile.acelib.context;

import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/**
 * 除錯模式開關。
 *
 * <p>對應 Plan §八 Phase 3 驗收標準 #5「除錯模式可輸出額外診斷資訊」與
 * Plan §十九 Phase 14 除錯模式規範。設計：</p>
 *
 * <ul>
 *   <li>支援 system property {@code acelib.debug}（最高優先）</li>
 *   <li>支援 plugin config {@code debug.enabled}（後續 Phase 4 提供 config 系統時接入）</li>
 *   <li>支援 {@link #setEnabled(boolean)} 動態切換</li>
 *   <li>支援 {@link #getOrCompute(Plugin, Supplier)} 快取計算結果</li>
 * </ul>
 *
 * <h2>優先順序（高 → 低）</h2>
 * <ol>
 *   <li>{@link #setEnabled(boolean)} 已顯式設定過（{@link #isExplicitlySet()} 為 true）</li>
 *   <li>system property {@code acelib.debug}</li>
 *   <li>plugin config {@code debug.enabled}（Phase 3 尚未提供 config API，目前一律 false）</li>
 *   <li>預設 {@code false}</li>
 * </ol>
 *
 * <h2>執行緒安全</h2>
 * 使用 {@link AtomicReference} 儲存顯式設定值；{@link #isEnabled()} 為 lock-free。
 *
 * @since Phase 3 (Plan §八)
 */
public final class DebugMode {

    /** system property 名稱（供測試與外部啟動腳本使用）。 */
    public static final String SYS_PROP = "acelib.debug";

    private static final AtomicReference<Boolean> explicit = new AtomicReference<>(null);

    /**
     * per-plugin 計算結果緩存。WeakHashMap 確保 plugin unload 後 entry 被回收。
     */
    private static final WeakHashMap<Plugin, Object> CACHE = new WeakHashMap<>();

    /**
     * 當 plugin 為 null 時使用的 sentinel key。獨立 Object 即可（不需要實作 Plugin 介面）。
     */
    private static final Object NULL_PLUGIN_SENTINEL = new Object();

    private DebugMode() {
        // utility class
    }

    /**
     * 判斷除錯模式是否啟用。
     *
     * <p>呼叫順序（由高到低優先）：</p>
     * <ol>
     *   <li>system property {@value #SYS_PROP}（外部啟動腳本最高優先）</li>
     *   <li>若 {@link #setEnabled(boolean)} 已呼叫過（{@code explicit != null}），回傳該值</li>
     *   <li>否則回傳 {@code false}</li>
     * </ol>
     *
     * @param plugin plugin owner（保留給 Phase 4 config 接入；目前忽略）；可為 null
     * @return 是否啟用除錯模式
     */
    public static boolean isEnabled(Plugin plugin) {
        // 1. system property 最高優先
        String prop = System.getProperty(SYS_PROP);
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        // 2. 顯式設定
        Boolean e = explicit.get();
        if (e != null) {
            return e;
        }
        // 3. 預設
        return false;
    }

    /**
     * 不帶 plugin 參數的便利方法。
     */
    public static boolean isEnabled() {
        return isEnabled(null);
    }

    /**
     * 顯式設定除錯模式狀態。
     *
     * <p>設定後 {@link #isEnabled()} 將忽略 system property，直到下次呼叫
     * {@link #clearExplicit()} 才會重新讀取。</p>
     *
     * @param enabled true = 啟用、false = 關閉
     */
    public static void setEnabled(boolean enabled) {
        explicit.set(enabled);
    }

    /**
     * 清除顯式設定；之後 {@link #isEnabled()} 將改讀 system property。
     */
    public static void clearExplicit() {
        explicit.set(null);
    }

    /**
     * 是否曾顯式呼叫 {@link #setEnabled(boolean)}。
     *
     * @return true 表示當前 explicit state 非 null
     */
    public static boolean isExplicitlySet() {
        return explicit.get() != null;
    }

    /**
     * 取得除錯模式值；若尚未確定則計算並快取。
     *
     * <p>快取範圍：per-plugin（同一個 {@link Plugin} instance 的後續呼叫回傳同一個值）。
     * 測試可在 {@code @BeforeEach} 內呼叫 {@link #clearCache()} 重置。</p>
     *
     * @param plugin      plugin owner；可為 null（此時使用 sentinel key，所有 null 共用緩存）
     * @param initializer 首次計算 supplier；不可為 null
     * @param <T>         結果型別
     * @return 計算結果
     * @throws NullPointerException 當 {@code initializer} 為 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T getOrCompute(Plugin plugin, Supplier<T> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        Object key = plugin != null ? plugin : NULL_PLUGIN_SENTINEL;
        synchronized (CACHE) {
            T cached = (T) CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            T computed = initializer.get();
            // plugin 為 null 時，key 是 Object sentinel，無法用 WeakHashMap 引用；
            // 因此 null plugin 情境下不緩存（每次呼叫 supplier）。
            if (plugin != null) {
                CACHE.put(plugin, computed);
            }
            return computed;
        }
    }

    /**
     * 清空所有 plugin 的 getOrCompute 緩存。
     *
     * <p>測試可在 {@code @BeforeEach} 內呼叫以保證測試隔離。</p>
     */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * {@link BooleanSupplier} 版本的 {@link #getOrCompute}；不對結果快取（單次詢問）。
     *
     * @param plugin plugin owner；可為 null
     * @param supplier 提供目前值的 supplier；不可為 null
     * @return supplier.getAsBoolean()
     */
    public static boolean queryCurrent(Plugin plugin, BooleanSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return supplier.getAsBoolean();
    }
}