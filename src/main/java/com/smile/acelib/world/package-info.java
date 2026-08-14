/**
 * 世界操作安全 facade（Supported API）。
 *
 * <p>本套件提供 Folia-safe 的世界操作入口 {@link com.smile.acelib.world.WorldService}：
 * 以不可變值型別 {@link com.smile.acelib.world.LocationSnapshot} /
 * {@link com.smile.acelib.world.EntityReference} 表達目標，避免 caller 把
 * {@code World} / {@code Location} / {@code Entity} / {@code Player} 的 mutable
 * reference 跨越執行緒或生命週期保存。所有操作在執行前重新驗證目標，失敗回傳
 * 對應的 {@code ACELIB-WORLD-*} 結果（見
 * {@link com.smile.acelib.world.WorldErrorCode}），不丟例外給 caller（null 輸入除外）。</p>
 *
 * <h2>Folia / 執行緒契約</h2>
 * <ul>
 *   <li>方塊與實體 mutate 操作必須發生在目標所屬的 region thread（Folia）
 *       或主執行緒（Paper）；本套件介面不承諾在任意執行緒呼叫都安全，實作內部
 *       以既有安全排程 API 安排 region context。</li>
 *   <li>傳送（{@link com.smile.acelib.world.WorldService#teleportPlayer} /
 *       {@link com.smile.acelib.world.WorldService#teleportEntity}）為非同步操作，
 *       透過 {@link java.util.concurrent.CompletionStage} 回傳最終
 *       {@link com.smile.acelib.world.TeleportResult}，不得假設立即完成。</li>
 * </ul>
 *
 * <h2>錯誤處理</h2>
 * <p>所有失敗結果攜帶 {@code ACELIB-WORLD-*} 錯誤代碼與人類可讀訊息；
 * 非 {@link com.smile.acelib.world.WorldState#SUCCESS} 狀態不丟例外。
 * 未啟用 / 已停用時由
 * {@link com.smile.acelib.world.WorldServiceUnavailableImpl} 回傳
 * {@code REJECTED + ACELIB-WORLD-001 / 002}。</p>
 *
 * <h2>SPI</h2>
 * <p>{@link com.smile.acelib.world.WorldBackend} 為 SPI：實作者負責把 UUID 介面
 * 解析為 Bukkit 物件並在安全執行緒內 mutate；每次操作即時解析、不得長期保存
 * Bukkit 物件 reference。實作請遵守
 * {@link com.smile.acelib.world.WorldBackendResult} 的回傳語意。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.world;
