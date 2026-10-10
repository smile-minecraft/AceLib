package com.smile.acelib.config;

/**
 * 跨欄位驗證規則（函式介面）。
 *
 * <p>向 {@link ConfigManager#registerCrossFieldValidator} 登記後，
 * 每次 {@code load}／{@code reload}／{@code startup}
 * 在候選快照通過既有驗證（schema 預設補齊、遷移、版本收斂）之後、
 * 發布新快照之前執行。規則讀候選快照的多個路徑，檢查它們之間的關係
 * （例如下限不得大於上限）；通過就直接回傳，失敗拋
 * {@link ConfigBindingException}（{@code ACELIB-CFG-007}），
 * 訊息內寫明失敗的規則與相關路徑。</p>
 *
 * <p>失敗語意與管線既有約定一致：不發布新快照、不推進世代、
 * 不改磁碟、不動最後成功副本；{@code load()} 原樣拋出、
 * {@code reload()} 回傳 {@code false} 並保留舊快照、
 * {@code startup()} 走損壞路徑（原檔不動，沿用最後成功副本）。</p>
 *
 * <h2>後備語意</h2>
 * <p>{@code startup()} 損壞時的後備快照（最後成功副本、呼叫端指定的
 * 保守後備、記憶體舊快照）<strong>不</strong>重新執行這裡登記的規則。
 * 後備是當時已驗證通過的狀態；對新規則重新驗證會把可恢復的啟動變成
 * 硬失敗，因此管線刻意跳過。後果是：後備快照的內容只保證「當時驗證通過」，
 * 不保證通過現行全部規則——不得對外宣稱所有回傳快照都通過現行規則。</p>
 *
 * <p>範例：</p>
 * <pre>{@code
 * manager.registerCrossFieldValidator(candidate -> {
 *     int min = ((Number) candidate.get("limits.min")).intValue();
 *     int max = ((Number) candidate.get("limits.max")).intValue();
 *     if (min > max) {
 *         throw new ConfigBindingException("limits",
 *             "規則 minNotGreaterThanMax 失敗：下限 " + min + " 大於上限 " + max);
 *     }
 * });
 * }</pre>
 *
 * @since 1.5.0
 */
@FunctionalInterface
public interface ConfigCrossFieldValidator {

    /**
     * 驗證候選快照。
     *
     * @param candidate 候選快照（驗證通過後才會發布的內容）；永不為 null
     * @throws ConfigBindingException 當跨欄位規則失敗（ACELIB-CFG-007，訊息帶規則名與路徑）
     */
    void validate(ConfigSnapshot candidate) throws ConfigBindingException;
}
