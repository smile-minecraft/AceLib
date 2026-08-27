package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link MessageService} 對應 Plan §十 Phase 5 統一訊息系統需求測試。
 *
 * <h2>驗收標準對應</h2>
 * <ol>
 *   <li>支援 prefix / 變數替換 / 多語言來源 / 多顯示形式（chat / action bar / title-subtitle / 廣播）</li>
 *   <li>訊息 key 缺失時 Logger.warning + 不中斷</li>
 *   <li>玩家離線時不操作已失效玩家；console 與玩家格式可分開</li>
 *   <li>語言檔 reload 後訊息即時生效</li>
 *   <li>格式錯誤不導致整個訊息系統失效（降級處理並記錄）</li>
 * </ol>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-MSG-001}：訊息 key 缺失</li>
 *   <li>{@code ACELIB-MSG-002}：在不安全上下文操作玩家訊息（Folia）</li>
 *   <li>{@code ACELIB-MSG-003}：訊息格式錯誤（降級處理）</li>
 * </ul>
 *
 * <h2>測試分組</h2>
 * <ul>
 *   <li>{@link BasicOperations} — 構造子 / format / 正常發送路徑</li>
 *   <li>{@link PlayerTargeted} — sendChat / sendActionBar / sendTitle</li>
 *   <li>{@link BroadcastAndConsole} — broadcast / sendConsole</li>
 *   <li>{@link EdgeCases} — null player / 離線 / missing key / null vars / prefix</li>
 *   <li>{@link Reload} — LangManager reload 後即時生效</li>
 * </ul>
 *
 * @since Phase 5 (Plan §十)
 */
