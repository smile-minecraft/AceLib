/**
 * 外部插件整合（Supported / SPI API）。
 *
 * <p>本套件提供外部插件（Vault、LuckPerms、PlaceholderAPI 等）整合狀態的
 * 安全查詢與生命週期管理，全程 <strong>reflection-only</strong>：不 import 任何
 * 外部插件 API 類別，外部類別不在 classpath 時仍可正常啟動。</p>
 *
 * <ul>
 *   <li>{@link com.smile.acelib.external.ExternalPluginProbe}（Supported）— 以
 *       marker FQCN + Bukkit {@code PluginManager} + 版本範圍判定整合狀態
 *       （{@link com.smile.acelib.external.IntegrationStatus}）。</li>
 *   <li>{@link com.smile.acelib.external.ExternalIntegrationService}（Supported）—
 *       對外查詢 facade；未啟用 / 已停用時由 unavailable 實作回
 *       {@code INIT_FAILED} 結果，永不為 null、不丟例外（null 輸入除外）。</li>
 *   <li>{@link com.smile.acelib.external.IntegrationAdapter}（SPI）— adapter
 *       生命週期契約（冪等 initialize / shutdown、失敗原因可查詢）。</li>
 *   <li>{@link com.smile.acelib.external.IntegrationRegistry}（Supported）—
 *       註冊 / 啟用 / 停用 / reload 協調；單一 adapter 失敗不中斷其他。</li>
 * </ul>
 *
 * <h2>可用性語意</h2>
 * <p>「外部 plugin 是否存在」依 {@link ExternalPluginProbe} / adapter source 與
 * tests 描述，不得在文件宣稱 runtime 可用；整合狀態以
 * {@link com.smile.acelib.external.IntegrationStatus} 五態呈現：AVAILABLE /
 * NOT_INSTALLED / NOT_ENABLED / VERSION_UNSUPPORTED / INIT_FAILED。</p>
 *
 * <h2>錯誤處理</h2>
 * <p>錯誤代碼見 {@link com.smile.acelib.external.ExternalIntegrationErrorCodes}
 * （{@code ACELIB-EXT-*}）。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.external;
