package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link MessageService#formatFormText} Red/Green 測試。
 */
@DisplayName("MessageService.formatFormText")
class MessageServiceFormTextTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private LangManager lang;
    private MessageService service;
    private List<LogRecord> captured;
    private Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File langDir = new File(dataFolder, "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("rich.click: '<red>Press</red><click:run_command:\"/say hi\"> me</click>'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
            w.write("  bedrock:\n");
            w.write("    fallback:\n");
            w.write("      run_command: 'RUNHINT:<payload>'\n");
        }
        captured = new ArrayList<>();
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
        service = new MessageService(plugin, lang);
    }

    @AfterEach
    void tearDown() {
        Logger root = Logger.getLogger("");
        root.removeHandler(handler);
        Logger acelib = Logger.getLogger("AceLib");
        acelib.removeHandler(handler);
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("不套用 message.prefix")
    void formatFormText_noPrefix() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("player", "Steve");
        String out = service.formatFormText("greeting", vars, Locale.US);
        assertTrue(out.contains("Hello Steve!"), "實際：" + out);
        assertFalse(out.contains("[AceLib]"), "不得套用 prefix，實際：" + out);
    }

    @Test
    @DisplayName("缺 key 回傳空字串並記錄 MSG-001（與 format 一致）")
    void formatFormText_missingKey_emptyAndMsg001() {
        String viaFormat = service.format("no.such.key", Map.of());
        String viaFormText = service.formatFormText("no.such.key", Map.of(), Locale.US);
        assertEquals(viaFormat, viaFormText, "缺 key 行為應與 format 一致");
        assertEquals("", viaFormText);
        assertTrue(captured.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("ACELIB-MSG-001")),
            "必須記錄 ACELIB-MSG-001");
    }

    @Test
    @DisplayName("null key 拋 NPE（與兄弟方法一致）")
    void formatFormText_nullKey_npe() {
        assertThrows(NullPointerException.class,
            () -> service.formatFormText(null, Map.of(), Locale.US));
        assertThrows(NullPointerException.class,
            () -> service.formatFormText(null, Map.of(), FormTextOptions.defaults()));
    }

    @Test
    @DisplayName("null options 以 IAE 拒絕")
    void formatFormText_nullOptions_iae() {
        assertThrows(IllegalArgumentException.class,
            () -> service.formatFormText("greeting", Map.of(), (FormTextOptions) null));
    }

    @Test
    @DisplayName("options.clickHints=true 使用插件語系提示")
    void formatFormText_clickHintsTrue_usesLangHint() {
        String out = service.formatFormText("rich.click", Map.of(),
            new FormTextOptions(true, 0, Locale.US));
        assertTrue(out.contains("Press"), "正文保留，實際：" + out);
        assertTrue(out.contains("RUNHINT:/say hi"),
            "應使用語言檔自訂提示，實際：" + out);
    }

    @Test
    @DisplayName("options.clickHints=false 不加提示")
    void formatFormText_clickHintsFalse_noHint() {
        String out = service.formatFormText("rich.click", Map.of(),
            new FormTextOptions(false, 0, Locale.US));
        assertTrue(out.contains("Press"), "實際：" + out);
        assertFalse(out.contains("RUNHINT"), "實際：" + out);
        assertFalse(out.contains("/say hi"), "實際：" + out);
    }

    @Test
    @DisplayName("變數值安全替換不被當成 MiniMessage 解析")
    void formatFormText_varsEscaped() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("player", "<red>evil</red>");
        String out = service.formatFormText("greeting", vars, Locale.US);
        assertTrue(out.contains("evil"), "實際：" + out);
        assertTrue(out.contains("<red>"), "注入標籤應保持字面，實際：" + out);
    }

    @Test
    @DisplayName("locale overload 與 options overload 一致（無提示、無截斷）")
    void formatFormText_localeOverload_matchesOptions() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("player", "Steve");
        String viaLocale = service.formatFormText("greeting", vars, Locale.US);
        String viaOptions = service.formatFormText("greeting", vars,
            new FormTextOptions(false, 0, Locale.US));
        assertEquals(viaLocale, viaOptions);
        assertTrue(viaLocale.endsWith("§r"), "實際：" + viaLocale);
    }
}
