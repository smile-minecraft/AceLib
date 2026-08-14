/**
 * 玩家狀態與會話（Supported）。
 *
 * <p>提供玩家 join / quit lifecycle 的協調：非同步載入 / 保存玩家資料、
 * session 生命週期管理、玩家冷卻與資料存取，並妥善處理 reload / disable
 * 與玩家離線情境。</p>
 *
 * <h2>取得方式</h2>
 * <p>由 {@link com.smile.acelib.AceLibApi} 或 plugin 組裝鏈提供
 * {@link com.smile.acelib.player.PlayerDataService}；建構時需已初始化的
 * {@link com.smile.acelib.data.DataStore} 與 I/O {@link java.util.concurrent.Executor}。
 * 玩家冷卻可獨立建立 {@link com.smile.acelib.player.PlayerCooldownService}。</p>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.player.PlayerDataService}（Supported）—
 *       玩家資料 / 會話服務（join / quit / markDirty / getData / shutdown）</li>
 *   <li>{@link com.smile.acelib.player.PlayerSession} /
 *       {@link com.smile.acelib.player.PlayerSessionRegistry} /
 *       {@link com.smile.acelib.player.PlayerSessionState}（Supported）—
 *       session 值型別、註冊表與生命週期狀態列舉</li>
 *   <li>{@link com.smile.acelib.player.PlayerCooldownService}（Supported）—
 *       玩家冷卻管理</li>
 *   <li>{@link com.smile.acelib.player.PlayerStateException}（Supported）—
 *       玩家狀態例外，攜帶 {@code ACELIB-PLAYER-*} 錯誤代碼</li>
 * </ul>
 *
 * <h2>資料格式與生命週期</h2>
 * <ul>
 *   <li>底層 {@link com.smile.acelib.data.DataStore} 使用
 *       {@code "players.<uuid>.<key>"} 路徑；資料變更需呼叫
 *       {@link com.smile.acelib.player.PlayerDataService#markDirty(java.util.UUID)}
 *       否則 quit 時不會保存</li>
 *   <li>join：{@code onPlayerJoin(UUID, String)} 同步建立 session（LOADING）
 *       並回傳 {@link java.util.concurrent.CompletableFuture}</li>
 *   <li>quit：{@code onPlayerQuit(UUID)} 保存資料並結束 session；
 *       玩家離線後資料不殘留於 registry</li>
 *   <li>關閉：{@code shutdown()} 封閉新工作、等待 in-flight、flush dirty、
 *       清除狀態；冪等</li>
 * </ul>
 *
 * <h2>執行緒模型</h2>
 * <p>呼叫端提供的 {@link java.util.concurrent.Executor} 用於 task queuing；
 * 實際 store I/O 由內部 per-store serial executor 執行，確保對
 * {@link com.smile.acelib.data.DataStore} 的存取永遠序列化。
 * {@code getData(UUID)} 回傳的 {@link com.smile.acelib.data.Record} 為
 * 執行緒安全包裝（與 service snapshot 共用 lock）。</p>
 *
 * <h2>錯誤代碼</h2>
 * <p>{@code ACELIB-PLAYER-001} ~ {@code ACELIB-PLAYER-008}，詳見
 * {@link com.smile.acelib.player.PlayerStateException} 與
 * {@link com.smile.acelib.player.PlayerDataService} 文件。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib.player;
