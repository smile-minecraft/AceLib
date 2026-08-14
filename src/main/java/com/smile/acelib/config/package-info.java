/**
 * 設定管理（Supported + SPI）。
 *
 * <p>提供設定檔載入 / 驗證 / 遷移 / 儲存與多語系訊息管理服務，並以
 * {@link com.smile.acelib.config.ConfigVersion} /
 * {@link com.smile.acelib.config.ConfigSchema} /
 * {@link com.smile.acelib.config.FieldSpec} 值型別描述設定結構。</p>
 *
 * <h2>取得方式</h2>
 * <p>config 模組以「建構注入 + 綁定」方式使用，不經
 * {@link com.smile.acelib.AceLibApi} facade（該 facade 不提供本套件 getter）：</p>
 * <ul>
 *   <li>以 {@code new ConfigManager(plugin, fileName, schema, currentVersion)}
 *       建立設定管理（{@code schema} 以 {@link com.smile.acelib.config.ConfigSchema}
 *       + {@link com.smile.acelib.config.FieldSpec} 描述），再呼叫
 *       {@code load()} 由 AceLib 負責綁定與遷移</li>
 *   <li>以 {@code new LangManager(plugin, defaultLocale)} 建立多語系訊息服務</li>
 *   <li>或以 {@link com.smile.acelib.config.AceLibConfig#bind(org.bukkit.plugin.java.JavaPlugin)}
 *       取得綁定到 plugin 的 facade（重複呼叫回傳同一 instance）</li>
 * </ul>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.config.ConfigManager}（Supported）— 載入 / 遷移 / 儲存</li>
 *   <li>{@link com.smile.acelib.config.LangManager}（Supported）— 多語系訊息</li>
 *   <li>{@link com.smile.acelib.config.AceLibConfig}（Supported）— 設定綁定工廠</li>
 *   <li>{@link com.smile.acelib.config.ConfigSchema} /
 *       {@link com.smile.acelib.config.FieldSpec} /
 *       {@link com.smile.acelib.config.ConfigVersion} /
 *       {@link com.smile.acelib.config.MigrationResult}（Supported）— 值型別</li>
 *   <li>{@link com.smile.acelib.config.ConfigMigration}（SPI）— 消費者實作的
 *       設定遷移介面（冪等、相容性責任見介面文件）</li>
 *   <li>{@link com.smile.acelib.config.ConfigException}（Supported）— 設定例外，
 *       攜帶 {@code ACELIB-CFG-*} 錯誤代碼</li>
 * </ul>
 *
 * <h2>生命週期</h2>
 * <p>reload 時以新 schema 重新呼叫載入流程即可：AceLib 依
 * {@link com.smile.acelib.config.ConfigVersion} 比較既有版本，套用
 * {@link com.smile.acelib.config.MigrationChain} 中符合 from→to 的遷移；
 * 任一失敗回傳失敗結果且<strong>不覆寫既有設定</strong>。disable 不需特殊處理，
 * 未儲存的變更由 caller 決定是否寫回。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>設定值型別皆為不可變；{@link com.smile.acelib.config.ConfigManager} /
 * {@link com.smile.acelib.config.LangManager} 預期由 plugin 主執行緒持有與操作。</p>
 *
 * <h2>錯誤代碼</h2>
 * <p>{@code ACELIB-CFG-*} 系列：載入失敗、驗證失敗、遷移失敗、無法識別格式等，
 * 詳見 {@link com.smile.acelib.config.ConfigException} 與對應介面文件。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.config;
