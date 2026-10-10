package com.smile.acelib.gui;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/**
 * 作用域橋接（Internal）。
 *
 * <p>同套件內 {@link GuiScope} 操作共用 session 登記的特權入口：
 * 帶擁有者標記的開啟／取代、擁有者檢查下的關閉、輸入提示與送出、
 * 玩家退服清理。第三方 {@link GuiService} 實作不會實作本介面 —
 * 作用域在該情況下以 {@code OPERATION_FAILED} 拒絕，不靜默降級。</p>
 *
 * <p>本介面為 package-private，不屬公開 API，不進入
 * {@code api-surface} 分類與簽章基線。</p>
 *
 * @since 1.4.0
 */
interface ScopedGuiOperations {

    /**
     * 帶擁有者標記開啟 session。
     *
     * @param owner 擁有者標記（plugin 名）；不可為 null
     * @param playerUuid 目標玩家；不可為 null
     * @param title 視圖標題；不可為 null
     * @param kind inventory 種類；不可為 null
     * @param size 總格數（CHEST 用；ANVIL 忽略）
     * @param protectedSlots 受保護集合（作用域視圖傳全部格＝服務層預設全擋；
     *     legacy 路徑傳呼叫端集合）；可為 null（視為空集合）
     * @param replaceExisting 已有 session 時取代（作用域導航）或拒絕
     *     （legacy {@code SESSION_EXISTS} 語意）
     * @return 開啟結果＋被取代的舊 session（無取代時為 null）
     */
    OwnedOpenOutcome openOwned(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting);

    /**
     * 帶擁有者標記開啟 session（含按鈕物品）。
     *
     * <p>與 {@link #openOwned(String, UUID, String, GuiView.Kind, int, Set, boolean)}
     * 相同，另攜帶按鈕物品表（欄位 → 物品快照）：開啟時於玩家 region context 內
     * 放入與按鈕相同的欄位；單一欄位放置失敗只記錄，不影響開啟結果與點擊語意。
     * 表可為 null（視為無物品）。未覆寫本方法的實作等同無物品
     * （預設委派給 {@link #openOwned(String, UUID, String, GuiView.Kind, int, Set, boolean)}）。</p>
     *
     * @param buttonIcons 按鈕物品表；可為 null
     * @return 開啟結果＋被取代的舊 session（無取代時為 null）
     */
    default OwnedOpenOutcome openOwned(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting, Map<Integer, ItemStack> buttonIcons) {
        return openOwned(owner, playerUuid, title, kind, size, protectedSlots,
            replaceExisting);
    }

    /**
     * 擁有者檢查下關閉 session（擁有者不符回 {@code NOT_OWNER}）。
     */
    GuiResult closeOwned(String owner, UUID playerUuid, long generation);

    /**
     * 註冊無視窗表單 session（基岩原生表單分支用：需要 generation 錨點、
     * 退服／關閉清理與擁有者歸屬，但不開啟 inventory 視窗、不綁定 link）。
     *
     * <p>取代語意：已有 session 時結束舊的（含票券／序號／輸入失效）再建新的。</p>
     */
    OwnedOpenOutcome openFormSessionOwned(String owner, UUID playerUuid, String title);

    /**
     * 讀取目前 session（不驗證擁有者；作用域自行比對）。
     *
     * @return 目前 session；無 session 時為 null
     */
    GuiSession currentSession(UUID playerUuid);

    /**
     * 建立輸入提示（CHAT 只登記；ANVIL 同時開啟鐵砧視圖，可能取代舊 session）。
     *
     * @return 提示結果＋票券（失敗時票券為 null）＋被取代的舊 session
     */
    InputPromptOutcome promptInputOwned(String owner, UUID playerUuid, long generation,
            GuiInputPrompt prompt, Consumer<GuiInputResult> consumer);

    /**
     * 以票券送出輸入文字（擁有者不符回 {@code NOT_OWNER}）。
     */
    GuiResult submitInputOwned(String owner, UUID token, String text);

    /**
     * 路由玩家聊天訊息到其目前 session 擁有者的待處理聊天提示。
     *
     * <p>成功（SUCCESS／ACCEPTED）表示訊息已被某個提示消耗，
     * 呼叫端（聊天 listener）應取消事件；其餘狀態表示無命中，放行。</p>
     */
    GuiResult routeChatInput(UUID playerUuid, String text);

    /**
     * 玩家退服清理：結束 session、失效票券與輸入、通知各作用域丟棄該玩家狀態。
     */
    void handleQuit(UUID playerUuid);
}
