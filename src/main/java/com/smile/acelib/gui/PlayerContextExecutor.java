package com.smile.acelib.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Inventory mutation 路由到玩家 region context 的 adapter（Internal）。
 *
 * <p>{@link GuiServiceImpl} 透過此介面把「建立 / 開啟 / 關閉 inventory」這類
 * mutate 操作送到玩家所在的 region 內執行，避免 Folia 環境下跨 region mutate
 * 觸發 {@code ACELIB-CTX-001}。</p>
 *
 * <h2>Production 實作</h2>
 * <ul>
 *   <li>{@link SafeSchedulerPlayerContextExecutor} —
 *       透過 {@code SafeScheduler.runForPlayer(player, runnable)} 派送；
 *       Paper 走 main thread、Folia 走 entity scheduler。回傳值反映底層
 *       {@code ScheduledTask.isCancelled()}：cancelled no-op task（scheduler
 *       disabled、player offline、平台不支援等）視為拒絕派送。</li>
 * </ul>
 *
 * <h2>測試實作</h2>
 * <ul>
 *   <li>直接同步執行 lambda（MockBukkit 是 Paper-like，main thread 直接執行
 *       即 region context 安全）— 透過建構子
 *       {@code new GuiServiceImpl((p, r) -> r.run())} 注入。</li>
 *   <li>No-op lambda（既有 service-layer unit test 不需要實際開 inventory） —
 *       透過 {@link GuiServiceImpl#GuiServiceImpl()} default constructor 取得。</li>
 * </ul>
 *
 * <h2>回傳語意（派送成功 vs 拒絕）</h2>
 * <p>{@link #runOnPlayerRegion(Player, Runnable)} 回傳 {@code boolean}：
 * <ul>
 *   <li>{@code true} — runnable 已被實際派送（Paper main thread 已執行 / Folia
 *       entity scheduler 已 enqueue）</li>
 *   <li>{@code false} — executor 拒絕派送（SafeScheduler 回傳 cancelled no-op
 *       task）。呼叫端必須視為「未實際派送」，並清理對應 session / link /
 *       inventory，避免留下「session 存在但實際未開視窗」的不一致狀態</li>
 * </ul>
 * </p>
 *
 * <h2>Player reference 處理</h2>
 * <p>本介面本身不持有 Player reference — 由 caller 透過 method 參數傳入；
 * adapter 在執行 runnable 期間使用 Player，執行完畢後即釋放。</p>
 *
 * @since 1.0.0
 */
@FunctionalInterface
interface PlayerContextExecutor {

    /**
     * 在指定玩家的 region context 內執行 {@code runnable}。
     *
     * <p>對於 region-bound mutate 操作（建立 / 開啟 / 關閉 inventory），
     * 必須透過此方法而非直接呼叫。</p>
     *
     * @param player   目標玩家；不可為 null
     * @param runnable 要執行的程式；不可為 null
     * @return {@code true} 表示 runnable 已實際派送；{@code false} 表示 executor
     *         拒絕派送（SafeScheduler cancelled / 平台不支援等），呼叫端必須
     *         清理對應 session / link 並回 FAILED 結果
     */
    boolean runOnPlayerRegion(Player player, Runnable runnable);

    /**
     * 建立 no-op executor（既有 service-layer 單元測試使用）。
     *
     * <p>呼叫 {@link #runOnPlayerRegion(Player, Runnable)} 時什麼都不做 —
     * 適用於僅驗證 session lifecycle / generation / protected slot 邏輯，
     * 不需要實際開啟 Bukkit inventory 的測試。</p>
     *
     * <p>回傳 {@code true}：測試不需要實際派送，但仍視為「派送成功」
     * （向後相容既有 service-layer 測試契約）。</p>
     */
    static PlayerContextExecutor noop() {
        return (player, runnable) -> true;
    }

    /**
     * 建立同步 executor（MockBukkit / Paper main-thread 測試環境使用）。
     *
     * <p>直接同步呼叫 {@code runnable.run()}。MockBukkit 是 Paper-like，
     * main thread 同步執行即 region context 安全。Folia runtime 不可使用
     * 此 executor — production 必須用
     * {@link SafeSchedulerPlayerContextExecutor}。</p>
     *
     * <p>回傳 {@code true}：同步執行即代表派送成功。</p>
     */
    static PlayerContextExecutor direct() {
        return (player, runnable) -> {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(runnable, "runnable");
            runnable.run();
            return true;
        };
    }

    /**
     * 建立延遲 executor（模擬 production {@link SafeSchedulerPlayerContextExecutor}
     * 的 enqueue 語意）。
     *
     * <p>呼叫 {@link #runOnPlayerRegion(Player, Runnable)} 時將 runnable 加入內部
     * 佇列並立即回傳 {@code true}（表示「派送已接受」），但<strong>不立即執行</strong>。
     * 測試可透過 {@link DeferredPlayerContextExecutor#runPending()} 手動觸發佇列中的
     * runnable，模擬「enqueue 後、renderer 執行前」狀態改變的 deferred race 場景。</p>
     *
     * <p>對應 Evidence Pack「deferred executor tests」需求：證明 {@code applyAsyncUpdate}
     * 在 renderer 真正執行前會重新驗證 running / session / generation / request /
     * player / link，即使 enqueue 當下所有條件都成立。這是 synchronous
     * {@link #direct()} executor 無法覆蓋的關鍵路徑。</p>
     *
     * @return 不可為 null 的 {@link DeferredPlayerContextExecutor}
     */
    static DeferredPlayerContextExecutor deferred() {
        return new DeferredPlayerContextExecutor();
    }

    /**
     * 建立 production executor：透過 {@code SafeScheduler.runForPlayer}
     * 派送到玩家所屬 region（Folia 用 entity scheduler、Paper 用 main thread）。
     *
     * <p>回傳值反映 {@code ScheduledTask.isCancelled()}：
     * cancelled no-op task（scheduler disabled、player offline、平台不支援）
     * 視為 {@code false}，呼叫端需清理對應資源。</p>
     *
     * @param plugin    plugin owner；不可為 null
     * @param scheduler 對應平台 SafeScheduler；不可為 null
     * @return 不可為 null 的 {@link PlayerContextExecutor}
     * @throws NullPointerException 必要參數為 null
     */
    static PlayerContextExecutor viaSafeScheduler(JavaPlugin plugin,
                                                    com.smile.acelib.scheduler.SafeScheduler scheduler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(scheduler, "scheduler");
        return (player, runnable) -> {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(runnable, "runnable");
            var task = scheduler.runForPlayer(player, runnable);
            return !task.isCancelled();
        };
    }

    /**
     * 延遲 executor 實作：enqueue 但不立即執行，供測試模擬 deferred race。
     *
     * <p>本類別位於 {@code gui} 套件內（package-private），僅測試可參照其
     * {@link #runPending()} / {@link #pendingCount()} 觀察與觸發佇列。production
     * 路徑使用 {@link SafeSchedulerPlayerContextExecutor}，不依賴本類別。</p>
     *
     * @since 1.0.0
     */
    final class DeferredPlayerContextExecutor implements PlayerContextExecutor {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public boolean runOnPlayerRegion(Player player, Runnable runnable) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(runnable, "runnable");
            // 僅 enqueue：不立即執行，模擬 Folia entity scheduler 已接受任務但尚未
            // 在玩家 region 內執行的視窗。回傳 true 表示「派送已接受」。
            pending.add(runnable);
            return true;
        }

        /**
         * 回傳目前佇列中尚未執行的 runnable 數量（測試觀察用）。
         *
         * @return 待執行 runnable 數量
         */
        int pendingCount() {
            return pending.size();
        }

        /**
         * 依序執行佇列中所有已 enqueue 的 runnable（模擬 player region 內執行）。
         *
         * <p>執行前先快照佇列，避免 runnable 內部再次 enqueue 造成的並發修改；
         * 快照執行完畢後才清空佇列。</p>
         */
        void runPending() {
            List<Runnable> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : snapshot) {
                r.run();
            }
        }
    }
}
