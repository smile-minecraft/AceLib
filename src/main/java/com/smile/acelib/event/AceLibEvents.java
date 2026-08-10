package com.smile.acelib.event;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link SafeEventRegistry} 的對外 facade + factory。
 *
 * <p>對應 Plan §十二 Phase 7「後續插件透過 AceLib 安全註冊事件」的統一入口。
 * 提供：</p>
 *
 * <ul>
 *   <li>{@link #create(JavaPlugin, Platform, PlatformCapability)} —
 *       從 plugin + platform + capability 建立 registry（內部用 {@link SafeEventRegistryImpl}）</li>
 *   <li>{@link #create(AceLibPlugin)} — 從 {@link AceLibPlugin} 直接建立並
 *       自動綁定 lifecycle（推薦用法）</li>
 *   <li>{@link #bind(AceLibPlugin, SafeEventRegistry)} / {@link #unbind(AceLibPlugin)} —
 *       將 registry 綁定到 plugin 的 lifecycle；{@link AceLibPlugin#onDisable()} 會自動呼叫
 *       {@link #unbind(AceLibPlugin)} 確保 listener 被清理</li>
 *   <li>{@link #boundTo(AceLibPlugin)} — 查詢 plugin 對應的已綁定 registry</li>
 * </ul>
 *
 * <h2>bind 機制</h2>
 * <p>呼叫 {@link #bind} 後，AceLib 會透過 process-local 的 {@link Bindings}
 * 維護 plugin → registry 對應；{@link AceLibPlugin#onDisable()} 會自動呼叫
 * {@link #unbind(AceLibPlugin)} 觸發 {@link SafeEventRegistry#onPluginDisable()}，
 * 確保 Bukkit {@code HandlerList} 不殘留 listener。</p>
 *
 * @see SafeEventRegistry
 * @see SafeEventRegistryImpl
 * @since Phase 7 (Plan §十二)
 */
public final class AceLibEvents {

    private AceLibEvents() {
        // utility facade；不可實例化
    }

    // -----------------------------------------------------------------
    // Factory 方法
    // -----------------------------------------------------------------

    /**
     * 從 plugin + platform + capability 建立 {@link SafeEventRegistryImpl}。
     *
     * <p>推薦使用 {@link #create(AceLibPlugin)} 以避免手動組合三個參數。</p>
     *
     * @param plugin     listener owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @return 新的 {@link SafeEventRegistryImpl} 實例（永遠不為 null）
     * @throws NullPointerException 當任一參數為 null
     */
    public static SafeEventRegistry create(JavaPlugin plugin,
                                           Platform platform,
                                           PlatformCapability capability) {
        return new SafeEventRegistryImpl(plugin, platform, capability);
    }

    /**
     * 從 {@link AceLibPlugin} 建立 registry 並自動綁定其 lifecycle（推薦用法）。
     *
     * <p>內部以 {@code plugin.getPlatformCapability()} 推導對應的 capability，
     * 確保傳入的 capability 與 {@code plugin.getApi().getPlatform()} 一致。</p>
     *
     * <p>建立後會自動呼叫 {@link #bind(AceLibPlugin, SafeEventRegistry)}，因此
     * caller <strong>不需</strong>再手動 bind：{@link #boundTo(AceLibPlugin)}
     * 立即回傳本次建立的 registry，且 {@link AceLibPlugin#onDisable()} 會自動
     * 清理其 listener。若同一 plugin 先前已綁定其他 registry，舊 registry 會在
     * 覆蓋前被 {@link SafeEventRegistry#onPluginDisable() disable}（避免
     * listener leak）；因此重複呼叫本方法等同「重建一個乾淨的 registry」。</p>
     *
     * @param plugin AceLib 主類別實例；不可為 null（必須已通過 {@code onEnable}）
     * @return 新建立且已綁定到 {@code plugin} 的 {@link SafeEventRegistryImpl}
     *         實例（永遠不為 null）
     * @throws NullPointerException 當 {@code plugin} 為 null
     */
    public static SafeEventRegistry create(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Platform platform = plugin.getApi().getPlatform();
        PlatformCapability capability = plugin.getPlatformCapability();
        SafeEventRegistry registry = new SafeEventRegistryImpl(plugin, platform, capability);
        // 自動納入 lifecycle ownership：caller 不需再手動 bind。
        // 走 bind() 共用路徑以繼承 replacement cleanup（覆蓋前 disable 舊 registry）。
        bind(plugin, registry);
        return registry;
    }

    // -----------------------------------------------------------------
    // 生命週期綁定
    // -----------------------------------------------------------------

    /**
     * 將 registry 綁定到 {@link AceLibPlugin} 的 onDisable 生命週期。
     *
     * <p>綁定後，{@link AceLibPlugin#onDisable()} 會自動呼叫
     * {@link #unbind(AceLibPlugin)} 觸發 registry 的
     * {@link SafeEventRegistry#onPluginDisable()}，確保 Bukkit
     * {@code HandlerList} 不殘留 listener。</p>
     *
     * <p>同一個 plugin 重複 bind 會覆蓋前一個綁定，但覆蓋前
     * 會先呼叫舊 registry 的 {@link SafeEventRegistry#onPluginDisable()} 解除
     * Bukkit 註冊，避免 listener leak；同 plugin 連續 bind 同一 registry 實例
     * （相等）為 no-op，不重複 disable。</p>
     *
     * @param plugin   AceLib 主類別實例；不可為 null
     * @param registry 要綁定的 registry；不可為 null
     * @throws NullPointerException 當任一參數為 null
     */
    public static void bind(AceLibPlugin plugin, SafeEventRegistry registry) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(registry, "registry");
        // 覆蓋既有 binding 前先 disable 舊 registry，避免 listener leak。
        SafeEventRegistry previous = Bindings.get(plugin);
        if (previous != null && previous != registry) {
            if (previous instanceof SafeEventRegistryImpl prevImpl) {
                prevImpl.onPluginDisable();
            }
        }
        Bindings.put(plugin, registry);
    }

    /**
     * 解除綁定並主動停用 registry。
     *
     * <p>若 plugin 之前未綁定 registry，本方法為 no-op。
     * 若綁定的 registry 為 {@link SafeEventRegistryImpl}，會同時呼叫
     * {@link SafeEventRegistryImpl#onPluginDisable()} 解除所有 listener
     * 並標記為 disabled。</p>
     *
     * <p>此方法由 {@link AceLibPlugin#onDisable()} 自動呼叫，亦可由
     * 後續插件在自訂 reload 流程中主動呼叫。</p>
     *
     * @param plugin AceLib 主類別實例；不可為 null
     */
    public static void unbind(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        SafeEventRegistry removed = Bindings.remove(plugin);
        if (removed instanceof SafeEventRegistryImpl impl) {
            impl.onPluginDisable();
        }
    }

    /**
     * 取得綁定到指定 plugin 的 registry（若存在）。
     *
     * @param plugin AceLib 主類別實例；不可為 null
     * @return 已綁定的 registry；未綁定時回傳 null
     */
    public static SafeEventRegistry boundTo(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return Bindings.get(plugin);
    }

    // -----------------------------------------------------------------
    // Bindings：plugin → registry 的 process-local 對應表
    // -----------------------------------------------------------------

    /**
     * Process-local 的 plugin → registry 綁定表。
     *
     * <p>使用 {@link java.util.IdentityHashMap}（而非基於 equals）以避免
     * plugin 子類別意外碰撞。</p>
     */
    private static final class Bindings {
        private static final java.util.Map<AceLibPlugin, SafeEventRegistry> MAP =
            new java.util.IdentityHashMap<>();

        static void put(AceLibPlugin p, SafeEventRegistry r) {
            synchronized (MAP) {
                MAP.put(p, r);
            }
        }

        static SafeEventRegistry remove(AceLibPlugin p) {
            synchronized (MAP) {
                return MAP.remove(p);
            }
        }

        static SafeEventRegistry get(AceLibPlugin p) {
            synchronized (MAP) {
                return MAP.get(p);
            }
        }

        /**
         * 解除所有 binding（測試 cleanup 用）。
         */
        static void clear() {
            synchronized (MAP) {
                MAP.clear();
            }
        }

        private Bindings() {
            // utility class
        }
    }
}