package com.smile.acelib.external;

/**
 * 具冪等生命週期的 {@link IntegrationAdapter} 基底實作（package-private）。
 *
 * <p>以 volatile {@code active} 旗標與最後一次 {@link IntegrationProbeResult} 實作冪等
 * 狀態機；具體 adapter 只需實作 {@link #doInitialize()} 與 {@link #doShutdown()} 兩個
 * hook。{@code initialize()} 失敗時保證 active 為 false 且 {@link #getStatus()} 回傳
 * 非 null 的 INIT_FAILED 結果。</p>
 */
abstract class AbstractIntegrationAdapter implements IntegrationAdapter {

    private final String id;
    private volatile boolean active = false;
    private volatile IntegrationProbeResult lastResult;

    protected AbstractIntegrationAdapter(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        this.id = id;
        this.lastResult = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
            "adapter '" + id + "' has not been initialized");
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final boolean isActive() {
        return active;
    }

    @Override
    public final IntegrationProbeResult getStatus() {
        return lastResult;
    }

    @Override
    public final void initialize() {
        if (active) {
            return;
        }
        try {
            IntegrationProbeResult result = doInitialize();
            this.lastResult = (result != null)
                ? result
                : IntegrationProbeResult.of(IntegrationStatus.AVAILABLE,
                    "adapter '" + id + "' initialized successfully");
            this.active = true;
        } catch (Exception e) {
            this.active = false;
            this.lastResult = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' failed to initialize: " + e.getMessage());
            throw new IntegrationLifecycleException(
                "initialization failed for integration adapter '" + id + "'", e);
        }
    }

    @Override
    public final void shutdown() {
        if (!active) {
            return;
        }
        try {
            doShutdown();
        } finally {
            this.active = false;
            this.lastResult = IntegrationProbeResult.of(IntegrationStatus.INIT_FAILED,
                "adapter '" + id + "' has been shut down");
        }
    }

    /**
     * 具體 adapter 的啟用邏輯；可拋出例外（將被 {@link #initialize()} 包裝為
     * {@link IntegrationLifecycleException}）。回傳非 null 結果作為啟用後狀態；
     * 回傳 null 時採用預設 AVAILABLE 結果。
     *
     * @return 啟用後的 {@link IntegrationProbeResult}，或 null 採用預設
     * @throws Exception 當啟用失敗
     */
    protected abstract IntegrationProbeResult doInitialize() throws Exception;

    /**
     * 具體 adapter 的停用邏輯；不得拋出（確保 {@link #shutdown()} 冪等且安全）。
     */
    protected abstract void doShutdown();
}
