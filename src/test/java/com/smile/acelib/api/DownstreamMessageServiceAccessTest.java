package com.smile.acelib.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 下游插件視角：從非 {@code com.smile.acelib.message} package 直接呼叫
 * {@code MessageService(JavaPlugin, LangManager, BedrockService)} 三參數建構子，
 * 驗證公開化後下游可注入 {@link BedrockService} 啟用基岩 click 降級。
 *
     * <p>此測試刻意放在 {@code com.smile.acelib.api}（非 message package），
     * 以模擬下游插件無法看見 package-private 建構子的真實限制；若建構子
     * 未公開化，此處將因無法解析符號而編譯失敗，從而保證下游視角下的
     * 可見性契約被持續守護。</p>
 */
@DisplayName("Downstream MessageService access (3-arg ctor)")
class DownstreamMessageServiceAccessTest {

    private ServerMock server;
    private JavaPlugin downstreamPlugin;
    private LangManager lang;
    private BedrockService bedrock;
    private MessageService service;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();

        // 下游插件：自己的 JavaPlugin（非 AceLibPlugin），模擬下游視角。
        downstreamPlugin = mock(JavaPlugin.class);
        File dataFolder = Files.createTempDirectory("acelib-downstream").toFile();
        when(downstreamPlugin.getDataFolder()).thenReturn(dataFolder);
        when(downstreamPlugin.isEnabled()).thenReturn(true);

        File langDir = new File(dataFolder, "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            throw new IOException("無法建立 lang dir");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "en_US.yml"))) {
            w.write("message.bedrock.fallback.run_command: 'Run command: <payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: 'Suggest command: <payload>'\n");
            w.write("message.bedrock.fallback.open_url: 'Open URL: <payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: 'Copy to clipboard: <payload>'\n");
        }
        try (FileWriter w = new FileWriter(new File(langDir, "zh_TW.yml"))) {
            w.write("message.bedrock.fallback.run_command: '執行指令：<payload>'\n");
            w.write("message.bedrock.fallback.suggest_command: '建議指令：<payload>'\n");
            w.write("message.bedrock.fallback.open_url: '開啟網址：<payload>'\n");
            w.write("message.bedrock.fallback.copy_to_clipboard: '複製到剪貼簿：<payload>'\n");
        }

        lang = new LangManager(downstreamPlugin, Locale.US);
        lang.load();

        bedrock = mock(BedrockService.class);
        // 公開化後下游可注入 BedrockService（Red 階段此行編譯失敗：建構子不可見）。
        service = new MessageService(downstreamPlugin, lang, bedrock);
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

    @Test
    @DisplayName("下游注入 BedrockService：基岩玩家 click 被剝離並附加可讀 hint")
    void downstream_bedrock_stripsClickAndAppendsHint() {
        PlayerMock p = bedrockPlayer("en_US");
        p.setLocale(Locale.US);
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
    @DisplayName("下游注入 BedrockService：Java 玩家保留原始 click event")
    void downstream_javaKeepsClick() {
        PlayerMock p = javaPlayer();
        Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));
        service.sendChatWithFallback(p, msg, null);
        Component sent = p.nextComponentMessage();
        assertNotNull(sent, "Java 玩家必須收到訊息");
        assertTrue(hasClick(sent), "Java 玩家應保留原始 ClickEvent");
    }

    @Test
    @DisplayName("下游注入 unavailable facade：不拋出、不降級、訊息原樣送出")
    void downstream_unavailableFacade_noFallback() {
        BedrockService unavailable = BedrockService.forUnavailable(BedrockService.NOT_READY);
        MessageService svc = new MessageService(downstreamPlugin, lang, unavailable);
        PlayerMock p = javaPlayer();
        Component msg = Component.text("click").clickEvent(ClickEvent.runCommand("/warp"));
        assertDoesNotThrow(() -> svc.sendChatWithFallback(p, msg, null),
            "unavailable facade 注入不得拋出");
        Component sent = p.nextComponentMessage();
        assertNotNull(sent, "unavailable facade 下仍應送出訊息");
        assertTrue(hasClick(sent), "unavailable facade 下應保留原始 ClickEvent（不降級）");
        assertFalse(text(sent).contains("Run command:"), "unavailable facade 下不應附加 hint");
    }

    @Test
    @DisplayName("下游注入 null BedrockService：NullPointerException")
    void downstream_nullBedrock_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> new MessageService(downstreamPlugin, lang, null),
            "bedrock 參數為 null 必須拋出 NullPointerException");
    }
}
