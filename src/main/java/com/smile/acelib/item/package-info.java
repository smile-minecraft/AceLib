/**
 * 自訂物品與資料遷移（Supported / SPI API）。
 *
 * <p>本套件提供自訂物品的建立、辨識、序列化與 schema 遷移能力：</p>
 * <ul>
 *   <li>{@link com.smile.acelib.item.AceItemFactory}（Supported）— 以
 *       {@code ItemStack} + {@code PersistentDataContainer} 建立可辨識、可序列化、
 *       可升級的自訂物品；辨識依 {@link com.smile.acelib.item.ItemIdentity}
 *       三欄位（namespace / key / formatVersion），不依賴 display name / lore。</li>
 *   <li>{@link com.smile.acelib.item.ItemMigration} /
 *       {@link com.smile.acelib.item.ItemMigrationContext}（SPI）—
 *       定義舊版資料升級契約；多個 migration 以
 *       {@link com.smile.acelib.item.ItemMigrationChain} 串接，任一失敗觸發
 *       rollback，輸入 {@code ItemStack} 不被部分修改。</li>
 * </ul>
 *
 * <h2>Ownership 與 Mutability</h2>
 * <ul>
 *   <li>所有回傳的 {@code ItemStack} 為「新的獨立物件」；caller 可自由修改，
 *       不會影響 factory 內部狀態。</li>
 *   <li>{@link com.smile.acelib.item.AceItemFactory#migrate} 對輸入
 *       {@code ItemStack} 做<strong>原地修改</strong>（若 chain 成功）；失敗時
 *       以備份 restore，輸入資料保持原樣。</li>
 *   <li>序列化（{@link com.smile.acelib.item.AceItemFactory#serialize}）回傳位元組
 *       陣列，可寫入任意 byte store；反序列化回傳新的 {@code ItemStack}。</li>
 * </ul>
 *
 * <h2>執行緒與平台</h2>
 * <p>factory 本身無可變狀態（{@code namespace} 與 PDC key 集皆為 immutable），
 * 單一實例可跨執行緒共用；但本套件讀寫的 {@code ItemStack} / {@code ItemMeta} /
 * {@code PersistentDataContainer} 都是 mutable Bukkit 物件，其執行緒／上下文限制
 * 由伺服器實作定義（Paper 通常要求主執行緒；Folia 對 inventory／世界綁定物件要求
 * 所屬 region thread）。factory 不做同步、不派送排程，<strong>不承諾</strong>跨執行緒
 * 操作這些物件安全；呼叫端必須依執行環境在伺服器允許的上下文內建立／存取物品。</p>
 *
 * <h2>錯誤處理</h2>
 * <p>錯誤代碼見 {@link com.smile.acelib.item.ItemErrorCode}（{@code ACELIB-ITEM-*}）；
 * 驗證失敗透過 {@link com.smile.acelib.item.ItemException}（unchecked）拋出並攜帶
 * 對應代碼。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.item;
