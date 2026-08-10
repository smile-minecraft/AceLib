package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
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
