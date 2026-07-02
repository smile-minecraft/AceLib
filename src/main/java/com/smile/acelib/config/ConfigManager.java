package com.smile.acelib.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 設定檔管理器（單一檔案）。
 *
 * <p>對應 Plan §九 Phase 4「設定檔與語言檔管理」所有驗收標準：</p>
 * <ul>
 *   <li>預設設定檔生成（首次啟動無檔案時自動建立）</li>
 *   <li>設定檔版本欄位存在；版本過舊自動觸發遷移</li>
 *   <li>缺失欄位可被補齊（不破壞既有值）</li>
 *   <li>reload 失敗保留舊設定且套用失敗可被記錄</li>
 *   <li>必填欄位驗證</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-CFG-001}：設定檔不存在且無法生成</li>
 *   <li>{@code ACELIB-CFG-002}：設定檔格式錯誤（YAML 解析失敗）</li>
 *   <li>{@code ACELIB-CFG-004}：設定遷移失敗</li>
 *   <li>{@code ACELIB-CFG-005}：必填欄位缺失</li>
 * </ul>
 *
 * <h2>執行緒模型</h2>
 * <p>{@link #current} 為 {@code volatile}，確保 reload 切換時對其他執行緒可見；
 * 但 {@link YamlConfiguration} 內部並非執行緒安全，並發呼叫 {@link #set} 應由
 * caller 負責同步（典型情境：只在主執行緒 reload）。</p>
 *
 * @since Phase 4 (Plan §九)
 */
public final class ConfigManager {

    /** 設定檔 YAML 內的版本欄位 key。 */
    public static final String VERSION_KEY = "version";

    private final JavaPlugin plugin;
    private final String fileName;
    private final ConfigSchema schema;
    private final ConfigVersion currentVersion;
    private final MigrationChain migrationChain = new MigrationChain();
    private volatile YamlConfiguration current;
    private volatile boolean ready = false;

    /**
     * 主要建構子。
     *
     * @param plugin         擁有此 manager 的 plugin；不可為 null
     * @param fileName       設定檔名稱（相對於 plugin data folder）；不可為 null/空白
     * @param schema         設定 schema；不可為 null
     * @param currentVersion schema 對應的當前版本；不可為 null
     * @throws NullPointerException     當 plugin/schema/version 為 null
     * @throws IllegalArgumentException 當 fileName 為 null/空白
     */
    public ConfigManager(JavaPlugin plugin,
                         String fileName,
                         ConfigSchema schema,
                         ConfigVersion currentVersion) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        this.fileName = fileName;
        this.schema = Objects.requireNonNull(schema, "schema");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
    }

    // -----------------------------------------------------------------
    // 註冊 / 狀態
    // -----------------------------------------------------------------

    /**
     * 註冊一個 {@link ConfigMigration} 到 chain。
     *
     * @param migration 要加入的 migration；不可為 null
     * @return this（鏈式 API）
     */
    public ConfigManager registerMigration(ConfigMigration migration) {
        migrationChain.add(migration);
        return this;
    }

    /**
     * 是否已通過 {@link #load()} 成功載入。
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 取得 schema 宣告的當前版本。
     */
    public ConfigVersion getCurrentVersion() {
        return currentVersion;
    }

    /**
     * 取得 schema 物件。
     */
    public ConfigSchema getSchema() {
        return schema;
    }

    /**
     * 取得當前 {@link YamlConfiguration}（用於進階讀取巢狀欄位）。
     *
     * <p>注意：load 前呼叫會回傳 null。</p>
     *
     * @return 當前設定檔；若尚未 load 則為 null
     */
    public YamlConfiguration getConfiguration() {
        return current;
    }

    // -----------------------------------------------------------------
    // 載入流程
    // -----------------------------------------------------------------

    /**
     * 載入設定檔。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>解析 {@code <dataFolder>/<fileName>} 的實際路徑</li>
     *   <li>若檔案不存在 → 嘗試從 JAR 內 {@code saveResource}；若失敗則用 schema defaults 生成</li>
     *   <li>若檔案存在 → 載入既有內容</li>
     *   <li>套用 schema defaults（補齊缺失欄位）</li>
     *   <li>比較版本：若檔案版本較舊則執行 {@link MigrationChain} 遷移</li>
     *   <li>將當前版本寫回檔案（標記已升級）</li>
     * </ol>
     *
     * @throws ConfigException 當設定檔無法生成、解析失敗、遷移失敗時
     */
    public void load() {
        File file = resolveFile();
        ensureParentDirectory(file);

        YamlConfiguration config;
        if (!file.exists()) {
            config = createDefault();
            writeToDisk(config, file);
        } else {
            config = loadFromDisk(file);
        }

        // 補齊缺失欄位（不破壞既有值）
        applySchemaDefaults(config);

        // 版本遷移
        ConfigVersion fileVersion = readVersion(config);
        if (fileVersion != null && fileVersion.compareTo(currentVersion) < 0) {
            MigrationResult result = migrationChain.migrateAll(config, currentVersion);
            if (!result.success()) {
                throw new ConfigException(
                    "ACELIB-CFG-004",
                    "設定遷移失敗：" + String.join("; ", result.warnings()),
                    null
                );
            }
        }

        // 確保 version 欄位存在且為當前版本
        config.set(VERSION_KEY, currentVersion.toString());

        // 回寫磁碟（若補齊或遷移過）
        writeToDisk(config, file);

        this.current = config;
        this.ready = true;
    }

    /**
     * 重新載入設定檔。
     *
     * <p>若新檔案損壞、無法解析、或發生任何例外，<strong>保留舊的 {@link YamlConfiguration}
     * 實例</strong>並回傳 false；plugin 不會崩潰，呼叫端可選擇重試或忽略。</p>
     *
     * @return 成功回傳 true；失敗回傳 false（舊值仍可用）
     */
    public boolean reload() {
        File file = resolveFile();
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration previous = this.current;
        try {
            YamlConfiguration fresh = loadFromDisk(file);
            applySchemaDefaults(fresh);
            this.current = fresh;
            this.ready = true;
            return true;
        } catch (RuntimeException ex) {
            // 還原舊值
            this.current = previous;
            return false;
        }
    }

    /**
     * 將當前設定寫回磁碟。
     *
     * @throws IllegalStateException 若 {@link #load()} 尚未成功執行
     * @throws ConfigException       若寫入磁碟失敗
     */
    public void save() {
        if (current == null) {
            throw new IllegalStateException("load() must be called before save()");
        }
        File file = resolveFile();
        ensureParentDirectory(file);
        writeToDisk(current, file);
    }

    // -----------------------------------------------------------------
    // 資料存取
    // -----------------------------------------------------------------

    /**
     * 取得指定路徑的設定值。
     *
     * @param path YAML 路徑（例如 {@code "nested.deep.value"}）；不可為 null
     * @return 對應的值；若路徑不存在或尚未 load 則回傳 null
     * @throws NullPointerException 當 {@code path} 為 null
     */
    public Object get(String path) {
        Objects.requireNonNull(path, "path");
        if (current == null) {
            return null;
        }
        return current.get(path);
    }

    /**
     * 設定指定路徑的值（記憶體內，不立即落盤；呼叫 {@link #save()} 才寫入磁碟）。
     *
     * @param path  YAML 路徑；不可為 null
     * @param value 欲設定的值；可為 null（表示清除欄位）
     * @throws NullPointerException     當 {@code path} 為 null
     * @throws IllegalStateException    若 {@link #load()} 尚未成功執行
     */
    public void set(String path, Object value) {
        Objects.requireNonNull(path, "path");
        if (current == null) {
            throw new IllegalStateException("load() must be called before set()");
        }
        current.set(path, value);
    }

    /**
     * 驗證指定設定檔是否符合 schema 必填欄位。
     *
     * @param config 欲驗證的設定檔；不可為 null
     * @throws NullPointerException 當 {@code config} 為 null
     * @throws ConfigException       當必填欄位缺失（攜帶 ACELIB-CFG-005）
     */
    public void validate(YamlConfiguration config) {
        Objects.requireNonNull(config, "config");
        List<String> missing = schema.validate(config);
        if (!missing.isEmpty()) {
            throw new ConfigException(
                "ACELIB-CFG-005",
                "必填欄位缺失：" + String.join(", ", missing)
            );
        }
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    /**
     * 解析設定檔的絕對路徑。
     */
    private File resolveFile() {
        return new File(plugin.getDataFolder(), fileName);
    }

    /**
     * 確保父目錄存在；若不存在則遞迴建立。
     */
    private static void ensureParentDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new ConfigException(
                "ACELIB-CFG-001",
                "無法建立設定檔父目錄：" + parent.getAbsolutePath()
            );
        }
    }

    /**
     * 從磁碟載入既有檔案。
     *
     * @throws ConfigException 當檔案格式錯誤（ACELIB-CFG-002）
     */
    private static YamlConfiguration loadFromDisk(File file) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
            return cfg;
        } catch (InvalidConfigurationException | IOException ex) {
            throw new ConfigException(
                "ACELIB-CFG-002",
                "設定檔格式錯誤：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）",
                ex
            );
        }
    }

    /**
     * 將設定寫回磁碟。
     *
     * @throws ConfigException 當寫入失敗（ACELIB-CFG-001）
     */
    private static void writeToDisk(YamlConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException ex) {
            throw new ConfigException(
                "ACELIB-CFG-001",
                "無法寫入設定檔：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）",
                ex
            );
        }
    }

    /**
     * 建立預設設定檔（從 schema defaults）。
     *
     * <p>優先嘗試 {@link JavaPlugin#saveResource(String, boolean)} 從 JAR 內
     * 複製；若 JAR 內無對應資源則用 schema defaults 自動生成。</p>
     */
    private YamlConfiguration createDefault() {
        // 嘗試從 JAR 內複製
        boolean resourceSaved = trySaveResource();
        if (resourceSaved) {
            // 從磁碟載入剛才複製的檔案
            return loadFromDisk(resolveFile());
        }
        // 用 schema defaults 生成
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set(VERSION_KEY, currentVersion.toString());
        applySchemaDefaults(cfg);
        return cfg;
    }

    /**
     * 嘗試從 JAR 內複製預設資源到 data folder。
     *
     * @return 成功回傳 true；若 JAR 內無對應資源回傳 false
     */
    private boolean trySaveResource() {
        try {
            plugin.saveResource(fileName, false);
            return true;
        } catch (IllegalArgumentException ex) {
            // JAR 內無對應資源
            return false;
        } catch (Throwable t) {
            // 其他錯誤（例如 IO）也算失敗
            return false;
        }
    }

    /**
     * 將 schema defaults 套用到 config（補齊缺失欄位）。
     */
    private void applySchemaDefaults(YamlConfiguration config) {
        for (FieldSpec field : schema.fields()) {
            if (!config.contains(field.path())) {
                config.set(field.path(), field.defaultValue());
            }
        }
    }

    /**
     * 從設定檔讀取版本欄位。
     */
    private static ConfigVersion readVersion(YamlConfiguration config) {
        Object raw = config.get(VERSION_KEY);
        if (raw == null) {
            return null;
        }
        return MigrationChain.parseVersion(raw.toString());
    }
}