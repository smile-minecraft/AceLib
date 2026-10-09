package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.smile.acelib.AceLibPlugin;import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.platform.PlatformDetector;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 訊息模組失敗處理：損壞語系檔的三態判定、廣播部分失敗的旗標、
 * 公開純文字入口的 legacy 色彩碼。
 */
@DisplayName("MessageService 失敗處理")
class MessageServiceFailureHandlingTest {

    private ServerMock server;
    private AceLibPlugin plugin;
    private File langDir;
    private LangManager lang;
    private BedrockService bedrock;
    private MessageService service;

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
            w.write("legacy: '§aGreen §x§f§f§0§0§0§0Red'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
            w.write("message.bedrock.fallback.run_command: 'Run command: <payload>'\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("greeting: '{player}你好！'\n");
        }

        lang = new LangManager(plugin, Locale.US);
        lang.load();
        bedrock = Mockito.mock(BedrockService.class);
        service = new MessageService(plugin, lang, bedrock);
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

    private PlayerMock javaPlayer() {
        PlayerMock p = server.addPlayer();
        when(bedrock.isBedrockPlayer(p.getUniqueId())).thenReturn(false);
        return p;
    }

    private static final class FailingPlayer extends PlayerMock {
        FailingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void sendMessage(Component message) {
            throw new RuntimeException("boom");
        }
    }

    // -----------------------------------------------------------------
    // 損壞語系檔的三態判定
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("損壞語系檔的三態判定")
    class CorruptLocale {

        @Test
        @DisplayName("指定語系檔損壞且其他層無該 key 時回 RENDER_FAILED")
        void corruptRequestedLocale_returnsRenderFailed() throws IOException {
            corruptZhTw();

            DetailedRender detailed = service.renderDetailed(
                "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE);

            assertEquals(RenderStatus.RENDER_FAILED, detailed.status(),
                "損壞檔案不是缺 key，必須是渲染失敗，診斷: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("ACELIB-MSG-003"),
                "診斷應攜帶 MSG-003，實際: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("some.missing.key"),
                "診斷應保留完整 key，實際: " + detailed.diagnosis());
        }

        @Test
        @DisplayName("指定語系檔損壞但預設層有該 key 時仍回退成功為 OK")
        void corruptRequestedLocale_keyInDefault_returnsOk() throws IOException {
            corruptZhTw();

            DetailedRender detailed = service.renderDetailed(
                "greeting", Map.of("player", "s"), Locale.TRADITIONAL_CHINESE);

            assertEquals(RenderStatus.OK, detailed.status(),
                "其他層成功回退必須仍為 OK，診斷: " + detailed.diagnosis());
            assertTrue(detailed.rendered().text().contains("Hello s"),
                "應取到預設層模板，實際: " + detailed.rendered().text());
        }
    }

    // -----------------------------------------------------------------
    // 廣播部分失敗的旗標
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("廣播部分失敗的旗標")
    class BroadcastPartialFailure {

        @Test
        @DisplayName("Java 成功、基岩降級後發送失敗：applied=true、delivered=true")
        void javaOkBedrockSendFails_appliedAndDelivered() {
            PlayerMock javaP = javaPlayer();
            FailingPlayer failing = new FailingPlayer(server, "boom-player");
            server.addPlayer(failing);
            UUID id = failing.getUniqueId();
            when(bedrock.isBedrockPlayer(id)).thenReturn(true);
            when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(new BedrockPlayerInfo(id,
                failing.getName(), BedrockPlayerInfo.DeviceOs.UNKNOWN,
                BedrockPlayerInfo.InputMode.UNKNOWN, "en_US",
                BedrockPlayerInfo.LinkState.UNLINKED, null)));
            Component msg = Component.text("hi").clickEvent(ClickEvent.runCommand("/b"));

            SendResult result = service.broadcastWithFallbackResult(msg, null);

            assertTrue(result.fallbackApplied(),
                "降級已完成就必須標記，不因後續發送失敗而丟失");
            assertTrue(result.delivered(), "Java 玩家送達，delivered 必須 true");
            assertNotNull(javaP.nextComponentMessage(), "Java 玩家必須收到廣播");
        }

        @Test
        @DisplayName("舊 void 廣播入口在部分失敗時不中斷")
        void legacyBroadcast_partialFailure_noThrow() {
            PlayerMock javaP = server.addPlayer();
            FailingPlayer failing = new FailingPlayer(server, "boom-legacy");
            server.addPlayer(failing);
            UUID id = failing.getUniqueId();
            when(bedrock.isBedrockPlayer(id)).thenReturn(true);
            when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(new BedrockPlayerInfo(id,
                failing.getName(), BedrockPlayerInfo.DeviceOs.UNKNOWN,
                BedrockPlayerInfo.InputMode.UNKNOWN, "en_US",
                BedrockPlayerInfo.LinkState.UNLINKED, null)));
            Component msg = Component.text("hi").clickEvent(ClickEvent.runCommand("/b"));

            service.broadcastWithFallback(msg, null);

            assertNotNull(javaP.nextComponentMessage(), "Java 玩家必須收到廣播");
            // 降級後發送失敗的玩家不影響其他人，也不向外拋錯（方法正常返回即證明）
        }
    }

    // -----------------------------------------------------------------
    // 公開純文字入口的 legacy 色彩碼
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("公開純文字入口的 legacy 色彩碼")
    class PlainLegacy {

        @Test
        @DisplayName("含 legacy 色彩碼的模板經公開入口輸出無 § 殘留")
        void legacyTemplate_noResidualSection() {
            String out = service.formatPlain("legacy", Map.of());

            assertFalse(out.contains("§"), "公開入口輸出不得殘留 §，實際: " + out);
            assertTrue(out.contains("Green"), "可讀文字必須保留，實際: " + out);
            assertTrue(out.contains("Red"), "可讀文字必須保留，實際: " + out);
        }
    }

    // -----------------------------------------------------------------
    // 內建資源讀取失敗的分類
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("內建資源讀取失敗的分類")
    class BuiltinReadFailure {

        private static final class FailingInputStream extends InputStream {
            @Override
            public int read() throws IOException {
                throw new IOException("stream boom");
            }
        }

        @Test
        @DisplayName("內建資源串流讀取失敗時回 RENDER_FAILED（非缺 key）")
        void builtinStreamReadFails_returnsRenderFailed() {
            AceLibPlugin spyPlugin = Mockito.spy(plugin);
            Mockito.doReturn(new FailingInputStream()).when(spyPlugin)
                .getResource("lang/zh_TW.yml");
            MessageService spyService = new MessageService(spyPlugin, lang);

            DetailedRender detailed = spyService.renderDetailed(
                "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE);

            assertEquals(RenderStatus.RENDER_FAILED, detailed.status(),
                "資源存在但讀不到是載入失敗，不是缺 key，診斷: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("ACELIB-MSG-003"),
                "診斷應攜帶 MSG-003，實際: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("some.missing.key"),
                "診斷應保留完整 key，實際: " + detailed.diagnosis());
        }

        @Test
        @DisplayName("內建資源不存在（getResource null）時仍為缺 key（語意不變）")
        void builtinResourceAbsent_stillKeyMissing() {
            DetailedRender detailed = service.renderDetailed(
                "some.missing.key", Map.of(), Locale.TRADITIONAL_CHINESE);

            assertEquals(RenderStatus.KEY_MISSING, detailed.status(),
                "資源不存在必須維持缺 key，診斷: " + detailed.diagnosis());
        }
    }
}
