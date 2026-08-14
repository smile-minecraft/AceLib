/**
 * 訊息服務（Supported）。
 *
 * <p>封裝「讀 {@link com.smile.acelib.config.LangManager} 模板 → 套用
 * prefix / 變數替換 → 送到目標媒介（chat / action bar / title-subtitle /
 * 廣播 / console）」的完整流程，讓後續插件不需要各自重複 i18n 與顯示形式
 * 分流邏輯。</p>
 *
 * <h2>取得方式</h2>
 * <p>以 {@link com.smile.acelib.config.LangManager} 建構
 * {@link com.smile.acelib.message.MessageService}（建議由
 * {@link com.smile.acelib.AceLibApi} 或 plugin 組裝鏈提供 instance），
 * 之後即可格式化與發送訊息。</p>
 *
 * <h2>主要型別</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.message.MessageService}（Supported）—
 *       格式化（{@code format} / {@code formatConsole}）與發送
 *       （{@code sendChat} / {@code sendActionBar} / {@code sendTitle} /
 *       {@code broadcast} / {@code sendConsole}）</li>
 * </ul>
 *
 * <h2>行為契約</h2>
 * <ul>
 *   <li>訊息 key 缺失 → 回傳空字串 + {@code ACELIB-MSG-001} warning，<strong>不</strong>中斷執行</li>
 *   <li>玩家 null 或離線 → no-op + 適當警告，<strong>不</strong>中斷執行</li>
 *   <li>Folia 環境下操作玩家訊息走 native API，並 try-catch
 *       {@link IllegalStateException} 模式（Folia 在 non-owned region 拋的
 *       標準例外）：捕獲時記錄 {@code ACELIB-MSG-002} warning，降級為 silent no-op</li>
 *   <li>格式錯誤 → 回傳空字串 + {@code ACELIB-MSG-003} warning</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>本類別為不可變狀態：所有欄位建構時設定且後續不變；內部呼叫 player /
 * server API 不修改自身狀態，符合多 region 並行安全。</p>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-MSG-001} — 訊息 key 缺失</li>
 *   <li>{@code ACELIB-MSG-002} — 在不安全上下文操作玩家訊息（Folia）</li>
 *   <li>{@code ACELIB-MSG-003} — 訊息格式錯誤或安全降級</li>
 * </ul>
 *
 * @since 1.0.0
 */
package com.smile.acelib.message;
