package com.smile.acelib.scheduler;

/**
 * 實體排程器已退休訊號（Internal）。
 *
 * <p>Folia 的 {@code EntityScheduler#run / runDelayed / runAtFixedRate} 在實體
 * 已被移除時回傳 {@code null}，且此時 retired callback 永遠不會被觸發（因為根本
 * 沒有任務可退役）。呼叫端必須把「回 null」視為「實體失效」，記為
 * {@code ACELIB-SCHED-003} 並回安全 no-op，而非當成平台不支援
 * （{@code ACELIB-SCHED-005}）。本例外即承載這個區分：由
 * {@link FoliaSchedulerBackend} 在偵測到 null 時拋出，再由
 * {@link SafeSchedulerImpl} 分類為 {@code SCHED-003}。
 *
 * <p>本類別為 package-private，不進 API surface。
 */
final class EntityRetiredException extends Exception {

    EntityRetiredException(String message) {
        super(message);
    }
}
