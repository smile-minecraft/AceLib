package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 一般渲染路徑缺 key 時的輕量語意（1.4.0 對照）。
 *
 * <p>一般路徑（render／format／formatComponent／formatFormText）缺 key 時
 * 直接回 1.4.0 語意（missingKey=true＋ACELIB-MSG-001），不做任何磁碟驗證
 * （不讀語言檔內容、不列目錄、不探測內建資源）。診斷三態與 RENDER_FAILED
 * 只由 renderDetailed 提供。</p>
 */
@DisplayName("MessageService 一般路徑缺 key 輕量語意")
class MessageServiceLightweightMissingKeyTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File langDir;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));

        langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("greeting: 'Hello {player}!'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("greeting: '{player}你好！'\n");
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void corruptZhTw() throws IOException {
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("greeting: [unclosed\n\tbroken-tab: 1\n");
        }
    }

    @Test
    @DisplayName("一般路徑缺 key 時不碰磁碟（不讀檔、不列目錄、不探測資源）")
    void generalPathMissingKey_doesNotTouchDisk() {
        LangManager emptyLang = mock(LangManager.class);
        when(emptyLang.get(anyString(), any())).thenReturn(Optional.empty());
        when(emptyLang.get(any(Locale.class), anyString())).thenReturn(Optional.empty());
        when(emptyLang.getCurrentLocale()).thenReturn(Locale.US);
        when(emptyLang.getDefaultLocale()).thenReturn(Locale.US);
        BedrockService bedrock = mock(BedrockService.class);

        AceLibPlugin spyPlugin = Mockito.spy(plugin);
        MessageService service = new MessageService(spyPlugin, emptyLang, bedrock);
        clearInvocations(spyPlugin);

        RenderedMessage rendered = service.render("does.not.exist", Map.of());
        assertTrue(rendered.missing(), "一般路徑缺 key 必須標 missing");
        assertTrue(rendered.diagnosis().contains("ACELIB-MSG-001"),
            "一般路徑缺 key 診斷維持 MSG-001，實際: " + rendered.diagnosis());

        assertEquals("", service.format("does.not.exist", Map.of()));
        assertEquals(Component.empty(),
            service.formatComponent("does.not.exist", Map.of()));
        assertEquals("", service.formatFormText("does.not.exist", Map.of(), Locale.US));

        verify(spyPlugin, never()).getDataFolder();
        verify(spyPlugin, never()).getResource(anyString());
    }

    @Test
    @DisplayName("語言檔損壞時一般路徑維持缺 key，診斷路徑回 RENDER_FAILED")
    void corruptFile_generalPathStaysMissingKey_detailedReportsRenderFailed()
        throws IOException {
        corruptZhTw();
        LangManager lang = new LangManager(plugin, Locale.US);
        lang.load();
        MessageService service = new MessageService(plugin, lang,
            mock(BedrockService.class));

        RenderedMessage rendered = service.render(
            "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE);
        assertTrue(rendered.missing(),
            "一般路徑在語言檔損壞時維持 1.4.0 語意（missing=true），實際: "
                + rendered.diagnosis());
        assertTrue(rendered.diagnosis().contains("ACELIB-MSG-001"),
            "一般路徑診斷維持 MSG-001，實際: " + rendered.diagnosis());
        assertEquals("", service.formatFormText(
            "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE));

        DetailedRender detailed = service.renderDetailed(
            "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE);
        assertEquals(RenderStatus.RENDER_FAILED, detailed.status(),
            "診斷路徑在語言檔損壞時為 RENDER_FAILED，診斷: " + detailed.diagnosis());
        assertTrue(!detailed.rendered().missing(), "診斷路徑的失敗結果不標 missing");
        assertTrue(detailed.diagnosis().contains("ACELIB-MSG-003"),
            "診斷路徑攜帶 MSG-003，實際: " + detailed.diagnosis());
    }

    @Test
    @DisplayName("診斷路徑三態不退化（ healthy 缺 key 仍為 KEY_MISSING 帶可用語系）")
    void detailedPath_healthyMissingKey_stillKeyMissing() {
        LangManager lang = new LangManager(plugin, Locale.US);
        lang.load();
        MessageService service = new MessageService(plugin, lang,
            mock(BedrockService.class));

        DetailedRender detailed = service.renderDetailed("does.not.exist", Map.of());
        assertEquals(RenderStatus.KEY_MISSING, detailed.status());
        assertTrue(detailed.rendered().missing());
        assertTrue(detailed.diagnosis().contains("does.not.exist"),
            "診斷必須帶完整 key，實際: " + detailed.diagnosis());
        assertTrue(detailed.diagnosis().contains("en_US"),
            "診斷必須帶可用語系，實際: " + detailed.diagnosis());
    }
}
