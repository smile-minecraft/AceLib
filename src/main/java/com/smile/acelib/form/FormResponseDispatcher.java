package com.smile.acelib.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表單回應派送 seam — 把消費者 callback 重新派送到玩家 region context 的
 * package 邊界（Internal）。
 *
 * <p>比照 {@code gui} 套件的 {@code PlayerContextExecutor} 先例：form 套件自建
 * package-private seam，不跨套件重用 gui 型別。production 實作
 * {@link SafeSchedulerFormResponseDispatcher} 包裝
 * {@code SafeScheduler.runForPlayer(Player, Runnable)}（UUID → Player 解析在
 * production 實作內），由 plugin 接線注入；Paper 走 main thread、Folia 走
 * entity scheduler。</p>
 *
 * <h2>回傳語意</h2>
 * <ul>
 *   <li>{@link #dispatch(UUID, Runnable)} 回傳 {@code true} — runnable 已被實際
 *       派送（已 enqueue 或已執行）；{@code false} — 拒絕派送（玩家離線、
 *       scheduler disabled、平台不支援等）。呼叫端必須把 {@code false} 視為
 *       「未派送」，並清理對應 pending 狀態。</li>
 *   <li>{@link #isPlayerOnline(UUID)} — 供派送後的 runnable 在玩家 context 內
 *       重檢玩家仍在線；預設實作回傳 {@code true}（lambda fake 不需覆寫）。</li>
 * </ul>
 *
 * @since 1.0.0
 */
@FunctionalInterface
interface FormResponseDispatcher {

    /**
     * 在指定玩家的 region context 內執行 {@code task}。
     *
     * @param playerId 目標玩家 UUID；不可為 null
     * @param task     要執行的程式；不可為 null
     * @return {@code true} 表示已實際派送；{@code false} 表示拒絕派送
     */
    boolean dispatch(UUID playerId, Runnable task);

    /**
     * 查詢玩家目前是否仍在線（派送後於 runnable 內重檢用）。
     *
     * @param playerId 目標玩家 UUID；不可為 null
     * @return 玩家仍在線回傳 {@code true}
     */
    default boolean isPlayerOnline(UUID playerId) {
        return true;
    }

    /**
     * 建立 no-op dispatcher：一律拒絕派送。
     *
     * <p>供「未接線排程器」的服務實例使用（如單一參數 production factory）；
     * 拒絕語意保證 callback 零執行、pending 不殘留。</p>
     */
    static FormResponseDispatcher noop() {
        return (playerId, task) -> false;
    }

    /**
     * 建立同步 dispatcher：立即在呼叫執行緒執行 task 並回傳 {@code true}
     * （MockBukkit 是 Paper-like，main thread 同步執行即 region context 安全）。
     *
     * <p>Folia runtime 不可使用此 dispatcher — production 必須使用
     * {@link SafeSchedulerFormResponseDispatcher}。</p>
     */
    static FormResponseDispatcher direct() {
        return (playerId, task) -> {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(task, "task");
            task.run();
            return true;
        };
    }

    /**
     * 建立延遲 dispatcher：enqueue 但不立即執行，模擬 production
     * {@link SafeSchedulerFormResponseDispatcher} 的「已接受、尚未在玩家
     * region 執行」視窗。測試透過
     * {@link DeferredFormResponseDispatcher#runPending()} 手動觸發。
     */
    static DeferredFormResponseDispatcher deferred() {
        return new DeferredFormResponseDispatcher();
    }

    /**
     * 延遲 dispatcher 實作：enqueue 但不立即執行（測試觀察與觸發用）。
     *
     * <p>本類別位於 form 套件內（package-private），僅測試可參照其
     * {@link #runPending()} / {@link #pendingCount()}；production 路徑不依賴
     * 本類別。</p>
     */
    final class DeferredFormResponseDispatcher implements FormResponseDispatcher {

        private final List<Runnable> queued = new ArrayList<>();
        private volatile boolean online = true;

        @Override
        public boolean dispatch(UUID playerId, Runnable task) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(task, "task");
            // 僅 enqueue：模擬 entity scheduler 已接受任務但尚未在玩家 region 執行
            queued.add(task);
            return true;
        }

        @Override
        public boolean isPlayerOnline(UUID playerId) {
            return online;
        }

        /** 切換 {@link #isPlayerOnline(UUID)} 回傳值（模擬派送後玩家離線）。 */
        public void setOnline(boolean online) {
            this.online = online;
        }

        /** @return 目前佇列中尚未執行的 runnable 數量（測試觀察用） */
        public int pendingCount() {
            return queued.size();
        }

        /** 依序執行佇列中所有 runnable（模擬玩家 region 內執行）。 */
        public void runPending() {
            List<Runnable> snapshot = new ArrayList<>(queued);
            queued.clear();
            for (Runnable task : snapshot) {
                task.run();
            }
        }
    }
}
