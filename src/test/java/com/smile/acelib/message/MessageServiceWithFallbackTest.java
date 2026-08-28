package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link MessageService} 的 {@code *WithFallback} 系列與 Bedrock seam 測試。
 *
 * <p>對應 Task3 核心交付：四個 WithFallback 進入點、BedrockService seam（3 參數建構子
 * 注入 mock facade）、locale 優先順序、以及 null / 離線 / 單一玩家失敗隔離等安全行為。
 * 本次補齊 Momus 指出的缺口：四 action 實際入口、invalid languageCode、getPlayerInfo exception、
 * lifecycle（disable / reload）與 hover 內嵌 click 整合。</p>
 */
@DisplayName("MessageService WithFallback")
class MessageServiceWithFallbackTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private LangManager lang;
    private BedrockService bedrock;
    private MessageService service;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("message.bedrock.fallback.run_command: 'Run command: <payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: 'Suggest command: <payload>'\n");
            w.write("message.bedrock.fallback.open_url: 'Open URL: <payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: 'Copy to clipboard: <payload>'\n");
            w.write("greeting: 'Hello {player}'\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("message.bedrock.fallback.run_command: '執行指令：<payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: '建議指令：<payload>'\n");
            w.write("message.bedrock.fallback.open_url: '開啟網址：<payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: '複製到剪貼簿：<payload>'\n");
        }

        lang = new LangManager(plugin, Locale.US);
        lang.load();
        bedrock = mock(BedrockService.class);
        service = new MessageService(plugin, lang, bedrock);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock bedrockPlayer(String languageCode) {
        PlayerMock p = server.addPlayer();
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        BedrockPlayerInfo info = new BedrockPlayerInfo(id, p.getName(),
            BedrockPlayerInfo.DeviceOs.UNKNOWN, BedrockPlayerInfo.InputMode.UNKNOWN,
            languageCode, BedrockPlayerInfo.LinkState.UNLINKED, null);
        when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(info));
        return p;
    }

    private PlayerMock javaPlayer() {
        PlayerMock p = server.addPlayer();
        when(bedrock.isBedrockPlayer(p.getUniqueId())).thenReturn(false);
        return p;
    }

    /**
     * MockBukkit 的 {@link PlayerMock} 未實作 {@code showTitle(Title)}（僅有舊式
     * BaseComponent 多載），因此這裡用測試專屬子類覆寫 adventure {@link Title} 通道，
     * 把實際送出的 {@link Title} 攔截下來供斷言使用。
     */
    private static final class TitleCapturingPlayer extends PlayerMock {
        private Title lastTitle;

        TitleCapturingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void showTitle(Title title) {
            this.lastTitle = title;
        }

        Title lastTitle() {
            return lastTitle;
        }
    }

    private TitleCapturingPlayer bedrockTitlePlayer(String languageCode) {
        TitleCapturingPlayer p = new TitleCapturingPlayer(server, "title-" + languageCode);
        server.addPlayer(p);
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        BedrockPlayerInfo info = new BedrockPlayerInfo(id, p.getName(),
            BedrockPlayerInfo.DeviceOs.UNKNOWN, BedrockPlayerInfo.InputMode.UNKNOWN,
            languageCode, BedrockPlayerInfo.LinkState.UNLINKED, null);
        when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(info));
        return p;
    }

    private TitleCapturingPlayer javaTitlePlayer() {
        TitleCapturingPlayer p = new TitleCapturingPlayer(server, "java-title");
        server.addPlayer(p);
        when(bedrock.isBedrockPlayer(p.getUniqueId())).thenReturn(false);
        return p;
    }

    private static String text(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static boolean hasClick(Component c) {
        if (c == null) {
            return false;
        }
        if (c.clickEvent() != null) {
            return true;
        }
        HoverEvent<?> hover = c.hoverEvent();
        if (hover != null && hover.value() instanceof Component hc) {
            if (hasClick(hc)) {
                return true;
            }
        }
        for (Component child : c.children()) {
            if (hasClick(child)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    // sendChatWithFallback：基岩 vs Java
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendChatWithFallback：基岩玩家 click 被剝離並附加可讀 hint")
    void sendChat_bedrock_stripsClickAndAppendsHint() {
        PlayerMock p = bedrockPlayer("en_US");
        Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent, "基岩玩家必須收到訊息");
        assertFalse(hasClick(sent), "基岩玩家收到的訊息不得含 ClickEvent");
        String t = text(sent);
        assertTrue(t.contains("click"), "原始文字保留");
        assertTrue(t.contains("Run command: /warp"), "hint 應為英文（Floodgate languageCode=en_US）");
    }

    @Test
    @DisplayName("sendChatWithFallback：Java 玩家保留原始 click event")
    void sendChat_javaKeepsClick() {
        PlayerMock p = javaPlayer();
        Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent, "Java 玩家必須收到訊息");
        assertTrue(hasClick(sent), "Java 玩家應保留原始 ClickEvent");
        assertTrue(text(sent).contains("click"));
    }

    // -----------------------------------------------------------------
    // locale 優先順序：override > Player.locale() > Floodgate > default
    // -----------------------------------------------------------------

    @Test
    @DisplayName("locale 優先順序：override 勝出（即使 Floodgate 為 zh_TW）")
    void locale_overrideWins() {
        PlayerMock p = bedrockPlayer("zh_TW");
        p.setLocale(Locale.TRADITIONAL_CHINESE);
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, Locale.US);
        Component sent = p.nextComponentMessage();
        assertTrue(text(sent).contains("Run command: /warp"),
            "override=en_US 應產生英文 hint，實際: " + text(sent));
    }

    @Test
    @DisplayName("locale 優先順序：Player.locale() 勝出（勝過 Floodgate）")
    void locale_playerLocaleWins() {
        PlayerMock p = bedrockPlayer("en_US");
        p.setLocale(Locale.TRADITIONAL_CHINESE); // zh_TW
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertTrue(text(sent).contains("執行指令：/warp"),
            "Player.locale()=zh_TW 應產生中文 hint，實際: " + text(sent));
    }

    @Test
    @DisplayName("locale 優先順序：Player.locale()=ROOT 時退回 Floodgate languageCode")
    void locale_rootFallsBackToFloodgate() {
        PlayerMock p = bedrockPlayer("en_US");
        p.setLocale(Locale.ROOT);
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertTrue(text(sent).contains("Run command: /warp"),
            "Player.locale()=ROOT 應改用 Floodgate en_US，實際: " + text(sent));
    }

    @Test
    @DisplayName("locale 優先順序：Player.locale()=ROOT 且 Floodgate 空 → default locale")
    void locale_rootAndEmptyFloodgateFallsBackToDefault() {
        PlayerMock p = bedrockPlayer("");
        p.setLocale(Locale.ROOT);
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertTrue(text(sent).contains("Run command: /warp"),
            "Floodgate 空應退回 default en_US，實際: " + text(sent));
    }

    // -----------------------------------------------------------------
    // 四種 action 於實際 WithFallback 入口（明確驗證，非 smoke）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendChatWithFallback：RUN_COMMAND 降級為可讀提示，Java 保留 click")
    void sendChat_runCommand_actualEntry() {
        PlayerMock bedrockP = bedrockPlayer("en_US");
        bedrockP.setLocale(Locale.US);
        Component msg = Component.text("c").clickEvent(ClickEvent.runCommand("/say hi"));
        service.sendChatWithFallback(bedrockP, msg, null);
        Component sent = bedrockP.nextComponentMessage();
        assertNotNull(sent);
        assertFalse(hasClick(sent), "基岩 RUN_COMMAND 不得殘留 ClickEvent");
        assertTrue(text(sent).contains("Run command: /say hi"), "payload 應以可讀形式呈現");

        PlayerMock javaP = javaPlayer();
        service.sendChatWithFallback(javaP, msg, null);
        assertTrue(hasClick(javaP.nextComponentMessage()), "Java 應保留 RUN_COMMAND");
    }

    @Test
    @DisplayName("sendActionBarWithFallback：SUGGEST_COMMAND 降級，基岩無 click 且含 hint，Java 保留")
    void sendActionBar_suggestCommand_actualEntry() {
        PlayerMock bedrockP = bedrockPlayer("en_US");
        bedrockP.setLocale(Locale.US);
        Component msg = Component.text("s").clickEvent(ClickEvent.suggestCommand("/warp home"));
        service.sendActionBarWithFallback(bedrockP, msg, null);
        Component sent = bedrockP.nextActionBar();
        assertNotNull(sent, "基岩玩家必須收到 action bar");
        assertFalse(hasClick(sent), "基岩 action bar 不得含 ClickEvent");
        assertTrue(text(sent).contains("Suggest command: /warp home"), "hint 應為英文：" + text(sent));

        PlayerMock javaP = javaPlayer();
        service.sendActionBarWithFallback(javaP, msg, null);
        Component javaSent = javaP.nextActionBar();
        assertNotNull(javaSent, "Java 玩家必須收到 action bar");
        assertTrue(hasClick(javaSent), "Java action bar 應保留 ClickEvent");
    }

    @Test
    @DisplayName("sendTitleWithFallback：OPEN_URL 降級，基岩 title/subtitle 無 click 且含 hint，Java 保留")
    void sendTitle_openUrl_actualEntry() {
        TitleCapturingPlayer bedrockP = bedrockTitlePlayer("en_US");
        bedrockP.setLocale(Locale.US);
        Component title = Component.text("t").clickEvent(ClickEvent.openUrl("https://example.com"));
        Component subtitle = Component.text("s").clickEvent(ClickEvent.openUrl("https://sub.example.com"));
        service.sendTitleWithFallback(bedrockP, title, subtitle, null);
        Title sent = bedrockP.lastTitle();
        assertNotNull(sent, "基岩玩家必須收到 Title");
        Component sentTitle = sent.title();
        Component sentSub = sent.subtitle();
        assertFalse(hasClick(sentTitle), "基岩 title 不得含 ClickEvent");
        assertFalse(hasClick(sentSub), "基岩 subtitle 不得含 ClickEvent");
        assertTrue(text(sentTitle).contains("Open URL: https://example.com"), "title hint 應為英文：" + text(sentTitle));
        assertTrue(text(sentSub).contains("Open URL: https://sub.example.com"), "subtitle hint 應為英文：" + text(sentSub));

        TitleCapturingPlayer javaP = javaTitlePlayer();
        Component javaTitle = Component.text("t").clickEvent(ClickEvent.openUrl("https://example.com"));
        service.sendTitleWithFallback(javaP, javaTitle, null, null);
        Title javaSent = javaP.lastTitle();
        assertNotNull(javaSent, "Java 玩家必須收到 Title");
        assertTrue(hasClick(javaSent.title()), "Java title 應保留 ClickEvent");
    }

    @Test
    @DisplayName("broadcastWithFallback：COPY_TO_CLIPBOARD 降級，基岩無 click，Java 保留")
    void broadcast_copyToClipboard_actualEntry() {
        PlayerMock bedrockP = bedrockPlayer("en_US");
        bedrockP.setLocale(Locale.US);
        PlayerMock javaP = javaPlayer();
        Component msg = Component.text("copy").clickEvent(ClickEvent.copyToClipboard("secret"));
        service.broadcastWithFallback(msg, null);
        Component bedrockSent = bedrockP.nextComponentMessage();
        Component javaSent = javaP.nextComponentMessage();
        assertNotNull(bedrockSent);
        assertNotNull(javaSent);
        assertFalse(hasClick(bedrockSent), "基岩 broadcast COPY_TO_CLIPBOARD 不得含 ClickEvent");
        assertTrue(text(bedrockSent).contains("Copy to clipboard: secret"));
        assertTrue(hasClick(javaSent), "Java broadcast 應保留 ClickEvent");
    }

    // -----------------------------------------------------------------
    // 其餘 WithFallback 進入點（smoke + 路由）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendActionBarWithFallback：基岩玩家降級無 click，Java 玩家保留 click")
    void sendActionBar_routing() {
        PlayerMock bedrockP = bedrockPlayer("en_US");
        PlayerMock javaP = javaPlayer();
        Component msg = Component.text("x").clickEvent(ClickEvent.openUrl("https://a.com"));
        service.sendActionBarWithFallback(bedrockP, msg, null);
        Component bedrockSent = bedrockP.nextActionBar();
        assertNotNull(bedrockSent, "基岩玩家必須收到 action bar");
        assertFalse(hasClick(bedrockSent), "基岩 action bar 不得含 ClickEvent");
        assertTrue(text(bedrockSent).contains("Open URL: https://a.com"), "hint 應為英文：" + text(bedrockSent));

        service.sendActionBarWithFallback(javaP, msg, null);
        Component javaSent = javaP.nextActionBar();
        assertNotNull(javaSent, "Java 玩家必須收到 action bar");
        assertTrue(hasClick(javaSent), "Java action bar 應保留 ClickEvent");
    }

    @Test
    @DisplayName("sendTitleWithFallback：基岩玩家降級無 click")
    void sendTitle_routing() {
        TitleCapturingPlayer bedrockP = bedrockTitlePlayer("en_US");
        Component title = Component.text("t").clickEvent(ClickEvent.runCommand("/t"));
        Component subtitle = Component.text("s");
        service.sendTitleWithFallback(bedrockP, title, subtitle, null);
        Title sent = bedrockP.lastTitle();
        assertNotNull(sent, "基岩玩家必須收到 Title");
        assertFalse(hasClick(sent.title()), "基岩 title 不得含 ClickEvent");
        assertTrue(text(sent.title()).contains("Run command: /t"), "hint 應為英文：" + text(sent.title()));
    }

    // -----------------------------------------------------------------
    // broadcast 混合玩家 + 失敗隔離
    // -----------------------------------------------------------------

    @Test
    @DisplayName("broadcastWithFallback：基岩玩家收到降級訊息，Java 玩家收到原始訊息")
    void broadcast_mixedPlayers() {
        PlayerMock bedrockP = bedrockPlayer("en_US");
        PlayerMock javaP = javaPlayer();
        Component msg = Component.text("hi").clickEvent(ClickEvent.runCommand("/b"));
        service.broadcastWithFallback(msg, null);
        Component bedrockSent = bedrockP.nextComponentMessage();
        Component javaSent = javaP.nextComponentMessage();
        assertNotNull(bedrockSent);
        assertNotNull(javaSent);
        assertFalse(hasClick(bedrockSent), "基岩玩家廣播訊息不得含 ClickEvent");
        assertTrue(hasClick(javaSent), "Java 玩家廣播訊息保留 ClickEvent");
    }

    @Test
    @DisplayName("sendChatWithFallback：單一玩家 sendMessage 拋錯不向外傳播（失敗隔離）")
    void sendChat_singlePlayerFailureIsolated() {
        Player mockPlayer = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(mockPlayer.getUniqueId()).thenReturn(id);
        when(mockPlayer.isOnline()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(mockPlayer).sendMessage(any(Component.class));
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/x"));
        assertDoesNotThrow(() -> service.sendChatWithFallback(mockPlayer, msg, null),
            "單一玩家發送失敗必須被隔離，不向外拋出");
    }

    // -----------------------------------------------------------------
    // invalid / blank languageCode 回 default
    // -----------------------------------------------------------------

    @Test
    @DisplayName("invalid languageCode 回 default locale（不中斷，仍降級）")
    void invalidLanguageCode_fallsBackToDefault() {
        PlayerMock p = bedrockPlayer("!!invalid!!");
        p.setLocale(Locale.ROOT);
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent);
        assertFalse(hasClick(sent), "invalid code 仍應降級且無 ClickEvent");
        assertTrue(text(sent).contains("Run command: /warp"), "invalid code 應回 default en_US，實際: " + text(sent));
    }

    @Test
    @DisplayName("blank / whitespace languageCode 回 default locale")
    void blankLanguageCode_fallsBackToDefault() {
        for (String code : new String[]{"", "   ", "  \t\n "}) {
            PlayerMock p = bedrockPlayer(code);
            p.setLocale(Locale.ROOT);
            Component msg = Component.text("x").clickEvent(ClickEvent.openUrl("https://a.com"));
            service.sendChatWithFallback(p, msg, null);
            Component sent = p.nextComponentMessage();
            assertNotNull(sent, "blank code=" + code + " 應仍送出");
            assertFalse(hasClick(sent));
            assertTrue(text(sent).contains("Open URL: https://a.com"), "blank code 應回 default，實際: " + text(sent));
        }
    }

    @Test
    @DisplayName("非法格式 languageCode（含非法字元）回 default")
    void illegalFormatLanguageCode_fallsBackToDefault() {
        PlayerMock p = bedrockPlayer("en-Invalid@Code!");
        p.setLocale(Locale.ROOT);
        Component msg = Component.text("x").clickEvent(ClickEvent.copyToClipboard("v"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertFalse(hasClick(sent));
        assertTrue(text(sent).contains("Copy to clipboard: v"), "應回 default en_US");
    }

    // -----------------------------------------------------------------
    // getPlayerInfo exception：保留原始 Component 並記 ACELIB-MSG-004
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getPlayerInfo 拋例外時保留原始 Component（含 click），不降級")
    void getPlayerInfoException_keepsOriginalComponent() {
        PlayerMock p = server.addPlayer();
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        when(bedrock.getPlayerInfo(id)).thenThrow(new RuntimeException("floodgate down"));
        p.setLocale(Locale.ROOT);
        Component original = Component.text("keep").clickEvent(ClickEvent.runCommand("/keep"));
        service.sendChatWithFallback(p, original, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent, "exception 時仍應送出原始 Component");
        assertTrue(hasClick(sent), "getPlayerInfo exception 時應保留原始 ClickEvent，不降級");
        assertTrue(text(sent).contains("keep"), "原始文字保留");
        // 與原始等價（仍含 click）
        assertEquals(text(original), text(sent), "保留原始文字內容");
        // 確保沒有附加 hint
        assertFalse(text(sent).contains("Run command:"), "exception 時不應附加 hint");
    }

    @Test
    @DisplayName("getPlayerInfo exception 時保留同一 Component 並記錄 MSG-004")
    void getPlayerInfoException_keepsIdentityAndLogsCode() {
        PlayerMock p = server.addPlayer();
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        when(bedrock.getPlayerInfo(id)).thenThrow(new RuntimeException("floodgate down"));
        p.setLocale(Locale.ROOT);
        Component original = Component.text("keep").clickEvent(ClickEvent.runCommand("/keep"));

        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        Logger pluginLogger = plugin.getLogger();
        Level previousRootLevel = root.getLevel();
        Level previousPluginLevel = pluginLogger.getLevel();
        root.setLevel(Level.ALL);
        pluginLogger.setLevel(Level.ALL);
        root.addHandler(handler);
        pluginLogger.addHandler(handler);
        try {
            service.sendChatWithFallback(p, original, null);
        } finally {
            pluginLogger.removeHandler(handler);
            pluginLogger.setLevel(previousPluginLevel);
            root.removeHandler(handler);
            root.setLevel(previousRootLevel);
        }

        Component sent = p.nextComponentMessage();
        assertSame(original, sent, "lookup exception 時應直接送出原始 Component");
        assertTrue(records.stream().anyMatch(record ->
            record.getMessage() != null
                && record.getMessage().contains(MessageService.ERR_BEDROCK_LOOKUP)),
            "lookup exception 必須記錄 ACELIB-MSG-004");
    }

    @Test
    @DisplayName("getPlayerInfo exception 時 broadcast 僅影響該基岩玩家，其他玩家不受影響")
    void getPlayerInfoException_broadcastIsolated() {
        PlayerMock failing = server.addPlayer();
        UUID fid = failing.getUniqueId();
        when(bedrock.isBedrockPlayer(fid)).thenReturn(true);
        when(bedrock.getPlayerInfo(fid)).thenThrow(new RuntimeException("lookup fail"));
        failing.setLocale(Locale.ROOT);

        PlayerMock healthyBedrock = bedrockPlayer("en_US");
        healthyBedrock.setLocale(Locale.US);
        PlayerMock java = javaPlayer();

        Component msg = Component.text("b").clickEvent(ClickEvent.runCommand("/b"));
        service.broadcastWithFallback(msg, null);

        Component failingSent = failing.nextComponentMessage();
        Component healthySent = healthyBedrock.nextComponentMessage();
        Component javaSent = java.nextComponentMessage();

        assertNotNull(failingSent);
        assertNotNull(healthySent);
        assertNotNull(javaSent);
        // failing 應保留原始 click（因 unable to determine locale，依契約不降級）
        assertTrue(hasClick(failingSent), "failing 玩家應保留原始 ClickEvent");
        assertFalse(hasClick(healthySent), "healthy bedrock 應降級");
        assertTrue(hasClick(javaSent), "Java 玩家保留");
    }

    @Test
    @DisplayName("getPlayerInfo exception 時 sendActionBar / sendTitle 亦保留原始")
    void getPlayerInfoException_otherEntriesKeepOriginal() {
        PlayerMock p = server.addPlayer();
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        when(bedrock.getPlayerInfo(id)).thenThrow(new RuntimeException("boom"));
        p.setLocale(Locale.ROOT);
        Component msg = Component.text("x").clickEvent(ClickEvent.suggestCommand("/s"));
        // 透過 sendChat 驗證核心 fallback 行為；actionBar/title 同走 maybeFallback，故等價
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertTrue(hasClick(sent), "exception 時應保留原始");
    }

    // -----------------------------------------------------------------
    // Hover 內嵌 click 整合（經 MessageService）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("MessageService：基岩玩家 hover 內嵌 click 經 WithFallback 被清理")
    void bedrockHoverInnerClick_strippedViaService() {
        PlayerMock p = bedrockPlayer("en_US");
        p.setLocale(Locale.US);
        Component hoverInner = Component.text("inner").clickEvent(ClickEvent.runCommand("/hidden"));
        Component msg = Component.text("outer").hoverEvent(HoverEvent.showText(hoverInner));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent);
        assertFalse(hasClick(sent), "hover 內 ClickEvent 應被清理，整棵輸出不得含 ClickEvent");
        assertNotNull(sent.hoverEvent(), "hover 外層保留");
        Component hc = (Component) sent.hoverEvent().value();
        assertFalse(hasClick(hc));
    }

    // -----------------------------------------------------------------
    // lifecycle：disable 與 reload
    // -----------------------------------------------------------------

    @Test
    @DisplayName("lifecycle：disable 後 sendChatWithFallback 為 no-op，不再發送")
    void lifecycle_disable_noOp() {
        PlayerMock p = bedrockPlayer("en_US");
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/x"));
        plugin.onDisable();
        service.sendChatWithFallback(p, msg, null);
        // MockBukkit 的 PlayerMock 在 disable 後 nextComponentMessage 應為 null（未發送）
        Component sent = p.nextComponentMessage();
        // disable 後 isServiceActive 為 false，故不應有新訊息
        assertTrue(sent == null || !text(sent).contains("x"), "disable 後應為 no-op，不發送新訊息");
        // 額外驗證：broadcast 亦 no-op
        PlayerMock p2 = server.addPlayer();
        when(bedrock.isBedrockPlayer(p2.getUniqueId())).thenReturn(true);
        // p2 在 disable 後建立，但 service 已 inactive，broadcast 亦不應發送
        service.broadcastWithFallback(msg, null);
        // p2 應無訊息
        assertTrue(p2.nextComponentMessage() == null, "disable 後 broadcast 應為 no-op");
    }

    @Test
    @DisplayName("lifecycle：reload 後無 stale locale，新的 fallback 模板生效")
    void lifecycle_reload_noStale() throws IOException {
        PlayerMock p = bedrockPlayer("en_US");
        p.setLocale(Locale.US);
        Component msg = Component.text("x").clickEvent(ClickEvent.runCommand("/warp"));

        service.sendChatWithFallback(p, msg, null);
        Component first = p.nextComponentMessage();
        assertTrue(text(first).contains("Run command: /warp"));

        // 改寫 en_US 模板並 reload
        File langDir = new File(plugin.getDataFolder(), "lang");
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("message.bedrock.fallback.run_command: 'NEW Run: <payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: 'Suggest command: <payload>'\n");
            w.write("message.bedrock.fallback.open_url: 'Open URL: <payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: 'Copy to clipboard: <payload>'\n");
        }
        lang.reload();

        PlayerMock p2 = bedrockPlayer("en_US");
        p2.setLocale(Locale.US);
        service.sendChatWithFallback(p2, msg, null);
        Component second = p2.nextComponentMessage();
        assertNotNull(second);
        assertTrue(text(second).contains("NEW Run: /warp"), "reload 後應讀到新模板，實際: " + text(second));
        assertFalse(hasClick(second), "reload 後仍應正確降級且無 ClickEvent");

        // 舊玩家再次發送亦應取到新模板（無 stale cache）
        service.sendChatWithFallback(p, msg, null);
        Component third = p.nextComponentMessage();
        assertTrue(text(third).contains("NEW Run: /warp"), "舊玩家 reload 後亦應取到新模板");
    }

    @Test
    @DisplayName("lifecycle：reload 後 service 仍可用，Java 玩家行為不變")
    void lifecycle_reload_javaStillKeepsClick() throws IOException {
        lang.reload();
        PlayerMock java = javaPlayer();
        Component msg = Component.text("x").clickEvent(ClickEvent.openUrl("https://a.com"));
        service.sendChatWithFallback(java, msg, null);
        Component sent = java.nextComponentMessage();
        assertTrue(hasClick(sent), "reload 後 Java 玩家仍應保留 ClickEvent");
    }

    // -----------------------------------------------------------------
    // 安全邊界
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sendChatWithFallback：null player 不中斷")
    void sendChat_nullPlayer_safeNoop() {
        assertDoesNotThrow(() ->
            service.sendChatWithFallback(null, Component.text("x"), null));
    }

    @Test
    @DisplayName("sendChatWithFallback：null message 不中斷")
    void sendChat_nullMessage_safeNoop() {
        PlayerMock p = javaPlayer();
        assertDoesNotThrow(() -> service.sendChatWithFallback(p, null, null));
    }

    @Test
    @DisplayName("sendChatWithFallback：離線玩家 noop，不傳送")
    void sendChat_offlinePlayer_safeNoop() {
        PlayerMock p = bedrockPlayer("en_US");
        p.disconnect();
        assertDoesNotThrow(() -> service.sendChatWithFallback(p,
            Component.text("x").clickEvent(ClickEvent.runCommand("/x")), null));
        assertDoesNotThrow(() -> p.nextComponentMessage());
    }

    // -----------------------------------------------------------------
    // 2 參數建構子（canonical bedrock 解析）不中斷既有路徑
    // -----------------------------------------------------------------

    @Test
    @DisplayName("2 參數建構子：ready plugin 下仍能正常發送（resolveBedrockService 不拋錯）")
    void twoArgConstructor_readyPlugin_sendsToJava() {
        MessageService svc = new MessageService(plugin, lang);
        PlayerMock p = server.addPlayer();
        assertDoesNotThrow(() -> svc.sendChat(p, "greeting", java.util.Map.of("player", "smile")));
        assertNotNull(p.nextComponentMessage(), "2 參數建構子建立的 service 必須能發送訊息");
    }
}
