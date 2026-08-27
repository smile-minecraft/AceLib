package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Folia 環境下的 {@link MessageService} 行為分流測試。
 *
 * <h2>目標</h2>
 * 對應 Plan §十 Phase 5 邊界條件「不可繞過 region context 傳送訊息」；
 * 錯誤代碼 {@code ACELIB-MSG-002}。
 *
 * <h2>MockBukkit 環境限制</h2>
 * MockBukkit 4.x 並未實作 Folia region scheduler；我們採用「Mockito stub」模擬
 * Folia 在 non-owned region 拋 {@link IllegalStateException} 的標準行為，驗證
 * {@link MessageService} 是否：</p>
 * <ul>
 *   <li>捕獲例外並 graceful 降級</li>
 *   <li>輸出 {@code ACELIB-MSG-002} warning</li>
 *   <li>不中斷後續邏輯</li>
 * </ul>
 *
 * <h2>注意</h2>
 * 真實 Folia 環境下，建議呼叫方透過 {@code SafeScheduler.runForPlayer(...)} 將
 * 訊息派送進玩家 region。本測試僅驗證「若直接呼叫且處於錯誤 region，服務能
 * 優雅降級」，不驗證 region 派送本身（那是 {@code SafeScheduler} 的職責）。
 */
@DisplayName("MessageService (Folia 分流)")
class MessageServiceFoliaTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private File langDir;
    private LangManager lang;
    private MessageService foliaService;
    private List<LogRecord> capturedLogs;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("dataFolder mkdirs failed");
        }
        langDir = new File(dataFolder, "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("lang dir mkdirs failed");
        }

        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("message:\n  prefix: '[AceLib] '\n");
            w.write("notice: 'Notice to all'\n");
        }

        capturedLogs = new ArrayList<>();
        handler = new RecordingHandler(capturedLogs);
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);
        root.addHandler(handler);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.setLevel(Level.ALL);
        acelib.addHandler(handler);

        lang = new LangManager(plugin, Locale.US);
        lang.load();
        // 重點：Folia environment
        foliaService = new MessageService(plugin, lang,
            Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
    }

    @AfterEach
    void tearDown() {
        Logger root = Logger.getLogger("");
        root.removeHandler(handler);
        root.setLevel(Level.INFO);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.removeHandler(handler);
        acelib.setLevel(Level.INFO);
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // Folia + IllegalStateException 路徑
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Folia + IllegalStateException (non-owned region)")
    class FoliaUnsafeContext {

        @Test
        @DisplayName("sendChat 在 Folia + mock player throws IllegalStateException 時 → 記 MSG-002 且不中斷")
        void sendChat_foliaUnsafe_warnsAndSwallows() {
            // 用 Mockito 模擬 player.sendMessage 拋 IllegalStateException
            // 並 stub isOnline() 回 true（避免被前置 null/離線檢查攔下）
            Player mockedPlayer = Mockito.mock(Player.class);
            Mockito.when(mockedPlayer.isOnline()).thenReturn(true);
            Mockito.when(mockedPlayer.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException(
                    "Player is not in their own region"))
                .when(mockedPlayer).sendMessage(Mockito.<String>any());

            assertDoesNotThrow(() ->
                foliaService.sendChat(mockedPlayer, "greeting",
                    Map.of("player", "tester")));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia + IllegalStateException 必須輸出 MSG-002 warning。實際訊息: "
                    + logMessages());
        }

        @Test
        @DisplayName("sendActionBar 在 Folia unsafe context 走相同降級路徑")
        void sendActionBar_foliaUnsafe_warnsAndSwallows() {
            Player mockedPlayer = Mockito.mock(Player.class);
            Mockito.when(mockedPlayer.isOnline()).thenReturn(true);
            Mockito.when(mockedPlayer.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException("non-region"))
                .when(mockedPlayer).sendActionBar(Mockito.<String>any());

            assertDoesNotThrow(() ->
                foliaService.sendActionBar(mockedPlayer, "greeting", Map.of("player", "x")));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia sendActionBar unsafe context 必須輸出 MSG-002。實際: "
                    + logMessages());
        }

        @Test
        @DisplayName("sendTitle 在 Folia unsafe context 走相同降級路徑")
        void sendTitle_foliaUnsafe_warnsAndSwallows() {
            Player mockedPlayer = Mockito.mock(Player.class);
            Mockito.when(mockedPlayer.isOnline()).thenReturn(true);
            Mockito.when(mockedPlayer.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException("non-region"))
                .when(mockedPlayer).sendTitle(
                    Mockito.<String>any(),
                    Mockito.<String>any(),
                    Mockito.anyInt(),
                    Mockito.anyInt(),
                    Mockito.anyInt());

            assertDoesNotThrow(() ->
                foliaService.sendTitle(mockedPlayer, "greeting",
                    Map.of("player", "x"), "notice", Map.of()));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia sendTitle unsafe context 必須輸出 MSG-002。實際: "
                    + logMessages());
        }

        @Test
        @DisplayName("sendChat 在 Folia 安全情境（玩家 mock 未拋例外）正常送出")
        void sendChat_foliaSafe_deliversMessage() {
            Player mockedPlayer = Mockito.mock(Player.class);
            Mockito.when(mockedPlayer.isOnline()).thenReturn(true);
            Mockito.when(mockedPlayer.getName()).thenReturn("SafeMockPlayer");

            // 不拋例外（正常路徑）：用 ArgumentCaptor 確認 sendMessage 被呼叫
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            assertDoesNotThrow(() ->
                foliaService.sendChat(mockedPlayer, "greeting",
                    Map.of("player", "tester")));

            Mockito.verify(mockedPlayer, Mockito.atLeastOnce()).sendMessage(captor.capture());
            String delivered = String.join("\n", captor.getAllValues());
            assertTrue(delivered.contains("Hello tester!"),
                "Folia 安全情境下訊息必須送到玩家。實際: " + delivered);
        }
    }

    // -----------------------------------------------------------------
    // Folia + IllegalStateException：新增 Component overload 分流
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Folia + IllegalStateException (Component overload)")
    class FoliaUnsafeComponentContext {

        @Test
        @DisplayName("sendChat(Player, Component) 在 Folia + sendMessage(Component) 拋 IllegalStateException → 記 MSG-002 且不中斷")
        void sendChatComponent_foliaUnsafe_warnsAndSwallows() {
            PlayerMock real = server.addPlayer("FoliaMockPlayer");
            Player spy = Mockito.spy(real);
            Mockito.when(spy.isOnline()).thenReturn(true);
            Mockito.when(spy.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException("Player is not in their own region"))
                .when(spy).sendMessage(Mockito.<Component>any());

            assertDoesNotThrow(() -> foliaService.sendChat(spy, Component.text("hello")));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia + IllegalStateException 必須輸出 MSG-002 warning。實際: " + logMessages());
        }

        @Test
        @DisplayName("sendActionBar(Player, Component) 在 Folia + sendActionBar(Component) 拋 IllegalStateException → 記 MSG-002 且不中斷")
        void sendActionBarComponent_foliaUnsafe_warnsAndSwallows() {
            PlayerMock real = server.addPlayer("FoliaMockPlayer");
            Player spy = Mockito.spy(real);
            Mockito.when(spy.isOnline()).thenReturn(true);
            Mockito.when(spy.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException("non-region"))
                .when(spy).sendActionBar(Mockito.<Component>any());

            assertDoesNotThrow(() -> foliaService.sendActionBar(spy, Component.text("hello")));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia sendActionBar unsafe context 必須輸出 MSG-002。實際: " + logMessages());
        }

        @Test
        @DisplayName("sendTitle(Player, Component, Component) 在 Folia + showTitle(Title) 拋 IllegalStateException → 記 MSG-002 且不中斷")
        void sendTitleComponent_foliaUnsafe_warnsAndSwallows() {
            PlayerMock real = server.addPlayer("FoliaMockPlayer");
            Player spy = Mockito.spy(real);
            Mockito.when(spy.isOnline()).thenReturn(true);
            Mockito.when(spy.getName()).thenReturn("FoliaMockPlayer");
            Mockito.doThrow(new IllegalStateException("non-region"))
                .when(spy).showTitle(Mockito.<Title>any());

            assertDoesNotThrow(() ->
                foliaService.sendTitle(spy, Component.text("t"), Component.text("s")));

            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "Folia sendTitle unsafe context 必須輸出 MSG-002。實際: " + logMessages());
        }
    }

    // -----------------------------------------------------------------
    // broadcast(Component) 逐收件者錯誤隔離
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("broadcast(Component) 逐收件者錯誤隔離")
    class BroadcastComponentIsolation {

        @Test
        @DisplayName("broadcast(Component) 單一收件者 sendMessage(Component) 拋例外時，仍送達其他線上玩家並記可追蹤 warning")
        void broadcast_oneRecipientFails_othersStillReceive() {
            // 收件者 A：sendMessage(Component) 拋 IllegalStateException（Folia unsafe）
            PlayerMock baseA = new PlayerMock(server, "ThrowingPlayer");
            PlayerMock spyA = Mockito.spy(baseA);
            Mockito.doThrow(new IllegalStateException("Player is not in their own region"))
                .when(spyA).sendMessage(Mockito.<Component>any());
            server.addPlayer(spyA);

            // 收件者 B：正常玩家，應仍收到廣播
            PlayerMock baseB = new PlayerMock(server, "ReceivingPlayer");
            PlayerMock spyB = Mockito.spy(baseB);
            server.addPlayer(spyB);

            assertDoesNotThrow(() -> foliaService.broadcast(Component.text("broadcast!")));

            // A 被嘗試送出（雖然拋例外）
            Mockito.verify(spyA, Mockito.atLeastOnce())
                .sendMessage(Mockito.<Component>any());
            // B 仍收到廣播
            Mockito.verify(spyB, Mockito.atLeastOnce())
                .sendMessage(Mockito.<Component>any());
            // 失敗有可追蹤 warning（Folia → MSG-002）
            assertTrue(hasLogContaining("ACELIB-MSG-002"),
                "單一收件者失敗必須記錄可追蹤 warning。實際: " + logMessages());
        }
    }

    // -----------------------------------------------------------------
    // 非 Folia + IllegalStateException 路徑（回歸測試）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Paper / UNKNOWN + IllegalStateException")
    class NonFoliaIllegalStateContext {

        @Test
        @DisplayName("sendChat 在非 Folia 平台記 MSG-003，不誤標 MSG-002")
        void sendChat_nonFoliaIllegalStateException_warnsFormatError() {
            for (Platform nonFolia : List.of(Platform.PAPER, Platform.UNKNOWN)) {
                capturedLogs.clear();
                MessageService service = serviceFor(nonFolia);
                Player mockedPlayer = onlinePlayer(nonFolia + "ChatPlayer");
                Mockito.doThrow(new IllegalStateException("paper player state"))
                    .when(mockedPlayer).sendMessage(Mockito.<String>any());

                assertDoesNotThrow(() ->
                    service.sendChat(mockedPlayer, "greeting", Map.of("player", "tester")));

                assertTrue(hasLogContaining("ACELIB-MSG-003"),
                    nonFolia + " + IllegalStateException 必須輸出 MSG-003。實際: "
                        + logMessages());
                assertFalse(hasLogContaining("ACELIB-MSG-002"),
                    nonFolia + " 不得將 IllegalStateException 誤標為 MSG-002。實際: "
                        + logMessages());
            }
        }

        @Test
        @DisplayName("sendActionBar 在非 Folia 平台記 MSG-003，不誤標 MSG-002")
        void sendActionBar_nonFoliaIllegalStateException_warnsFormatError() {
            for (Platform nonFolia : List.of(Platform.PAPER, Platform.UNKNOWN)) {
                capturedLogs.clear();
                MessageService service = serviceFor(nonFolia);
                Player mockedPlayer = onlinePlayer(nonFolia + "ActionBarPlayer");
                Mockito.doThrow(new IllegalStateException("paper action bar state"))
                    .when(mockedPlayer).sendActionBar(Mockito.<String>any());

                assertDoesNotThrow(() ->
                    service.sendActionBar(mockedPlayer, "greeting",
                        Map.of("player", "tester")));

                assertTrue(hasLogContaining("ACELIB-MSG-003"),
                    nonFolia + " + IllegalStateException 必須輸出 MSG-003。實際: "
                        + logMessages());
                assertFalse(hasLogContaining("ACELIB-MSG-002"),
                    nonFolia + " 不得將 IllegalStateException 誤標為 MSG-002。實際: "
                        + logMessages());
            }
        }

        @Test
        @DisplayName("sendTitle 在非 Folia 平台記 MSG-003，不誤標 MSG-002")
        void sendTitle_nonFoliaIllegalStateException_warnsFormatError() {
            for (Platform nonFolia : List.of(Platform.PAPER, Platform.UNKNOWN)) {
                capturedLogs.clear();
                MessageService service = serviceFor(nonFolia);
                Player mockedPlayer = onlinePlayer(nonFolia + "TitlePlayer");
                Mockito.doThrow(new IllegalStateException("paper title state"))
                    .when(mockedPlayer).sendTitle(
                        Mockito.<String>any(),
                        Mockito.<String>any(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyInt());

                assertDoesNotThrow(() ->
                    service.sendTitle(mockedPlayer, "greeting",
                        Map.of("player", "tester"), "notice", Map.of()));

                assertTrue(hasLogContaining("ACELIB-MSG-003"),
                    nonFolia + " + IllegalStateException 必須輸出 MSG-003。實際: "
                        + logMessages());
                assertFalse(hasLogContaining("ACELIB-MSG-002"),
                    nonFolia + " 不得將 IllegalStateException 誤標為 MSG-002。實際: "
                        + logMessages());
            }
        }
    }


    @Nested
    @DisplayName("平台標記")
    class PlatformAccessors {

        @Test
        @DisplayName("getPlatform() 在 Folia 環境下回傳 FOLIA")
        void foliaPlatformAccessor() {
            assertSame(Platform.FOLIA, foliaService.getPlatform());
        }

        @Test
        @DisplayName("getCapability() 在 Folia 環境下支援 regionScheduling")
        void foliaCapabilityAccessor() {
            PlatformCapability cap = foliaService.getCapability();
            assertTrue(cap.regionScheduling(), "Folia capability 必須支援 regionScheduling");
            assertTrue(cap.globalScheduler(), "Folia capability 必須支援 globalScheduler");
            assertTrue(cap.bukkitApi(), "Folia capability 必須支援 bukkitApi");
            assertTrue(cap.foliaThreadedRegionsApi(),
                "Folia capability 必須支援 foliaThreadedRegionsApi");
            // 與 forPlatform 一致（by value 而非 by reference；record 為值相等）
            assertEquals(PlatformCapability.forPlatform(Platform.FOLIA), cap);
        }
    }

    // -----------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------

    private MessageService serviceFor(Platform platform) {
        return new MessageService(plugin, lang, platform,
            PlatformCapability.forPlatform(platform));
    }

    private Player onlinePlayer(String name) {
        Player mockedPlayer = Mockito.mock(Player.class);
        Mockito.when(mockedPlayer.isOnline()).thenReturn(true);
        Mockito.when(mockedPlayer.getName()).thenReturn(name);
        return mockedPlayer;
    }

    private boolean hasLogContaining(String text) {
        return capturedLogs.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains(text));
    }

    private List<String> logMessages() {
        return capturedLogs.stream()
            .map(LogRecord::getMessage)
            .toList();
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> sink;

        RecordingHandler(List<LogRecord> sink) {
            this.sink = sink;
        }

        @Override
        public void publish(LogRecord record) {
            sink.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
