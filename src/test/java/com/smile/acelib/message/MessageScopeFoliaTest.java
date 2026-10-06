package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 插件作用域訊息服務的 Paper／Folia 顯示路徑分流測試。
 *
 * <p>以 Mockito stub 模擬 Folia 在 non-owned region 拋
 * {@link IllegalStateException} 的標準行為，驗證共用渲染結果的發送入口
 * （{@link RenderedMessage} 多載）走同一套降級記錄：
 * Folia 記 {@code ACELIB-MSG-002}，Paper 記 {@code ACELIB-MSG-003}。</p>
 */
@DisplayName("MessageScope（Paper／Folia 顯示路徑）")
class MessageScopeFoliaTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private LangManager lang;
    private MessageService foliaService;
    private MessageService paperService;
    private final List<LogRecord> captured = new ArrayList<>();
    private Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        File langDir = new File(plugin.getDataFolder(), "lang");
        langDir.mkdirs();
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("message:\n  prefix: '[AceLib] '\n");
        }

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

        lang = new LangManager(plugin, Locale.US);
        lang.load();
        foliaService = new MessageService(plugin, lang,
            Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
        paperService = new MessageService(plugin, lang,
            Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
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

    private boolean hasLogContaining(String text) {
        return captured.stream().anyMatch(r ->
            r.getMessage() != null && r.getMessage().contains(text));
    }

    private PlayerMock throwingPlayer() {
        PlayerMock spy = spy(server.addPlayer());
        doThrow(new IllegalStateException("not owned region"))
            .when(spy).sendMessage(any(Component.class));
        doThrow(new IllegalStateException("not owned region"))
            .when(spy).sendActionBar(any(Component.class));
        return spy;
    }

    private RenderedMessage greeting(MessageService service) {
        return service.render("greeting", Map.of("player", "smile"));
    }

    @Test
    @DisplayName("Folia：共用渲染發送在錯誤 region 記 MSG-002 並優雅降級")
    void foliaRenderedSendsDegradeWithMsg002() {
        RenderedMessage rendered = greeting(foliaService);
        assertNotNull(rendered);

        PlayerMock chat = throwingPlayer();
        PlayerMock bar = throwingPlayer();
        captured.clear();

        assertDoesNotThrow(() -> foliaService.sendChat(chat, rendered));
        assertDoesNotThrow(() -> foliaService.sendActionBar(bar, rendered));
        assertDoesNotThrow(() -> foliaService.broadcast(rendered));

        assertTrue(hasLogContaining("ACELIB-MSG-002"),
            "Folia region 違規必須記 MSG-002");
    }

    @Test
    @DisplayName("Paper：同型例外記 MSG-003，不誤標為 Folia region 違規")
    void paperRenderedSendsDegradeWithMsg003() {
        RenderedMessage rendered = greeting(paperService);

        PlayerMock chat = throwingPlayer();
        captured.clear();

        assertDoesNotThrow(() -> paperService.sendChat(chat, rendered));

        assertTrue(hasLogContaining("ACELIB-MSG-003"),
            "Paper 同型例外必須走 MSG-003 降級");
    }

    @Test
    @DisplayName("Folia 作用域：解析器語系發送同樣受 region 保護")
    void foliaScopeSendResolvesAndDegrades() {
        MessageScope scope = new MessageScope(plugin, lang, foliaService,
            PlayerLocaleResolver.playerLocale(Locale.US));
        try {
            RenderedMessage rendered =
                scope.render("greeting", Map.of("player", "smile"));
            assertTrue(rendered.text().contains("Hello smile!"));

            PlayerMock p = throwingPlayer();
            captured.clear();
            assertDoesNotThrow(() -> scope.messages().sendChat(p, rendered));
            assertTrue(hasLogContaining("ACELIB-MSG-002"));
        } finally {
            scope.close();
        }
    }
}
