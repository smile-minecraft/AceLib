package com.smile.acelib.config;

import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 設定檔 schema（immutable record）。
 *
 * <p>一個 {@link ConfigSchema} 描述設定檔的「目標版本」與「欄位清單」，
 * 用於 {@link ConfigManager} 載入流程中：</p>
 * <ul>
 *   <li>生成預設檔時依 schema 的 {@link FieldSpec} 填入 {@code defaultValue}</li>
 *   <li>載入既有檔案時補齊缺失欄位</li>
 *   <li>驗證必填欄位是否存在（{@link ConfigManager#validate}）</li>
 * </ul>
 *
 * <h2>範例</h2>
 * <pre>{@code
 * ConfigSchema schema = new ConfigSchema(
 *     new ConfigVersion(1, 0),
 *     List.of(
 *         new FieldSpec("greeting", "hello", true),
 *         new FieldSpec("maxPlayers", 10, false)
 *     )
 * );
 * }</pre>
 *
 * @param version schema 宣告的目標版本；不可為 null
 * @param fields  欄位規格清單；不可為 null，內容會以不可變清單保存
 * @since 1.0.0
 */
public record ConfigSchema(ConfigVersion version, List<FieldSpec> fields) {

    /**
     * Compact constructor：不可空欄位檢查 + 不可變化。
     */
    public ConfigSchema {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
    }

    /**
     * 驗證 {@code config} 是否包含所有必填欄位（{@code required=true}）。
     *
     * <p>只檢查 {@link FieldSpec#required()} 為 {@code true} 的欄位；
     * 非必填欄位即使缺失也不視為錯誤。</p>
     *
     * @param config 欲驗證的設定檔
     * @return 缺失的必填欄位路徑清單；若全數存在則回傳空 list
     */
    public List<String> validate(YamlConfiguration config) {
        Objects.requireNonNull(config, "config");
        List<String> missing = new java.util.ArrayList<>();
        for (FieldSpec field : fields) {
            if (field.required() && !config.contains(field.path())) {
                missing.add(field.path());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 取得所有 {@code required=true} 的欄位清單。
     *
     * @return 不可變的必填欄位清單
     */
    public List<FieldSpec> requiredFields() {
        return fields.stream()
            .filter(FieldSpec::required)
            .toList();
    }
}