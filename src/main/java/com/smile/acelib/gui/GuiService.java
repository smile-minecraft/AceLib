package com.smile.acelib.gui;

import com.smile.acelib.scheduler.SafeScheduler;
import java.util.UUID;
import org.bukkit.event.Listener;

/**
 * GUI 服務對外 facade（Supported API）。
 *
 * <p>提供一組 Folia-safe 的 GUI 操作入口，後續插件不需要直接接觸
 * {@code Bukkit.createInventory} / {@code InventoryClickEvent} / 自行保存
 * session state 等容易跨執行緒 / 跨關閉事件丟失狀態的 API，
 * 改透過本介面取得統一、可重用 generation 的 session 物件與點擊驗證。</p>
 *
 * <h2>設計原則</h2>
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
 * @since 1.0.0
 */
public interface GuiService {

    /**
     * Production factory：透過 {@link SafeScheduler} 把 inventory mutation 派送到
     * 玩家 region context（Folia entity scheduler、Paper main thread），建立並回傳
     * 對外 {@link GuiService} facade。
     *
     * <p>實作類別 {@code GuiServiceImpl} 為 package-private（不暴露為 public API）；
     * 本方法為下游插件與內部 wiring 取得實作實例的唯一 public 入口，回傳型別為
     * 介面本身，隱藏內部實作。</p>
     *
     * @param scheduler 對應平台 SafeScheduler；不可為 null
     * @return 新的 {@link GuiService} 實作實例；never null
     * @throws NullPointerException 當 {@code scheduler} 為 null
     * @since 1.0.0
     */
    static GuiService forProduction(SafeScheduler scheduler) {
        return GuiServiceImpl.forProduction(scheduler);
    }

    /**
     * Unavailable factory：建立未啟用 / 已停用狀態下的可診斷 facade。
     *
     * <p>實作類別 {@code GuiServiceUnavailableImpl} 為 package-private（不暴露為 public API）；
     * 本方法為內部 wiring 與下游插件取得 unavailable 實例的唯一 public 入口，回傳型別為
     * 介面本身，隱藏內部實作。{@code code} 必須為 {@link GuiErrorCode#NOT_READY} 或
     * {@link GuiErrorCode#SHUTDOWN}，否則丟 {@link IllegalArgumentException}（不吞錯）。</p>
     *
     * @param code 狀態碼；不可為 null，且必須為 NOT_READY 或 SHUTDOWN
     * @return 新的 {@link GuiService} unavailable 實作實例；never null
     * @throws IllegalArgumentException 當 {@code code} 為 null 或不是 NOT_READY / SHUTDOWN
     * @since 1.0.0
     */
    static GuiService forUnavailable(String code) {
        return new GuiServiceUnavailableImpl(code);
    }

    /**
     * 取得內部 Bukkit listener reference（plugin lifecycle seam）。
     *
     * <p>由 {@code AceLibPlugin} 持有，以便在 onDisable / reload 時透過
     * {@link org.bukkit.event.HandlerList#unregisterAll(Listener)} 解除註冊；
     * 回傳型別為 Bukkit {@link Listener} 介面，不暴露內部 listener 實作類別。
     * 未啟用 / 已停用的 facade 回傳 {@code null}（無 listener 可註冊）。</p>
     *
     * @return listener reference；unavailable facade 回傳 null
     */
    default Listener getListener() {
        return null;
    }


