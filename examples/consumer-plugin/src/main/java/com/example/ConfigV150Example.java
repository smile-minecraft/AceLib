package com.example;

import com.smile.acelib.config.ConfigBindingException;
import com.smile.acelib.config.ConfigCrossFieldValidator;
import com.smile.acelib.config.ConfigException;
import com.smile.acelib.config.ConfigManager;
import com.smile.acelib.config.ConfigMissingFileHandler;
import com.smile.acelib.config.ConfigSnapshot;
import java.util.Objects;

/**
 * AceLib 1.5.0 設定線的外部 consumer 範例。
 *
 * <p>本類別位於 AceLib 外部套件（{@code com.example}），只用公開 API：
 * 以 {@link ConfigSnapshot#generation} 判斷 reload 之後內容有沒有換世代；
 * 以 {@link ConfigSnapshot#getLong}／{@link ConfigSnapshot#getDouble} 讀數值；
 * 以 {@link ConfigManager#registerCrossFieldValidator} 登記跨欄位規則
 * （下限不得大於上限）；以
 * {@link ConfigManager#registerMissingFileHandler} 在使用後缺檔時拒絕靜默還原。</p>
 *
 * <p>讀快照與登記規則的方法隨伺服器啟動執行；規則本身與攔截本身是純函式，
 * 可在單元測試直接呼叫。</p>
 */
public final class ConfigV150Example {

    private ConfigV150Example() {
    }

    /**
     * 把兩條規則登記到設定管線（跨欄位驗證在先，缺檔攔截在後）。
     *
     * @param manager 設定管線；不可為 null
     * @return 同一個管線（鏈式 API）；永不為 null
     */
    public static ConfigManager wireUp(ConfigManager manager) {
        Objects.requireNonNull(manager, "manager");
        return manager.registerCrossFieldValidator(minNotGreaterThanMax())
            .registerMissingFileHandler(rejectRestore());
    }

    /**
     * 快照的世代：管線每次成功發布世代 +1，失敗不推進。
     *
     * @param snapshot 快照；不可為 null
     * @return 世代；不為負
     */
    public static long generationOf(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.generation();
    }

    /**
     * 以 long 讀取指定路徑（嚴格：小數與溢位報錯，不存在回預設值）。
     *
     * @param snapshot 快照；不可為 null
     * @param path 點分隔路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 長整數值或預設值
     */
    public static long readLong(ConfigSnapshot snapshot, String path, long defaultValue) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.getLong(path, defaultValue);
    }

    /**
     * 以 double 讀取指定路徑（NaN 與無限大報錯，不存在回預設值）。
     *
     * @param snapshot 快照；不可為 null
     * @param path 點分隔路徑；不可為 null
     * @param defaultValue 不存在或型別不合時的回傳
     * @return 雙精度浮點值或預設值
     */
    public static double readDouble(ConfigSnapshot snapshot, String path, double defaultValue) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.getDouble(path, defaultValue);
    }

    /**
     * 把快照的世代與關鍵數值翻成一句可記錄的摘要。
     *
     * @param snapshot 快照；不可為 null
     * @return 快照摘要；永不為 null
     */
    public static String auditSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return summarizeLimits(snapshot.generation(),
            snapshot.getLong("limits.min", 0L),
            snapshot.getLong("limits.max", 0L),
            snapshot.getDouble("ratio", 0.0));
    }

    /**
     * 把世代與關鍵數值翻成一句可記錄的摘要（純函式，不需伺服器）。
     *
     * @param generation 世代；不得為負
     * @param min 下限值
     * @param max 上限值
     * @param ratio 比率值
     * @return 快照摘要；永不為 null
     */
    public static String summarizeLimits(long generation, long min, long max, double ratio) {
        return "generation=" + generation + ", min=" + min + ", max=" + max + ", ratio=" + ratio;
    }

    /**
     * 跨欄位規則：下限不得大於上限（純函式，不需伺服器）。
     *
     * @param min 下限值
     * @param max 上限值
     * @throws ConfigBindingException 當下限大於上限（ACELIB-CFG-007）
     */
    public static void checkMinNotGreaterThanMax(long min, long max) {
        if (min > max) {
            throw new ConfigBindingException("limits",
                "規則 minNotGreaterThanMax 失敗：下限 " + min + " 大於上限 " + max);
        }
    }

    /**
     * 跨欄位驗證規則：讀候選快照的上下限，套用同一條規則。
     *
     * @return 驗證規則；永不為 null
     */
    public static ConfigCrossFieldValidator minNotGreaterThanMax() {
        return candidate -> checkMinNotGreaterThanMax(
            candidate.getLong("limits.min", 0L),
            candidate.getLong("limits.max", 0L));
    }

    /**
     * 缺檔攔截：使用後缺檔時拒絕靜默還原，直接失敗（純函式，不需伺服器）。
     *
     * @return 缺檔攔截規則；永不為 null
     */
    public static ConfigMissingFileHandler rejectRestore() {
        return configFile -> {
            throw new ConfigException("ACELIB-EXT-001",
                "設定檔遺失，拒絕以舊副本啟動：" + configFile.getAbsolutePath());
        };
    }
}
