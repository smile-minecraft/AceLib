package com.smile.acelib.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@link MessageService} 新介面測試（AceLib 1.5.0 第四階段訊息四項）。
 *
 * <ul>
 *   <li>渲染三態：{@link MessageService#renderDetailed} 區分語系未載入、缺 key、渲染失敗</li>
 *   <li>發送結果：{@code *WithFallbackResult} 回傳 {@link SendResult}（含基岩降級旗標）</li>
 *   <li>純文字輸出：{@link MessageService#formatPlain} 去除全部 MiniMessage 標記，可選前綴</li>
 *   <li>基岩降級風格：{@link BedrockFallbackStyle#PLAIN_TEXT} 攤平，預設仍為提示</li>
 * </ul>
 */
@DisplayName("MessageService 詳細渲染與發送結果")
class MessageServiceDetailedTest {

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
            w.write("colored: '<red>Hello</red> <gradient:red:blue>World</gradient>'\n");
            w.write("broken: 'Hello {player} <green'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
            w.write("message.bedrock.fallback.run_command: 'Run command: <payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: 'Suggest command: <payload>'\n");
            w.write("message.bedrock.fallback.open_url: 'Open URL: <payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: 'Copy to clipboard: <payload>'\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("greeting: '{player}你好！'\n");
            w.write("message:\n");
            w.write("  prefix: '[AceLib] '\n");
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

    private PlayerMock bedrockPlayer() {
        PlayerMock p = server.addPlayer();
        UUID id = p.getUniqueId();
        when(bedrock.isBedrockPlayer(id)).thenReturn(true);
        BedrockPlayerInfo info = new BedrockPlayerInfo(id, p.getName(),
            BedrockPlayerInfo.DeviceOs.UNKNOWN, BedrockPlayerInfo.InputMode.UNKNOWN,
            "en_US", BedrockPlayerInfo.LinkState.UNLINKED, null);
        when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(info));
        p.setLocale(Locale.US);
        return p;
    }

    private PlayerMock javaPlayer() {
        PlayerMock p = server.addPlayer();
        when(bedrock.isBedrockPlayer(p.getUniqueId())).thenReturn(false);
        return p;
    }

    private static String text(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

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

    // -----------------------------------------------------------------
    // 渲染三態
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("renderDetailed 三態")
    class RenderDetailed {

        @Test
        @DisplayName("查找鏈完全無內容時回 LOCALE_NOT_LOADED，診斷帶完整 key")
        void noChainContent_returnsLocaleNotLoaded() throws IOException {
            Files.deleteIfExists(new File(langDir, "en_US.yml").toPath());
            Files.deleteIfExists(new File(langDir, "zh_TW.yml").toPath());
            LangManager fresh = new LangManager(plugin, Locale.US);
            MessageService freshService = new MessageService(plugin, fresh);

            DetailedRender detailed = freshService.renderDetailed("missing.deep.key", Map.of());

            assertEquals(RenderStatus.LOCALE_NOT_LOADED, detailed.status());
            assertTrue(detailed.rendered().missing(), "未載入語系時 rendered 必須標 missing");
            assertTrue(detailed.diagnosis().contains("missing.deep.key"),
                "診斷必須帶完整 key，實際: " + detailed.diagnosis());
            assertTrue(detailed.rendered().diagnosis().contains("ACELIB-MSG-001"),
                "底層診斷維持 MSG-001，實際: " + detailed.rendered().diagnosis());
        }

        @Test
        @DisplayName("語系存在但 key 不存在時回 KEY_MISSING，診斷帶完整 key 與可用語系")
        void localeLoadedKeyMissing_returnsKeyMissing() {
            DetailedRender detailed = service.renderDetailed("does.not.exist", Map.of("a", "b"));

            assertEquals(RenderStatus.KEY_MISSING, detailed.status());
            assertTrue(detailed.rendered().missing());
            assertTrue(detailed.diagnosis().contains("does.not.exist"),
                "診斷必須帶完整 key，實際: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("en_US"),
                "診斷必須帶可用語系，實際: " + detailed.diagnosis());
            assertTrue(detailed.diagnosis().contains("zh_TW"),
                "診斷必須帶可用語系，實際: " + detailed.diagnosis());
            assertTrue(detailed.availableLocales().contains(Locale.US));
            // 舊診斷字串原樣保留在 rendered 內，不被改寫
            assertFalse(detailed.rendered().diagnosis().contains("availableLocales"),
                "舊診斷字串必須原樣保留，實際: " + detailed.rendered().diagnosis());
        }

        @Test
        @DisplayName("指定不存在的語系但預設語系存在時仍為 KEY_MISSING（非未載入）")
        void unknownLocaleWithDefaultLoaded_returnsKeyMissing() {
            DetailedRender detailed =
                service.renderDetailed("does.not.exist", Map.of(), Locale.FRANCE);

            assertEquals(RenderStatus.KEY_MISSING, detailed.status(),
                "預設語系檔存在代表查找鏈有內容，應為缺 key 而非未載入");
        }

        @Test
        @DisplayName("語言檔讀取拋錯時回 RENDER_FAILED")
        void langThrows_returnsRenderFailed() {
            LangManager throwing = mock(LangManager.class);
            when(throwing.get(anyString(), any())).thenThrow(new RuntimeException("disk boom"));
            when(throwing.getCurrentLocale()).thenReturn(Locale.US);
            when(throwing.getDefaultLocale()).thenReturn(Locale.US);
            MessageService throwingService = new MessageService(plugin, throwing);

            DetailedRender detailed = throwingService.renderDetailed("any.key", Map.of());

            assertEquals(RenderStatus.RENDER_FAILED, detailed.status());
            assertTrue(detailed.diagnosis().contains("ACELIB-MSG-003"),
                "渲染失敗診斷應攜帶 MSG-003，實際: " + detailed.diagnosis());
        }

        @Test
        @DisplayName("正常渲染回 OK，診斷為空且與 render 一致")
        void existingKey_returnsOk() {
            DetailedRender detailed =
                service.renderDetailed("greeting", Map.of("player", "smile"));

            assertEquals(RenderStatus.OK, detailed.status());
            assertEquals("", detailed.diagnosis());
            assertFalse(detailed.rendered().missing());
            assertEquals(service.render("greeting", Map.of("player", "smile")).component(),
                detailed.rendered().component());
            assertEquals(service.render("greeting", Map.of("player", "smile")).text(),
                detailed.rendered().text());
        }

        @Test
        @DisplayName("寬容解析器把未閉合標記留作原文時仍為 OK（記錄解析器實際行為）")
        void lenientParserKeepsLiteral_returnsOk() {
            DetailedRender detailed =
                service.renderDetailed("broken", Map.of("player", "smile"));

            assertEquals(RenderStatus.OK, detailed.status(),
                "此版 MiniMessage 寬容未閉合標記，應為 OK 而非 RENDER_FAILED");
            assertTrue(detailed.rendered().text().contains("Hello smile"),
                "可見正文必須保留，實際: " + detailed.rendered().text());
        }
    }

    // -----------------------------------------------------------------
    // 發送結果
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("WithFallbackResult 發送結果")
    class SendResultCases {

        @Test
        @DisplayName("基岩玩家帶 click 訊息：fallbackApplied=true、delivered=true")
        void chat_bedrockWithClick_applied() {
            PlayerMock p = bedrockPlayer();
            Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));

            SendResult result = service.sendChatWithFallbackResult(p, msg, null);

            assertTrue(result.fallbackApplied(), "基岩降級必須被標記");
            assertTrue(result.delivered(), "訊息必須送達");
            Component sent = p.nextComponentMessage();
            assertNotNull(sent);
            assertTrue(text(sent).contains("Run command: /warp"));
        }

        @Test
        @DisplayName("Java 玩家帶 click 訊息：fallbackApplied=false、delivered=true")
        void chat_javaWithClick_notApplied() {
            PlayerMock p = javaPlayer();
            Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));

            SendResult result = service.sendChatWithFallbackResult(p, msg, null);

            assertFalse(result.fallbackApplied(), "Java 玩家不得標記降級");
            assertTrue(result.delivered());
            assertNotNull(p.nextComponentMessage().clickEvent(), "Java 玩家保留原始 click");
        }

        @Test
        @DisplayName("基岩玩家無 click 訊息走提示路徑時不標記（未實際插入提示）")
        void chat_bedrockWithoutClick_notApplied() {
            PlayerMock p = bedrockPlayer();

            SendResult result =
                service.sendChatWithFallbackResult(p, Component.text("plain"), null);

            assertFalse(result.fallbackApplied(), "沒有 click 就沒有插入提示，不得標記");
            assertTrue(result.delivered());
        }

        @Test
        @DisplayName("查詢失敗時保留原始並回 false（不代表降級）")
        void chat_lookupFailure_notAppliedButDelivered() {
            PlayerMock p = server.addPlayer();
            UUID id = p.getUniqueId();
            when(bedrock.isBedrockPlayer(id)).thenReturn(true);
            when(bedrock.getPlayerInfo(id)).thenThrow(new RuntimeException("floodgate down"));
            p.setLocale(Locale.ROOT);
            Component original =
                Component.text("keep").clickEvent(ClickEvent.runCommand("/keep"));

            SendResult result = service.sendChatWithFallbackResult(p, original, null);

            assertFalse(result.fallbackApplied(), "無法判定 locale 時不得標記降級");
            assertTrue(result.delivered());
            assertNotNull(p.nextComponentMessage().clickEvent(), "原始 click 必須保留");
        }

        @Test
        @DisplayName("null 玩家與離線玩家：delivered=false，不拋錯")
        void chat_nullAndOffline_notDelivered() {
            Component msg = Component.text("x");
            SendResult nullResult = service.sendChatWithFallbackResult(null, msg, null);
            assertFalse(nullResult.delivered());
            assertFalse(nullResult.fallbackApplied());

            PlayerMock offline = bedrockPlayer();
            offline.disconnect();
            SendResult offlineResult =
                service.sendChatWithFallbackResult(offline, msg, null);
            assertFalse(offlineResult.delivered());
            assertFalse(offlineResult.fallbackApplied());
        }

        @Test
        @DisplayName("actionBar 基岩降級回 true")
        void actionBar_bedrock_applied() {
            PlayerMock p = bedrockPlayer();
            Component msg = Component.text("s").clickEvent(ClickEvent.suggestCommand("/warp"));

            SendResult result = service.sendActionBarWithFallbackResult(p, msg, null);

            assertTrue(result.fallbackApplied());
            assertTrue(result.delivered());
            assertTrue(text(p.nextActionBar()).contains("Suggest command: /warp"));
        }

        @Test
        @DisplayName("title 基岩降級回 true")
        void title_bedrock_applied() {
            TitleCapturingPlayer p = new TitleCapturingPlayer(server, "t-bedrock");
            server.addPlayer(p);
            UUID id = p.getUniqueId();
            when(bedrock.isBedrockPlayer(id)).thenReturn(true);
            when(bedrock.getPlayerInfo(id)).thenReturn(Optional.of(new BedrockPlayerInfo(id,
                p.getName(), BedrockPlayerInfo.DeviceOs.UNKNOWN,
                BedrockPlayerInfo.InputMode.UNKNOWN, "en_US",
                BedrockPlayerInfo.LinkState.UNLINKED, null)));
            Component title =
                Component.text("t").clickEvent(ClickEvent.openUrl("https://example.com"));

            SendResult result =
                service.sendTitleWithFallbackResult(p, title, Component.text("s"), null);

            assertTrue(result.fallbackApplied());
            assertTrue(result.delivered());
            assertTrue(text(p.lastTitle().title()).contains("Open URL: https://example.com"));
        }

        @Test
        @DisplayName("廣播混合玩家：任一降級即 true；全 Java 則 false")
        void broadcast_mixedAndJavaOnly() {
            PlayerMock bedrockP = bedrockPlayer();
            PlayerMock javaP = javaPlayer();
            Component msg = Component.text("hi").clickEvent(ClickEvent.runCommand("/b"));

            SendResult mixed = service.broadcastWithFallbackResult(msg, null);

            assertTrue(mixed.fallbackApplied(), "有基岩玩家被降級就必須標記");
            assertTrue(mixed.delivered());
            assertNotNull(bedrockP.nextComponentMessage());
            assertNotNull(javaP.nextComponentMessage());
        }

        @Test
        @DisplayName("廣播全 Java 玩家不標記")
        void broadcast_javaOnly_notApplied() {
            PlayerMock javaP = javaPlayer();
            Component msg = Component.text("hi").clickEvent(ClickEvent.runCommand("/b"));

            SendResult result = service.broadcastWithFallbackResult(msg, null);

            assertFalse(result.fallbackApplied());
            assertTrue(result.delivered());
            assertNotNull(javaP.nextComponentMessage());
        }
    }

    // -----------------------------------------------------------------
    // 純文字輸出
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("formatPlain 純文字輸出")
    class PlainText {

        @Test
        @DisplayName("含 red 與 gradient 的模板只剩可讀文字（預設不帶前綴）")
        void coloredTemplate_plainReadableText() {
            String out = service.formatPlain("colored", Map.of());

            assertEquals("Hello World", out, "純文字輸出應只剩可讀文字，實際: " + out);
            assertFalse(out.contains("<"), "不得殘留 MiniMessage 標記");
            assertFalse(out.contains("§"), "不得殘留色彩碼");
        }

        @Test
        @DisplayName("前綴選項：帶與不帶")
        void prefixOption_onAndOff() {
            String with = service.formatPlain("colored", Map.of(), true);
            String without = service.formatPlain("colored", Map.of(), false);

            assertEquals("[AceLib] Hello World", with, "帶前綴時應含 prefix，實際: " + with);
            assertEquals("Hello World", without, "不帶前綴時只有正文，實際: " + without);
        }

        @Test
        @DisplayName("缺 key 回空字串")
        void missingKey_returnsEmpty() {
            assertEquals("", service.formatPlain("does.not.exist", Map.of()));
        }

        @Test
        @DisplayName("失敗路徑盡力去標記：stripTagsBestEffort 去除標記與色彩碼")
        void stripHelper_removesTagsAndCodes() {
            String nasty =
                "<red>Hello</red> <gradient:red:blue>World</gradient> §aGreen <unclosed";
            String out = MessageService.stripTagsBestEffort(nasty);

            assertFalse(out.contains("<"), "標記必須去除，實際: " + out);
            assertFalse(out.contains("§"), "色彩碼必須去除，實際: " + out);
            assertTrue(out.contains("Hello"), "可讀文字必須保留，實際: " + out);
            assertTrue(out.contains("World"), "可讀文字必須保留，實際: " + out);
            assertTrue(out.contains("Green"), "可讀文字必須保留，實際: " + out);
        }
    }

    // -----------------------------------------------------------------
    // 基岩降級風格
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("BedrockFallbackStyle 攤平")
    class FallbackStyle {

        @Test
        @DisplayName("PLAIN_TEXT 把 click 與顏色攤成純文字並標記")
        void plainText_flattens() {
            PlayerMock p = bedrockPlayer();
            Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));

            SendResult result = service.sendChatWithFallbackResult(
                p, msg, null, BedrockFallbackStyle.PLAIN_TEXT);

            assertTrue(result.fallbackApplied(), "走基岩分支就必須標記");
            assertTrue(result.delivered());
            Component sent = p.nextComponentMessage();
            assertNotNull(sent);
            assertEquals(Component.text("click"), sent, "應攤成純文字 Component");
        }

        @Test
        @DisplayName("預設仍為提示風格（不傳風格＝HINTS）")
        void defaultStyle_isHints() {
            PlayerMock p = bedrockPlayer();
            Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));

            SendResult result = service.sendChatWithFallbackResult(p, msg, null);

            assertTrue(result.fallbackApplied());
            assertTrue(text(p.nextComponentMessage()).contains("Run command: /warp"),
                "預設必須維持提示風格");
        }

        @Test
        @DisplayName("舊 void 入口維持提示風格（行為不變）")
        void legacyVoidEntry_stillHints() {
            PlayerMock p = bedrockPlayer();
            Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));

            service.sendChatWithFallback(p, msg, null);

            assertTrue(text(p.nextComponentMessage()).contains("Run command: /warp"),
                "舊 void 入口必須維持提示風格");
        }
    }
}
