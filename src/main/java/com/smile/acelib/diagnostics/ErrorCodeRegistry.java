package com.smile.acelib.diagnostics;

import java.util.Map;
import java.util.Objects;

/**
 * 錯誤代碼 → 分類與說明的 registry。
 *
 * <p>提供兩種查詢：</p>
 * <ul>
 *   <li>{@link #categorize(String)} — 將 {@code ACELIB-<AREA>-<CODE>}
 *       抽出 {@code AREA} 並對應到 {@link ErrorCategory}；
 *       未知或 null 前綴一律回傳 {@link ErrorCategory#UNKNOWN}</li>
 *   <li>{@link #lookup(String)} — 查詢已知代碼的完整 metadata
 *       （{@link ErrorCodeInfo}）；未知代碼回傳 null（不丟例外）</li>
 * </ul>
 *
 * <h2>規範</h2>
 * <ul>
 *   <li>{@code ACELIB-* } 前綴為必要條件；缺少或大小寫不符一律視為
 *       {@link ErrorCategory#UNKNOWN}</li>
 *   <li>area 部分（{@code SCHED} / {@code CFG} 等）大小寫敏感；
 *       {@code acelib-sched-001} 不可被歸類為 SCHEDULER</li>
 *   <li>未登錄的 area（如 {@code NEWAREA}）也回傳 {@link ErrorCategory#UNKNOWN}，
 *       不丟例外</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>所有方法皆 stateless，可安全於多 region 並行環境下使用。</p>
 *
 * @see ErrorCategory
 * @see ErrorCodeInfo
 * @since 1.0.0
 */
public final class ErrorCodeRegistry {

    private ErrorCodeRegistry() {
        // utility class
    }

    /**
     * 預設已知代碼的 metadata 對照表。
     *
     * <p>僅列出已明確定義的代碼；未登錄的代碼回傳
     * {@link ErrorCategory#UNKNOWN} 與 lookup null。</p>
     */
    private static final Map<String, ErrorCodeInfo> KNOWN;

