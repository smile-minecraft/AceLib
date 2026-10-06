package com.smile.acelib.config;

import java.util.Objects;

/**
 * 設定綁定例外（extends {@link ConfigException}）。
 *
 * <p>{@link ConfigBinder} 把快照綁定到 record／一般類別時，
 * 型別不合、數值超出範圍、列舉值非法或必填缺失一律拋本例外，
 * 攜帶 {@code ACELIB-CFG-007}，訊息內含完整欄位路徑。</p>
 *
 * @since 1.4.0
 */
public class ConfigBindingException extends ConfigException {

    private final String path;

    /**
     * 主要建構子。
     *
     * @param path    出錯的完整欄位路徑（例如 {@code "server.port"}）；不可為 null
     * @param reason 人可讀的原因；不可為 null
     */
    public ConfigBindingException(String path, String reason) {
        super("ACELIB-CFG-007",
            "設定綁定失敗（" + Objects.requireNonNull(path, "path") + "）：" + reason);
        this.path = path;
    }

    /**
     * 取得出錯的完整欄位路徑。
     *
     * @return 點分隔路徑
     */
    public String getPath() {
        return path;
    }
}
