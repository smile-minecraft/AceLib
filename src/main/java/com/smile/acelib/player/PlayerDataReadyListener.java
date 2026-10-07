package com.smile.acelib.player;

import com.smile.acelib.data.Record;
import java.util.UUID;

/**
 * 玩家資料就緒時的回呼（SPI）。
 *
 * <p>當 {@link PlayerDataService} 完成某位玩家的資料載入、session 進入
 * {@link PlayerSessionState#READY} 之後呼叫一次。同一玩家的同一次載入只通知一次；
 * 載入失敗時不通知（失敗以 {@link PlayerDataService#onPlayerJoin} 的 future
 * 表達）。</p>
 *
 * <h2>為什麼不是 Bukkit Event</h2>
 * <p>資料是在 I/O executor 上就緒的，不在主執行緒也不在任何 region。若以 Bukkit
 * Event 發出，就會踩到兩件事：Paper 要求同步 Event 在主執行緒 dispatch，
 * Folia 要求在玩家所屬 region 的執行緒；兩者都不適用於 I/O 完成回呼。因此改以
 * 本 SPI 表達，執行緒語意由呼叫端清楚掌握。</p>
 *
 * <h2>執行緒</h2>
 * <p>回呼在 <strong>PlayerDataService 的 I/O executor 執行緒</strong>上被呼叫，
 * 不是主執行緒、也不是 region 執行緒。要操作玩家、實體或世界，必須再用
 * AceLib 的安全排程送回正確上下文。</p>
 *
 * <h2>例外</h2>
 * <p>回呼拋出的例外由服務攔截並記錄（{@code ACELIB-PLAYER-009}），
 * <strong>不影響資料載入結果，也不影響其他 listener</strong>。</p>
 *
 * @see PlayerDataService#addReadyListener(PlayerDataReadyListener)
 * @since 1.4.0
 */
@FunctionalInterface
public interface PlayerDataReadyListener {

    /**
     * 資料就緒回呼。
     *
     * @param uuid   玩家 UUID；不可為 null
     * @param record 該玩家當下的資料視圖；不可為 null，內容為載入結果
     */
    void onPlayerDataReady(UUID uuid, Record record);

    /**
     * listener 註冊 handle。
     *
     * <p>{@link #close()} 解除註冊後不再收到通知；重複呼叫為 no-op。</p>
     */
    interface Registration extends AutoCloseable {

        /**
         * 解除註冊。冪等；重複呼叫不丟例外。
         */
        @Override
        void close();

        /**
         * 是否已解除註冊。
         *
         * @return true 表示已解除
         */
        boolean isClosed();
    }
}