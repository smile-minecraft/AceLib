package com.smile.acelib.lifecycle;

import java.util.Collection;
import org.bukkit.plugin.Plugin;

/**
 * 為下游模組驗證相依圖、排序啟用並反向釋放 handle 的生命週期宿主。
 *
 * <p>由 {@code AceLibApi.getLifecycleHost()} 取得。註冊必須以擁有者為單位提交
 * 整批模組；結構錯誤會回傳完整問題清單，且不呼叫任何啟用回呼。宿主不接管
 * handle 以外的設定監看器、事件登記、scheduler 或 GUI scope。</p>
 *
 * @since 1.4.0
 */
public interface LifecycleHost {

    /**
     * 目前宿主狀態；可重試的核心 rollback 會維持 READY，失敗結果另見
     * {@link #lastResult()}。
     */
    Status status();

    /** 最近一次註冊、撤銷、reload 或停用操作的結構化結果。 */
    LifecycleResult lastResult();

    /**
     * 原子驗證並註冊同一 plugin 擁有的一批模組。
     *
     * @param owner 模組擁有者；不可為 null
     * @param modules 本次完整模組批次；不可為 null
     * @return 成功、結構拒絕或執行失敗的結果；永不為 null
     */
    LifecycleResult register(Plugin owner, Collection<LifecycleModule> modules);

    /**
     * 停用並移除指定 plugin 自己擁有的模組。
     *
     * <p>正常狀態下，若其他 plugin 的模組直接或間接依賴這批模組，會拒絕操作並列出相依者。
     * 宿主處於 {@code FAILED} 時仍允許撤銷此 owner 自己的模組以釋放資源；此清理不會讓宿主恢復可用。
     * </p>
     *
     * @param owner 模組擁有者；不可為 null
     * @return 停用、明確拒絕或清理失敗的結果；永不為 null
     */
    LifecycleResult unregister(Plugin owner);

    /** 宿主可接受註冊、正在重建、失敗或已停用的狀態。 */
    enum Status {
        /** AceLib 尚未完成啟用，或目前 facade 沒有可用的宿主。 */
        NOT_READY,
        /**
         * 相依圖有效且可開始 lifecycle 操作。若 {@link LifecycleHost#lastResult()} 記錄可重試的
         * 核心 reload 失敗，模組 handle 尚未重建，此時會暫拒新註冊，直到一次 reload 完成。
         */
        READY,
        /** reload 正在停用舊模組或依圖重建。 */
        RELOADING,
        /** 核心無法安全重試、回滾不完整或 reload 未能重建；只能停用或撤銷自身模組。 */
        FAILED,
        /** AceLib 已停用，不能再註冊或啟用模組。 */
        SHUTDOWN
    }
}
