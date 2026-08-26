package com.smile.acelib.bedrock;

import java.util.UUID;

/**
 * 基岩版玩家資訊值型別（Supported）。
 *
 * <p>承載經 Geyser/Floodgate 連線的基岩版玩家之裝置 / 輸入 / 語言 / 連結資料。
 * 上游列舉常數以名稱鏡射；上游新增未知常數時映射為 {@code UNKNOWN}，不拋例外。</p>
 *
 * @param playerId       玩家 UUID；不可為 null
 * @param username       玩家名稱；可為 null（上游缺漏時）
 * @param deviceOs       裝置作業系統；未知上游值為 {@link DeviceOs#UNKNOWN}
 * @param inputMode      輸入模式；未知上游值為 {@link InputMode#UNKNOWN}
 * @param languageCode   語言代碼（例如 {@code "zh_TW"}）；上游缺漏時為空字串
 * @param linkState      Xbox 帳號連結狀態
 * @param linkedUsername 已連結的 Java 版帳號名稱；未連結時為 null
 * @since 1.0.0
 */
public record BedrockPlayerInfo(
    UUID playerId,
    String username,
    DeviceOs deviceOs,
    InputMode inputMode,
    String languageCode,
    LinkState linkState,
    String linkedUsername) {

    /** 基岩裝置作業系統（鏡射 Floodgate {@code DeviceOs} 名稱）。 */
    public enum DeviceOs {
        /** 無法識別或上游新增未知裝置。 */
        UNKNOWN,
        /** Android 裝置。 */
        GOOGLE,
        /** iOS 裝置。 */
        IOS,
        /** macOS 裝置。 */
        OSX,
        /** Amazon Fire 裝置。 */
        AMAZON,
        /** Gear VR 裝置。 */
        GEARVR,
        /** HoloLens 裝置。 */
        HOLOLENS,
        /** Windows UWP（Store 版）。 */
        UWP,
        /** Windows 32 位元。 */
        WIN32,
        /** 專用伺服器。 */
        DEDICATED,
        /** Apple TV。 */
        TVOS,
        /** PlayStation。 */
        PS4,
        /** Nintendo Switch。 */
        NX,
        /** Xbox。 */
        XBOX,
        /** Windows Phone。 */
        WINDOWS_PHONE
    }

    /** 基岩輸入模式（鏡射 Floodgate {@code InputMode} 名稱）。 */
    public enum InputMode {
        /** 無法識別或上游新增未知輸入模式。 */
        UNKNOWN,
        /** 鍵盤滑鼠。 */
        KEYBOARD_MOUSE,
        /** 觸控。 */
        TOUCH,
        /** 控制器。 */
        CONTROLLER,
        /** VR。 */
        VR
    }

    /** Xbox 帳號連結狀態（由 LinkedPlayer 存在與否推導）。 */
    public enum LinkState {
        /** 已連結 Java 版帳號。 */
        LINKED,
        /** 未連結。 */
        UNLINKED
    }
}
