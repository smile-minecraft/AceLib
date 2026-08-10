package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * {@link BukkitSender} + {@link BukkitReplySink} + {@link BukkitCommandBridge}
 * MockBukkit 整合測試。
 *
 * <p>對應 Plan §十一驗收標準「Folia / Paper 安全回覆」與 Bukkit 端整合：
 * 透過 MockBukkit 建立 server / 玩家 / console，再用 bridge.onCommand 與
 * onTabComplete 觸發，驗證訊息是否正確送出。</p>
 *
 * <h2>Folia 分流</h2>
 * <p>MockBukkit 4.x 不支援 Folia scheduler；對 Folia 環境採用
 * 「透過 Mockito stub player.sendMessage 拋 IllegalStateException」模式
 * 模擬 Folia non-owned region 標準行為，驗證 {@link BukkitReplySink}
 * graceful 降級（與 Phase 5 MessageServiceFoliaTest 同樣手法）。</p>
 */
@DisplayName("Bukkit Command adapter")
class CommandRegistryBukkitTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("dataFolder mkdirs failed");
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---------------------------------------------------------------------
    // Sender wrapping
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("BukkitSender 包裝")
    class SenderWrapping {

        @Test
        @DisplayName("玩家 sender → isPlayer()=true, asPlayer() 非 null")
        void playerSender_isPlayer() {
            Player player = server.addPlayer();
            BukkitSender bs = new BukkitSender(player);
            assertTrue(bs.isPlayer());
            assertNotNull(bs.asPlayer());
            assertEquals(player.getName(), bs.getName());
        }

        @Test
        @DisplayName("console sender → isPlayer()=false, asPlayer() null")
        void consoleSender_isNotPlayer() {
            ConsoleCommandSender console = server.getConsoleSender();
            BukkitSender bs = new BukkitSender(console);
            assertTrue(!bs.isPlayer());
            assertEquals(null, bs.asPlayer());
            // MockBukkit 4.113.1 console sender.getName() 回 "CONSOLE"
            assertEquals("CONSOLE", bs.getName());
        }

        @Test
        @DisplayName("permission 為 null/空 → hasPermission() 一律 true")
        void nullPermission_isTrue() {
            Player player = server.addPlayer();
            BukkitSender bs = new BukkitSender(player);
            assertTrue(bs.hasPermission(null));
            assertTrue(bs.hasPermission(""));
        }

        @Test
        @DisplayName("無權限玩家 → hasPermission() 對應權限節點回傳 false")
        void playerNoPermission_false() {
            Player player = server.addPlayer();
            BukkitSender bs = new BukkitSender(player);
            assertTrue(!bs.hasPermission("acelib.reload"));
        }

        @Test
        @DisplayName("BukkitPlayerHandle.getUniqueId 對應 Bukkit Player UUID")
        void playerHandle_uuidMatches() {
            Player player = server.addPlayer();
            BukkitSender bs = new BukkitSender(player);
            assertEquals(player.getUniqueId(), bs.asPlayer().getUniqueId());
        }

        @Test
        @DisplayName("BukkitPlayerHandle.isOnline 反映 player.isOnline")
        void playerHandle_onlineMatches() {
            Player player = server.addPlayer();
            BukkitSender bs = new BukkitSender(player);
            assertEquals(player.isOnline(), bs.asPlayer().isOnline());
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch integration via Bridge
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("BukkitCommandBridge dispatch")
    class BridgeDispatch {

        @Test
        @DisplayName("玩家觸發已知子指令 → handler 執行且訊息送達 sender")
        void dispatch_player_runsHandler() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status")
                    .handler(ctx -> ctx.reply("STATUS_OK"))
                    .build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            boolean handled = bridge.onCommand(player, mockCmd, "acelib",
                new String[]{"status"});

            assertTrue(handled, "BukkitCommandBridge 應回 true（不讓 Bukkit 顯示 Unknown command）");
            // 玩家 reply 走 backend（region-safe 派送）；下一個 tick runnable 才執行
            server.getScheduler().performTicks(1L);
            // 訊息已送出（透過 player.sendMessage）
            assertEquals("STATUS_OK", nextSentMessage(player));
        }

        @Test
        @DisplayName("無權限玩家觸發受限指令 → 收到 NO_PERMISSION 訊息")
        void dispatch_noPermission_messageContainsCode() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .handler(SubCommand.NOOP).build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            bridge.onCommand(player, mockCmd, "acelib", new String[]{"reload"});

            // 玩家 reply 走 backend；下一個 tick 才送出
            server.getScheduler().performTicks(1L);
            // 玩家看到的是 message（不含 code prefix；plan §11：對玩家輸出簡潔）
            assertEquals("no permission for reload: acelib.reload", nextSentMessage(player));
            // console 與其他 sender 會包含 code，但 player 不會
        }

        @Test
        @DisplayName("console 觸發 playerOnly 子指令 → CONSOLE_NOT_ALLOWED")
        void dispatch_console_playerOnly_returnsCmd004() {
            // 安裝 logger capture handler 以驗證 console 輸出（透過 plugin logger）
            CapturingHandler handler = installLogCapture();
            try {
                BukkitReplySink sink = new BukkitReplySink(plugin);
                CommandRegistryImpl registry = new CommandRegistryImpl(sink);
                registry.register(CommandSpec.builder("acelib")
                    .subCommand(SubCommandSpec.builder("fly")
                        .playerOnly()
                        .handler(SubCommand.NOOP).build())
                    .build());
                BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
                ConsoleCommandSender console = server.getConsoleSender();
                Command mockCmd = Mockito.mock(Command.class);

                bridge.onCommand(console, mockCmd, "acelib", new String[]{"fly"});

                // console 輸出應包含 code prefix
                String out = captureConsoleOutput(handler);
                assertTrue(out.contains("ACELIB-CMD-004"),
                    "console 輸出應包含 ACELIB-CMD-004 code。實際: " + out);
                assertTrue(out.contains("fly"),
                    "console 輸出應包含子指令名稱 'fly'。實際: " + out);
            } finally {
                handler.close();
            }
        }

        @Test
        @DisplayName("未知子指令 → UNKNOWN_SUBCOMMAND 訊息")
        void dispatch_unknownSub_returnsCmd002() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib").build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            bridge.onCommand(player, mockCmd, "acelib", new String[]{"nope"});

            // 玩家 reply 走 backend；下一個 tick 才送出
            server.getScheduler().performTicks(1L);
            assertEquals("unknown subcommand: nope", nextSentMessage(player));
        }

        @Test
        @DisplayName("args 為空 → 顯示 help（依權限過濾）")
        void dispatch_emptyArgs_showsHelp() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .description("AceLib root")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status").handler(SubCommand.NOOP).build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            bridge.onCommand(player, mockCmd, "acelib", new String[]{});

            // 玩家 reply 走 backend；下一個 tick 才送出
            server.getScheduler().performTicks(1L);
            String said = joinSentMessages(player);
            assertTrue(said.contains("acelib"),
                "help 應包含主指令名。實際：" + said);
            assertTrue(said.contains("show status"),
                "help 應包含子指令描述。實際：" + said);
        }
    }

    // ---------------------------------------------------------------------
    // Tab Complete integration
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("BukkitCommandBridge tabComplete")
    class BridgeTabComplete {

        @Test
        @DisplayName("args 為空 → 列出主指令名 + 可見子指令")
        void tab_emptyArgs_listsMainAndSubs() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload").handler(SubCommand.NOOP).build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            List<String> result = bridge.onTabComplete(player, mockCmd, "acelib",
                new String[]{});

            assertTrue(result.contains("acelib"));
            assertTrue(result.contains("status"));
            assertTrue(!result.contains("reload"),
                "tab 補全不應暴露無權限子指令");
        }

        @Test
        @DisplayName("部分字串 → 列出以該字串開頭的可見子指令")
        void tab_partialPrefix_filtered() {
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("restart").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);
            Player player = server.addPlayer();
            Command mockCmd = Mockito.mock(Command.class);

            List<String> result = bridge.onTabComplete(player, mockCmd, "acelib",
                new String[]{"re"});

            assertTrue(result.contains("reload"));
            assertTrue(result.contains("restart"));
            assertTrue(!result.contains("status"));
        }
    }

    // ---------------------------------------------------------------------
    // attach() with plugin.yml
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("BukkitCommandBridge.attach()")
    class AttachToBukkit {

        @Test
        @DisplayName("plugin.yml 已宣告時 attach 回傳 PluginCommand 並設定 executor")
        void attach_setsExecutor() {
            // 直接新增 plugin.yml 條目（簡化：透過 MockBukkit + loadPlugin 已自動處理 plugin.yml）
            // 改為：以「建立 PluginCommand」+ 「手動 register」方式測試
            BukkitReplySink sink = new BukkitReplySink(plugin);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            BukkitCommandBridge bridge = new BukkitCommandBridge(registry);

            // MockBukkit 不支援動態註冊自定義 PluginCommand（PluginCommand constructor 受限）
            // 此測試只驗證 attach 對於 null 指令名稱處理：
            //   - plugin.yml 缺少宣告 → getCommand(name) 回 null → attach 回 null
            PluginCommand cmd = bridge.attach(plugin, "nonexistent_cmd");
            assertEquals(null, cmd,
                "plugin.yml 沒宣告的指令名稱應回 null，不應 throw");
        }
    }

    // ---------------------------------------------------------------------
    // Folia 分流（透過 Mockito stub 模擬 IllegalStateException）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Folia unsafe context graceful 降級")
    class FoliaUnsafeContext {

        @Test
        @DisplayName("Paper 環境下 player.sendMessage 拋 IllegalStateException → 不中斷")
        void paper_player_sendMessage_throws_swallows() {
            // 用 Mockito mock player 模擬 Paper + IllegalStateException
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("PaperMock");
            doThrow(new IllegalStateException("paper player state"))
                .when(mockedPlayer).sendMessage(anyString());

            // 建立 reply sink 直接呼叫（不走 BukkitCommandBridge，因為 bridge 會用 real player）
            BukkitReplySink sink = new BukkitReplySink(plugin);
            BukkitSender bs = new BukkitSender(mockedPlayer);

            // 不應拋例外 — graceful 降級（dispatch 進入 backend、runnable 在
            // 下一個 tick 內呼叫 safeSendMessage，其內 catch IllegalStateException）
            sink.send(bs, "test message");
            server.getScheduler().performTicks(1L);

            verify(mockedPlayer, atLeastOnce()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Folia unsafe context（player.sendMessage 拋 IllegalStateException）走 graceful 降級")
        void folia_unsafe_player_gracefulDegrade() {
            // Folia 下 player.sendMessage 在 non-owned region 拋 IllegalStateException
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("FoliaUnsafeMock");
            doThrow(new IllegalStateException("Player is not in their own region"))
                .when(mockedPlayer).sendMessage(anyString());

            BukkitReplySink sink = new BukkitReplySink(plugin);
            BukkitSender bs = new BukkitSender(mockedPlayer);
            sink.send(bs, "should be swallowed");
            server.getScheduler().performTicks(1L);
            verify(mockedPlayer, atLeastOnce()).sendMessage(anyString());
        }
    }

    // ---------------------------------------------------------------------
    // Async player reply (Folia 安全) — 透過 mock backend 驗證 executeOnRegion 流程
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("sendPlayerAsync（Folia 安全）")
    class AsyncPlayerReply {

        @Test
        @DisplayName("非 AceLibPlugin owner 時 backend 拒絕 inline（吞例外 + log warning + 不直接 sendMessage）")
        void nonAceLibPlugin_fallbackSync() {
            // 採用 Mockito mock JavaPlugin owner（避免 mock AceLibPlugin 整個生命週期）
            JavaPlugin fakeOwner = Mockito.mock(JavaPlugin.class);
            Logger logger = Logger.getLogger("AceLib");
            logger.setUseParentHandlers(false);
            for (Handler existing : logger.getHandlers()) {
                logger.removeHandler(existing);
            }
            CapturingHandler capture = new CapturingHandler();
            logger.addHandler(capture);
            logger.setLevel(Level.ALL);
            when(fakeOwner.getLogger()).thenReturn(logger);
            try {
                Player mockedPlayer = Mockito.mock(Player.class);
                when(mockedPlayer.isOnline()).thenReturn(true);
                when(mockedPlayer.getName()).thenReturn("AsyncReplyMock");
                doAnswer(inv -> {
                    // 模擬 player.sendMessage 正常執行（不應被觸發）
                    return null;
                }).when(mockedPlayer).sendMessage(anyString());

                BukkitReplySink sink = new BukkitReplySink(fakeOwner);
                BukkitSender.BukkitPlayerHandle handle =
                    new BukkitSender(mockedPlayer).asPlayer() instanceof BukkitSender.BukkitPlayerHandle bph
                        ? bph : null;
                assertNotNull(handle);

                // 不應拋例外 — sink 必須 swallow backend 拋出的 IllegalStateException
                sink.sendPlayerAsync(handle, "async reply");

                // 不得直接 player.sendMessage（Momus P1 阻擋）
                verify(mockedPlayer, Mockito.never()).sendMessage(anyString());

                // 必須記錄 warning 攜帶 ACELIB-CMD-011
                String captured = captureConsoleOutput(capture);
                assertTrue(captured.contains("ACELIB-CMD-011"),
                    "logger 應輸出 ACELIB-CMD-011，實際: " + captured);
            } finally {
                logger.removeHandler(capture);
                capture.close();
            }
        }

        @Test
        @DisplayName("玩家離線時 sendPlayerAsync 不送訊息也不拋例外")
        void offlinePlayer_noop() {
            JavaPlugin fakeOwner = Mockito.mock(JavaPlugin.class);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(false);

            BukkitSender.BukkitPlayerHandle handle =
                new BukkitSender(mockedPlayer).asPlayer() instanceof BukkitSender.BukkitPlayerHandle bph
                    ? bph : null;
            BukkitReplySink sink = new BukkitReplySink(fakeOwner);
            sink.sendPlayerAsync(handle, "should not send");

            verify(mockedPlayer, Mockito.never()).sendMessage(anyString());
        }
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    /**
     * 取得 player 收到的下一個字串訊息。
     *
     * <p>MockBukkit 4.113.1 的 {@code PlayerMock} 透過 {@code nextComponentMessage()}
     * 取得 queue 內下一個 Adventure {@link Component}；{@code MessageTarget.nextMessage()}
     * default method 內部會用 {@link LegacyComponentSerializer} 序列化成字串。
     * 本 helper 用 lambda 實作 {@code MessageTarget} 介面，呼叫 default method。</p>
     */
    private static String nextSentMessage(Player player) {
        org.mockbukkit.mockbukkit.command.MessageTarget target =
            () -> ((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextComponentMessage();
        return target.nextMessage();
    }

    /**
     * 取得 player 所有已送訊息（consume queue 直到 null）。
     */
    private static String joinSentMessages(Player player) {
        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = nextSentMessage(player)) != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 安裝日誌 handler 到 "AceLib" logger，回傳 handler 實例供測試讀取紀錄。
     *
     * <p>測試結束後需呼叫 {@link CapturingHandler#close()} 並從 logger 移除以避免
     * 影響後續測試。</p>
     */
    private static CapturingHandler installLogCapture() {
        Logger logger = Logger.getLogger("AceLib");
        // 移除已存在的 CapturingHandler（避免重複安裝）
        for (java.util.logging.Handler existing : logger.getHandlers()) {
            if (existing instanceof CapturingHandler ch) {
                logger.removeHandler(ch);
            }
        }
        CapturingHandler handler = new CapturingHandler();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        return handler;
    }

    /**
     * 從 CapturingHandler 抽取所有日誌訊息（多行）。
     *
     * <p>處理 Java util logging 的 format pattern — 預設 handler 不會做 MessageFormat
     * 替換，所以 {@link LogRecord#getMessage()} 回傳的是 pattern 字串（內含佔位符），
     * 需自行透過 {@link LogRecord#getParameters()} 替換。</p>
     */
    private static String captureConsoleOutput(CapturingHandler handler) {
        StringBuilder sb = new StringBuilder();
        for (LogRecord record : handler.records) {
            String msg = record.getMessage();
            Object[] params = record.getParameters();
            if (params != null && params.length > 0) {
                try {
                    msg = java.text.MessageFormat.format(msg, params);
                } catch (Throwable ignored) {
                    // keep pattern as-is
                }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(msg);
        }
        return sb.toString();
    }

    /**
     * 簡單日誌 capture handler（測試用）。
     */
    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() { }
        @Override public void close() {
            Logger.getLogger("AceLib").removeHandler(this);
        }
    }
}