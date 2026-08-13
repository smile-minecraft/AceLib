package com.smile.acelib.external;

/**
 * adapter 生命週期失敗時拋出的未檢查例外（package-private）。
 *
 * <p>由 {@link AbstractIntegrationAdapter#initialize()} 包裝具體啟用邏輯的例外後拋出；
 * registry 的 {@code initializeAll()} / {@code reload()} 會逐個捕捉以達成失敗隔離，
 * 不讓單一 adapter 失敗污染其他 adapter。</p>
 */
class IntegrationLifecycleException extends RuntimeException {

    IntegrationLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
