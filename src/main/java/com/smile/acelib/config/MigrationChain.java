package com.smile.acelib.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 設定遷移鏈。
 *
 * <p>對應 Plan §九 Phase 4「設定檔版本欄位存在；版本過舊自動觸發遷移」。
 * 維護一組 {@link ConfigMigration}，按 {@link ConfigMigration#fromVersion()}
 * 排序，並依序執行直到達成目標版本。</p>
 *
 * <h2>執行語意</h2>
 * <ul>
 *   <li>若 {@code current.version >= target.version} → 直接回傳 success，無副作用</li>
 *   <li>若鏈中存在對應的 {@code from → to} → 依序套用，失敗時包裝為 failure result</li>
 *   <li>若找不到對應 migration（例如跳版本） → 回傳 failure + warning</li>
 * </ul>
 *
 * <h2>錯誤處理</h2>
 * <p>{@link #migrateAll} 永遠不會直接拋例外；任何 migration 內部例外都會被
 * 包裝為 {@link MigrationResult#failure} 並附帶警告訊息，
 * 確保 plugin 載入流程不會因單一 migration bug 而崩潰。</p>
 *
 * @since Phase 4 (Plan §九)
 */
public final class MigrationChain {

    private final List<ConfigMigration> migrations = new ArrayList<>();

    /**
     * 新增一個 migration 到鏈尾。
     *
     * @param migration 要加入的 migration；不可為 null
     * @return this（鏈式 API）
     * @throws NullPointerException 當 {@code migration} 為 null
     */
    public MigrationChain add(ConfigMigration migration) {
        Objects.requireNonNull(migration, "migration");
        migrations.add(migration);
        return this;
    }

    /**
     * 回傳按 {@code fromVersion} 升冪排序的 migration 清單。
     *
     * <p>回傳的是不可變 list，避免外部修改影響鏈狀態。</p>
     *
     * @return 不可變的排序後清單
     */
    public List<ConfigMigration> ordered() {
        List<ConfigMigration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparing(ConfigMigration::fromVersion));
        return List.copyOf(sorted);
    }

    /**
     * 將 {@code current} 從當前版本升級到 {@code targetVersion}。
     *
     * <p>演算法：</p>
     * <ol>
     *   <li>讀取 {@code current.version} 欄位；若不存在視為 {@code 0.0}</li>
     *   <li>若 {@code currentVersion >= targetVersion} → 直接 success</li>
     *   <li>否則，從 {@code currentVersion} 開始依序尋找
     *       {@code fromVersion == currentVersion} 的 migration 套用，
     *       直到 {@code currentVersion >= targetVersion}</li>
     *   <li>任何步驟失敗 → 回傳 failure + warning</li>
     * </ol>
     *
     * @param current        當前 YamlConfiguration（會被就地修改）
     * @param targetVersion  目標版本
     * @return 遷移結果，永不為 null
     */
    public MigrationResult migrateAll(YamlConfiguration current, ConfigVersion targetVersion) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(targetVersion, "targetVersion");

        ConfigVersion currentVersion = readVersion(current);
        if (currentVersion == null) {
            currentVersion = new ConfigVersion(0, 0);
        }

        // 已是目標版本
        if (currentVersion.compareTo(targetVersion) >= 0) {
            return MigrationResult.success(currentVersion, targetVersion);
        }

        List<ConfigMigration> sorted = ordered();
        List<String> warnings = new ArrayList<>();
        ConfigVersion cursor = currentVersion;

        // 防呆：限制最大迭代次數避免無限迴圈
        int maxIterations = sorted.size() + 1;
        int iterations = 0;

        while (cursor.compareTo(targetVersion) < 0) {
            if (++iterations > maxIterations) {
                warnings.add("migration loop exceeded max iterations; aborting");
                return new MigrationResult(currentVersion, targetVersion, false, List.copyOf(warnings));
            }

            ConfigMigration next = findMigrationStartingAt(sorted, cursor);
            if (next == null) {
                warnings.add(String.format(
                    "no migration registered from version %s; cannot reach target %s",
                    cursor, targetVersion));
                return new MigrationResult(currentVersion, targetVersion, false, List.copyOf(warnings));
            }

            try {
                next.migrate(current, current);
            } catch (RuntimeException ex) {
                warnings.add(String.format(
                    "migration %s -> %s threw exception: %s",
                    next.fromVersion(), next.toVersion(), ex.getMessage()));
                return new MigrationResult(currentVersion, targetVersion, false, List.copyOf(warnings));
            }

            cursor = next.toVersion();
            // 更新 version 欄位，讓後續讀取正確
            current.set("version", cursor.toString());
        }

        return MigrationResult.success(currentVersion, cursor, warnings);
    }

    /**
     * 從清單中找到 {@code fromVersion == target} 的 migration。
     */
    private static ConfigMigration findMigrationStartingAt(List<ConfigMigration> sorted, ConfigVersion target) {
        for (ConfigMigration m : sorted) {
            if (m.fromVersion().compareTo(target) == 0) {
                return m;
            }
        }
        return null;
    }

    /**
     * 從 YamlConfiguration 讀取 {@code version} 欄位。
     *
     * @return 解析成功的版本；若欄位不存在或格式錯誤則回傳 null
     */
    private static ConfigVersion readVersion(YamlConfiguration config) {
        Object raw = config.get("version");
        if (raw == null) {
            return null;
        }
        return parseVersion(raw.toString());
    }

    /**
     * 解析版本字串（支援 {@code "1.0"} 與 {@code "1"} 兩種格式）。
     *
     * @param text 版本字串
     * @return 解析結果；格式錯誤時回傳 null
     */
    static ConfigVersion parseVersion(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dot = trimmed.indexOf('.');
        try {
            if (dot < 0) {
                int major = Integer.parseInt(trimmed);
                return new ConfigVersion(major, 0);
            }
            int major = Integer.parseInt(trimmed.substring(0, dot));
            int minor = Integer.parseInt(trimmed.substring(dot + 1));
            return new ConfigVersion(major, minor);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}