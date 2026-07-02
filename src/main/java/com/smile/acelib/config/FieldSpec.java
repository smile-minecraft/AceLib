package com.smile.acelib.config;

import java.util.Objects;

/**
 * 設定欄位規格（immutable record）。
 *
 * <p>對應 Plan §九 Phase 4「缺失欄位可被補齊（不破壞既有值）」需求。
 * 描述 schema 中一個欄位的「路徑」、「預設值」、「是否必填」。
 * {@link ConfigManager} 在載入時會檢查每個欄位是否存在，
 * 若必填欄位缺失則補上 {@link #defaultValue()}。</p>
 *
 * <h2>範例</h2>
 * <pre>{@code
 * new FieldSpec("greeting", "hello", true);
 * new FieldSpec("maxPlayers", 10, false);
 * new FieldSpec("nested.path.value", "default", false);
 * }</pre>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>{@code path} 不可為 null 或空白</li>
 *   <li>{@code defaultValue} 不可為 null（避免補齊時出現歧義）</li>
 * </ul>
 *
 * @since Phase 4 (Plan §九)
 */
public record FieldSpec(String path, Object defaultValue, boolean required) {

    /**
     * Compact constructor：對不可空欄位做 null / blank 檢查。
     */
    public FieldSpec {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(defaultValue, "defaultValue");
    }
}