    static {
        Map<String, ErrorCodeInfo> m = new java.util.LinkedHashMap<>();
        // SCHED
        m.put("ACELIB-SCHED-001", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "任務內部拋出例外"));
        m.put("ACELIB-SCHED-002", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "目標玩家已離線"));
        m.put("ACELIB-SCHED-003", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "目標實體已失效"));
        m.put("ACELIB-SCHED-004", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "目標 chunk 尚未載入"));
        m.put("ACELIB-SCHED-005", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "當前平台不支援此排程模式"));
        m.put("ACELIB-SCHED-006", new ErrorCodeInfo(ErrorCategory.SCHEDULER,
            "插件已停用"));
        // CFG
        m.put("ACELIB-CFG-001", new ErrorCodeInfo(ErrorCategory.CONFIG,
            "設定檔不存在或無法生成"));
        m.put("ACELIB-CFG-002", new ErrorCodeInfo(ErrorCategory.CONFIG,
            "設定檔 YAML 格式錯誤"));
        m.put("ACELIB-CFG-003", new ErrorCodeInfo(ErrorCategory.CONFIG,
            "reload 失敗且無舊值可回退"));
        m.put("ACELIB-CFG-004", new ErrorCodeInfo(ErrorCategory.CONFIG,
            "設定檔版本遷移失敗"));
        m.put("ACELIB-CFG-005", new ErrorCodeInfo(ErrorCategory.CONFIG,
            "必填欄位缺失"));
        // LANG
        m.put("ACELIB-LANG-001", new ErrorCodeInfo(ErrorCategory.LANGUAGE,
            "訊息 key 缺失"));
        m.put("ACELIB-LANG-002", new ErrorCodeInfo(ErrorCategory.LANGUAGE,
            "語言檔格式錯誤"));
        // PLAT
        m.put("ACELIB-PLAT-001", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "無法識別的伺服器實作"));
        m.put("ACELIB-PLAT-004", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "伺服器實作判定失敗"));
        m.put("ACELIB-PLAT-005", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "相容性探測：關鍵 class 不存在於 classpath"));
        m.put("ACELIB-PLAT-006", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "相容性探測：關鍵 method shape 不存在"));
        m.put("ACELIB-PLAT-007", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "相容性探測：class linkage 失敗"));
        m.put("ACELIB-PLAT-008", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "相容性探測：classloader 拒絕探測（SecurityException）"));
        m.put("ACELIB-PLAT-009", new ErrorCodeInfo(ErrorCategory.PLATFORM,
            "runtime 與內建已驗證矩陣不相容，plugin 拒絕啟用"));
        // CTX
        m.put("ACELIB-CTX-001", new ErrorCodeInfo(ErrorCategory.CONTEXT,
            "執行緒上下文不安全"));
        m.put("ACELIB-CTX-002", new ErrorCodeInfo(ErrorCategory.CONTEXT,
            "在非同步流程後直接操作玩家／實體"));
        // DBG
        m.put("ACELIB-DBG-001", new ErrorCodeInfo(ErrorCategory.DEBUG,
            "診斷模組自身錯誤"));
        // MSG
        m.put("ACELIB-MSG-001", new ErrorCodeInfo(ErrorCategory.MESSAGE,
            "訊息服務內部錯誤"));
        // CMD
        m.put("ACELIB-CMD-001", new ErrorCodeInfo(ErrorCategory.COMMAND,
            "指令執行錯誤"));
        // EVT
        m.put("ACELIB-EVT-001", new ErrorCodeInfo(ErrorCategory.EVENT,
            "事件監聽錯誤"));
        // 資料儲存區
        m.put("ACELIB-DATA-001", new ErrorCodeInfo(ErrorCategory.DATA,
            "資料儲存錯誤"));
        // EXT
        m.put("ACELIB-EXT-001", new ErrorCodeInfo(ErrorCategory.EXTERNAL,
            "外部整合錯誤"));
        // BED
        m.put("ACELIB-BED-001", new ErrorCodeInfo(ErrorCategory.BEDROCK,
            "基岩服務尚未啟用"));
        m.put("ACELIB-BED-002", new ErrorCodeInfo(ErrorCategory.BEDROCK,
            "基岩服務已停用"));
        m.put("ACELIB-BED-003", new ErrorCodeInfo(ErrorCategory.BEDROCK,
            "基岩查詢輸入為 null 或語意不合法"));
        // FORM（表單服務；常數表見 com.smile.acelib.form.FormErrorCodes）
        m.put("ACELIB-FORM-001", new ErrorCodeInfo(ErrorCategory.FORM,
            "表單服務尚未啟用"));
        m.put("ACELIB-FORM-002", new ErrorCodeInfo(ErrorCategory.FORM,
            "表單服務已停用"));
        KNOWN = Map.copyOf(m);
    }

    /**
     * 將 {@code ACELIB-<AREA>-<CODE>} 抽出 {@code AREA} 並對應到 {@link ErrorCategory}。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>格式不是 {@code ACELIB-<AREA>-<CODE>}（缺少前綴、缺少 dash、
     *       空字串）→ {@link ErrorCategory#UNKNOWN}</li>
     *   <li>area 不在 {@link ErrorCategory} 對照表內 → {@link ErrorCategory#UNKNOWN}</li>
     *   <li>area 大小寫不符（如 {@code acelib-sched-001}）→ {@link ErrorCategory#UNKNOWN}</li>
     *   <li>null 輸入 → {@link NullPointerException}（避免吞錯）</li>
     * </ul>
     *
     * @param code 錯誤代碼；不可為 null
     * @return 對應的 {@link ErrorCategory}；永遠不為 null
     * @throws NullPointerException 當 {@code code} 為 null
     */
    public static ErrorCategory categorize(String code) {
        Objects.requireNonNull(code, "code");
        if (code.isEmpty()) {
            return ErrorCategory.UNKNOWN;
        }
        // 必須以 "ACELIB-" 開頭（大小寫敏感）
        if (!code.startsWith("ACELIB-")) {
            return ErrorCategory.UNKNOWN;
        }
        // 抽出 <AREA>
        int firstDash = code.indexOf('-', "ACELIB-".length());
        if (firstDash <= 0) {
            return ErrorCategory.UNKNOWN;
        }
        String area = code.substring("ACELIB-".length(), firstDash);
        for (ErrorCategory c : ErrorCategory.values()) {
            if (c.areaPrefix().equals(area)) {
                return c;
            }
        }
        return ErrorCategory.UNKNOWN;
    }

    /**
     * 查詢已知代碼的 {@link ErrorCodeInfo}。
     *
     * <p>已知代碼（存在於預設對照表內）回傳完整 metadata；
     * 未知代碼（包含空字串、無前綴、未知 area）回傳 null，<strong>不丟例外</strong>。</p>
     *
     * @param code 錯誤代碼；不可為 null
     * @return 對應的 {@link ErrorCodeInfo}；未知代碼回傳 null
     * @throws NullPointerException 當 {@code code} 為 null
     */
    public static ErrorCodeInfo lookup(String code) {
        Objects.requireNonNull(code, "code");
        return KNOWN.get(code);
    }
}
