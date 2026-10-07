package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 需要 Bukkit Server 的引數型別測試（MockBukkit 環境）。
 *
 * <p>玩家（在線）、離線玩家、世界三種型別的解析依賴
 * {@code Bukkit.getPlayerExact / getOfflinePlayer / getWorld}，
 * 必須在 mock server 下執行；純解析邏輯見 {@link TypedArgumentsTest}。</p>
 */
@DisplayName("型別化引數解析（MockBukkit）")
class TypedArgumentsBukkitTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Nested
    @DisplayName("玩家引數（在線）")
    class PlayerArgument {

        @Test
        @DisplayName("在線玩家依名解析為 PlayerHandle")
        void onlinePlayer_parses() {
            PlayerMock player = server.addPlayer("Steve");
            CommandArgument<PlayerHandle> arg = Arguments.player("target");
            PlayerHandle handle = arg.parse("Steve");
            assertEquals(player.getUniqueId(), handle.getUniqueId());
            assertTrue(handle.isOnline());
        }

        @Test
        @DisplayName("大小寫不敏感；不存在或離線拋 PLAYER_OFFLINE（ACELIB-CMD-007）")
        void unknownOrOffline_playerOffline() {
            server.addPlayer("Steve");
            CommandArgument<PlayerHandle> arg = Arguments.player("target");
            assertEquals(server.getPlayer("Steve").getUniqueId(),
                arg.parse("steve").getUniqueId());
            CommandException unknown =
                assertThrows(CommandException.class, () -> arg.parse("Nobody_xyz"));
            assertEquals(CommandErrorKind.PLAYER_OFFLINE, unknown.getKind());
            assertEquals("ACELIB-CMD-007", unknown.getCode());
        }

        @Test
        @DisplayName("補全列出在線玩家並依前綴過濾")
        void suggest_listsOnlinePlayers() {
            server.addPlayer("Steve");
            server.addPlayer("Alex");
            CommandArgument<PlayerHandle> arg = Arguments.player("target");
            List<String> all = arg.suggest("");
            assertTrue(all.contains("Steve") && all.contains("Alex"),
                "應列出在線玩家；實際: " + all);
            List<String> filtered = arg.suggest("St");
            assertTrue(filtered.contains("Steve") && !filtered.contains("Alex"),
                "應依前綴過濾；實際: " + filtered);
        }
    }

    @Nested
    @DisplayName("離線玩家引數")
    class OfflinePlayerArgument {

        @Test
        @DisplayName("玩過的離線玩家可解析；從未上線的名稱拋 INVALID_ARGUMENT")
        void offlinePlayed_parses_unknown_invalid() {
            PlayerMock played = server.addPlayer("Steve");
            played.kick();
            CommandArgument<org.bukkit.OfflinePlayer> arg = Arguments.offlinePlayer("target");
            org.bukkit.OfflinePlayer resolved = arg.parse("Steve");
            assertEquals(played.getUniqueId(), resolved.getUniqueId());
            CommandException unknown =
                assertThrows(CommandException.class, () -> arg.parse("Nobody_xyz_never_joined"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, unknown.getKind());
            assertEquals("ACELIB-CMD-015", unknown.getCode());
        }
    }

    @Nested
    @DisplayName("世界引數")
    class WorldArgument {

        @Test
        @DisplayName("已載入世界依名解析；不存在拋 INVALID_ARGUMENT")
        void loadedWorld_parses_unknown_invalid() {
            server.addSimpleWorld("world");
            CommandArgument<World> arg = Arguments.world("world");
            assertEquals("world", arg.parse("world").getName());
            CommandException unknown =
                assertThrows(CommandException.class, () -> arg.parse("no_such_world_xyz"));
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, unknown.getKind());
            assertEquals("ACELIB-CMD-015", unknown.getCode());
        }

        @Test
        @DisplayName("維度鍵形式（overworld / minecraft:overworld）解析出主世界")
        void dimensionKeyForms_parseToMainWorld() {
            // 實機缺陷：主世界的 Bukkit 名是 'world'，但 Brigadier 樹送給客戶端
            // 的是維度鍵（overworld / minecraft:overworld）。AceLib 的 parse 只認
            // Bukkit 名，導致 vanilla 通過的輸入在 parse 階段被拒。
            //
            // MockBukkit 的 addSimpleWorld("world") 同樣產生 key
            // 'minecraft:overworld'（Bukkit 名與維度鍵不同），與實機一致。
            server.addSimpleWorld("world");
            CommandArgument<World> arg = Arguments.world("world");
            World expected = server.getWorld("world");
            assertNotNull(expected, "前置條件：應有已載入的 world");

            assertSame(expected, arg.parse("world"),
                "Bukkit 世界名形式應維持既有解析");
            assertSame(expected, arg.parse("overworld"),
                "裸維度鍵形式應解析出主世界");
            assertSame(expected, arg.parse("minecraft:overworld"),
                "完整維度鍵形式應解析出主世界");
        }

        @Test
        @DisplayName("維度鍵形式大小寫不敏感（Vanilla key 為小寫）")
        void dimensionKeyForms_areCaseInsensitive() {
            server.addSimpleWorld("world");
            CommandArgument<World> arg = Arguments.world("world");
            World expected = server.getWorld("world");
            assertNotNull(expected);
            assertSame(expected, arg.parse("Overworld"),
                "維度鍵大小寫不敏感（NamespacedKey 本身拒絕大寫，需退回小寫鍵）");
            assertSame(expected, arg.parse("MineCraft:Overworld"),
                "命名空間與鍵值皆應大小寫不敏感");
        }

        @Test
        @DisplayName("補全列出已載入世界")
        void suggest_listsWorlds() {
            server.addSimpleWorld("world");
            CommandArgument<World> arg = Arguments.world("world");
            assertTrue(arg.suggest("").contains("world"));
        }
    }
}
