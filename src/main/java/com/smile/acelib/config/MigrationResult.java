package com.smile.acelib.config;

import java.util.List;
import java.util.Objects;

/**
 * 設定遷移結果（immutable record）。
 *
 * <p>當 {@link MigrationChain#migrateAll} 執行多個 {@link ConfigMigration}
 * 後回傳此結果，記錄：</p>
 * <ul>
 *   <li>{@link #from()} — 起始版本</li>
 *   <li>{@link #to()} — 目標版本</li>
 *   <li>{@link #success()} — 是否全部 migration 成功</li>
 *   <li>{@link #warnings()} — 警告訊息清單（含 migration 內部例外敘述、
 *       找不到對應 migration 的提示等）</li>
 * </ul>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>{@code from} / {@code to} 不可為 null</li>
 *   <li>{@code warnings} 不可為 null（空清單亦可）</li>
 * </ul>
 *
 * @param from     起始版本；不可為 null
 * @param to       目標版本；不可為 null
 * @param success  是否全部 migration 成功
 * @param warnings 警告訊息清單；不可為 null（可能為空）
 * @since 1.0.0
 */
public record MigrationResult(
    ConfigVersion from,
    ConfigVersion to,
    boolean success,
    List<String> warnings
) {

    /**
     * Compact constructor：不可空欄位檢查；warnings 不可變化。
     */
    public MigrationResult {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
    }

    /**
     * 建立成功結果（無警告）。
     */
    public static MigrationResult success(ConfigVersion from, ConfigVersion to) {
        return new MigrationResult(from, to, true, List.of());
    }

    /**
     * 建立成功結果（含警告）。
     */
    public static MigrationResult success(ConfigVersion from, ConfigVersion to, List<String> warnings) {
        return new MigrationResult(from, to, true, warnings);
    }

    /**
     * 建立失敗結果。
     *
     * @param from    起始版本
     * @param to      目標版本
     * @param warning 失敗原因（單行文字）
     */
    public static MigrationResult failure(ConfigVersion from, ConfigVersion to, String warning) {
        return new MigrationResult(from, to, false, List.of(warning));
    }

    /**
     * 建立失敗結果（多個警告）。
     */
    public static MigrationResult failure(ConfigVersion from, ConfigVersion to, List<String> warnings) {
        return new MigrationResult(from, to, false, warnings);
    }
}