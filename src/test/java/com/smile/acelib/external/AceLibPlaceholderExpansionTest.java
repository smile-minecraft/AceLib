package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibVersion;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 下游佔位符 expansion 子類別測試。
 */
@DisplayName("自有佔位符 expansion")
class AceLibPlaceholderExpansionTest {

    @Test
    @DisplayName("識別／作者／版本／persist 語意")
    void metadata() {
        AceLibPlaceholderExpansion expansion =
            new AceLibPlaceholderExpansion("acelib_test", (player, params) -> "v");

        assertEquals("acelib_test", expansion.getIdentifier());
        assertEquals("AceLib", expansion.getAuthor());
        assertEquals(AceLibVersion.VERSION, expansion.getVersion());
        assertTrue(expansion.persist(), "AceLib 管理的註冊應在 PAPI 自身 reload 時保留");
    }

    @Test
    @DisplayName("onRequest 轉交玩家識別與參數")
    void onRequest_delegates() {
        UUID id = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        AceLibPlaceholderExpansion expansion =
            new AceLibPlaceholderExpansion("acelib_test",
                (playerId, params) -> playerId + ":" + params);

        assertEquals(id + ":hp", expansion.onRequest(player, "hp"));
    }

    @Test
    @DisplayName("onRequest：null 玩家與 null 參數正規化（不拋例外）")
    void onRequest_nullsNormalized() {
        AceLibPlaceholderExpansion expansion =
            new AceLibPlaceholderExpansion("acelib_test",
                (playerId, params) -> (playerId == null ? "none" : "some") + ":" + params);

        OfflinePlayer nullPlayer = null;
        assertEquals("none:", expansion.onRequest(nullPlayer, null));
    }

    @Test
    @DisplayName("onRequest：處理器拋例外時回 null（不逃逸進 PAPI 執行緒）")
    void onRequest_handlerThrows_returnsNull() {
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);
        AceLibPlaceholderExpansion expansion =
            new AceLibPlaceholderExpansion("acelib_test", (playerId, params) -> {
                throw new IllegalStateException("boom");
            });

        assertNull(expansion.onRequest(player, "hp"));
    }

    @Test
    @DisplayName("建構子拒絕空白識別與 null 處理器")
    void constructor_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class,
            () -> new AceLibPlaceholderExpansion("  ", (p, params) -> "v"));
        assertThrows(IllegalArgumentException.class,
            () -> new AceLibPlaceholderExpansion(null, (p, params) -> "v"));
        assertThrows(IllegalArgumentException.class,
            () -> new AceLibPlaceholderExpansion("ok", null));
    }
}
