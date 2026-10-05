package com.smile.acelib.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 設定檔管理器（單一檔案）。
 *
 * <p>負責設定檔的載入、遷移、儲存與驗證，對外承諾：</p>
 * <ul>
 *   <li>預設設定檔生成（首次啟動無檔案時自動建立）</li>
 *   <li>設定檔版本欄位存在；版本過舊自動觸發遷移</li>
 *   <li>缺失欄位可被補齊（不破壞既有值）</li>
 *   <li>磁碟版本比當前版本新時拒絕載入，不降版覆寫既有檔案（拒絕發生在任何寫盤之前）</li>
 *   <li>reload 失敗保留舊設定，且失敗原因一定會被記錄</li>
 *   <li>寫入走 temp + atomic move；不支援 {@code ATOMIC_MOVE}
 *       的檔案系統只降級重試一次非原子取代，仍可能失敗，原子性僅在支援的檔案系統上成立；
 *       替換開始前的失敗不動目標檔，取代開始後的保證見 {@link #save()}</li>
 *   <li>必填欄位驗證</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-CFG-001}：設定檔不存在且無法生成／寫入失敗</li>
 *   <li>{@code ACELIB-CFG-002}：設定檔格式錯誤（YAML 解析失敗）</li>
 *   <li>{@code ACELIB-CFG-004}：設定遷移失敗</li>
 *   <li>{@code ACELIB-CFG-005}：必填欄位缺失</li>
 *   <li>{@code ACELIB-CFG-006}：磁碟上的設定版本比當前版本新（拒絕降版覆寫）</li>
 * </ul>
 *
 * <h2>執行緒模型</h2>
 * <p>{@link #current} 為 {@code volatile}，確保 reload 切換時對其他執行緒可見；
 * 但 {@link YamlConfiguration} 內部並非執行緒安全，並發呼叫 {@link #set} 應由
 * caller 負責同步（典型情境：只在主執行緒 reload）。</p>
 *
 * @since 1.0.0
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
     *   <li>套用 schema defaults（補齊缺失欄位）</li>
     *   <li>比較版本：檔案版本較新且檔案本來就存在於磁碟 → 拒絕（ACELIB-CFG-006）；
     *       較舊則執行 {@link MigrationChain} 遷移</li>
     *   <li>將當前版本寫回檔案（標記已升級）</li>
     * </ol>
     *
     * <p>第 4 步的拒絕只針對「磁碟上已存在的檔案」。自己生成的預設檔沒有管理者資料可保護，
     * 沿用原本的行為（版本一律收斂到 schema 宣告的當前版本）。</p>
     *
     * <p>成功時一律回寫：即使版本相同、內容沒有變動，結果仍會經重新序列化後寫回磁碟；
     * 原檔的排版可能改變，註解不會保留。版本較新被拒絕、或替換開始前的任何一步失敗時，
     * 目標檔逐位元不變；一旦進入取代階段，保證依寫入路徑而定（見 {@link #save()}）。</p>
     *
     * @throws ConfigException 當設定檔無法生成、解析失敗、磁碟版本較新、遷移失敗或寫入失敗時
     */
    public void load() {
        File file = resolveFile();
        ensureParentDirectory(file);

        boolean adoptingExistingFile = file.exists();
        YamlConfiguration config =
            adoptingExistingFile ? loadFromDisk(file) : createDefault();

        this.current = normalize(config, file, adoptingExistingFile);
        this.ready = true;
    }

    /**
     * 重新載入設定檔。
     *
     * <p>採用與 {@link #load()} 相同的版本政策：磁碟版本較新時拒絕（ACELIB-CFG-006）、
     * 版本較舊時執行 migration、補齊 schema 缺欄位，並把結果寫回磁碟。
     * 差異只在於檔案不存在時不會重新生成，而是回傳 {@code false}。</p>
     *
     * <p>成功時一律回寫：即使內容沒有變動，結果仍會重新序列化後寫回磁碟；
     * 原檔的排版可能改變，註解不會保留。替換開始前的失敗不動目標檔；
     * 一旦進入取代階段，保證依寫入路徑而定（見 {@link #save()}）。</p>
     *
     * <p>若新檔案損壞、無法解析、或發生任何例外，<strong>保留舊的 {@link YamlConfiguration}
     * 實例</strong>並回傳 false；plugin 不會崩潰，呼叫端可選擇重試或忽略。
     * 失敗原因一定會以 {@link #safeLogger()} 記錄（含 {@code ACELIB-*} 錯誤代碼與檔名），
     * 不做靜默的 {@code false}。</p>
     *
     * @return 成功回傳 true；失敗回傳 false（舊值仍可用，且失敗原因已記錄）
     */
    public boolean reload() {
        File file = resolveFile();
        if (!file.exists()) {
            recordReloadFailure("ACELIB-CFG-001",
                "設定檔不存在，未套用磁碟上的變更：" + file.getAbsolutePath());
            return false;
        }
        try {
            this.current = normalize(loadFromDisk(file), file, true);
            this.ready = true;
            return true;
        } catch (ConfigException ex) {
            recordReloadFailure(ex.getCode(), ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            // 每個已知失敗點都已被上方的分支帶上錯誤代碼；走到這裡代表是未預期例外，
            // 連同例外型別一起記錄，讓原因仍可從 log 追查。
            recordReloadFailure(null,
                "未預期例外 " + ex.getClass().getName() + "：" + ex.getMessage()
                    + "（檔案：" + file.getAbsolutePath() + "）");
            return false;
        }
    }

    /**
     * 將當前設定寫回磁碟。
     *
     * <p>走 temp + atomic move：暫存檔建在與目標檔同目錄（不涉及跨檔案系統搬移），
     * 完整寫好後才取代目標檔。保證分階段而定：暫存檔建置與寫入失敗時目標檔逐位元不變，
     * 暫存檔會被清掉；原子取代具備全有全無的性質。不支援原子搬移時只降級重試一次
     * 非原子取代，仍可能失敗，且一旦取代開始後失敗，目標檔可能處於不完整狀態，
     * 不承諾舊檔逐位元不變。取代後檔案權限可能變為暫存檔建立時的權限，目前尚未實測。</p>
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
     * 將設定寫回磁碟（temp + atomic move；替換開始前的失敗不動目標檔，
     * 取代開始後的保證依寫入路徑而定，見 {@link #save()}）。
     *
     * @throws ConfigException 當寫入失敗（ACELIB-CFG-001）
     */
    private static void writeToDisk(YamlConfiguration config, File file) {
        YamlFileWriter.writeAtomically(config, file.toPath(), "ACELIB-CFG-001", "設定檔");
    }

    /**
     * 統一套用版本政策並落盤；{@link #load()} 與 {@link #reload()} 共用這條路徑，
     * 避免兩邊對「較新／較舊版本」的處理日久生分歧。
     *
     * <p>順序刻意如此：先擋掉較新版本（此時還沒碰磁碟），再補欄位與遷移，最後才寫回。
     * 替換開始前的任何一步失敗都不會動到磁碟上的目標檔；一旦進入取代階段，
     * 保證依寫入路徑而定：在降級為非原子取代的檔案系統上，取代動作本身失敗時
     * 目標檔可能短暫處於不完整狀態，不承諾舊檔逐位元不變。</p>
     *
     * @param config              已載入或新建的設定內容
     * @param file                目標檔案
     * @param adoptingExistingFile 是否為「採用磁碟上既有的檔案」。
     *                              為 true 時磁碟版本比當前版本新會拒絕；
     *                              自己生成的預設檔則沿用原本的收斂行為
     * @return 處理完畢、可直接發布的設定內容
     * @throws ConfigException 當版本較新被拒絕（ACELIB-CFG-006）、遷移失敗（ACELIB-CFG-004）
     *                         或寫入失敗（ACELIB-CFG-001）
     */
    private YamlConfiguration normalize(YamlConfiguration config, File file,
                                        boolean adoptingExistingFile) {
        // 補齊缺失欄位（不破壞既有值）
        applySchemaDefaults(config);

        ConfigVersion fileVersion = readVersion(config);
        if (fileVersion != null) {
            int comparison = fileVersion.compareTo(currentVersion);
            if (comparison > 0 && adoptingExistingFile) {
                throw new ConfigException("ACELIB-CFG-006",
                    "磁碟上的設定版本 " + fileVersion + " 比當前版本 " + currentVersion
                        + " 新，已拒絕降版覆寫（" + file.getAbsolutePath()
                        + "）。請升級 AceLib，或先備份並移除該檔讓它重新生成。");
            }
            if (comparison < 0) {
                MigrationResult result = migrationChain.migrateAll(config, currentVersion);
                if (!result.success()) {
                    throw new ConfigException(
                        "ACELIB-CFG-004",
                        "設定遷移失敗：" + String.join("; ", result.warnings()),
                        null
                    );
                }
            }
        }

        // 確保 version 欄位存在且為當前版本
        config.set(VERSION_KEY, currentVersion.toString());

        writeToDisk(config, file);
        return config;
    }

    /**
     * 記錄 {@link #reload()} 的失敗原因。
     *
     * <p>{@code reload()} 沿用「回傳 boolean」的約定，失敗時不改變已生效的設定。
     * 為了讓呼叫端與管理者仍能查到原因，這裡固定以 WARNING 記錄（plugin 會繼續用舊設定運行，
     * 但問題必須可見），並盡量帶上 {@code ACELIB-*} 錯誤代碼。</p>
     *
     * @param code   錯誤代碼；未預期例外時為 null
     * @param reason 人可讀的原因說明
     */
    private void recordReloadFailure(String code, String reason) {
        Logger logger = safeLogger();
        if (code == null) {
            logger.log(Level.WARNING,
                "設定檔 reload 失敗，已保留舊設定（未分類錯誤）：{0}", reason);
        } else {
            logger.log(Level.WARNING,
                "[{0}] 設定檔 reload 失敗，已保留舊設定：{1}", new Object[]{code, reason});
        }
    }

    /**
     * 取得 plugin logger（測試環境或 disable 後安全退避）。
     */
    private Logger safeLogger() {
        try {
            Logger logger = plugin.getLogger();
            return logger != null ? logger : Logger.getLogger("AceLib");
        } catch (Throwable t) {
            return Logger.getLogger("AceLib");
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