package com.smile.acelib.display;

/**
 * 顯示服務內部生命週期入口（Internal）。
 *
 * <p>由 {@code AceLibPlugin} 在 reload／disable 時持有，用來停用現有服務
 * （具冪等性），再把 facade 替換為 {@code SHUTDOWN} 不可用實作。
 * 下游不得依賴此內部入口——結束顯示請關閉自己的追蹤
 *（{@link DisplayService#closePlayer}／{@link DisplayService#closeAll}）。</p>
 *
 * @since 1.4.0
 */
public interface DisplayServiceControl {

    /**
     * 停用服務：先拒絕並取消在途變更，再清除本服務追蹤的顯示。
     *
     * <p>必須為冪等：重複呼叫不丟例外、不重複清除。</p>
     */
    void shutdownService();
}
