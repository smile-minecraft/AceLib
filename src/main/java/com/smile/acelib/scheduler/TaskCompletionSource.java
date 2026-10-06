package com.smile.acelib.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 終態完成單元（Internal）。
 *
 * <p>以 {@link CompletableFuture} 的 once 語意落實「終態只完成一次」：
 * {@link #tryComplete(TaskResult)} 先勝出者回傳 true，後續重複完成回傳 false
 * 且不改變已定終態。本單元永遠只以正常完成寫入（不寫 exceptional），
 * 因此等待端不會看到 {@code ExecutionException}。</p>
 *
 * <p>本類別為 package-private，不進 API surface。</p>
 *
 * @param <T> 完成時攜回的值型別
 */
final class TaskCompletionSource<T> {

    private final CompletableFuture<TaskResult<T>> future = new CompletableFuture<>();

    /**
     * 嘗試寫入終態（只完成一次）。
     *
     * @param result 終態；不可為 null
     * @return 本次寫入生效為 true；已有終態時為 false（傳入值被丟棄）
     */
    boolean tryComplete(TaskResult<T> result) {
        return future.complete(result);
    }

    /**
     * 終態是否已出現。
     *
     * @return 已有終態為 true
     */
    boolean isDone() {
        return future.isDone();
    }

    /**
     * 目前終態（若尚未完成回傳 null）。
     *
     * @return 已定終態；未完成時為 null
     */
    TaskResult<T> peek() {
        return future.getNow(null);
    }

    /**
     * 終態的唯讀串接視圖。
     *
     * @return 只可觀察、不可外部完成的視圖
     */
    CompletionStage<TaskResult<T>> stage() {
        return future.minimalCompletionStage();
    }

    /**
     * 在呼叫端自己的執行緒上等待終態。
     *
     * @param timeout 最長等待時間
     * @param unit    時間單位
     * @return 終態
     * @throws InterruptedException 當等待被中斷
     * @throws TimeoutException 當時限內未出現終態
     */
    TaskResult<T> await(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } catch (java.util.concurrent.ExecutionException impossible) {
            // 本單元永遠只正常完成；此分支不可達，轉為非檢查例外避免污染簽章。
            throw new IllegalStateException("completion completed exceptionally", impossible);
        }
    }
}
