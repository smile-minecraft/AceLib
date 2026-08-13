package com.smile.acelib.external;

/**
 * 外部整合 adapter 生命週期契約。
 *
 * <p>每個 adapter 以 integration id（例如 {@code "vault"}）唯一識別，並具備冪等的
 * {@code initialize()} / {@code shutdown()} 生命週期：已啟用時再次 {@code initialize()}
 * 為 no-op；未啟用時再次 {@code shutdown()} 為 no-op。</p>
 *
 * <p>{@code initialize()} 失敗時 adapter 不得保持 active，且失敗原因必須可經由
 * {@link #getStatus()} 取得（非 null 的 {@link IntegrationProbeResult}）。</p>
 *
 * <p>後續具體 adapter 可繼承 package-private {@code AbstractIntegrationAdapter} 以複用
 * 冪等狀態機，或直接實作本介面。</p>
 *
 * @see AbstractIntegrationAdapter
 * @see IntegrationRegistry
 * @see IntegrationProbeResult
 */
public interface IntegrationAdapter {

    /**
     * 取得本 adapter 的 integration id（例如 {@code "vault"}）。
     *
     * @return 非空且於 registry 內唯一的識別字串
     */
    String getId();

    /**
     * 啟用 adapter（冪等）。
     *
     * <p>已啟用時為 no-op；未啟用時執行啟用邏輯。啟用失敗時本 adapter 不得保持 active，
     * 且失敗原因記錄於 {@link #getStatus()}，並拋出例外（不吞錯）。</p>
     *
     * @throws RuntimeException 當啟用邏輯失敗
     */
    void initialize();

    /**
     * 停用 adapter 並釋放資源（冪等）。
     *
     * <p>未啟用時為 no-op；已啟用時執行停用邏輯並將 active 設為 false。</p>
     */
    void shutdown();

    /**
     * 查詢本 adapter 目前是否處於啟用狀態。
     *
     * @return {@code true} 表示已啟用
     */
    boolean isActive();

    /**
     * 取得本 adapter 目前狀態的不可變快照。
     *
     * <p>未啟用 / 啟用失敗時回傳 {@link IntegrationStatus#INIT_FAILED} 結果，其 reason
     * 說明原因；永不為 null。</p>
     *
     * @return 不可變的 {@link IntegrationProbeResult}
     */
    IntegrationProbeResult getStatus();
}
