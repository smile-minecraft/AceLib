package com.smile.acelib.display;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 每玩家顯示服務對外 facade（Supported）。
 *
 * <p>把三種 per-player 顯示的排程與清理包起來：計分板（每人一份
 * {@code getNewScoreboard}＋{@code setScoreboard}）、BossBar（每人專屬一條
 * {@code addPlayer}／{@code removePlayer}）、全息字（native TextDisplay，
 * 位置型，不做跟隨玩家變體）。後續插件不需要直接處理 Folia 的
 * region／entity scheduler 選擇與有效性檢查。</p>
 *
 * <h2>排程歸屬</h2>
 * <ul>
 *   <li>玩家操作（計分板、BossBar、可見性）一律走該玩家的 scheduler</li>
 *   <li>全息字生成走位置所在 region 的 scheduler（placement 屬擁有 region）</li>
 *   <li>全息字後續操作（文字更新、移除）一律走 {@code entity.getScheduler()}
 *       對應的實體上下文，不得用 region scheduler 操作實體</li>
 * </ul>
 *
 * <h2>擁有者模型與清理</h2>
 * <ul>
 *   <li>每個顯示物件綁定建立它的 service 實例（背後是擁有 plugin）；
 *       {@link #closePlayer(UUID)}／{@link #closeAll()} 只觸及自身追蹤的資源，
 *       不接管、不清除其他 plugin 的顯示</li>
 *   <li>全息字生成預設不可見於全服（{@code setVisibleByDefault(false)}）且
 *       不寫入 chunk（{@code setPersistent(false)}），只對明確指定的觀看者
 *       顯示；殘留實體預設不可見、不落盤</li>
 *   <li>對外輸入一律用 {@link UUID}／{@link Component}／{@link Location}，
 *       內部不長期保存 {@code Player} reference（全息字追蹤需持有實體
 *       reference 以便派送實體上下文，實體失效時立即清除追蹤）</li>
 * </ul>
 *
 * <h2>結果與去重</h2>
 * <p>所有操作回 {@link DisplayResult}，不因領域失敗丟例外。
 * null 參數一律丟 {@link IllegalArgumentException} 並攜帶
 * {@link DisplayErrorCode#INVALID_INPUT}（與 {@code GuiService} 預檢慣例一致）。
 * 內容與上次相同的更新回 {@code deduped} 成功，不實際派送。</p>
 *
 * @since 1.4.0
 */
public interface DisplayService {

    /** 計分板側欄行數上限（sidebar 最多顯示 15 行）。 */
    int MAX_SCOREBOARD_LINES = 15;

    /**
     * Production factory：以預設 Bukkit 後端建立實例。
     *
     * @param owner 擁有者 plugin；不可為 null
     * @param scheduler 對應平台 SafeScheduler；不可為 null
     * @return 新的 {@link DisplayService} 實作實例；never null
     */
    static DisplayService forProduction(JavaPlugin owner,
            com.smile.acelib.scheduler.SafeScheduler scheduler) {
        return new DisplayServiceImpl(owner, scheduler, new BukkitDisplayPlatform(owner));
    }

    /**
     * Unavailable factory：建立未啟用／已停用狀態下的可診斷 facade。
     *
     * @param code 狀態碼；必須為 {@link DisplayErrorCode#NOT_READY} 或
     *     {@link DisplayErrorCode#SHUTDOWN}，否則丟 {@link IllegalArgumentException}
     * @return 新的 unavailable 實作實例；never null
     */
    static DisplayService forUnavailable(String code) {
        return new DisplayServiceUnavailableImpl(code);
    }

    /**
     * null-input 預檢：null 必須丟 {@link IllegalArgumentException}
     * 並攜帶 {@link DisplayErrorCode#INVALID_INPUT}，與各實作契約一致；不吞錯。
     */
    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("[" + DisplayErrorCode.INVALID_INPUT
                + "] " + name + " must not be null");
        }
    }

    /**
     * 建立或更新指定玩家的計分板（派送到該玩家上下文執行）。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>玩家離線 → {@code REJECTED + ACELIB-DISP-004}</li>
     *   <li>行數超過 {@link #MAX_SCOREBOARD_LINES}、行內含 null → {@code REJECTED + ACELIB-DISP-003}</li>
     *   <li>空行表 → 清空分數（只留標題）</li>
     *   <li>內容與上次相同 → {@code deduped} 成功，不實際派送</li>
     * </ul>
     *
     * @param playerId 目標玩家；不可為 null
     * @param title 側欄標題；不可為 null
     * @param lines 側欄行（由上而下）；不可為 null、可為空
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult showScoreboard(UUID playerId, Component title, List<Component> lines);

    /**
     * 移除指定玩家的計分板（把他設回主計分板並清除追蹤）。
     *
     * <p>該玩家沒有被追蹤的計分板時回成功（無操作，具冪等性）。</p>
     *
     * @param playerId 目標玩家；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult hideScoreboard(UUID playerId);

    /**
     * 建立或更新指定玩家的專屬 BossBar（派送到該玩家上下文執行）。
     *
     * <p>進度必須在 {@code [0.0, 1.0]} 閉區間內（含端點），超出或為 NaN
     * 回 {@code REJECTED + ACELIB-DISP-003}（不做靜默 clamp）。
     * 已存在的 BossBar 只更新標題與進度，顏色與樣式維持建立時的值；
     * 共享一條 Bar 給多人的需求由呼叫端逐玩家建立。</p>
     *
     * @param playerId 目標玩家；不可為 null
     * @param title 標題；不可為 null
     * @param progress 進度；必須在 [0.0, 1.0] 內
     * @param color 顏色；不可為 null
     * @param style 樣式；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult showBossBar(UUID playerId, Component title, double progress,
        BarColor color, BarStyle style);

    /**
     * 更新指定玩家的 BossBar 標題與進度。
     *
     * <p>該玩家沒有被追蹤的 BossBar 時回 {@code REJECTED + ACELIB-DISP-003}。
     * 內容與上次相同 → {@code deduped} 成功。</p>
     *
     * @param playerId 目標玩家；不可為 null
     * @param title 新標題；不可為 null
     * @param progress 新進度；必須在 [0.0, 1.0] 內
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult updateBossBar(UUID playerId, Component title, double progress);

    /**
     * 移除指定玩家的 BossBar 並清除追蹤。
     *
     * <p>該玩家沒有被追蹤的 BossBar 時回成功（無操作，具冪等性）。</p>
     *
     * @param playerId 目標玩家；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult hideBossBar(UUID playerId);

    /**
     * 在指定位置生成一隻全息字（派送到該位置所在 region 執行）。
     *
     * <p>同步排程下回 {@code SUCCESS} 並攜帶全息字 id；
     * 非同步排程（真 Folia 下位置派送）會先登記待生成 id，再回 {@code ACCEPTED}。
     * 實體尚未生成時 {@link #findHologram(UUID)} 仍為空；此時更新會替換待生成文字，
     * 移除則回 {@code SUCCESS} 並取消生成。生成完成後可用 {@link #findHologram(UUID)} 查詢。
     * 位置無世界時回 {@code REJECTED + ACELIB-DISP-003}；
     * chunk 未載入時回 {@code REJECTED + ACELIB-DISP-006}。</p>
     *
     * @param location 生成位置；不可為 null
     * @param text 初始文字；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult showHologram(Location location, Component text);

    /**
     * 更新全息字文字（派送到該實體上下文執行）。
     *
     * <p>生成仍在途時，更新會回 {@code ACCEPTED} 並取代待生成文字；實體已失效時
     * 清除追蹤並回 {@code REJECTED + ACELIB-DISP-005}（不留孤兒追蹤）。
     * 未知 id 回 {@code REJECTED + ACELIB-DISP-003}。內容與上次相同 →
     * {@code deduped} 成功。</p>
     *
     * @param hologramId 全息字 id；不可為 null
     * @param text 新文字；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult updateHologram(UUID hologramId, Component text);

    /**
     * 設定全息字對指定觀看者的可見性（派送到該觀看者上下文執行）。
     *
     * <p>底層走 per-plugin 疊加的隱藏計數，不覆蓋其他 plugin 的可見性。
     * 觀看者離線 → {@code REJECTED + ACELIB-DISP-004}。</p>
     *
     * @param hologramId 全息字 id；不可為 null
     * @param viewerId 觀看者；不可為 null
     * @param visible 是否可見
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult setHologramVisible(UUID hologramId, UUID viewerId, boolean visible);

    /**
     * 移除全息字並清除追蹤（派送到該實體上下文執行）。
     *
     * <p>具冪等性：未知 id 回成功（無操作）；待生成 id 回 {@code SUCCESS} 並取消生成，
     * 不會留下稍後才建立的實體。實體已失效時清除追蹤並回
     * {@code REJECTED + ACELIB-DISP-005}。</p>
     *
     * @param hologramId 全息字 id；不可為 null
     * @return 對應 {@link DisplayResult}；never null
     */
    DisplayResult removeHologram(UUID hologramId);

    /**
     * 查詢追蹤中的全息字快照。
     *
     * @param hologramId 全息字 id；不可為 null
     * @return 快照；未知或已清除時為 empty
     */
    Optional<Hologram> findHologram(UUID hologramId);

    /**
     * 清除指定玩家的所有顯示（計分板設回主計分板、移除 BossBar、
     * 自全息字觀看者集合剔除），具冪等性。
     *
     * <p>退服清理與 {@link #handlePlayerQuit(UUID)} 共用此路徑。</p>
     *
     * @param playerId 目標玩家；不可為 null
     * @return 有清除到任何追蹤時為 true；無追蹤時為 false
     */
    boolean closePlayer(UUID playerId);

    /**
     * 玩家離線時的接線入口（供 AceLib 內部 quit listener 與測試呼叫）。
     *
     * <p>語意等同 {@link #closePlayer(UUID)}；永不拋例外，
     * null 直接返回（下游仍應在 quit 時主動清理，不要依賴此自動關閉）。</p>
     *
     * @param playerId 離線玩家；可為 null
     */
    void handlePlayerQuit(UUID playerId);

    /**
     * 清除本服務追蹤的全部顯示（具冪等性，只動自己的資源）。
     *
     * @return 本次清除的追蹤數量；無追蹤時為 0
     */
    int closeAll();
}
