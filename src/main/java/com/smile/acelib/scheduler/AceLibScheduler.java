package com.smile.acelib.scheduler;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.List;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@link SafeScheduler} 的對外 facade + factory（Supported）。
 *
 * <p>本類別提供：</p>
 * <ul>
 *   <li>{@link #create(JavaPlugin, Platform, PlatformCapability)} — 從 plugin +
 *       platform + capability 建立 {@link SafeScheduler}（consumer 使用方式）</li>
 *   <li>{@link #create(AceLibPlugin)} — 從 {@link AceLibPlugin} 建立的 AceLib
 *       內部便利方法（Internal；下游 consumer 不應依賴 {@link AceLibPlugin}）</li>
 *   <li>{@link #bind(AceLibPlugin, SafeScheduler)} / {@link #unbind(AceLibPlugin)} —
 *       將 scheduler 掛載到 plugin 的 onDisable 生命週期</li>
 *   <li>{@link #getRecorderErrors(SafeScheduler, int)} — 統一查詢錯誤紀錄</li>
 * </ul>
 *
 * <h2>bind 機制</h2>
 * <p>呼叫 {@link #bind} 後，plugin → scheduler 對應會記入 process-local 綁定表；
 * 於 {@link #unbind(AceLibPlugin)} 時主動呼叫
 * {@link SafeSchedulerImpl#onPluginDisable()}，確保所有任務被取消、
 * 後續任務不再被派送。若需要掛載到 {@link AceLibPlugin#onDisable()} 自動觸發，
 * 請由 caller 在 disable 流程中呼叫 {@link #unbind(AceLibPlugin)}。</p>
 *
 * <h2>unbind 機制</h2>
 * <p>{@link #unbind} 會主動呼叫 {@link SafeSchedulerImpl#onPluginDisable()} 並解除綁定，
 * 適用於 reload / 測試清理情境。</p>
 *
 * @see SafeScheduler
 * @see SafeSchedulerImpl
 * @since 1.0.0
 */
public final class AceLibScheduler {

    private AceLibScheduler() {
        // utility facade；不可實例化
    }

    // -----------------------------------------------------------------
    // Factory 方法
    // -----------------------------------------------------------------

    /**
     * 從 plugin + platform + capability 建立 {@link SafeSchedulerImpl}。
     *
     * <p>這是 consumer 使用的標準 factory：`plugin` 為下游自身的
     * {@link JavaPlugin}，`platform` / `capability` 建議從
     * {@code AceLibApi#getPlatform()} / {@code AceLibApi#getPlatformCapability()}
     * 取得，確保與 AceLib 偵測結果一致。</p>
     *
     * @param plugin     派送任務的 plugin owner；不可為 null
     * @param platform   偵測到的平台；不可為 null
     * @param capability 對應的 capability profile；不可為 null
     * @return 新的 {@link SafeSchedulerImpl} 實例（永遠不為 null）
     * @throws NullPointerException 當任一參數為 null
     */
    public static SafeScheduler create(JavaPlugin plugin,
                                       Platform platform,
                                       PlatformCapability capability) {
        return new SafeSchedulerImpl(plugin, platform, capability);
    }

    /**
     * 從 {@link AceLibPlugin} 建立 scheduler（AceLib 內部便利方法）。
     *
     * <p>內部以 {@code plugin.getPlatformCapability()} 推導對應的 capability，
     * 確保傳入的 capability 與 {@code plugin.getApi().getPlatform()} 一致。
     * 此 overload 依賴 {@link AceLibPlugin}（Internal），供 AceLib 內部整合或
     * 測試使用；下游 consumer 應改用
     * {@link #create(JavaPlugin, Platform, PlatformCapability)}。</p>
     *
     * @param plugin AceLib 主類別實例；不可為 null（必須已通過 {@code onEnable}）
     * @return 新的 {@link SafeSchedulerImpl} 實例（永遠不為 null）
     * @throws NullPointerException 當 {@code plugin} 為 null
     */
    public static SafeScheduler create(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Platform platform = plugin.getApi().getPlatform();
        PlatformCapability capability = plugin.getPlatformCapability();
        return new SafeSchedulerImpl(plugin, platform, capability);
    }

    // -----------------------------------------------------------------
    // 生命週期綁定
    // -----------------------------------------------------------------

    /**
     * 將 scheduler 綁定到指定 {@link AceLibPlugin}（process-local 綁定表）。
     *
     * <p>重複綁定同一個 plugin 不會留下殘留（後者覆蓋前者）。
     * 此方法不直接修改 {@link AceLibPlugin} 內部狀態；disable 時需由 caller
     * 呼叫 {@link #unbind(AceLibPlugin)} 完成 {@link SafeSchedulerImpl#onPluginDisable()}。
     * 對直接以 {@link AceLibPlugin} 內部 scheduler 運作的整合方式，plugin 自身
     * 的 onDisable 已處理 teardown，不需額外呼叫本方法。</p>
     *
     * @param plugin    AceLib 主類別實例；不可為 null
     * @param scheduler 要綁定的 scheduler；不可為 null
     * @throws NullPointerException 當任一參數為 null
     */
    public static void bind(AceLibPlugin plugin, SafeScheduler scheduler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(scheduler, "scheduler");
        Bindings.put(plugin, scheduler);
    }

    /**
     * 解除綁定並主動停用 scheduler。
     *
     * <p>若 plugin 之前未綁定 scheduler，本方法為 no-op。
     * 若綁定的 scheduler 為 {@link SafeSchedulerImpl}，會同時呼叫
     * {@link SafeSchedulerImpl#onPluginDisable()}。</p>
     *
     * @param plugin AceLib 主類別實例；不可為 null
     */
    public static void unbind(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        SafeScheduler removed = Bindings.remove(plugin);
        if (removed instanceof SafeSchedulerImpl impl) {
            impl.onPluginDisable();
        }
    }

    /**
     * 取得綁定到指定 plugin 的 scheduler（若存在）。
     *
     * @param plugin AceLib 主類別實例；不可為 null
     * @return 已綁定的 scheduler；未綁定時回傳 null
     */
    public static SafeScheduler boundTo(AceLibPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return Bindings.get(plugin);
    }

    // -----------------------------------------------------------------
    // 統一錯誤紀錄查詢
    // -----------------------------------------------------------------

    /**
     * 取得 scheduler 最近 N 筆錯誤紀錄（{@link SafeScheduler#getRecorderErrors(int)} 的捷徑）。
     *
     * @param scheduler 目標 scheduler；不可為 null
     * @param max       最多回傳幾筆
     * @return 不可變的「時間由舊到新」紀錄清單
     */
    public static List<TaskErrorRecord> getRecorderErrors(SafeScheduler scheduler, int max) {
        Objects.requireNonNull(scheduler, "scheduler");
        return scheduler.getRecorderErrors(max);
    }

    // -----------------------------------------------------------------
    // Bindings：plugin → scheduler 的 process-local 對應表
    // -----------------------------------------------------------------

    /**
     * Process-local 的 plugin → scheduler 綁定表。
     *
     * <p>使用 {@link java.util.IdentityHashMap}（而非基於 equals）以避免
     * plugin 子類別意外碰撞。</p>
     */
    private static final class Bindings {
        private static final java.util.Map<AceLibPlugin, SafeScheduler> MAP =
            new java.util.IdentityHashMap<>();

        static void put(AceLibPlugin p, SafeScheduler s) {
            synchronized (MAP) {
                MAP.put(p, s);
            }
        }

        static SafeScheduler remove(AceLibPlugin p) {
            synchronized (MAP) {
                return MAP.remove(p);
            }
        }

        static SafeScheduler get(AceLibPlugin p) {
            synchronized (MAP) {
                return MAP.get(p);
            }
        }

        private Bindings() {
            // utility class
        }
    }
}