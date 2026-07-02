package com.smile.acelib.config;

import java.util.Objects;

/**
 * 設定檔／語言檔例外（extends {@link RuntimeException}）。
 *
 * <p>對應 Plan §九 Phase 4 錯誤代碼：
 * <ul>
 *   <li>{@code ACELIB-CFG-001}：設定檔不存在且無法生成</li>
 *   <li>{@code ACELIB-CFG-002}：設定檔格式錯誤（YAML 解析失敗）</li>
 *   <li>{@code ACELIB-CFG-003}：設定檔載入失敗且無舊值可回退</li>
 *   <li>{@code ACELIB-CFG-004}：設定遷移失敗</li>
 *   <li>{@code ACELIB-CFG-005}：必填欄位缺失（不允許重載）</li>
 *   <li>{@code ACELIB-LANG-001}：訊息 key 缺失（記錄 warning，不中斷）</li>
 *   <li>{@code ACELIB-LANG-002}：語言檔格式錯誤</li>
 * </ul>
 *
 * <h2>使用約定</h2>
 * <ul>
 *   <li>所有對外拋出的例外必須攜帶 {@link #getCode() code}</li>
 *   <li>{@code ACELIB-LANG-001} 不應作為 throw，而是用於 log warning</li>
 * </ul>
 *
 * @since Phase 4 (Plan §九)
 */
public class ConfigException extends RuntimeException {

    private final String code;

    /**
     * 主要建構子（無 cause）。
     */
    public ConfigException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 完整建構子（含 cause）。
     */
    public ConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 取得錯誤分類代碼。
     *
     * @return {@code ACELIB-<AREA>-<CODE>} 格式字串
     */
    public String getCode() {
        return code;
    }
}