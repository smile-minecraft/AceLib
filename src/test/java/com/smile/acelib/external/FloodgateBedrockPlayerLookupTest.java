package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.DeviceOs;
import org.geysermc.floodgate.util.InputMode;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FloodgateBedrockPlayerLookup} typed 映射測試。
 *
 * <p>以 {@link Proxy} 製作 FloodgateApi / FloodgatePlayer 的 fake（而非 Mockito）：
 * 這兩個介面的部分方法簽章引用上游 SNAPSHOT 傳遞依賴（geyser events），Mockito
 * inline mock 需完整解析所有簽章而失敗；Proxy 只做名稱分派，不受缺失型別影響。</p>
 *
 * <p>驗證：isBedrockPlayer 委派、裝置/輸入/語言/連結欄位映射、未知上游列舉值 →
 * UNKNOWN 不拋例外，以及 null / 缺漏欄位的防禦行為。</p>
 */
@DisplayName("FloodgateBedrockPlayerLookup typed 映射")
class FloodgateBedrockPlayerLookupTest {

    private FloodgateApi api;

    private FloodgateBedrockPlayerLookup lookup(FloodgatePlayer player) {
        api = fakeApi(player);
        return new FloodgateBedrockPlayerLookup(() -> api);
    }

    /** 以 Proxy 建立 FloodgateApi fake：isFloodgatePlayer / getPlayer 分派到注入值。 */
    private static FloodgateApi fakeApi(FloodgatePlayer player) {
        return (FloodgateApi) Proxy.newProxyInstance(
            FloodgateApi.class.getClassLoader(),
            new Class<?>[] {FloodgateApi.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isFloodgatePlayer":
                        return player != null && Boolean.TRUE.equals(
                            (Boolean) invokeIsFloodgate(args[0], player));
                    case "getPlayer":
                        return player;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "FakeFloodgateApi";
                    default:
                        throw new UnsupportedOperationException(
                            "fake does not implement " + method.getName());
                }
            });
    }

    private static Object invokeIsFloodgate(Object uuid, FloodgatePlayer player) {
        // fake 的 isFloodgatePlayer 語意：有注入 player 即視為基岩玩家
        return uuid != null;
    }

    /** 以 Proxy 建立 FloodgatePlayer fake：getter 回傳注入欄位。 */
    private static FloodgatePlayer fakePlayer(String username, DeviceOs deviceOs,
                                              InputMode inputMode, String languageCode,
                                              LinkedPlayer linked) {
        return (FloodgatePlayer) Proxy.newProxyInstance(
            FloodgatePlayer.class.getClassLoader(),
            new Class<?>[] {FloodgatePlayer.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUsername":
                        return username;
                    case "getDeviceOs":
                        return deviceOs;
                    case "getInputMode":
                        return inputMode;
                    case "getLanguageCode":
                        return languageCode;
                    case "getLinkedPlayer":
                        return linked;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "FakeFloodgatePlayer";
                    default:
                        throw new UnsupportedOperationException(
                            "fake does not implement " + method.getName());
                }
            });
    }

    @Test
    @DisplayName("lookup 完整映射裝置/輸入/語言/連結資料")
    void lookup_mapsAllFields() {
        FloodgateBedrockPlayerLookup lookup = lookup(fakePlayer(
            "bedrocker", DeviceOs.IOS, InputMode.TOUCH, "en_US",
            LinkedPlayer.of("JavaName", UUID.randomUUID(), UUID.randomUUID())));
        UUID id = UUID.randomUUID();

        Optional<BedrockPlayerInfo> info = lookup.lookup(id);
        assertTrue(info.isPresent(), "基岩玩家必須查得到資訊");
        BedrockPlayerInfo value = info.get();
        assertEquals(id, value.playerId());
        assertEquals("bedrocker", value.username());
        assertEquals(BedrockPlayerInfo.DeviceOs.IOS, value.deviceOs());
        assertEquals(BedrockPlayerInfo.InputMode.TOUCH, value.inputMode());
        assertEquals("en_US", value.languageCode());
        assertEquals(BedrockPlayerInfo.LinkState.LINKED, value.linkState());
        assertEquals("JavaName", value.linkedUsername());
    }

    @Test
    @DisplayName("未連結玩家 → LinkState.UNLINKED、linkedUsername 為 null")
    void lookup_unlinkedPlayer() {
        FloodgateBedrockPlayerLookup lookup = lookup(fakePlayer(
            "bedrocker", DeviceOs.NX, InputMode.CONTROLLER, "zh_TW", null));
        UUID id = UUID.randomUUID();

        Optional<BedrockPlayerInfo> info = lookup.lookup(id);
        assertTrue(info.isPresent());
        assertEquals(BedrockPlayerInfo.LinkState.UNLINKED, info.get().linkState());
        assertNull(info.get().linkedUsername());
    }

    @Test
    @DisplayName("非基岩或離線玩家 → Optional.empty()")
    void lookup_javaOrOfflinePlayer_empty() {
        // player == null 代表 FloodgateApi.isFloodgatePlayer 回 false（fake 語意）
        FloodgateBedrockPlayerLookup lookup = lookup(null);
        assertTrue(lookup.lookup(UUID.randomUUID()).isEmpty(), "非基岩玩家必須回 empty");
        assertFalse(lookup.isBedrockPlayer(UUID.randomUUID()));
    }

    @Test
    @DisplayName("isBedrockPlayer 委派 FloodgateApi.isFloodgatePlayer")
    void isBedrockPlayer_delegates() {
        FloodgateBedrockPlayerLookup lookup = lookup(fakePlayer(
            "bedrocker", DeviceOs.IOS, InputMode.TOUCH, "en_US", null));
        assertTrue(lookup.isBedrockPlayer(UUID.randomUUID()),
            "fake 有 player 時視為基岩玩家");
    }

    @Test
    @DisplayName("未知上游列舉值 → UNKNOWN，不拋例外（名稱映射路徑）")
    void unknownUpstreamEnumValues_mapToUnknownWithoutThrowing() {
        assertEquals(BedrockPlayerInfo.DeviceOs.UNKNOWN,
            FloodgateBedrockPlayerLookup.mapDeviceOsName("SOME_FUTURE_DEVICE"));
        assertEquals(BedrockPlayerInfo.InputMode.UNKNOWN,
            FloodgateBedrockPlayerLookup.mapInputModeName("BRAIN_INTERFACE"));
        // null 名稱同樣防禦為 UNKNOWN，不拋例外
        assertEquals(BedrockPlayerInfo.DeviceOs.UNKNOWN,
            FloodgateBedrockPlayerLookup.mapDeviceOsName(null));
        assertEquals(BedrockPlayerInfo.InputMode.UNKNOWN,
            FloodgateBedrockPlayerLookup.mapInputModeName(null));
    }

    @Test
    @DisplayName("已知上游列舉名稱正確映射（含大小寫敏感契約）")
    void knownUpstreamEnumNames_mapCorrectly() {
        assertEquals(BedrockPlayerInfo.DeviceOs.WIN32,
            FloodgateBedrockPlayerLookup.mapDeviceOsName("WIN32"));
        assertEquals(BedrockPlayerInfo.DeviceOs.PS4,
            FloodgateBedrockPlayerLookup.mapDeviceOsName("PS4"));
        assertEquals(BedrockPlayerInfo.InputMode.KEYBOARD_MOUSE,
            FloodgateBedrockPlayerLookup.mapInputModeName("KEYBOARD_MOUSE"));
        // 小寫不符視為未知（列舉名稱映射為大小寫敏感）
        assertEquals(BedrockPlayerInfo.DeviceOs.UNKNOWN,
            FloodgateBedrockPlayerLookup.mapDeviceOsName("win32"));
    }

    @Test
    @DisplayName("缺漏欄位防禦：null username / null languageCode 不拋例外且可診斷")
    void lookup_nullOptionalFields_defensive() {
        FloodgateBedrockPlayerLookup lookup = lookup(fakePlayer(
            null, DeviceOs.UNKNOWN, InputMode.UNKNOWN, null, null));
        UUID id = UUID.randomUUID();

        Optional<BedrockPlayerInfo> info = lookup.lookup(id);
        assertTrue(info.isPresent(), "缺漏欄位不得讓查詢失敗");
        assertNull(info.get().username(), "null username 保持 null（呼叫端自行處理）");
        assertEquals("", info.get().languageCode(), "null languageCode 防禦為空字串");
        assertEquals(BedrockPlayerInfo.DeviceOs.UNKNOWN, info.get().deviceOs());
    }

    @Test
    @DisplayName("seam 契約：null playerId 必須拋 IllegalArgumentException")
    void seam_nullPlayerId_throws() {
        FloodgateBedrockPlayerLookup lookup = lookup(null);
        assertThrows(IllegalArgumentException.class, () -> lookup.isBedrockPlayer(null));
        assertThrows(IllegalArgumentException.class, () -> lookup.lookup(null));
    }
}