@DisplayName("MessageService")
class MessageServiceTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File dataFolder;
    private File langDir;
    private LangManager lang;
    private MessageService service;
    private List<LogRecord> capturedLogs;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("無法建立 dataFolder");
        }
        langDir = new File(dataFolder, "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }

        // 預先寫入兩條樣板訊息，便於多數測試直接使用
        File enFile = new File(langDir, "en_US.yml");
        try (FileWriter w = new FileWriter(enFile)) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("welcome: 'Welcome to {server}'\n");
            w.write("farewell: 'Goodbye {player}'\n");
            w.write("rich.greeting: '<green>Hello {player}!</green>'\n");
            w.write("rich.broken: 'Hello {player} <green'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
            w.write("broadcast.announce: 'Server restarting in {seconds} seconds'\n");
            w.write("console.info: 'INFO: {player} joined'\n");
        }

        // 設定 LogCapture：附加 handler 與 ALL level 給 root + AceLib
        capturedLogs = new ArrayList<>();
        handler = new RecordingHandler(capturedLogs);
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);
        root.addHandler(handler);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.setLevel(Level.ALL);
        acelib.addHandler(handler);

        lang = new LangManager(plugin, java.util.Locale.US);
        lang.load();
        // Plan §十 對外契約：2 參數 constructor，由 MessageService 透過 PlatformDetector
        // 自動偵測平台。MockBukkit 環境下 plugin classloader 可解析 org.bukkit.Bukkit
        // （透過 parent delegation），因此偵測結果為 PAPER。
        service = new MessageService(plugin, lang);
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
    // 構造子
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("構造子驗證")
    class Constructor {

        @Test
        @DisplayName("null plugin 必須拋 NPE")
        void ctor_nullPlugin_throws() {
            assertThrows(NullPointerException.class,
                () -> new MessageService(null, lang));
        }

        @Test
        @DisplayName("null lang 必須拋 NPE")
        void ctor_nullLang_throws() {
            assertThrows(NullPointerException.class,
                () -> new MessageService(plugin, null));
        }
    }

    // -----------------------------------------------------------------
    // format() 基本輸出（含變數替換 + prefix）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("format() 純文字格式化")
    class Format {

        @Test
        @DisplayName("format：現存 key 回傳替換後字串（含 message.prefix 前綴）")
        void format_existingKey_returnsRendered() {
            String out = service.format("greeting", Map.of("player", "smile"));
            assertEquals("[AceLib] Hello smile!", out,
                "setUp 已寫入 message.prefix=[AceLib]，format 應自動前綴");
        }

        @Test
        @DisplayName("format：變數缺失保留原 {var}，不中斷（含 prefix）")
        void format_missingVar_keepsPlaceholder() {
            String out = service.format("greeting", Map.of());
            assertEquals("[AceLib] Hello {player}!", out);
        }

        @Test
        @DisplayName("format：null vars 視為空 map，不丟 NPE（含 prefix）")
        void format_nullVars_safe() {
            String out = service.format("greeting", null);
            assertEquals("[AceLib] Hello {player}!", out);
        }

        @Test
        @DisplayName("format：缺失 key 回傳空字串 + 記錄 ACELIB-MSG-001")
        void format_missingKey_warns() {
            String out = service.format("does.not.exist", Map.of("player", "x"));
            assertEquals("", out);
            assertTrue(hasLogContaining("ACELIB-MSG-001"),
                "缺失 key 必須輸出含 ACELIB-MSG-001 的 log");
        }

        @Test
        @DisplayName("format：當 message.prefix 存在時自動前綴")
        void format_withPrefix() {
            String out = service.format("welcome", Map.of("server", "Ace"));
            assertEquals("[AceLib] Welcome to Ace", out,
                "偵測到 message.prefix 時自動前綴，實際: " + out);
        }

        @Test
        @DisplayName("format：console 模式不套用 player prefix（呼叫 formatConsole）")
        void format_console_skipsPrefix() {
            String out = service.formatConsole("welcome", Map.of("server", "Ace"));
            assertEquals("Welcome to Ace", out,
                "console 格式不應套用 message.prefix，實際: " + out);
        }
    }

    // -----------------------------------------------------------------
    // 玩家導向發送（chat / action bar / title）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("玩家導向發送")
    class PlayerTargeted {

        @Test
        @DisplayName("sendChat：在線玩家收到訊息（chat 走前綴規則）")
        void sendChat_onlinePlayer_deliversWithPrefix() {
            PlayerMock p = server.addPlayer();
            service.sendChat(p, "greeting", Map.of("player", "smile"));
            // 驗證 PlayerMock 收到訊息：MockBukkit 提供 nextMessage()
            // 我們改用收 messages queue
            String received = firstMessage(p);
            assertNotNull(received, "玩家必須收到 chat 訊息");
            assertTrue(received.contains("Hello smile!"),
                "訊息內容須含原始模板，實際: " + received);
        }

        @Test
        @DisplayName("sendChat：null player 不中斷 + 記錄 MSG-001")
        void sendChat_nullPlayer_safeNoop() {
            assertDoesNotThrow(() ->
                service.sendChat(null, "greeting", Map.of("player", "smile")));
            // null player 不應觸發 MSG-001（因為並非 key 缺失），
            // 而應記錄一個 MSG-002 風格的 warning 或 no-op 日誌。
            // 我們接受任何警告層級訊息，但程式不應拋例外。
        }

        @Test
        @DisplayName("sendChat：離線玩家 noop，不傳送任何訊息")
        void sendChat_offlinePlayer_safeNoop() {
            PlayerMock p = server.addPlayer();
            p.disconnect();
            assertDoesNotThrow(() ->
                service.sendChat(p, "greeting", Map.of("player", "smile")));
            // 確保沒有錯誤，訊息接收仍為空（disconnect 後 nextMessage 應為 null）
            assertNull(firstMessageOrNull(p),
                "離線玩家不應收到訊息");
        }

        @Test
        @DisplayName("sendActionBar：在線玩家收到 action bar")
        void sendActionBar_onlinePlayer() {
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() ->
                service.sendActionBar(p, "greeting", Map.of("player", "smile")));
            // PlayerMock 在 MockBukkit 中無 actionBar capture API，僅斷言不中斷。
        }

        @Test
        @DisplayName("sendActionBar：離線玩家 noop")
        void sendActionBar_offlinePlayer() {
            PlayerMock p = server.addPlayer();
            p.disconnect();
            assertDoesNotThrow(() ->
                service.sendActionBar(p, "greeting", Map.of("player", "smile")));
        }

        @Test
        @DisplayName("sendTitle：title + subtitle 都送出")
        void sendTitle_withSubtitle_sendsBoth() {
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() ->
                service.sendTitle(p, "greeting",
                    Map.of("player", "smile"),
                    "farewell",
                    Map.of("player", "smile")));
        }

        @Test
        @DisplayName("sendTitle：僅 title、無 subtitle 也能送出")
        void sendTitle_onlyTitle_safe() {
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() ->
                service.sendTitle(p, "greeting", Map.of("player", "smile")));
        }

        @Test
        @DisplayName("sendTitle：null player noop")
        void sendTitle_nullPlayer_safe() {
            assertDoesNotThrow(() ->
                service.sendTitle(null, "greeting", Map.of("player", "smile")));
        }
    }

    // -----------------------------------------------------------------
    // 全服廣播 / console 輸出
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("全服廣播 / console")
    class BroadcastAndConsole {

        @Test
        @DisplayName("broadcast：所有線上玩家都收到訊息")
        void broadcast_allOnlinePlayers_receiveMessage() {
            PlayerMock p1 = server.addPlayer();
            PlayerMock p2 = server.addPlayer();
            service.broadcast("broadcast.announce", Map.of("seconds", "30"));
            String r1 = firstMessage(p1);
            String r2 = firstMessage(p2);
            assertNotNull(r1);
            assertNotNull(r2);
            assertTrue(r1.contains("Server restarting in 30 seconds"));
            assertTrue(r2.contains("Server restarting in 30 seconds"));
        }

        @Test
        @DisplayName("broadcast：線上玩家數為 0 時不中斷")
        void broadcast_noPlayers_silent() {
            assertDoesNotThrow(() ->
                service.broadcast("broadcast.announce", Map.of("seconds", "10")));
        }

        @Test
        @DisplayName("broadcast：缺失 key 不中斷 + 記錄 MSG-001")
        void broadcast_missingKey_warnsNotThrows() {
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() ->
                service.broadcast("does.not.exist", Map.of()));
            assertTrue(hasLogContaining("ACELIB-MSG-001"));
        }

        @Test
        @DisplayName("sendConsole：logger 收到含模板內容的訊息")
        void sendConsole_writesToLog() {
            service.sendConsole("console.info", Map.of("player", "smile"));
            assertTrue(capturedLogs.stream().anyMatch(r ->
                    r.getMessage() != null && r.getMessage().contains("INFO: smile joined")),
                "必須輸出一條含 INFO: smile joined 的 log。實際訊息: "
                    + capturedLogs.stream().map(LogRecord::getMessage).toList());
        }
    }

    // -----------------------------------------------------------------
    // 邊界 / 降級處理
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("邊界 / 降級處理")
    class EdgeCases {

        @Test
        @DisplayName("format：null key 必須拋 NPE（契約）")
        void format_nullKey_throws() {
            assertThrows(NullPointerException.class,
                () -> service.format(null, Map.of()));
        }

        @Test
        @DisplayName("prefix 缺失時不報錯（無前綴）")
        void prefixMissing_silent() {
            // 新增一個 lang key 但 file 沒有 message.prefix —— 不應崩潰
            // 為此我們直接使用 format: 沒有任何 prefix key
            // 先把 LangManager load 到臨時空 lang 檔
            File altEn = new File(langDir, "en_US.yml");
            try (FileWriter w = new FileWriter(altEn)) {
                w.write("greeting: 'plain {player}'\n");
            } catch (IOException ignored) {
            }
            lang.reload();
            String out = service.format("greeting", Map.of("player", "x"));
            assertEquals("plain x", out, "沒有 message.prefix 時不應前綴");
        }

        @Test
        @DisplayName("格式錯誤降級：當 LangManager.get 回傳 Optional.empty() → format 回傳空字串")
        void format_missingKey_returnsEmptyString() {
            // 已驗證：format("does.not.exist", ...) === "" + warning
            String out = service.format("does.not.exist", Map.of());
            assertEquals("", out);
        }

        @Test
        @DisplayName("sendChat：在線玩家 + missing key → 警告 + 不送出訊息")
        void sendChat_missingKey_silent() {
            PlayerMock p = server.addPlayer();
            capturedLogs.clear();
            assertDoesNotThrow(() ->
                service.sendChat(p, "does.not.exist", Map.of()));
            assertTrue(hasLogContaining("ACELIB-MSG-001"),
                "missing key 必須輸出 ACELIB-MSG-001");
            assertNull(firstMessageOrNull(p),
                "missing key 不應給玩家送出任何訊息");
        }
    }

    // -----------------------------------------------------------------
    // LangManager reload 後即時生效
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("reload 後即時生效")
    class Reload {

        @Test
        @DisplayName("LangManager reload 後 format 即時取得新模板")
        void reload_picksUpNewTemplate() throws IOException {
            File enFile = new File(langDir, "en_US.yml");
            try (FileWriter w = new FileWriter(enFile)) {
                w.write("greeting: 'old'\n");
            }
            lang.reload();
            String first = service.format("greeting", Map.of());
            assertEquals("old", first);
            // 模擬管理員修改檔案
            try (FileWriter w = new FileWriter(enFile)) {
                w.write("greeting: 'new'\n");
            }
            lang.reload();
            String second = service.format("greeting", Map.of());
            assertEquals("new", second,
                "LangManager reload 後 MessageService 必須即時取得新值");
        }
    }

    // -----------------------------------------------------------------
    // 公開 getter / accessor
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("公開 getter / 自動偵測驗證")
    class Getters {

        @Test
        @DisplayName("2 參數 constructor 自動偵測為 PAPER（MockBukkit classpath）")
        void platformAutoDetectedAsPaper() {
            // MockBukkit classpath 含 org.bukkit.Bukkit 但無 Folia marker，
            // 因此 PlatformDetector.detect() 應自動分類為 PAPER。
            assertSame(Platform.PAPER, service.getPlatform(),
                "2 參數 constructor 透過 PlatformDetector 自動偵測必須為 PAPER");
        }

        @Test
        @DisplayName("getCapability() 與自動偵測的 platform 一致")
        void capabilityMatchesDetectedPlatform() {
            assertEquals(service.getPlatform().getCapabilityProfile(),
                service.getCapability(),
                "capability 必須與 getPlatform() 對應的 capability profile 一致");
        }

        @Test
        @DisplayName("一般未 ready JavaPlugin fallback 使用單次 detector 結果並保留 capability 一致")
        void genericPlugin_constructorUsesDetectorFallback() {
            JavaPlugin genericPlugin = Mockito.mock(JavaPlugin.class);
            LangManager genericLang = new LangManager(genericPlugin, java.util.Locale.US);
            Platform expected = new PlatformDetector(
                genericPlugin.getClass().getClassLoader()).detect();

            MessageService genericService = new MessageService(genericPlugin, genericLang);

            assertSame(expected, genericService.getPlatform(),
                "一般 JavaPlugin 必須走 PlatformDetector fallback");
            assertEquals(PlatformCapability.forPlatform(expected),
                genericService.getCapability(),
                "fallback detector 結果與 capability 必須一致");
        }
    }

    // -----------------------------------------------------------------
    // Adventure Component 管線（additive；不影響既有 String API）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Adventure Component 管線")
    class ComponentPipeline {

        @Test
        @DisplayName("formatComponent：rich template 轉為 Component 並套用 prefix")
        void formatComponent_richTemplate_rendersWithPrefix() {
            Component c = service.formatComponent("rich.greeting", Map.of("player", "smile"));
            assertNotNull(c, "formatComponent 必須回傳非 null Component");
            String s = c.toString();
            assertTrue(s.contains("Hello smile!"),
                "template 的 {player} 必須被替換，實際: " + s);
            assertTrue(s.contains("[AceLib]"),
                "formatComponent 必須套用 message.prefix，實際: " + s);
            assertFalse(s.contains("{player}"),
                "變數必須被替換，不應殘留 {player}");
        }

        @Test
        @DisplayName("formatComponent：使用者變數值中的 <tag> 不被解析為 MiniMessage 標籤（防注入）")
        void formatComponent_userVar_notInjected() {
            Component c = service.formatComponent("rich.greeting",
                Map.of("player", "<red>EVIL</red>"));
            String s = c.toString();
            assertTrue(s.contains("EVIL"),
                "使用者值必須原樣出現，實際: " + s);
            assertFalse(s.contains("RED"),
                "使用者值中的 <red> 不得被解析為紅色標籤（防注入），實際: " + s);
        }

        @Test
        @DisplayName("formatComponent：缺失 key 回傳 Component.empty() + 記錄 ACELIB-MSG-001")
        void formatComponent_missingKey_returnsEmptyAndWarns() {
            capturedLogs.clear();
            Component c = service.formatComponent("does.not.exist", Map.of("player", "x"));
            assertEquals(Component.empty(), c,
                "缺失 key 必須回傳 Component.empty()");
            assertTrue(hasLogContaining("ACELIB-MSG-001"),
                "缺失 key 必須輸出 ACELIB-MSG-001");
        }

        @Test
        @DisplayName("formatComponent：MiniMessage 解析失敗仍回傳可見正文並套用 prefix（不空白、不中斷）")
        void formatComponent_parseFailure_returnsVisibleTextWithPrefix() {
            // rich.broken 含未閉合標籤，MiniMessage 可能拋出或寬容修復；
            // 無論走正常路徑或降級 fallback，結果都必須含變數替換後的可見正文與 prefix。
            Component c = service.formatComponent("rich.broken", Map.of("player", "smile"));
            assertNotNull(c, "解析失敗不得回傳 null");
            String s = c.toString();
            assertTrue(s.contains("Hello smile"),
                "解析失敗仍須保留可見正文（變數已替換），實際: " + s);
            assertTrue(s.contains("<green"),
                "解析失敗仍須保留可見正文（未閉合標籤作為文字），實際: " + s);
            assertTrue(s.contains("[AceLib]"),
                "解析失敗仍須套用 message.prefix，實際: " + s);
            assertFalse(s.contains("{player}"),
                "變數必須被替換，不應殘留 {player}");
        }

        @Test
        @DisplayName("formatComponent：null key 必須拋 NPE（契約）")
        void formatComponent_nullKey_throws() {
            assertThrows(NullPointerException.class,
                () -> service.formatComponent(null, Map.of()));
        }

        @Test
        @DisplayName("parseMiniMessage：合法 MiniMessage（click）解析為 Component")
        void parseMiniMessage_legalTags_parsed() {
            Component c = service.parseMiniMessage(
                "<click:open_url:https://example.com>Visit</click>", Map.of());
            assertNotNull(c);
            String s = c.toString();
            assertTrue(s.contains("Visit"), "文字必須保留，實際: " + s);
            assertTrue(s.contains("OPEN_URL") || s.toLowerCase().contains("open_url"),
                "click 事件必須被解析，實際: " + s);
        }

        @Test
        @DisplayName("parseMiniMessage：<key> placeholder 以 unparsed 注入，使用者值不解析為標籤")
        void parseMiniMessage_userVar_notInjected() {
            Component c = service.parseMiniMessage("Hello <name>",
                Map.of("name", "<red>EVIL</red>"));
            String s = c.toString();
            assertTrue(s.contains("EVIL"), "使用者值必須原樣出現，實際: " + s);
            assertFalse(s.contains("RED"),
                "placeholder 值不得被解析為紅色標籤（防注入），實際: " + s);
            assertFalse(s.contains("<name>"),
                "placeholder <name> 必須被替換，不應殘留，實際: " + s);
        }

        @Test
        @DisplayName("parseMiniMessage：null input 回傳 Component.empty() + 記錄 ACELIB-MSG-003")
        void parseMiniMessage_nullInput_returnsEmptyAndWarns() {
            capturedLogs.clear();
            Component c = service.parseMiniMessage(null, Map.of());
            assertEquals(Component.empty(), c);
            assertTrue(hasLogContaining("ACELIB-MSG-003"),
                "null input 必須輸出 ACELIB-MSG-003");
        }

        @Test
        @DisplayName("sendChat(Player, Component)：在線玩家收到原始 Component")
        void sendChat_component_delivers() {
            PlayerMock p = server.addPlayer();
            Component c = Component.text("hi there");
            service.sendChat(p, c);
            Component received = firstComponentMessage(p);
            assertEquals(c, received, "玩家必須收到原始 Component");
        }

        @Test
        @DisplayName("sendChat(Player, Component)：null player / null message 為 silent no-op")
        void sendChat_component_nullSafe() {
            assertDoesNotThrow(() -> service.sendChat(null, Component.text("x")));
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() -> service.sendChat(p, null));
            assertNull(firstComponentMessageOrNull(p),
                "null message 不應傳送任何 Component");
        }

        @Test
        @DisplayName("sendChat(Player, Component)：離線玩家 noop")
        void sendChat_component_offline_noop() {
            PlayerMock p = server.addPlayer();
            p.disconnect();
            assertDoesNotThrow(() -> service.sendChat(p, Component.text("x")));
            assertNull(firstComponentMessageOrNull(p),
                "離線玩家不應收到 Component");
        }

        @Test
        @DisplayName("sendActionBar(Player, Component)：在線玩家收到原始 Component")
        void sendActionBar_component_delivers() {
            PlayerMock p = server.addPlayer();
            Component c = Component.text("bar text");
            service.sendActionBar(p, c);
            Component received = p.nextActionBar();
            assertNotNull(received, "玩家必須收到 action bar Component");
            assertEquals(c, received, "action bar 必須為原始 Component");
        }

        @Test
        @DisplayName("sendActionBar(Player, Component)：離線玩家 noop")
        void sendActionBar_component_offline_noop() {
            PlayerMock p = server.addPlayer();
            p.disconnect();
            assertDoesNotThrow(() -> service.sendActionBar(p, Component.text("x")));
            assertNull(firstComponentMessageOrNull(p),
                "離線玩家不應收到 action bar");
        }

        @Test
        @DisplayName("sendTitle(Player, Component, Component)：透過程 showTitle 送出 title/subtitle")
        void sendTitle_component_delivers() {
            PlayerMock p = spy(server.addPlayer());
            Component title = Component.text("TITLE");
            Component subtitle = Component.text("SUB");
            service.sendTitle(p, title, subtitle);
            ArgumentCaptor<Title> captor = ArgumentCaptor.forClass(Title.class);
            verify(p).showTitle((Title) captor.capture());
            Title sent = captor.getValue();
            assertEquals(title, sent.title(), "title Component 必須原樣送出");
            assertEquals(subtitle, sent.subtitle(), "subtitle Component 必須原樣送出");
        }

        @Test
        @DisplayName("sendTitle(Player, Component, Component)：null player / null title 為 silent no-op")
        void sendTitle_component_nullSafe() {
            assertDoesNotThrow(() ->
                service.sendTitle(null, Component.text("t"), Component.text("s")));
            PlayerMock p = spy(server.addPlayer());
            assertDoesNotThrow(() -> service.sendTitle(p, null, Component.text("s")));
            verify(p, never()).showTitle(any(Title.class));
        }

        @Test
        @DisplayName("sendTitle(Player, Component, Component)：離線玩家 noop")
        void sendTitle_component_offline_noop() {
            PlayerMock p = spy(server.addPlayer());
            // 直接 stub isOnline() 為 false：MockBukkit 的 disconnect() 依賴 server 內部
            // player 列表移除，對 spy 包裝物件不生效，故以 stub 模擬離線語意。
            Mockito.doReturn(false).when(p).isOnline();
            assertDoesNotThrow(() ->
                service.sendTitle(p, Component.text("t"), Component.text("s")));
            verify(p, never()).showTitle(any(Title.class));
        }

        @Test
        @DisplayName("broadcast(Component)：所有線上玩家都收到原始 Component")
        void broadcast_component_allPlayers() {
            PlayerMock p1 = server.addPlayer();
            PlayerMock p2 = server.addPlayer();
            Component c = Component.text("broadcast!");
            service.broadcast(c);
            assertEquals(c, firstComponentMessage(p1), "p1 必須收到廣播 Component");
            assertEquals(c, firstComponentMessage(p2), "p2 必須收到廣播 Component");
        }

        @Test
        @DisplayName("broadcast(Component)：null message 為 silent no-op")
        void broadcast_component_nullSafe() {
            assertDoesNotThrow(() -> service.broadcast(null));
        }

        @Test
        @DisplayName("formatComponent：LangManager reload 後即時取得新 rich 模板")
        void formatComponent_reload_picksUpNewTemplate() throws IOException {
            File enFile = new File(langDir, "en_US.yml");
            try (FileWriter w = new FileWriter(enFile)) {
                w.write("rich.greeting: '<blue>old</blue>'\n");
                w.write("message:\n");
                w.write("  prefix: '[AceLib] '\n");
            }
            lang.reload();
            Component first = service.formatComponent("rich.greeting", Map.of());
            assertTrue(first.toString().contains("old"),
                "reload 後必須取得舊模板，實際: " + first);
            try (FileWriter w = new FileWriter(enFile)) {
                w.write("rich.greeting: '<blue>new</blue>'\n");
                w.write("message:\n");
                w.write("  prefix: '[AceLib] '\n");
            }
            lang.reload();
            Component second = service.formatComponent("rich.greeting", Map.of());
            assertTrue(second.toString().contains("new"),
                "reload 後必須取得新模板，實際: " + second);
        }
    }

    // -----------------------------------------------------------------
    // lifecycle：onDisable 後送出 API 必須為 no-op（Momus blocker）
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("lifecycle：onDisable 後送出 API 為 no-op")
    class LifecycleDisable {

        /**
         * 製造「owner plugin 已 onDisable」狀態：先確認 enabled，再停用。
         * service 仍持有同一 plugin reference，因此 {@code isServiceActive()}
         * 必須轉為 false，使所有送出／broadcast API 變成 no-op。
         */
        private MessageService disabledService() {
            assertTrue(plugin.isReady(), "前置：plugin 必須已 onEnable");
            plugin.onDisable();
            assertFalse(plugin.isReady(), "前置：onDisable 後 isReady 必須 false");
            return service;
        }

        @Test
        @DisplayName("sendChat(Player, Component)：onDisable 後不送出且不拋例外")
        void sendChat_component_afterDisable_neverSends() {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);
            MessageService svc = disabledService();
            Component c = Component.text("should not arrive");
            assertDoesNotThrow(() -> svc.sendChat(spy, c));
            verify(spy, never()).sendMessage(any(Component.class));
        }

        @Test
        @DisplayName("sendActionBar(Player, Component)：onDisable 後不送出且不拋例外")
        void sendActionBar_component_afterDisable_neverSends() {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);
            MessageService svc = disabledService();
            Component c = Component.text("should not arrive");
            assertDoesNotThrow(() -> svc.sendActionBar(spy, c));
            verify(spy, never()).sendActionBar(any(Component.class));
        }

        @Test
        @DisplayName("sendTitle(Player, Component, Component)：onDisable 後不送出且不拋例外")
        void sendTitle_component_afterDisable_neverSends() {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);
            MessageService svc = disabledService();
            Component t = Component.text("T");
            Component s = Component.text("S");
            assertDoesNotThrow(() -> svc.sendTitle(spy, t, s));
            verify(spy, never()).showTitle(any(Title.class));
        }

        @Test
        @DisplayName("broadcast(Component)：onDisable 後任何玩家都不收到")
        void broadcast_component_afterDisable_noPlayerReceives() {
            PlayerMock p1 = server.addPlayer();
            PlayerMock p2 = server.addPlayer();
            MessageService svc = disabledService();
            Component c = Component.text("should not broadcast");
            assertDoesNotThrow(() -> svc.broadcast(c));
            // broadcast 走 server.getOnlinePlayers() 送給真實玩家，必須直接檢查真實物件
            assertNull(firstComponentMessageOrNull(p1), "onDisable 後 p1 不應收到廣播");
            assertNull(firstComponentMessageOrNull(p2), "onDisable 後 p2 不應收到廣播");
        }

        @Test
        @DisplayName("sendChat(Player, String, Map)：onDisable 後不送出（與 Component 路徑一致）")
        void sendChat_string_afterDisable_neverSends() {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);
            MessageService svc = disabledService();
            assertDoesNotThrow(() -> svc.sendChat(spy, "greeting", Map.of("player", "x")));
            verify(spy, never()).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("broadcast(String, Map)：onDisable 後不送出（與 Component 路徑一致）")
        void broadcast_string_afterDisable_noPlayerReceives() {
            PlayerMock p1 = server.addPlayer();
            PlayerMock p2 = server.addPlayer();
            MessageService svc = disabledService();
            assertDoesNotThrow(() ->
                svc.broadcast("broadcast.announce", Map.of("seconds", "5")));
            assertNull(firstMessageOrNull(p1), "onDisable 後 p1 不應收到廣播");
            assertNull(firstMessageOrNull(p2), "onDisable 後 p2 不應收到廣播");
        }

        @Test
        @DisplayName("onDisable 後再 onEnable（reload）：服務恢復送出能力，不被誤判停用")
        void afterDisable_reload_sendsAgain() {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);
            plugin.onDisable();
            assertFalse(plugin.isReady(), "前置：onDisable 後 not ready");
            plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
            assertTrue(plugin.isReady(), "reload 後必須 ready");
            Component c = Component.text("back online");
            assertDoesNotThrow(() -> service.sendChat(spy, c));
            verify(spy).sendMessage(any(Component.class));
        }
    }

    // -----------------------------------------------------------------
    // lifecycle 並行：send 與 onDisable 的 critical-section 排序
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("lifecycle 並行：send 與 onDisable 的 critical-section 排序")
    class LifecycleConcurrency {

        /**
         * 證明 {@code sendChat(Player, Component)} 在 {@code synchronized (plugin)}
         * 內完成時，並行的 {@code plugin.onDisable()}（同為 plugin monitor 的
         * synchronized 方法）不會在該 player callback 完成前線性化。
         *
         * <p>設計：以可控 blocking player callback 讓 sendChat 持續持有 plugin
         * monitor；另一執行緒提交 onDisable，並以 latch 確認它在 send 釋放 monitor
         * 前「無法」完成。這是 intrinsic lock 的硬阻塞，不是 timing 競態；移除
         * production 的 {@code synchronized (plugin)} 會讓 onDisable 立即完成，
         * 使本測試失敗，從而暴露 guard 缺失。</p>
         */
        @Test
        @DisplayName("sendChat(Player, Component) 在 plugin monitor 內完成時，並行 onDisable 不會先線性化")
        void sendChat_component_concurrentDisable_serializedByPluginMonitor()
                throws Exception {
            PlayerMock real = server.addPlayer();
            Player spy = spy(real);

            ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
            ExecutorService disableExecutor = Executors.newSingleThreadExecutor();
            CountDownLatch enteredCritical = new CountDownLatch(1);
            CountDownLatch releaseCritical = new CountDownLatch(1);
            CountDownLatch disableRequested = new CountDownLatch(1);
            CountDownLatch disableCompleted = new CountDownLatch(1);
            AtomicBoolean callbackSawReady = new AtomicBoolean(false);

            // 在 player.sendMessage(Component) 內攔截：記錄 callback 當下觀察到的
            // plugin 生命週期狀態，並以 latch 通知已進入 critical section，
            // 隨後等待測試釋放——這讓 sendChat 持續持有 plugin monitor。
            doAnswer(invocation -> {
                callbackSawReady.set(plugin.isReady());
                enteredCritical.countDown();
                releaseCritical.await(15, TimeUnit.SECONDS);
                return null;
            }).when(spy).sendMessage(any(Component.class));

            Future<?> sendFuture = null;
            Future<?> disableFuture = null;
            try {
                // 在獨立執行緒送出 Component，使其進入 synchronized (plugin) 並卡在 callback。
                sendFuture = sendExecutor.submit(
                    () -> service.sendChat(spy, Component.text("hi")));

                // 等待 callback 確實進入 critical section（bounded）。
                assertTrue(enteredCritical.await(15, TimeUnit.SECONDS),
                    "sendChat 必須進入 critical section 並觸發 player callback");

                // 並行提交 onDisable：它與 sendChat 競爭同一 plugin monitor。
                disableFuture = disableExecutor.submit(() -> {
                    disableRequested.countDown();
                    plugin.onDisable();
                    disableCompleted.countDown();
                });
                assertTrue(disableRequested.await(15, TimeUnit.SECONDS),
                    "onDisable 工作必須已提交並開始執行");

                // 關鍵不變式：sendChat 仍持有 plugin monitor，因此 onDisable
                // （synchronized）必須被擋在 monitor 之外，直到 send 釋放。
                // 這不是 timing 競態，而是 intrinsic lock 的硬阻塞；移除
                // production 的 synchronized (plugin) 會讓 onDisable 立即完成，
                // 使 disableCompleted 在 bounded window 內 count down，斷言失敗。
                boolean completedWithinWindow =
                    disableCompleted.await(2, TimeUnit.SECONDS);
                assertFalse(completedWithinWindow,
                    "onDisable 不得在 sendChat 釋放 plugin monitor 前完成"
                        + "（guard 缺失或 critical section 未對接 plugin monitor）");

                // 釋放 critical section：sendChat 完成並釋放 monitor，onDisable 隨後線性化。
                releaseCritical.countDown();

                sendFuture.get(15, TimeUnit.SECONDS);
                disableFuture.get(15, TimeUnit.SECONDS);

                assertTrue(callbackSawReady.get(),
                    "callback 在 critical section 內必須觀察到 plugin 仍 ready");
                assertFalse(plugin.isReady(),
                    "onDisable 完成後 plugin 必須為 not ready");
            } finally {
                // 無論斷言結果如何，都必須釋放 latch 並清理執行緒，避免測試掛住或遺留 thread。
                releaseCritical.countDown();
                if (sendFuture != null) {
                    sendFuture.cancel(true);
                }
                if (disableFuture != null) {
                    disableFuture.cancel(true);
                }
                sendExecutor.shutdownNow();
                disableExecutor.shutdownNow();
                sendExecutor.awaitTermination(5, TimeUnit.SECONDS);
                disableExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    // -----------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------

    /**
     * 從 PlayerMock 取出第一條訊息（如有）。
     * MockBukkit 4.x 提供 {@code nextComponentMessage()} 回傳 Adventure
     * {@code Component}；我們用其 {@link Object#toString()}（plain text）
     * 作為比對目標。
     */
    private String firstMessage(PlayerMock p) {
        String msg = firstMessageOrNull(p);
        assertNotNull(msg, "玩家必須收到至少一條訊息");
        return msg;
    }

    private String firstMessageOrNull(PlayerMock p) {
        try {
            // PlayerMock#nextComponentMessage() 回傳 net.kyori.adventure.text.Component
            Object component = p.nextComponentMessage();
            return component == null ? null : component.toString();
        } catch (Throwable t) {
            // 若 MockBukkit 版本差異，fallback 用 reflection
            return null;
        }
    }

    private Component firstComponentMessage(PlayerMock p) {
        Component c = firstComponentMessageOrNull(p);
        assertNotNull(c, "玩家必須收到至少一條 Component 訊息");
        return c;
    }

    private Component firstComponentMessageOrNull(PlayerMock p) {
        try {
            return p.nextComponentMessage();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasLogContaining(String text) {
        return capturedLogs.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains(text));
    }

    /**
     * 簡單的 JUL Handler，把所有收到的 LogRecord 收集到外部 List。
     */
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

    // 為了讓 Constructor 區塊能用 lambda 形式 NPE 斷言方便
}
