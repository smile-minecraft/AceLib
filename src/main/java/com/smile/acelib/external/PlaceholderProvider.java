package com.smile.acelib.external;

/**
 * 佔位符提供者（AceLib 自有 SPI）。
 *
 * <p>內建實作以 PlaceholderAPI {@code PlaceholderExpansion} 子類別承載下游的
 * 自有佔位符（外部型別集中於僅在 AVAILABLE 後載入的內部持有者）；下游可以
 * {@link ExternalIntegrationService#setPlaceholderProvider(PlaceholderProvider)} 替換為
 * 自有實作，{@code clearPlaceholderProvider()} 恢復內建。提供者缺席／停用時門面回
 * 不可用結果。</p>
 *
 * <p>生命週期：{@link #close()} 由門面在服務停用、reload 與提供者被替換時呼叫，
 * 實作必須取消所有已註冊的佔位符，不得殘留。</p>
 *
 * @see PlaceholderHandler
 * @see ExternalOperationResult
 * @see ExternalIntegrationService
 * @since 1.4.0
 */
public interface PlaceholderProvider {

    /**
     * 註冊一個自有佔位符。
     *
     * @param identifier 佔位符識別；不可為 null／空白
     * @param handler 解析處理器；不可為 null
     * @return 永不為 null 的 {@link ExternalOperationResult}
     */
    ExternalOperationResult registerPlaceholder(String identifier, PlaceholderHandler handler);

    /**
     * 取消註冊一個自有佔位符（不存在視為操作失敗，不拋例外）。
     *
     * @param identifier 佔位符識別；不可為 null／空白
     * @return 永不為 null 的 {@link ExternalOperationResult}
     */
    ExternalOperationResult unregisterPlaceholder(String identifier);

    /**
     * 釋放本提供者持有的全部註冊（服務停用／reload／被替換時由門面呼叫）。
     *
     * <p>預設為 no-op；持有外部註冊的實作必須覆寫並盡力清理（個別失敗不中斷
     * 其他清理）。呼叫後本提供者不得再被使用（引用已由門面丟棄）。</p>
     */
    default void close() {
        // 預設無外部註冊需要清理。
    }
}
