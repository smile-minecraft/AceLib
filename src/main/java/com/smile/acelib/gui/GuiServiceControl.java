package com.smile.acelib.gui;

/**
 * GUI 服務內部生命週期入口（Internal）。
 *
 * <p>公開 {@link GuiService} 契約不再提供關閉整個服務的方法
 * （1.4.0 已決定的破壞性變更：每個 plugin 只關閉自己的作用域，
 * 見 {@link GuiScopes}）；reload／disable 等內部接線改走本介面停用服務。
 * 下游插件不得依賴本介面 — 需要結束 GUI 時請關閉自己的
 * {@link GuiScope}，需要最新服務時請重新讀取 provider 的
 * {@code api()}。</p>
 *
 * @see GuiScopes
 * @since 1.4.0
 */
public interface GuiServiceControl {

    /**
     * 停用服務並釋放全部資源（冪等）。
     *
     * <p>僅供 AceLib 內部接線（reload／disable）呼叫；
     * 正常 reload／disable 不應由下游直接呼叫。</p>
     */
    void shutdownService();
}
