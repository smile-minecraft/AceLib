package com.smile.acelib.player;

import java.util.EnumSet;
import java.util.Set;

/**
 * 玩家 session 生命週期狀態。
 *
 * <p>session 必須有明確可觀察的狀態，使外部 caller 能區分
 * 「資料未就緒」與「資料已就緒」並選擇對應的處理策略
 * （等待、拒絕、降級訊息）。</p>
 *
 * <h2>狀態轉換圖</h2>
 * <pre>
 *     ┌─────────┐
 *     │ LOADING │  ─── data load 成功 ──▶ ┌───────┐
 *     └─────────┘                         │ READY │
 *         │                               └───────┘
 *         │ data load 失敗                   │
 *         ▼                                  │ onPlayerQuit
 *     ┌───────┐                              ▼
 *     │ ENDED │ ◀─── 任意路徑終止 ─── ┌────────────┐
 *     └───────┘                       │ UNLOADING │
 *                                     └────────────┘
 * </pre>
 *
 * <h2>合法轉換</h2>
 * <ul>
 *   <li>{@link #LOADING} → {@link #READY}（load 成功）</li>
 *   <li>{@link #LOADING} → {@link #ENDED}（load 失敗或 session 終止）</li>
 *   <li>{@link #READY} → {@link #UNLOADING}（onPlayerQuit 開始）</li>
 *   <li>{@link #READY} → {@link #ENDED}（強制終止，例如 disable）</li>
 *   <li>{@link #UNLOADING} → {@link #ENDED}（save 完成或失敗）</li>
 * </ul>
 *
 * @see PlayerSession
 * @since 1.0.0
 */
public enum PlayerSessionState {

    /** 剛登入，資料尚未載入完成。 */
    LOADING,

    /** 資料已載入完成，可正常讀寫。 */
    READY,

    /** 已收到 quit 信號，正在保存資料（仍未寫回磁碟）。 */
    UNLOADING,

    /** Session 已結束（保存完成或失敗），不再持有 session 物件。 */
    ENDED;

    private static final Set<PlayerSessionState> TERMINAL =
        EnumSet.of(ENDED);

    /**
     * 是否為終態（不可再轉換）。
     *
     * @return true 表示為終態
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 是否處於「資料已就緒、可操作」狀態。
     *
     * <p>只有 {@link #READY} 回傳 true；{@link #LOADING} 視為「尚未就緒」
     * （caller 可選擇等待或拒絕）；{@link #UNLOADING} 視為「正在卸載」
     * （不可再讀寫）；{@link #ENDED} 視為「已結束」（session 已移除）。</p>
     *
     * @return true 表示資料已就緒
     */
    public boolean isReady() {
        return this == READY;
    }

    /**
     * 判斷從當前狀態是否可轉換到目標狀態。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@link #ENDED} 不可轉換到任何狀態（終態）</li>
     *   <li>合法路徑見 class-level Javadoc「合法轉換」</li>
     * </ul>
     *
     * @param target 目標狀態；不可為 null
     * @return true 表示合法
     */
    public boolean canTransitionTo(PlayerSessionState target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return false;
        }
        if (this == ENDED) {
            return false;
        }
        // LOADING 合法路徑：READY, ENDED
        if (this == LOADING) {
            return target == READY || target == ENDED;
        }
        // READY 合法路徑：UNLOADING, ENDED
        if (this == READY) {
            return target == UNLOADING || target == ENDED;
        }
        // UNLOADING 合法路徑：ENDED
        if (this == UNLOADING) {
            return target == ENDED;
        }
        return false;
    }
}
