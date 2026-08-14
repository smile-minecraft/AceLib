/**
 * GUI 服務安全 facade（Supported API）。
 *
 * <p>本套件提供 Folia-safe 的 GUI 操作入口 {@link com.smile.acelib.gui.GuiService}：
 * 以 {@link java.util.UUID} 標記玩家、以不可變 {@link com.smile.acelib.gui.GuiSession}
 * 表達「單一玩家目前開啟的 GUI」，內部不長期保存 {@code Player} reference。
 * 所有操作回傳 {@link com.smile.acelib.gui.GuiResult}，不丟例外給 caller
 * （null 輸入除外）。</p>
 *
 * <h2>Session 生命週期</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.gui.GuiSession#generation()} 為不可重用、單調遞增的
 *       session 世代；同一 UUID 重新開啟 GUI 時拿到更大值。</li>
 *   <li>{@link com.smile.acelib.gui.GuiService#closeInventory} /
 *       {@link com.smile.acelib.gui.GuiService#validateClick} 要求傳入正確 generation，
 *       避免關閉舊代後仍對新代操作。</li>
 *   <li>關閉 / 重載 / 停用時 session 會被清理；shutdown 後呼叫一律回
 *       {@code REJECTED + ACELIB-GUI-002}。</li>
 * </ul>
 *
 * <h2>Folia / 執行緒契約</h2>
 * <p>實際 inventory mutation（建立 / 開啟 / 關閉）必須在玩家所屬 region context
 * 內執行：Folia 走 entity scheduler、Paper 走主執行緒。本套件透過
 * {@link com.smile.acelib.gui.PlayerContextExecutor}（package-private）安排，
 * caller 不得直接呼叫 {@code Bukkit.createInventory} 或全域 scheduler。
 * 非同步資料載入後更新 GUI 使用
 * {@link com.smile.acelib.gui.GuiService#beginAsyncUpdate} /
 * {@link com.smile.acelib.gui.GuiService#applyAsyncUpdate}；{@code renderer} 會在
 * 玩家 region 內恰好執行一次，舊請求因序號不符被拒絕（{@code ACELIB-GUI-016}）。</p>
 *
 * <h2>錯誤處理</h2>
 * <p>錯誤代碼見 {@link com.smile.acelib.gui.GuiErrorCode}（{@code ACELIB-GUI-*}）；
 * 未啟用 / 已停用時由 {@link com.smile.acelib.gui.GuiServiceUnavailableImpl}
 * 回傳 {@code NOT_READY / SHUTDOWN} 對應結果。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.gui;
