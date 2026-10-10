package com.smile.acelib.config;

import java.io.File;

/**
 * 缺檔還原前攔截（函式介面）。
 *
 * <p>向 {@link ConfigManager#registerMissingFileHandler} 登記後，
 * 只在「使用後缺檔」（設定檔不存在、且安裝狀態 sidecar 證明曾經成功載入過）時，
 * 於還原最後成功副本之前依登記順序執行。規則讀不到設定內容——檔案已經不在了，
 * 只能依缺檔這件事本身決定：正常回傳即照現行流程還原（最後成功副本、
 * 副本無效時退回預設生成）；拋 {@link ConfigException} 即拒絕還原，
 * 錯誤碼與訊息由下游自行決定，AceLib 不指定。</p>
 *
 * <p>拒絕語意：不還原、不寫入目標檔、不產生預設檔、不發布新快照、
 * 不推進世代、不動最後成功副本；{@code load()} 原樣拋出、
 * {@code startup()} 回傳 {@code MISSING_AFTER_USE}
 *（快照取記憶體舊快照 → 呼叫端後備 → null，診斷帶該例外的錯誤碼與訊息）。
 * 未登記任何規則時行為與過去完全一致；首次安裝（從未成功載入過）
 * 不觸發這裡登記的規則。</p>
 *
 * <p>範例：缺檔時直接失敗，不靜默還原舊值。</p>
 * <pre>{@code
 * manager.registerMissingFileHandler(missing -> {
 *     throw new ConfigException("ACELIB-EXT-001",
 *         "設定檔遺失，拒絕以舊副本啟動：" + missing.getAbsolutePath());
 * });
 * }</pre>
 *
 * @since 1.5.0
 */
@FunctionalInterface
public interface ConfigMissingFileHandler {

    /**
     * 缺檔時決定是否還原。
     *
     * @param configFile 缺失的設定檔（已確認不存在）；永不為 null
     * @throws ConfigException 當拒絕還原（錯誤碼與訊息由下游決定，原樣傳播）
     */
    void onMissingFile(File configFile) throws ConfigException;
}
