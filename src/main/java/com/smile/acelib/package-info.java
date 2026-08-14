/**
 * AceLib 核心套件：Folia-first 基礎函式庫插件的對外入口與生命週期。
 *
 * <h2>套件內容</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.AceLibApi} — 對外 API facade（Supported）；不可變的
 *       instance 彙整版本、平台、capability 與各 service facade</li>
 *   <li>{@link com.smile.acelib.AceLibApi.AceLibProvider} — 正式取得入口
 *       （Supported）；由 Bukkit/Paper {@code ServicesManager} 註冊</li>
 *   <li>{@link com.smile.acelib.AceLibPlugin} — plugin 主類別（Internal）；由
 *       Bukkit/Paper/Folia 伺服器直接載入，下游不得直接依賴</li>
 *   <li>{@link com.smile.acelib.AceLibVersion} — 版本常數（Supported）</li>
 * </ul>
 *
 * <h2>取得方式</h2>
 * <p>下游插件應透過 {@code ServicesManager} 取得
 * {@link com.smile.acelib.AceLibApi.AceLibProvider}，再呼叫
 * {@code provider.api()} 取得目前 {@link com.smile.acelib.AceLibApi}；不要直接
 * 依賴 {@link com.smile.acelib.AceLibPlugin} 或 static singleton。</p>
 *
 * <h2>執行緒與生命週期</h2>
 * <ul>
 *   <li>{@link com.smile.acelib.AceLibApi} 本身不可變；{@code api()} 可在任何
 *       thread 安全呼叫。Paper 與 Folia 環境行為一致。</li>
 *   <li>plugin enable 後 provider 註冊、disable 時解除註冊；reload 不解除註冊，
 *       {@code api()} 反映 reload 後的最新 facade。</li>
 *   <li>{@code api()} 永不回傳 null；但回傳物件的 {@code isReady()} 在
 *       disable 後為 false（shutdown facade），呼叫端使用前必須檢查。</li>
 * </ul>
 *
 * <h2>相容性承諾</h2>
 * <p>本套件為 v1 對外契約的一部分：public 型別（{@link com.smile.acelib.AceLibApi}、
 * {@link com.smile.acelib.AceLibVersion}、{@link com.smile.acelib.AceLibApi.AceLibProvider}）
 * 的簽章與語意在 v1 穩定版本內不破壞性變更；{@link com.smile.acelib.AceLibPlugin} 為
 * Internal，不提供相容性承諾。</p>
 *
 * @since 1.0.0
 */
package com.smile.acelib;
