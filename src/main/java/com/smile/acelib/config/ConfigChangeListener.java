package com.smile.acelib.config;

/**
 * 設定檔監看回呼。
 *
 * <p>{@link ConfigManager#startWatching(ConfigChangeListener)} 註冊後，
 * 外部修改導致自動重載成功時呼叫 {@link #onReload}；
 * 新內容驗證失敗（舊快照保留）時呼叫 {@link #onInvalidReload} 並附錯誤碼，
 * 不做靜默略過。</p>
 *
 * <p>實作不得修改遊戲物件：回呼只做純資料處理或排到安全執行緒，
 * 耗時工作應自行轉交排程器。</p>
 *
 * @since 1.4.0
 */
public interface ConfigChangeListener {

    /**
     * 自動重載成功，新快照已發布。
     *
     * @param snapshot 新發布的不可變快照；永不為 null
     */
    void onReload(ConfigSnapshot snapshot);

    /**
     * 新內容驗證失敗，舊快照保留、原檔未動。
     *
     * @param code   {@code ACELIB-CFG-*} 錯誤代碼；永不為 null
     * @param detail 人可讀的原因（含檔名）；永不為 null
     */
    void onInvalidReload(String code, String detail);
}
