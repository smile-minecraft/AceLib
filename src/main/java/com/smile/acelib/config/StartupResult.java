package com.smile.acelib.config;

import java.util.Objects;

/**
 * 設定啟動結果（immutable record）。
 *
 * <p>{@link ConfigManager#startup()} 把「檔案不存在就建預設檔」拆成四類，
 * 識別依據是安裝狀態 sidecar（只在驗證成功後寫入），不是設定檔是否存在：</p>
 * <ul>
 *   <li>{@link Status#FRESH_INSTALL}：從未成功載入過，剛生成預設檔</li>
 *   <li>{@link Status#LOADED}：磁碟設定驗證通過並生效</li>
 *   <li>{@link Status#CORRUPT}：磁碟設定損壞（格式、版本、遷移任一失敗），
 *       原檔逐位元不動；快照為最後成功副本、呼叫端後備或 null</li>
 *   <li>{@link Status#MISSING_AFTER_USE}：曾經成功載入，之後檔案被刪；
 *       已重建預設檔讓伺服器繼續跑，快照為最後成功副本</li>
 * </ul>
 *
 * <p>設定損壞時禁止哪些操作，由下游依 {@link #status()} 與
 * {@link #snapshot()}（是否為 null）自行決定，AceLib 只保證：
 * 壞檔不覆寫、快照只在整份驗證通過後發布。</p>
 *
 * @param status   啟動分類；不可為 null
 * @param snapshot 生效的不可變快照；損壞且無副本又無後備時為 null
 * @param detail   人可讀的說明（含 {@code ACELIB-CFG-*} 代碼）；不可為 null
 * @since 1.4.0
 */
public record StartupResult(Status status, ConfigSnapshot snapshot, String detail) {

    /**
     * 啟動分類。
     */
    public enum Status {
        /** 首次安裝：從未成功載入，已生成預設檔。 */
        FRESH_INSTALL,
        /** 有效設定：磁碟設定驗證通過並生效。 */
        LOADED,
        /** 損壞設定：原檔未動，快照為副本／後備或 null。 */
        CORRUPT,
        /** 使用後缺檔：曾成功載入、之後檔案被刪，已重建預設檔。 */
        MISSING_AFTER_USE
    }

    /**
     * Compact constructor：不可空欄位檢查。
     */
    public StartupResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * 是否有可用快照（下游可據此決定是否禁用操作）。
     *
     * @return 快照非 null 回傳 true
     */
    public boolean isUsable() {
        return snapshot != null;
    }
}
