package com.smile.acelib.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>啟動四分類（{@link #startup()}）：首次安裝／有效設定／損壞設定／使用後缺檔；
 *       識別依據是安裝狀態 sidecar，不是檔案是否存在</li>
 *   <li>預設設定檔生成（首次啟動無檔案時自動建立；使用後缺檔時以最後成功副本重建）</li>
 *   <li>設定檔版本欄位存在；版本過舊自動觸發遷移</li>
 *   <li>缺失欄位可被補齊（不破壞既有值）</li>
 *   <li>磁碟版本比當前版本新時拒絕載入，不降版覆寫既有檔案（拒絕發生在任何寫盤之前）</li>
 *   <li>損壞設定不覆寫原檔；保留最後驗證成功副本，呼叫端可指定保守後備設定</li>
 *   <li>整份驗證通過後一次發布不可變快照（{@link #snapshot()}）；同輪操作固定同一快照</li>
 *   <li>reload 失敗保留舊設定，且失敗原因一定會被記錄</li>
 *   <li>寫入走 temp + atomic move；不支援 {@code ATOMIC_MOVE}
 *       的檔案系統只降級重試一次非原子取代，仍可能失敗，原子性僅在支援的檔案系統上成立；
 *       替換開始前的失敗不動目標檔，取代開始後的保證見 {@link #save()}</li>
 *   <li>寫回保留既有註解：只改值變了的行、只補缺的 key（含欄位說明），其餘逐位元保留</li>
 *   <li>檔案監看自動重載（{@link #startWatching})；無效新內容保留舊快照並診斷；
 *       reload 不殺監看器，{@link #close()} 徹底清理</li>
 *   <li>必填欄位驗證；型別／範圍／列舉綁定驗證（{@link #bind(Class)}）</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-CFG-001}：設定檔不存在且無法生成／寫入失敗</li>
 *   <li>{@code ACELIB-CFG-002}：設定檔格式錯誤（YAML 解析失敗）</li>
 *   <li>{@code ACELIB-CFG-003}：損壞且無最後成功副本、無後備可用</li>
 *   <li>{@code ACELIB-CFG-004}：設定遷移失敗</li>
 *   <li>{@code ACELIB-CFG-005}：必填欄位缺失</li>
 *   <li>{@code ACELIB-CFG-006}：磁碟上的設定版本比當前版本新（拒絕降版覆寫）</li>
 *   <li>{@code ACELIB-CFG-007}：綁定失敗（型別／範圍／列舉／缺失，帶完整欄位路徑）</li>
 * </ul>
 *
 * <h2>執行緒模型</h2>
 * <p>{@link #current} 與快照引用為 {@code volatile}，確保 reload 切換時對其他執行緒可見；
 * 但 {@link YamlConfiguration} 內部並非執行緒安全，並發呼叫 {@link #set} 應由
 * caller 負責同步（典型情境：只在主執行緒 reload）。
 * 監看執行緒只做檔案雜湊比對與呼叫驗證管線，不碰遊戲物件，且為 daemon。</p>
 *
 * <h2>併發寫入互斥</h2>
 * <p>{@code load}／{@code reload}／{@code save}／{@code startup}／{@code set}
 * 以 manager 實例為鎖互斥：監看執行緒觸發的重載與主執行緒的寫入不會併發落盤、
 * 不會互相覆寫。監看回呼（{@link ConfigChangeListener}）在鎖外執行，
 * 回呼內再呼叫本 manager 的寫入方法不會死鎖，但應避免耗時工作。</p>
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
    /** 欄位說明（路徑→註解）：寫回補缺 key 時附在旁邊；監看執行緒只讀。 */
    private final Map<String, String> descriptions = new ConcurrentHashMap<>();
    /**
     * 明確刪除追蹤（{@code set(path, null)} 登記、設值取消登記）。
     *
     * <p>合併寫回時據此刪行；{@link #save()} 成功後清空，
     * {@code load}／{@code reload}／{@code startup} 成功也會清空
     *（從磁碟重建後，舊的刪除追蹤已無意義）。</p>
     */
    private final java.util.Set<String> removedPaths = new java.util.HashSet<>();
    private volatile YamlConfiguration current;
    private volatile ConfigSnapshot snapshotRef;
    private volatile boolean ready = false;
    private volatile ConfigFileWatcher watcher;
    private volatile ConfigChangeListener watchListener;
    private volatile String lastFailureCode;
    private volatile String lastFailureDetail;

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

    /**
     * 取得當前不可變快照（驗證通過後發布的值）。
     *
     * <p>快照深層不可變，外部修改必失敗；同輪操作（get／set／save）
     * 固定回傳同一個實例，只有 load／reload／startup 成功才換新實例。</p>
     *
     * @return 當前快照；尚未成功載入回傳 null
     */
    public ConfigSnapshot snapshot() {
        return snapshotRef;
    }

    /**
     * 把當前快照綁定到 record 或一般類別（型別／範圍／列舉一次驗證）。
     *
     * @param type 目標型別；不可為 null
     * @param <T>  目標型別
     * @return 綁定完成的實例
     * @throws NullPointerException   當 {@code type} 為 null
     * @throws IllegalStateException  尚未成功載入（無快照可綁）
     * @throws ConfigBindingException 當驗證失敗（ACELIB-CFG-007，帶完整欄位路徑）
     * @see ConfigBinder#bind(ConfigSnapshot, Class)
     */
    public <T> T bind(Class<T> type) {
        Objects.requireNonNull(type, "type");
        ConfigSnapshot snapshot = snapshotRef;
        if (snapshot == null) {
            throw new IllegalStateException("load()/startup() 必須先成功，才有快照可綁定");
        }
        return ConfigBinder.bind(snapshot, type);
    }

    /**
     * 設定欄位的說明文字（寫回補缺 key 時寫在旁邊當註解）。
     *
     * @param path        點分隔路徑；不可為 null
     * @param description 說明文字；不可為 null
     * @throws NullPointerException 當任一參數為 null
     */
    public void setFieldDescription(String path, String description) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
        descriptions.put(path, description);
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
     * <p>成功時做保註解合併寫回：只改值變了的行、只補缺的 key，
     * 原檔的註解與排版保留。版本較新被拒絕、或替換開始前的任何一步失敗時，
     * 目標檔逐位元不變；一旦進入取代階段，保證依寫入路徑而定（見 {@link #save()}）。</p>
     *
     * <p>與 {@link #startup()} 的差異：本方法不分類、不回傳結果，
     * 損壞時直接拋 {@link ConfigException}（既有呼叫端行為不變）。
     * 需要四分類或損壞時繼續跑請用 {@link #startup()}。</p>
     *
     * @throws ConfigException 當設定檔無法生成、解析失敗、磁碟版本較新、遷移失敗或寫入失敗時
     */
    public synchronized void load() {
        startupInternal(null, false);
    }

    /**
     * 啟動設定：四分類、壞檔不覆寫、快照只在整份驗證通過後發布。
     *
     * <p>損壞（{@link StartupResult.Status#CORRUPT}）時原檔逐位元不動，
     * 快照依序取用：最後成功副本 → 記憶體舊快照 → null
     *（無副本時 detail 帶 {@code ACELIB-CFG-003}，下游可據此禁用操作）。
     * 本方法不拋 {@link ConfigException}（非預期的 runtime 例外除外）。</p>
     *
     * @return 啟動結果（含分類、快照與診斷）；永不為 null
     */
    public synchronized StartupResult startup() {
        return startupInternal(null, true);
    }

    /**
     * 同 {@link #startup()}，但損壞且無最後成功副本時改用呼叫端指定的
     * 保守後備設定（後備物件由呼叫端背書，直接做成快照，不覆寫原檔）。
     *
     * @param fallback 保守後備設定；可為 null（等同 {@link #startup()}）
     * @return 啟動結果；永不為 null
     */
    public synchronized StartupResult startup(YamlConfiguration fallback) {
        return startupInternal(fallback, true);
    }

    /**
     * 啟動管線共用入口（{@link #load()} 與 {@link #startup()} 共用）。
     *
     * @param fallback 呼叫端後備；可為 null
     * @param lenient  true=損壞時回傳 CORRUPT；false=損壞時原樣拋出（load 語意）
     * @return lenient 時的啟動結果；非 lenient 時成功回傳 LOADED／FRESH 結果（忽略即可）
     * @throws ConfigException 當非 lenient 且管線失敗
     */
    private StartupResult startupInternal(YamlConfiguration fallback, boolean lenient) {
        File file = resolveFile();
        ensureParentDirectory(file);
        Path path = file.toPath();

        if (!file.exists()) {
            boolean everInstalled = InstallStateStore.wasEverInstalled(path);
            if (everInstalled) {
                // 使用後缺檔：優先以最後成功副本重建，讓伺服器回到上次能跑的狀態
                StartupResult restored = tryRestoreLastGood(file, path);
                if (restored != null) {
                    return restored;
                }
            }
            try {
                verifyAndPublish(createDefault(), file, false, true);
            } catch (ConfigException ex) {
                if (!lenient) {
                    throw ex;
                }
                return corruptResult(file, path, ex, fallback);
            }
            StartupResult.Status status = everInstalled
                ? StartupResult.Status.MISSING_AFTER_USE
                : StartupResult.Status.FRESH_INSTALL;
            String detail = everInstalled
                ? "使用後缺檔：" + file.getAbsolutePath() + " 不存在，已重建預設檔並生效"
                : "首次安裝：已依 schema 生成預設檔 " + file.getAbsolutePath();
            return new StartupResult(status, snapshotRef, detail);
        }

        try {
            verifyAndPublish(loadFromDisk(file), file, true, true);
            return new StartupResult(StartupResult.Status.LOADED, snapshotRef,
                "設定驗證通過並生效（版本 " + currentVersion + "）：" + file.getAbsolutePath());
        } catch (ConfigException ex) {
            if (!lenient) {
                throw ex;
            }
            return corruptResult(file, path, ex, fallback);
        }
    }

    /**
     * 建構損壞結果：原檔不動，快照取最後成功副本／後備／記憶體舊快照，
     * 全無時為 null 並在診斷帶 {@code ACELIB-CFG-003}。
     */
    private StartupResult corruptResult(File file, Path path, ConfigException ex,
                                        YamlConfiguration fallback) {
        FallbackResolution resolution = resolveFallbackSnapshot(path, fallback);
        String detail = "[" + ex.getCode() + "] " + ex.getMessage() + "；原檔未動，"
            + (resolution.snapshot() != null
                ? "已沿用" + resolution.source() + "。"
                : "且無可用副本（ACELIB-CFG-003：損壞且無最後成功副本、無後備可用）。");
        recordReloadFailure(ex.getCode(), "啟動時設定損壞：" + ex.getMessage()
            + "（檔案：" + file.getAbsolutePath() + "）");
        return new StartupResult(StartupResult.Status.CORRUPT, resolution.snapshot(), detail);
    }

    /**
     * 以最後成功副本重建缺失的設定檔（使用後缺檔路徑）。
     *
     * <p>還原走與既有檔案相同的版本政策：副本版本比當前版本新時拒絕
     * （ACELIB-CFG-006），不覆寫副本，改用預設值重建（同樣不覆寫副本）。</p>
     *
     * @return 重建成功回傳 MISSING_AFTER_USE 結果；副本無效回傳 null（呼叫端改走預設生成）
     */
    private StartupResult tryRestoreLastGood(File file, Path path) {
        String lastGood = InstallStateStore.readLastGood(path);
        if (lastGood == null) {
            return null;
        }
        YamlConfiguration restored = new YamlConfiguration();
        try {
            restored.loadFromString(lastGood);
        } catch (Exception ex) {
            // 副本解析失敗：退回預設生成，不因副本壞掉而讓啟動失敗
            return null;
        }
        try {
            // 採用既有檔案的版本政策（true）：副本較新時拒絕，不降版覆寫
            verifyAndPublish(restored, file, true, true);
            return new StartupResult(StartupResult.Status.MISSING_AFTER_USE, snapshotRef,
                "使用後缺檔：" + file.getAbsolutePath() + " 不存在，已用最後成功副本重建並生效");
        } catch (ConfigException ex) {
            if ("ACELIB-CFG-006".equals(ex.getCode())) {
                // 副本較新：拒絕還原，副本不動；改用預設值重建，同樣不覆寫副本
                verifyAndPublish(createDefault(), file, false, false);
                ConfigVersion copyVersion = readVersion(restored);
                recordReloadFailure(ex.getCode(),
                    "最後成功副本版本 " + copyVersion + " 比當前版本 " + currentVersion
                        + " 新，已拒絕還原並改用預設值重建（檔案：" + file.getAbsolutePath()
                        + "）；副本未動。");
                return new StartupResult(StartupResult.Status.MISSING_AFTER_USE, snapshotRef,
                    "使用後缺檔：" + file.getAbsolutePath() + " 不存在；最後成功副本版本較新"
                        + "（ACELIB-CFG-006），已拒絕還原並改用預設值重建，副本未動");
            }
            // 副本在新 schema 下已無效（遷移失敗等）：退回預設生成
            return null;
        }
    }

    /** 後備快照解析結果（快照＋來源說明）。 */
    private record FallbackResolution(ConfigSnapshot snapshot, String source) {
    }

    /**
     * 解析損壞時的後備快照：最後成功副本 → 呼叫端後備 → 記憶體舊快照 → null。
     */
    private FallbackResolution resolveFallbackSnapshot(Path path, YamlConfiguration fallback) {
        String lastGood = InstallStateStore.readLastGood(path);
        if (lastGood != null) {
            try {
                YamlConfiguration parsed = new YamlConfiguration();
                parsed.loadFromString(lastGood);
                return new FallbackResolution(
                    new ConfigSnapshot(parsed.getValues(false)), "最後成功副本");
            } catch (Exception ignored) {
                // 副本壞了就往下一個來源找
            }
        }
        if (fallback != null) {
            return new FallbackResolution(
                new ConfigSnapshot(fallback.getValues(false)), "呼叫端指定的後備設定");
        }
        ConfigSnapshot memory = snapshotRef;
        if (memory != null) {
            return new FallbackResolution(memory, "記憶體中的舊快照");
        }
        return new FallbackResolution(null, "");
    }

    /**
     * 重新載入設定檔。
     *
     * <p>採用與 {@link #load()} 相同的版本政策：磁碟版本較新時拒絕（ACELIB-CFG-006）、
     * 版本較舊時執行 migration、補齊 schema 缺欄位，並把結果寫回磁碟。
     * 差異只在於檔案不存在時不會重新生成，而是回傳 {@code false}。</p>
     *
     * <p>成功時做保註解合併寫回（見 {@link #load()}），並發布新快照；
     * 舊快照實例的值保持凍結。替換開始前的失敗不動目標檔；
     * 一旦進入取代階段，保證依寫入路徑而定（見 {@link #save()}）。</p>
     *
     * <p>若新檔案損壞、無法解析、或發生任何例外，<strong>保留舊的 {@link YamlConfiguration}
     * 實例</strong>並回傳 false；plugin 不會崩潰，呼叫端可選擇重試或忽略。
     * 失敗原因一定會以 {@link #safeLogger()} 記錄（含 {@code ACELIB-*} 錯誤代碼與檔名），
     * 不做靜默的 {@code false}。</p>
     *
     * @return 成功回傳 true；失敗回傳 false（舊值仍可用，且失敗原因已記錄）
     */
    public synchronized boolean reload() {
        File file = resolveFile();
        if (!file.exists()) {
            recordReloadFailure("ACELIB-CFG-001",
                "設定檔不存在，未套用磁碟上的變更：" + file.getAbsolutePath());
            return false;
        }
        try {
            verifyAndPublish(loadFromDisk(file), file, true, true);
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
     * <p>走保註解合併寫回：只改值變了的行、只補缺的 key，
     * {@code set(path, null)} 明確刪除的 key 會真的刪行（連同因此變空的祖先節頭），
     * 原檔註解與排版保留；寫回後 {@link #getConfiguration()} 會包含磁碟上原有的未知 key
     *（合併保留使用者資料），但已發布的快照不變（同輪一致）。</p>
     *
     * <p>走 temp + atomic move：暫存檔建在與目標檔同目錄（不涉及跨檔案系統搬移），
     * 完整寫好後才取代目標檔。保證分階段而定：暫存檔建置與寫入失敗時目標檔逐位元不變，
     * 暫存檔會被清掉；原子取代具備全有全無的性質。不支援原子搬移時只降級重試一次
     * 非原子取代，仍可能失敗，且一旦取代開始後失敗，目標檔可能處於不完整狀態，
     * 不承諾舊檔逐位元不變。取代成功後盡力還原目標檔原有 POSIX 權限（最佳努力）。</p>
     *
     * @throws IllegalStateException 若 {@link #load()} 尚未成功執行
     * @throws ConfigException       若寫入磁碟失敗
     */
    public synchronized void save() {
        if (current == null) {
            throw new IllegalStateException("load() must be called before save()");
        }
        File file = resolveFile();
        ensureParentDirectory(file);
        String merged = writeToDisk(current, file, new java.util.HashSet<>(removedPaths));
        // 寫回可能保留了磁碟上原有的未知 key：把合併結果載回 current，
        // 讓 getConfiguration() 與磁碟一致；快照刻意不換（同輪一致）。
        YamlConfiguration refreshed = new YamlConfiguration();
        try {
            refreshed.loadFromString(merged);
        } catch (InvalidConfigurationException ex) {
            throw new ConfigException("ACELIB-CFG-001",
                "設定寫回後回讀失敗：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）", ex);
        }
        this.current = refreshed;
        removedPaths.clear();
        markWatcherClean(file.toPath());
    }

    // -----------------------------------------------------------------
    // 檔案監看
    // -----------------------------------------------------------------

    /**
     * 啟動檔案監看：外部修改且驗證通過時自動重載並通知。
     *
     * <p>新內容驗證失敗時保留舊快照，原檔不動，並以
     * {@link ConfigChangeListener#onInvalidReload} 診斷（含錯誤碼）。
     * 自己的寫回（load／reload／save 的落盤）不會觸發重載迴圈。
     * 重複呼叫會先停掉舊監看器再啟動，不洩漏執行緒；
     * {@link #reload()} 不影響監看，{@link #close()} 徹底停止。</p>
     *
     * @param listener 監看回呼；不可為 null
     * @throws NullPointerException  當 {@code listener} 為 null
     * @throws IllegalStateException 當尚未成功載入（無基準內容可比對）
     */
    public synchronized void startWatching(ConfigChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!ready) {
            throw new IllegalStateException("load()/startup() 必須先成功，才能啟動監看");
        }
        stopWatcherLocked();
        Path path = resolveFile().toPath();
        ConfigFileWatcher fresh = new ConfigFileWatcher(
            path, this::reloadForWatcher, 200, safeLogger());
        fresh.markClean(path);
        this.watchListener = listener;
        this.watcher = fresh;
        fresh.start(watcherThreadName());
    }

    /**
     * 停止檔案監看（冪等）。停止後外部變更不再自動套用。
     */
    public synchronized void stopWatching() {
        stopWatcherLocked();
    }

    /**
     * 是否正在監看。
     */
    public boolean isWatching() {
        ConfigFileWatcher active = watcher;
        return active != null && active.isRunning();
    }

    /**
     * 關閉本 manager 持有的資源（目前即監看器）。
     *
     * <p>冪等，可重複呼叫。plugin disable 時必須呼叫，
     * 否則監看執行緒（daemon）雖不擋 JVM 退出，仍會在重載後殘留誤觸。</p>
     */
    public synchronized void close() {
        stopWatcherLocked();
    }

    /**
     * 受控輪詢一次（監看執行緒收到事件後走同一入口）。
     *
     * <p>測試與除錯用：決定性驅動一次檢查，不依賴真實時間。
     * 未啟動監看時為 no-op。</p>
     */
    synchronized void pollWatcherOnce() {
        ConfigFileWatcher active = watcher;
        if (active != null) {
            // 受控輪詢走強制路徑：單次呼叫即反應，決定性且不依賴真實時間
            active.checkNow(resolveFile().toPath(), true);
        }
    }

    private void stopWatcherLocked() {
        ConfigFileWatcher active = watcher;
        watcher = null;
        watchListener = null;
        if (active != null) {
            active.stop();
        }
    }

    private String watcherThreadName() {
        String safe = fileName.replaceAll("[^A-Za-z0-9]+", "-");
        return "acelib-config-watch-" + safe + "@"
            + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * 監看觸發的重載：成功發布新快照並通知，失敗保留舊快照並診斷。
     */
    private boolean reloadForWatcher() {
        boolean ok = reload();
        ConfigChangeListener listener = watchListener;
        if (listener == null) {
            return ok;
        }
        if (ok) {
            ConfigSnapshot published = snapshotRef;
            if (published != null) {
                listener.onReload(published);
            }
        } else {
            listener.onInvalidReload(
                lastFailureCode == null ? "ACELIB-CFG-002" : lastFailureCode,
                lastFailureDetail == null ? "重載失敗" : lastFailureDetail);
        }
        return ok;
    }

    private void markWatcherClean(Path path) {
        ConfigFileWatcher active = watcher;
        if (active != null) {
            active.markClean(path);
        }
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
     * <p>傳 null 即明確刪除：記憶體內立即清除，{@link #save()} 時也會從檔案刪行
     *（含因此變空的祖先節頭）。之後再設回非 null 值，刪除登記即取消。</p>
     *
     * @param path  YAML 路徑；不可為 null
     * @param value 欲設定的值；可為 null（表示清除欄位）
     * @throws NullPointerException     當 {@code path} 為 null
     * @throws IllegalStateException    若 {@link #load()} 尚未成功執行
     */
    public synchronized void set(String path, Object value) {
        Objects.requireNonNull(path, "path");
        if (current == null) {
            throw new IllegalStateException("load() must be called before set()");
        }
        current.set(path, value);
        if (value == null) {
            removedPaths.add(path);
        } else {
            removedPaths.remove(path);
        }
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
     * 將設定寫回磁碟（保註解合併＋temp + atomic move；替換開始前的失敗不動目標檔，
     * 取代開始後的保證依寫入路徑而定，見 {@link #save()}）。
     *
     * @param removed 明確刪除的路徑（合併時刪行）；不可為 null
     * @return 實際落盤的全文（呼叫端據此發布快照與保存副本）
     * @throws ConfigException 當寫入失敗（ACELIB-CFG-001）
     */
    private String writeToDisk(YamlConfiguration config, File file, java.util.Set<String> removed) {
        Path target = file.toPath();
        String original = null;
        if (Files.isRegularFile(target)) {
            try {
                original = Files.readString(target, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ex) {
                // 讀不到原檔就當新生成；合併器會處理 null
                original = null;
            }
        }
        Map<String, Object> flat = CommentPreservingWriter.flatten(config.getValues(false));
        String merged = CommentPreservingWriter.merge(original, flat, descriptions, removed);
        YamlFileWriter.writeTextAtomically(merged, target, "ACELIB-CFG-001", "設定檔");
        return merged;
    }

    /**
     * 統一驗證、落盤與發布；{@link #load()}、{@link #reload()} 與
     * {@link #startup()} 共用這條路徑，避免多邊對「較新／較舊版本」的處理日久生分歧。
     *
     * <p>順序刻意如此：先擋掉較新版本（此時還沒碰磁碟），再補欄位與遷移，
     * 最後做保註解合併寫回。整份驗證通過後才一次發布不可變快照；
     * 替換開始前的任何一步失敗都不會動到磁碟上的目標檔，也不會更換快照；
     * 一旦進入取代階段，保證依寫入路徑而定：在降級為非原子取代的檔案系統上，
     * 取代動作本身失敗時目標檔可能短暫處於不完整狀態，不承諾舊檔逐位元不變。</p>
     *
     * <p>成功後另做三件 sidecar 維護（皆最佳努力、失敗不影響本次生效）：
     * 保存最後成功副本、標記安裝狀態、把監看基準對齊新內容。</p>
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
        return verifyAndPublish(config, file, adoptingExistingFile, true);
    }

    /**
     * 見 {@link #normalize} 的完整契約說明。
     *
     * @param updateLastGood 成功後是否更新最後成功副本；副本較新被拒、改用預設重建時
     *                       傳 false（副本不動），其餘傳 true
     */
    private YamlConfiguration verifyAndPublish(YamlConfiguration config, File file,
                                               boolean adoptingExistingFile,
                                               boolean updateLastGood) {
        // 補齊缺失欄位（不破壞既有值）
        applySchemaDefaults(config);

        java.util.Set<String> migrationRemoved = java.util.Collections.emptySet();
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
                // 記下遷移前的 key：遷移刪掉的 key 必須真的從輸出移除，
                // 不能只因為「原檔有、新值沒有」就保留（那是給使用者未知 key 的）。
                java.util.Set<String> before = CommentPreservingWriter
                    .flatten(config.getValues(false)).keySet();
                MigrationResult result = migrationChain.migrateAll(config, currentVersion);
                if (!result.success()) {
                    throw new ConfigException(
                        "ACELIB-CFG-004",
                        "設定遷移失敗：" + String.join("; ", result.warnings()),
                        null
                    );
                }
                java.util.Set<String> after = CommentPreservingWriter
                    .flatten(config.getValues(false)).keySet();
                migrationRemoved = new java.util.HashSet<>(before);
                migrationRemoved.removeAll(after);
            }
        }

        // 確保 version 欄位存在且為當前版本
        config.set(VERSION_KEY, currentVersion.toString());

        String merged = writeToDisk(config, file, migrationRemoved);

        YamlConfiguration published = new YamlConfiguration();
        try {
            published.loadFromString(merged);
        } catch (InvalidConfigurationException ex) {
            throw new ConfigException("ACELIB-CFG-001",
                "設定寫回後回讀失敗：" + file.getAbsolutePath() + "（" + ex.getMessage() + "）", ex);
        }
        this.current = published;
        this.snapshotRef = new ConfigSnapshot(published.getValues(false));
        this.ready = true;
        // 從磁碟重建後，之前 set(null) 的追蹤已無意義（記憶體即磁碟）
        removedPaths.clear();

        Path path = file.toPath();
        if (updateLastGood) {
            InstallStateStore.saveLastGood(path, merged);
        }
        InstallStateStore.markInstalled(path);
        markWatcherClean(path);
        return published;
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
        // 監看回呼需要同樣的診斷：先存下來，再記 log
        this.lastFailureCode = code;
        this.lastFailureDetail = reason;
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