    /**
     * null-input 預檢（default method 共用）：null 必須丟 {@link IllegalArgumentException}
     * 並攜帶 {@link GuiErrorCode#INVALID_INPUT}，與各實作契約一致；不吞錯。
     */
    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

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
     * 為指定玩家建立一個待確認 action，綁定 session generation 與唯一 action token。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#rejected} + {@code NOT_READY / SHUTDOWN}</li>
     *   <li>該玩家沒有 active session → {@link GuiResult#rejected} + {@code SESSION_NOT_FOUND}</li>
     *   <li>傳入的 generation 與持有 session 不符 →
     *       {@link GuiResult#rejected} + {@code GENERATION_MISMATCH}</li>
     *   <li>成功 → {@link GuiResult#success} + 對應 {@link GuiConfirmation}
     *       （含不透明 {@code actionToken}）</li>
     * </ul>
     *
     * <p>回傳的 {@link GuiConfirmation} 必須由呼叫端保存，並將其 {@code actionToken}
     * 綁定回 {@link #confirm} / {@link #cancel}。服務不保留 {@code Player} reference；
     * {@code callback} 為 domain action，由服務在 confirm 成功時一次性執行。</p>
     *
     * <h4>callback 執行上下文（Folia / Paper 安全）</h4>
     * <p>{@code confirm} 會<strong>同步</strong>執行 {@code callback}（目前實作於呼叫端
     * 執行緒內直接呼叫）。因此 {@code callback} 不得直接操作 Bukkit / Folia 綁定的
     * 狀態（例如直接 mutate 玩家 inventory、實體、方塊或呼叫全域
     * {@code Bukkit.getScheduler()}），否則在 Folia 環境下會觸發跨 region 違規
     * （{@code ACELIB-CTX-001}）。若 callback 需要更新 GUI / inventory，必須改呼叫
     * 本服務提供的安全 API（例如 {@link #applyAsyncUpdate} 或透過
     * {@code PlayerContextExecutor} 派送到玩家 region），由服務保證在合法 context 內執行。</p>
     *
     * @param playerUuid 玩家 UUID；不可為 null
     * @param generation 對應 session 的 generation
     * @param actionId   語意識別（例如 "delete-item-42"）；不可為 null
     * @param callback   confirm 成功時執行的 domain action；不可為 null
     * @return 對應 {@link GuiResult}（SUCCESS 帶 {@link GuiConfirmation}）
     * @implSpec 本方法為 default method：未 override 的既有實作會得到
     *           {@code FAILED + ACELIB-GUI-012} 的安全拒絕，不執行 callback，
     *           以維持 binary/source compatibility。
     */
    default GuiResult createConfirmation(UUID playerUuid, long generation,
                                         String actionId, Runnable callback) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionId, "actionId");
        requireNonNull(callback, "callback");
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "GuiService implementation does not override createConfirmation; "
                + "the confirmation flow is not supported by this implementation");
    }

    /**
     * 確認並執行 action 的 domain callback（一次性）。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#rejected} + {@code SHUTDOWN}</li>
     *   <li>action token 不存在、已過期（session 關閉 / shutdown）或與玩家不符 →
     *       {@link GuiResult#rejected} + {@code UNKNOWN_ACTION}</li>
     *   <li>generation 與 action 綁定不符 →
     *       {@link GuiResult#rejected} + {@code GENERATION_MISMATCH}</li>
     *   <li>action 已 confirm / cancel（重複點擊）→
     *       {@link GuiResult#rejected} + {@code ACTION_ALREADY_RESOLVED}，callback 不重複執行</li>
     *   <li>callback 拋例外 → {@link GuiResult#failed} + {@code OPERATION_FAILED}（不吞錯，
     *       可追蹤）；action 仍視為已解決</li>
     *   <li>成功 → {@link GuiResult#success}，callback 恰好執行一次</li>
     * </ul>
     *
     * @param playerUuid  玩家 UUID；不可為 null
     * @param generation  對應 session 的 generation
     * @param actionToken 由 {@link #createConfirmation} 產生的不透明 token；不可為 null
     * @return 對應 {@link GuiResult}
     * @implSpec 本方法為 default method：未 override 的既有實作會得到
     *           {@code FAILED + ACELIB-GUI-012} 的安全拒絕，以維持
     *           binary/source compatibility。
     */
    default GuiResult confirm(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "GuiService implementation does not override confirm; "
                + "the confirmation flow is not supported by this implementation");
    }

    /**
     * 取消 action（不執行 callback）。
     *
     * <p>規則與 {@link #confirm} 對稱；成功 → {@link GuiResult#success}，
     * action 一次性失效，callback 永不執行。重複 cancel 回
     * {@code ACTION_ALREADY_RESOLVED}。</p>
     *
     * @param playerUuid  玩家 UUID；不可為 null
     * @param generation  對應 session 的 generation
     * @param actionToken 由 {@link #createConfirmation} 產生的不透明 token；不可為 null
     * @return 對應 {@link GuiResult}
     * @implSpec 本方法為 default method：未 override 的既有實作會得到
     *           {@code FAILED + ACELIB-GUI-012} 的安全拒絕，以維持
     *           binary/source compatibility。
     */
    default GuiResult cancel(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "GuiService implementation does not override cancel; "
                + "the confirmation flow is not supported by this implementation");
    }

    /**
     * 取得當前模組狀態（{@code READY} / {@code FAILED} / {@code NOT_INITIALIZED}）。
     *
     * <p>用於診斷；不屬於穩定 public API。</p>
     */
    String getModuleStatus();

