package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 指定語系前綴與純文字變數跳脫的驗收測試。
 *
 * <p>全域目前語系固定為 {@code en_US}；凡是傳入 {@code zh_TW} 的渲染，
 * 本體與前綴都必須來自 {@code zh_TW} 檔案。
 *
 * <p>純文字視圖（{@code text}／{@code format}）的變數值必須與富文字視圖
 * 同規則跳脫，模板本身的 MiniMessage 標記不受影響。
 */
@DisplayName("MessageService 指定語系前綴與純文字跳脫")
class MessageServiceLocalePrefixEscapeTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private MessageService service;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("無法建立 dataFolder");
        }
        File langDir = new File(dataFolder, "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("rich.greeting: '<green>Hello {player}!</green>'\n");
            w.write("rich.gradient: '<gradient:#5e4fa2:#f79459>Greetings {player}</gradient>'\n");
            w.write("message:\n");
            w.write("  prefix: '[EN] '\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("greeting: '{player} 你好！'\n");
            w.write("rich.greeting: '<green>{player} 你好！</green>'\n");
            w.write("rich.gradient: '<gradient:#5e4fa2:#f79459>歡迎 {player}</gradient>'\n");
            w.write("message:\n");
            w.write("  prefix: '[ZH] '\n");
        }

        LangManager lang = new LangManager(plugin, Locale.US);
        lang.load();
        service = new MessageService(plugin, lang);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("指定語系渲染時前綴取同一語系，不取全域目前語系")
    void explicitLocale_prefixFollowsBodyLocale() {
        RenderedMessage rendered =
            service.render("greeting", Map.of("player", "A"), Locale.TAIWAN);
        assertTrue(rendered.text().startsWith("[ZH] "),
            "前綴必須來自 zh_TW，實際: " + rendered.text());
        assertTrue(rendered.text().contains("你好"),
            "本體必須來自 zh_TW，實際: " + rendered.text());
        assertTrue(rendered.component().toString().contains("[ZH]"),
            "Component 視圖的前綴也必須來自 zh_TW，實際: "
                + rendered.component());
    }

    @Test
    @DisplayName("locale 為 null 時前綴維持全域目前語系（回歸）")
    void nullLocale_prefixFollowsGlobal() {
        RenderedMessage rendered = service.render("greeting", Map.of("player", "A"), null);
        assertTrue(rendered.text().startsWith("[EN] "),
            "null locale 必須沿用全域目前語系前綴，實際: " + rendered.text());
    }

    @Test
    @DisplayName("純文字路徑的變數值帶 MiniMessage 標記時被跳脫")
    void plainText_varTagsEscaped() {
        String out = service.format("greeting", Map.of("player", "<red>EVIL</red>"));
        assertTrue(out.startsWith("[EN] "), "前綴必須保留，實際: " + out);
        assertFalse(out.contains("<red>EVIL</red>"),
            "變數值不得以原始標記出現，實際: " + out);
        assertTrue(out.contains("\\<red>EVIL\\</red>"),
            "變數值必須被跳脫，實際: " + out);
    }

    @Test
    @DisplayName("同一模板的富文字視圖與純文字視圖對變數值處理一致")
    void richAndPlain_consistentEscape() {
        Map<String, Object> vars = Map.of("player", "<red>EVIL</red>");
        String text = service.render("greeting", vars).text();
        Component component = service.render("greeting", vars).component();
        String shown = component.toString();
        assertTrue(text.contains("EVIL") && shown.contains("EVIL"),
            "兩種視圖都必須保留使用者值的可見文字，text=" + text + " component=" + shown);
        assertFalse(shown.contains("RED"),
            "富文字視圖不得把使用者值解析成紅色標籤，實際: " + shown);
        assertFalse(text.contains("<red>EVIL</red>"),
            "純文字視圖不得殘留未跳脫的使用者標記，實際: " + text);
    }

    @Test
    @DisplayName("模板本身的標記仍正常解析，只有變數值被跳脫")
    void templateTagsStillParsed() {
        Component parsed =
            service.formatComponent("rich.greeting", Map.of("player", "smile"));
        assertTrue(parsed.toString().toLowerCase(java.util.Locale.ROOT).contains("green"),
            "模板的 <green> 必須被解析為顏色，實際: " + parsed);
        String text = service.format("rich.greeting", Map.of("player", "smile"));
        assertTrue(text.contains("<green>Hello smile!</green>"),
            "純文字視圖保留模板標記且變數已替換，實際: " + text);
    }

    @Test
    @DisplayName("含 gradient 模板的變數值也被跳脫且只跳脫一次")
    void gradientVar_escapedOnce() {
        String out = service.format("rich.gradient",
            Map.of("player", "<gradient>EVIL</gradient>"));
        assertTrue(out.contains("\\<gradient>EVIL\\</gradient>"),
            "變數值必須被跳脫，實際: " + out);
        assertFalse(out.contains("\\\\"),
            "不得雙重跳脫，實際: " + out);
        assertTrue(out.contains("<gradient:#5e4fa2:#f79459>"),
            "模板本身的 gradient 標記必須保留，實際: " + out);
    }

    @Test
    @DisplayName("沒有變數的訊息不受跳脫影響（回歸）")
    void noVars_unaffected() {
        String out = service.format("greeting", null);
        assertEquals("[EN] Hello {player}!", out, "無變數時輸出必須原樣，實際: " + out);
    }

    @Test
    @DisplayName("format 與 render 的 text 視圖輸出一致")
    void format_matchesRenderedText() {
        Map<String, Object> vars = Map.of("player", "<red>EVIL</red>");
        assertEquals(service.render("greeting", vars).text(),
            service.format("greeting", vars),
            "同一模板兩種純文字入口必須一致");
    }

    @Test
    @DisplayName("變數值原本含反斜線時只跳脫標記，反斜線原樣保留且只跳脫一次")
    void varWithBackslash_escapedOnce() {
        Map<String, Object> vars = Map.of("player", "a\\b<red>c");
        assertEquals("[EN] Hello a\\b\\<red>c!",
            service.format("greeting", vars),
            "反斜線保留、`<` 跳脫，實際: " + service.format("greeting", vars));
        RenderedMessage rendered = service.render("greeting", vars);
        assertEquals("[EN] Hello a\\b<red>c!",
            PlainTextComponentSerializer.plainText().serialize(rendered.component()),
            "富文字可見文字必須與跳脫前的值一致（單次跳脫往返），實際: "
                + PlainTextComponentSerializer.plainText()
                    .serialize(rendered.component()));
    }

    @Test
    @DisplayName("指定語系缺 prefix 時按分層規則退回預設語系的 prefix")
    void explicitLocale_prefixFallsBackToDefault() throws IOException {
        File zhFile = new File(new File(plugin.getDataFolder(), "lang"), "zh_TW.yml");
        try (FileWriter w = new FileWriter(zhFile)) {
            w.write("greeting: '{player} 你好！'\n");
            w.write("rich.greeting: '<green>{player} 你好！</green>'\n");
            w.write("rich.gradient: '<gradient:#5e4fa2:#f79459>歡迎 {player}</gradient>'\n");
        }
        RenderedMessage rendered =
            service.render("greeting", Map.of("player", "A"), Locale.TAIWAN);
        assertEquals("[EN] A 你好！", rendered.text(),
            "本體取 zh_TW、prefix 退回 en_US，實際: " + rendered.text());
        assertEquals("[EN] A 你好！",
            PlainTextComponentSerializer.plainText().serialize(rendered.component()),
            "Component 可見文字的前綴同樣退回 en_US，實際: "
                + PlainTextComponentSerializer.plainText()
                    .serialize(rendered.component()));
    }
}
