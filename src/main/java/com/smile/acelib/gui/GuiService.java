package com.smile.acelib.gui;

import java.util.UUID;

/**
 * GUI 服務對外 facade（Plan §十六 Phase 11 canonical public API）。
 *
 * <p>提供一組 Folia-safe 的 GUI 操作入口，後續插件不需要直接接觸
 * {@code Bukkit.createInventory} / {@code InventoryClickEvent} / 自行保存
 * session state 等容易跨執行緒 / 跨關閉事件丟失狀態的 API，
 * 改透過本介面取得統一、可重用 generation 的 session 物件與點擊驗證。</p>
 *
 * <h2>設計原則（Plan §十六 §二十一共同契約）</h2>
 * <ul>
 *   <li>對外輸入僅接受 {@link UUID} / {@link GuiArgument} —
 *       內部不長期保存 {@code Player} reference</li>
 *   <li>每次呼叫於執行前重新驗證 session 與 generation；失敗回對應
 *       {@code ACELIB-GUI-*} 結果，不丟例外給 caller</li>
 *   <li>Session 物件為不可變；同一 {@link UUID} 重新開啟時拿到更大 generation</li>
 *   <li>模組於未啟用（{@link com.smile.acelib.AceLibApi#uninitialized()}）或
 *       停用後呼叫一律回 {@code REJECTED + ACELIB-GUI-001 / 002}；既有的
 *       active session 在 shutdown 時會被清理</li>
 * </ul>
 *
 * @see GuiArgument
 * @see GuiResult
 * @see GuiSession
 * @since Phase 11 (Plan §十六 §二十一)
 */
public interface GuiService {

    /**
     * 為指定玩家開啟一個基本 inventory GUI。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#failed} + {@link GuiErrorCode#NOT_READY} /
     *       {@link GuiErrorCode#SHUTDOWN}</li>
     *   <li>該玩家已有 active session → {@link GuiResult#rejected} + {@link GuiErrorCode#SESSION_EXISTS}</li>
     *   <li>成功 → {@link GuiResult#success} + 對應 session</li>
     * </ul>
     *
     * <p>實作內部仍需透過既有 {@link com.smile.acelib.context.SafeExecutor}
     * 切換到玩家 region context 才能實際打開 inventory。
     * 本介面回傳 result；實際 {@code Bukkit} 派送由實作層安排。</p>
     *
     * @param argument 開啟請求參數；不可為 null
     * @return 對應 {@link GuiResult}；never null
     */
    GuiResult openInventory(GuiArgument argument);

    /**
     * 關閉指定玩家的當前 GUI session。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#failed} + {@code NOT_READY / SHUTDOWN}</li>
     *   <li>該玩家沒有 active session → {@link GuiResult#rejected} + {@link GuiErrorCode#SESSION_NOT_FOUND}</li>
     *   <li>傳入的 generation 與持有 session 不符 →
     *       {@link GuiResult#rejected} + {@link GuiErrorCode#GENERATION_MISMATCH}</li>
     *   <li>成功 → {@link GuiResult#success}（session 已被移除）</li>
     * </ul>
     *
     * @param playerUuid 玩家 UUID；不可為 null
     * @param generation 對應 session 的 generation
     * @return 對應 {@link GuiResult}
     */
    GuiResult closeInventory(UUID playerUuid, long generation);

    /**
     * 取得指定玩家的當前 active session（若存在）。
     *
     * @param playerUuid 玩家 UUID；不可為 null
     * @return 對應 {@link GuiResult}（SUCCESS 帶 session / REJECTED 帶原因）
     */
    GuiResult getActiveSession(UUID playerUuid);

    /**
     * 驗證玩家對某 slot 的點擊。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#failed} + {@code NOT_READY / SHUTDOWN}</li>
     *   <li>該玩家沒有 active session → {@link GuiResult#rejected} + {@link GuiErrorCode#SESSION_NOT_FOUND}</li>
     *   <li>傳入的 generation 與持有 session 不符 →
     *       {@link GuiResult#rejected} + {@link GuiErrorCode#GENERATION_MISMATCH}</li>
     *   <li>slot 越界（負數或 &gt;= session.size）→
     *       {@link GuiResult#rejected} + {@link GuiErrorCode#INVALID_INPUT}</li>
     *   <li>slot 受保護 → {@link GuiResult#rejected} + {@link GuiErrorCode#SLOT_PROTECTED}</li>
     *   <li>slot 未受保護 → {@link GuiResult#allowed}（實際遊戲邏輯可繼續）</li>
     * </ul>
     *
     * <p>本方法為服務層契約；實際 Bukkit {@code InventoryClickEvent} 觸發時
     * 由 listener 內部呼叫，藉此統一驗證邏輯。</p>
     *
     * @param playerUuid 玩家 UUID；不可為 null
     * @param generation 對應 session 的 generation
     * @param slot       點擊 slot 編號
     * @return 對應 {@link GuiResult}
     */
    GuiResult validateClick(UUID playerUuid, long generation, int slot);

    /**
     * 取得當前模組狀態（{@code READY} / {@code FAILED} / {@code NOT_INITIALIZED}）。
     *
     * <p>用於診斷；不屬於穩定 public API。</p>
     */
    String getModuleStatus();

    /**
     * 取消所有 active session 並標記 stopped。測試 seam；正常 reload/disable
     * 不應直接呼叫。
     */
    void shutdown();
}