    /**
     * 建立一個非同步更新請求合約（非同步資料載入後安全更新 GUI）。
     *
     * <p>後續插件在發起非同步資料載入（資料庫查詢、網路請求、檔案讀寫等）之前呼叫本方法，
     * 取得一個 {@link GuiAsyncRequest}；非同步結果回來後，將其連同 {@link GuiPage} 與
     * renderer 傳入 {@link #applyAsyncUpdate(GuiAsyncRequest, GuiPage, Runnable)}。</p>
     *
     * <p>規則：</p>
     * <ul>
     *   <li>服務未啟用 / 已停用 → {@link GuiResult#rejected} + {@code SHUTDOWN}</li>
     *   <li>該玩家沒有 active session → {@link GuiResult#rejected} + {@code SESSION_NOT_FOUND}</li>
     *   <li>傳入的 generation 與持有 session 不符 →
     *       {@link GuiResult#rejected} + {@code GENERATION_MISMATCH}</li>
     *   <li>成功 → {@link GuiResult#success} + 對應 {@link GuiAsyncRequest}
     *       （含單調遞增的 {@code requestGeneration}）</li>
     * </ul>
     *
     * <p>每次呼叫都會讓該 session 的 request generation 單調遞增；因此同一 session 內
     * 後發的請求會取代先前的請求，舊請求套用時因序號不符被拒絕
     * （{@code ACELIB-GUI-016}），避免延遲回來的舊結果覆寫目前 GUI。</p>
     *
     * @param playerUuid        玩家 UUID；不可為 null
     * @param sessionGeneration 對應 session 的 generation
     * @param pageIndex         本請求所屬頁碼（0-based）；用於結果仍屬於目前頁面的重新驗證
     * @return 對應 {@link GuiResult}（SUCCESS 帶 {@link GuiAsyncRequest}）
     * @implSpec 本方法為 default method：未 override 的既有實作會得到
     *           {@code FAILED + ACELIB-GUI-012} 的安全拒絕，以維持
     *           binary/source compatibility。
     */
    default GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration,
                                       int pageIndex) {
        requireNonNull(playerUuid, "playerUuid");
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "GuiService implementation does not override beginAsyncUpdate; "
                + "async update is not supported by this implementation");
    }

    /**
     * 套用非同步更新結果到目前 GUI。
     *
     * <p>非同步結果回來後呼叫。本方法會在套用前重新驗證下列維度，
     * 任一不符即拒絕且不執行 {@code renderer}、不留下 pending state，並回對應
     * {@code ACELIB-GUI-*}：</p>
     * <ul>
     *   <li>服務仍 running（否則 {@code SHUTDOWN}）</li>
     *   <li>玩家仍有 active session 且 generation 相符（否則 {@code SESSION_NOT_FOUND} /
     *       {@code GENERATION_MISMATCH}）</li>
     *   <li>request generation 仍為目前有效值（否則 {@code STALE_REQUEST} /
     *       {@code ACELIB-GUI-016}，舊請求被取代）</li>
     *   <li>玩家仍在線（否則 {@code PLAYER_OFFLINE} / {@code ACELIB-GUI-017}）</li>
     *   <li>玩家當前開啟的 inventory 仍綁定本 session generation（否則
     *       {@code INVENTORY_MISMATCH} / {@code ACELIB-GUI-018}，避免覆寫新 inventory）</li>
     *   <li>player context executor 拒絕派送（否則 {@code SCHEDULER_REJECTED} /
     *       {@code ACELIB-GUI-013}）</li>
     * </ul>
     *
     * <p>所有檢查通過後，{@code renderer} 會在玩家 region context 內<strong>恰好執行一次</strong>，
     * 用於實際更新 inventory 內容（渲染 {@code page}）。{@code renderer} 拋例外不吞錯，
     * 回 {@code OPERATION_FAILED} 並附可追蹤訊息。</p>
     *
     * <p>{@code page} 的種類（CONTENT / EMPTY / LOADING / ERROR）由 {@code renderer} 決定如何
     * 呈現；本方法只保證「在安全 context 內、且僅當重新驗證通過時」執行 {@code renderer}。</p>
     *
     * <h4>回傳語意（同步 vs 延遲 executor）</h4>
     * <ul>
     *   <li>同步 executor（{@code PlayerContextExecutor#direct()} / 實際立即執行）：
     *       {@code renderer} 已執行，回 {@code SUCCESS}（已套用）或
     *       {@code REJECTED}/{@code FAILED}（被拒絕）。</li>
     *   <li>延遲 executor（production {@code SafeSchedulerPlayerContextExecutor} 僅 enqueue）：
     *       本方法回 {@code ACCEPTED} 表示「派送已接受、renderer 尚未執行」，
     *       <strong>不得視為 renderer 已完成</strong>。最終結果於 player region 內
     *       重新驗證後決定；失效時 renderer 不執行且不殘留 pending state。</li>
     * </ul>
     *
     * @param request  由 {@link #beginAsyncUpdate} 取得的請求合約；不可為 null
     * @param page     非同步結果頁面（可為 CONTENT / EMPTY / LOADING / ERROR）；不可為 null
     * @param renderer 在玩家 region context 內執行、實際更新 inventory 的 callback；不可為 null
     * @param <T>      頁面項目型別
     * @return 對應 {@link GuiResult}：同步 executor 回 {@code SUCCESS}（已套用）或
     *         {@code REJECTED}/{@code FAILED}（被拒絕）；延遲 executor 回
     *         {@code ACCEPTED}（派送已接受，renderer 尚未執行）
     * @implSpec 本方法為 default method：未 override 的既有實作會得到
     *           {@code FAILED + ACELIB-GUI-012} 的安全拒絕，不執行 renderer，
     *           以維持 binary/source compatibility。
     */
    default <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page,
                                           Runnable renderer) {
        requireNonNull(request, "request");
        requireNonNull(page, "page");
        requireNonNull(renderer, "renderer");
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "GuiService implementation does not override applyAsyncUpdate; "
                + "async update is not supported by this implementation");
    }

    /**
     * 取消所有 active session 並標記 stopped。測試 seam；正常 reload/disable
     * 不應直接呼叫。
     */
    void shutdown();
}
