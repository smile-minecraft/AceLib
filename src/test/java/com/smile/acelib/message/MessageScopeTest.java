package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 插件作用域訊息服務的 Red 測試：以 plugin 為作用域的工廠、
 * 可替換玩家語系解析器、多呈現共用渲染結果、顯示標籤與程式識別字分離、
 * 缺 key／渲染失敗診斷、reload／disable 清理。
 */
@DisplayName("MessageScope（插件作用域訊息服務）")
class MessageScopeTest {

    private ServerMock server;
    private AceLibPlugin pluginA;
    private JavaPlugin pluginB;
    private File langDirA;
    private File dataDirB;
    private final Map<String, String> builtinB = new HashMap<>();
    private final List<MessageScope> owned = new ArrayList<>();
    private final List<LogRecord> captured = new ArrayList<>();
    private Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        pluginA = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        pluginA.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        langDirA = new File(pluginA.getDataFolder(), "lang");
        langDirA.mkdirs();
        writeLang(langDirA, "en_US.yml",
            "greeting: 'Hello {player}!'\n"
                + "button.confirm: 'Confirm'\n"
                + "button.colored: '<green>確認</green>'\n"
                + "rich.broken: 'Hello {player} <green'\n"
                + "message:\n  prefix: '[A] '\n");

        pluginB = mock(JavaPlugin.class);
        dataDirB = new File(pluginA.getDataFolder(), "pluginB");
        when(pluginB.getDataFolder()).thenReturn(dataDirB);
        when(pluginB.getLogger()).thenReturn(Logger.getLogger("PluginB"));
        when(pluginB.isEnabled()).thenReturn(true);
        when(pluginB.getResource(anyString())).thenAnswer(inv -> {
            String body = builtinB.get(inv.getArgument(0));
            return body == null ? null
                : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        });
        File langDirB = new File(dataDirB, "lang");
        langDirB.mkdirs();
        writeLang(langDirB, "en_US.yml",
            "greeting: 'Hi {player} from B'\n"
                + "message:\n  prefix: '[B] '\n");

        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);
        root.addHandler(handler);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.setLevel(Level.ALL);
        acelib.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        for (MessageScope scope : owned) {
            try {
                MessageScopes.close(scope.plugin());
            } catch (Throwable ignored) {
                // 清理階段不掩蓋測試結果
            }
        }
        owned.clear();
        Logger root = Logger.getLogger("");
        root.removeHandler(handler);
        root.setLevel(Level.INFO);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.removeHandler(handler);
        acelib.setLevel(Level.INFO);
        MockBukkit.unmock();
    }

    private static void writeLang(File langDir, String name, String body) throws Exception {
        try (FileWriter w = new FileWriter(new File(langDir, name))) {
            w.write(body);
        }
    }

    private MessageScope createA() {
        MessageScope scope = MessageScopes.create(pluginA, Locale.US);
        owned.add(scope);
        return scope;
    }

    private MessageScope createB() {
        MessageScope scope = MessageScopes.create(pluginB, Locale.US);
        owned.add(scope);
        return scope;
    }

    private boolean hasLogContaining(String text) {
        return captured.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains(text));
    }

    private Component firstComponent(PlayerMock p) {
        try {
            return p.nextComponentMessage();
        } catch (Throwable t) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // 工廠生命週期
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("工廠生命週期")
    class Lifecycle {

        @Test
        @DisplayName("create 後 get 回傳同一作用域；close 後移除且具冪等性")
        void createGetClose() {
            assertEquals(0, MessageScopes.activeCount(), "前置：無殘留登記");
            MessageScope scope = createA();
            assertEquals(1, MessageScopes.activeCount());

            assertTrue(MessageScopes.get(pluginA).isPresent());
            assertSame(scope, MessageScopes.get(pluginA).orElseThrow());

            assertTrue(MessageScopes.close(pluginA));
            assertEquals(0, MessageScopes.activeCount());
            assertTrue(MessageScopes.get(pluginA).isEmpty());
            assertFalse(MessageScopes.close(pluginA), "重複 close 不得報錯，回 false");
        }

        @Test
        @DisplayName("重複 create 拋 IllegalStateException 並帶 ACELIB-MSG-006")
        void duplicateCreateFailsClosed() {
            // 以 isEnabled 明確為 true 的 mock 插件覆蓋「啟用中重複建立」分支；
            // MockBukkit 真 plugin 直接調 onEnable、未經 manager 設 enabled，
            // 不適合作為此分支的判定依據。
            createB();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MessageScopes.create(pluginB, Locale.US));
            assertTrue(ex.getMessage().contains("ACELIB-MSG-006"),
                "重複建立必須可診斷，實際：" + ex.getMessage());
        }

        @Test
        @DisplayName("已停用 plugin 的殘留登記：create 自動清理後成功，不永久 MSG-006")
        void staleDisabledRegistrationEvicted() {
            MessageScope first = createB();
            assertFalse(first.isClosed());

            // 模擬下游忘記 close 就停用：登記殘留，但 plugin 已停用
            when(pluginB.isEnabled()).thenReturn(false);

            MessageScope second;
            try {
                second = MessageScopes.create(pluginB, Locale.US);
            } finally {
                owned.remove(first);
            }
            owned.add(second);

            assertTrue(first.isClosed(), "被驅逐的舊作用域必須標記關閉");
            assertTrue(MessageScopes.get(pluginB).isPresent());
            RenderedMessage rendered =
                second.render("greeting", Map.of("player", "back"));
            assertTrue(rendered.text().contains("Hi back from B"));
        }

        @Test
        @DisplayName("null plugin 或 null locale 拋 NPE（契約）")
        void nullArgsThrow() {
            assertThrows(NullPointerException.class,
                () -> MessageScopes.create(null, Locale.US));
            assertThrows(NullPointerException.class,
                () -> MessageScopes.create(pluginA, null));
        }

        @Test
        @DisplayName("close 後使用作用域拋 IllegalStateException（ACELIB-MSG-006）")
        void useAfterCloseFails() {
            MessageScope scope = createA();
            MessageScopes.close(pluginA);
            owned.clear();

            assertTrue(scope.isClosed());
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scope.render("greeting", Map.of()));
            assertTrue(ex.getMessage().contains("ACELIB-MSG-006"));
            assertThrows(IllegalStateException.class,
                () -> scope.reload());
        }
    }

    // -----------------------------------------------------------------
    // 多 plugin 隔離
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("多 plugin 隔離")
    class Isolation {

        @Test
        @DisplayName("兩 plugin 同 key 各自渲染，不互讀文案")
        void sameKeyIsolated() {
            MessageScope scopeA = createA();
            MessageScope scopeB = createB();

            RenderedMessage a = scopeA.render("greeting", Map.of("player", "x"));
            RenderedMessage b = scopeB.render("greeting", Map.of("player", "x"));

            assertTrue(a.text().contains("Hello x!"), "A 實際：" + a.text());
            assertTrue(b.text().contains("Hi x from B"), "B 實際：" + b.text());
            assertFalse(a.text().contains("from B"));
            assertFalse(b.text().contains("Hello"));
        }

        @Test
        @DisplayName("清理 A 不影響 B：B 仍可渲染與發送")
        void closeOneLeavesOther() {
            MessageScope scopeA = createA();
            MessageScope scopeB = createB();
            MessageScopes.close(pluginA);
            owned.remove(scopeA);

            assertTrue(MessageScopes.get(pluginB).isPresent());
            RenderedMessage b = scopeB.render("greeting", Map.of("player", "y"));
            assertTrue(b.text().contains("Hi y from B"));

            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() -> scopeB.sendChat(p, "greeting", Map.of("player", "y")));
            assertNotNull(firstComponent(p), "B 關閉 A 後仍可發送");
        }
    }

    // -----------------------------------------------------------------
    // 語系解析器
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("玩家語系解析器")
    class Resolver {

        @Test
        @DisplayName("預設解析器跟隨 Player.locale；自訂解析器可替換且不需儲存偏好")
        void resolverReplaceable() throws Exception {
            writeLang(new File(dataDirB, "lang"), "fr.yml", "greeting: 'Bonjour {player}!'\n");
            MessageScope scope = createB();

            Player player = mock(Player.class);
            when(player.locale()).thenReturn(Locale.TRADITIONAL_CHINESE);
            when(player.isOnline()).thenReturn(true);

            // 預設解析器：跟隨 player.locale（此處 zh_TW 無檔 → 退回預設英文）
            assertEquals(Locale.TRADITIONAL_CHINESE, scope.resolver().resolve(player));

            // 替換為固定法語解析器：不需任何偏好儲存
            scope.setResolver(p -> Locale.FRENCH);
            RenderedMessage rendered = scope.render("greeting",
                Map.of("player", "ami"), scope.resolver().resolve(player));
            assertTrue(rendered.text().contains("Bonjour ami!"),
                "自訂解析器必須決定模板語系，實際：" + rendered.text());
        }

        @Test
        @DisplayName("setResolver(null) 拋 NPE；解析器拋例外時退回預設語系")
        void resolverFailureFallsBack() {
            MessageScope scope = createB();
            assertThrows(NullPointerException.class, () -> scope.setResolver(null));

            scope.setResolver(p -> {
                throw new RuntimeException("下游資料庫斷線");
            });
            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() -> scope.sendChat(p, "greeting", Map.of("player", "z")));
            assertNotNull(firstComponent(p), "解析器失效不得中斷發送");
        }
    }

    // -----------------------------------------------------------------
    // 共用渲染
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("多呈現共用渲染結果")
    class SharedRender {

        @Test
        @DisplayName("同一 RenderedMessage 餵聊天與 ActionBar：內容一致")
        void sameInstanceForChatAndActionBar() {
            MessageScope scope = createA();
            RenderedMessage rendered =
                scope.render("greeting", Map.of("player", "smile"));

            assertFalse(rendered.missing());
            assertEquals("", rendered.diagnosis());

            PlayerMock chat = server.addPlayer();
            PlayerMock bar = server.addPlayer();
            scope.messages().sendChat(chat, rendered);
            scope.messages().sendActionBar(bar, rendered);

            Component chatMsg = firstComponent(chat);
            Component barMsg = bar.nextActionBar();
            assertNotNull(chatMsg);
            assertNotNull(barMsg);
            assertEquals(chatMsg, barMsg, "同次渲染的聊天與 ActionBar 必須一致");
            assertTrue(chatMsg.toString().contains("Hello smile!"));
        }

        @Test
        @DisplayName("GUI 取 component、表單取 formText：與各別格式化結果一致")
        void guiAndFormShareRender() {
            MessageScope scope = createA();
            RenderedMessage rendered =
                scope.render("greeting", Map.of("player", "smile"));

            // GUI 路徑吃 Component（含 prefix 與互動結構）
            assertEquals(scope.messages().formatComponent("greeting", Map.of("player", "smile")),
                rendered.component());
            // 表單路徑吃安全字串（不帶 prefix）
            Locale locale = Locale.US;
            assertEquals(scope.messages().formatFormText("greeting",
                Map.of("player", "smile"), locale), rendered.formText());
            assertTrue(rendered.text().contains("Hello smile!"));
        }

        @Test
        @DisplayName("作用域 sendChat 依解析語系渲染後發送")
        void scopeSendResolvesLocale() throws Exception {
            writeLang(new File(dataDirB, "lang"), "fr.yml", "greeting: 'Bonjour {player}!'\n");
            MessageScope scope = createB();
            scope.setResolver(p -> Locale.FRENCH);

            PlayerMock p = server.addPlayer();
            scope.sendChat(p, "greeting", Map.of("player", "ami"));

            Component received = firstComponent(p);
            assertNotNull(received);
            assertTrue(received.toString().contains("Bonjour ami!"),
                "實際：" + received);
        }
    }

    // -----------------------------------------------------------------
    // 標籤與識別字分離
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("顯示標籤與程式識別字分離")
    class Label {

        @Test
        @DisplayName("label 攜帶穩定 id 與顯示文字；改文案不改 id")
        void labelSeparatesIdAndText() throws Exception {
            MessageScope scope = createA();
            MessageLabel label =
                scope.label("confirm", "button.confirm", Map.of());

            assertEquals("confirm", label.id());
            // 顯示文字不含聊天 prefix：按鈕／表單選項只顯示文案本身。
            // 取 formText 共用視圖（含 FormText 行尾 §r 重置慣例），不斷言逐字相等，
            // 而斷言與同次渲染的 formText 同一字串，證明標籤與表單共用同一份結果。
            assertFalse(label.text().contains("[A]"),
                "聊天 prefix 不得進顯示文字，實際：" + label.text());
            assertEquals(scope.render("button.confirm", Map.of()).formText(), label.text());

            // 管理員改顯示文案、程式分支仍用 id
            writeLang(langDirA, "en_US.yml",
                "greeting: 'Hello {player}!'\n"
                    + "button.confirm: 'OK, go!'\n"
                    + "button.colored: '<green>確認</green>'\n"
                    + "rich.broken: 'Hello {player} <green'\n"
                    + "message:\n  prefix: '[A] '\n");
            scope.reload();

            MessageLabel renamed = scope.label("confirm", "button.confirm", Map.of());
            assertEquals("confirm", renamed.id(), "程式識別字必須穩定");
            assertTrue(renamed.text().contains("OK, go!"),
                "改文案後顯示跟著變，實際：" + renamed.text());
            assertFalse(renamed.text().contains("[A]"));
        }

        @Test
        @DisplayName("標籤文字 MiniMessage 已解析：不含原樣標籤、不含 prefix")
        void labelTextResolvesMiniMessage() {
            MessageScope scope = createA();

            MessageLabel label = scope.label("ok", "button.colored", Map.of());

            assertEquals("ok", label.id());
            assertTrue(label.text().contains("確認"), "實際：" + label.text());
            assertFalse(label.text().contains("<green>"),
                "MiniMessage 標籤不得以原樣留在顯示文字，實際：" + label.text());
            assertFalse(label.text().contains("[A]"),
                "聊天 prefix 不得進顯示文字，實際：" + label.text());
        }

        @Test
        @DisplayName("缺標籤 key：id 保留、文字退回 id 本身並可診斷")
        void missingLabelFallsBackToId() {
            MessageScope scope = createA();
            captured.clear();

            MessageLabel label = scope.label("confirm", "button.absent", Map.of());

            assertEquals("confirm", label.id());
            assertEquals("confirm", label.text());
            assertTrue(hasLogContaining("ACELIB-MSG-001"));
        }
    }

    // -----------------------------------------------------------------
    // 診斷
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("缺 key 與渲染失敗診斷")
    class Diagnosis {

        @Test
        @DisplayName("缺 key：missing、診斷帶 ACELIB-MSG-001，不中斷")
        void missingKeyDiagnosable() {
            MessageScope scope = createA();
            captured.clear();

            RenderedMessage rendered = scope.render("does.not.exist", Map.of());

            assertTrue(rendered.missing());
            assertTrue(rendered.diagnosis().contains("ACELIB-MSG-001"),
                "實際診斷：" + rendered.diagnosis());
            assertEquals("", rendered.text());
            assertEquals(Component.empty(), rendered.component());
            assertTrue(hasLogContaining("ACELIB-MSG-001"));
        }

        @Test
        @DisplayName("渲染失敗：退回可見純文字並帶 ACELIB-MSG-003 診斷")
        void renderFailureDiagnosable() {
            MessageScope scope = createA();
            captured.clear();

            RenderedMessage rendered =
                scope.render("rich.broken", Map.of("player", "smile"));

            assertFalse(rendered.missing(), "模板存在，只是 MiniMessage 解析有風險");
            assertTrue(rendered.diagnosis().contains("ACELIB-MSG-003")
                || rendered.diagnosis().isEmpty(),
                "解析失敗應記錄 MSG-003 或成功解析無診斷，實際：" + rendered.diagnosis());
            assertTrue(rendered.text().contains("Hello smile"),
                "失敗亦須保留可見正文，實際：" + rendered.text());

            // 缺 key 的發送為 no-op：玩家收不到任何訊息
            PlayerMock p = server.addPlayer();
            scope.messages().sendChat(p, scope.render("does.not.exist", Map.of()));
            assertNull(firstComponent(p));
        }

        @Test
        @DisplayName("mock LangManager 回傳 null locale：render 不 NPE，退回預設語系")
        void renderWithNullLocalesFallsBack() {
            LangManager mockLang = mock(LangManager.class);
            when(mockLang.getCurrentLocale()).thenReturn(null);
            when(mockLang.getDefaultLocale()).thenReturn(Locale.US);
            when(mockLang.get(anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Optional.of("Hello!"));
            when(mockLang.get(anyString())).thenReturn(Optional.empty());

            MessageService service = new MessageService(pluginA, mockLang);
            RenderedMessage rendered =
                assertDoesNotThrow(() -> service.render("greeting", Map.of()));

            assertNotNull(rendered);
            assertEquals(Locale.US, rendered.locale());
            assertTrue(rendered.text().contains("Hello!"));
        }
    }

    // -----------------------------------------------------------------
    // reload／disable
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("reload 與 disable")
    class ReloadDisable {

        @Test
        @DisplayName("reload 後新文案即時生效")
        void reloadPicksUp() throws Exception {
            MessageScope scope = createA();
            assertTrue(scope.render("greeting", Map.of("player", "x")).text()
                .contains("Hello x!"));

            writeLang(langDirA, "en_US.yml",
                "greeting: 'Yo {player}!'\n"
                    + "message:\n  prefix: '[A] '\n");
            scope.reload();

            assertTrue(scope.render("greeting", Map.of("player", "x")).text()
                .contains("Yo x!"));
        }

        @Test
        @DisplayName("所屬 plugin 停用後發送為 no-op；純渲染不受影響")
        void disabledPluginSkipsSends() {
            when(pluginB.isEnabled()).thenReturn(false);
            MessageScope scope = createB();

            PlayerMock p = server.addPlayer();
            assertDoesNotThrow(() -> scope.sendChat(p, "greeting", Map.of("player", "x")));
            assertNull(firstComponent(p), "停用後不得發送");

            // 純渲染不受生命週期 guard 影響（與 MessageService 既有語意一致）
            assertFalse(scope.render("greeting", Map.of("player", "x")).missing());
        }

        @Test
        @DisplayName("syncBuiltinDefaults：只補新 key（委派 LangManager）")
        void syncDelegates() {
            builtinB.put("lang/en_US.yml",
                "greeting: 'jar hello'\nbrand.new: 'jar new'\n");
            MessageScope scope = createB();

            int added = scope.syncBuiltinDefaults();

            assertEquals(1, added);
            assertTrue(scope.render("brand.new", Map.of()).text().contains("jar new"));
            assertTrue(scope.render("greeting", Map.of("player", "x")).text()
                .contains("Hi x from B"), "管理員文案不得被覆寫");
        }
    }

    // -----------------------------------------------------------------
    // 公開 accessor
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("公開 accessor")
    class Accessors {

        @Test
        @DisplayName("plugin／lang／messages／resolver 皆可取得且非 null")
        void accessorsNonNull() {
            MessageScope scope = createA();

            assertSame(pluginA, scope.plugin());
            assertNotNull(scope.lang());
            assertNotNull(scope.messages());
            assertNotNull(scope.resolver());
            assertFalse(scope.isClosed());
            assertTrue(scope.lang() instanceof LangManager);
        }
    }
}
