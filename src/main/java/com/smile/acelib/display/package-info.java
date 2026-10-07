/**
 * 每玩家顯示工具（Supported API）。
 *
 * <p>提供每位玩家的計分板、BossBar 與全息字（native TextDisplay）
 * 的公開取得、更新、安全排程與清理。所有玩家／實體操作都會回到
 * 正確的擁有者上下文；生命週期清理只觸及自身追蹤的資源，
 * 不影響其他 plugin 的顯示。</p>
 *
 * <p>進入點為 {@link com.smile.acelib.display.DisplayService}；
 * Bukkit 觸碰點收斂於 {@link com.smile.acelib.display.DisplayPlatform}（SPI）。</p>
 */
package com.smile.acelib.display;
