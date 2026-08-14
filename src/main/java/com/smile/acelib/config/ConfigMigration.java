package com.smile.acelib.config;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 設定遷移介面。
 *
 * <p>每個 {@link ConfigMigration} 負責把設定檔從 {@link #fromVersion()}
 * 升級到 {@link #toVersion()}；多個 migration 透過 {@link MigrationChain}
 * 串接成鏈。</p>
 *
 * <h2>使用情境</h2>
 * <pre>{@code
 * MigrationChain chain = new MigrationChain()
 *     .add(new ConfigMigration() {
 *         public ConfigVersion fromVersion() { return new ConfigVersion(1, 0); }
 *         public ConfigVersion toVersion() { return new ConfigVersion(1, 1); }
 *         public void migrate(YamlConfiguration old, YamlConfiguration next) {
 *             // 從 old 讀舊欄位，寫入 next（新欄位）
 *             next.set("newField", old.getString("oldField", "default"));
 *         }
 *     });
 * }</pre>
 *
 * <h2>設計約束</h2>
 * <ul>
 *   <li>{@link #migrate(YamlConfiguration, YamlConfiguration)} 內若拋例外，
 *       {@link MigrationChain} 會將其包裝為 {@link MigrationResult#failure}，
 *       確保不會中斷整個載入流程</li>
 *   <li>migration 應為冪等：重複執行結果應相同</li>
 * </ul>
 *
 * @see MigrationChain
 * @see MigrationResult
 * @since 1.0.0
 */
public interface ConfigMigration {

    /**
     * 起始版本（migration 處理的「舊版本」）。
     *
     * @return 不可為 null
     */
    ConfigVersion fromVersion();

    /**
     * 目標版本（migration 處理後的「新版本」）。
     *
     * @return 不可為 null
     */
    ConfigVersion toVersion();

    /**
     * 執行遷移。
     *
     * <p>典型實作：從 {@code old} 讀既有欄位、寫入 {@code next} 新欄位。
     * 注意：{@code old} 與 {@code next} 可能是同一個 {@link YamlConfiguration}
     * 實例（{@link MigrationChain} 預設走「就地修改」模式）。</p>
     *
     * @param old  舊版設定（可讀既有欄位）
     * @param next 設定容器（寫入新欄位）
     */
    void migrate(YamlConfiguration old, YamlConfiguration next);
}