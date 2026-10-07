package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.smile.acelib.message.MessageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 指令錯誤訊息在地化測試（Slice 5）。
 *
 * <p>{@link CommandMessages} 為在地化契約：{@link DefaultCommandMessages}
 * 提供英文預設；{@link MessageServiceCommandMessages} 經 message 模組查 key；
 * {@link LocalizingReplySink} 在 presentation 層套用，缺 key 時退回例外原文。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指令錯誤訊息在地化")
class CommandMessagesTest {

    @Mock
    MessageService messageService;

    @Nested
    @DisplayName("預設英文訊息")
    class Defaults {

        @Test
        @DisplayName("各分類訊息非空且含關鍵變數")
        void defaults_nonEmpty() {
            CommandMessages messages = DefaultCommandMessages.instance();
            assertTrue(!messages.noPermission("punish.ban", "punish", "ban").isEmpty());
            assertTrue(messages.noPermission("punish.ban", "punish", "ban")
                .contains("punish.ban"));
            assertTrue(!messages.invalidArgument("amount", "abc", "not a number").isEmpty());
            assertTrue(!messages.unknownSubcommand("punish", "foo").isEmpty());
            assertTrue(!messages.cooldownActive("ban", 1500L).contains("{"),
                "預設訊息不應殘留未替換 placeholder；實際: "
                    + messages.cooldownActive("ban", 1500L));
            assertTrue(messages.cooldownActive("ban", 1500L).contains("1500")
                || messages.cooldownActive("ban", 1500L).contains("1.5"),
                "冷卻訊息應含剩餘時間；實際: " + messages.cooldownActive("ban", 1500L));
        }

        @Test
        @DisplayName("null 參數契約：null permission 視為無需求（不斷言 NPE）")
        void nullPermission_tolerated() {
            CommandMessages messages = DefaultCommandMessages.instance();
            assertTrue(!messages.noPermission(null, "punish", "ban").isEmpty());
        }
    }

    @Nested
    @DisplayName("LocalizingReplySink")
    class LocalizingSink {

        @Test
        @DisplayName("CommandException 按 kind 在地化；非 CommandException 走原文")
        void sendError_localizesByKind() {
            CommandRegistryTest.RecordingReplySink inner =
                new CommandRegistryTest.RecordingReplySink();
            CommandMessages messages = new CommandMessages() {
                @Override
                public String noPermission(String p, String c, String s) {
                    return "沒有權限：" + p;
                }
            };
            LocalizingReplySink sink = new LocalizingReplySink(inner, messages);
            CommandRegistryTest.TestSender sender =
                new CommandRegistryTest.TestSender("Steve", true);
            sink.sendError(sender, new CommandException(CommandErrorKind.NO_PERMISSION,
                "no permission: punish.ban", Map.of("permission", "punish.ban")));
            assertEquals(1, inner.errors.size());
            assertTrue(inner.errors.get(0).getMessage().contains("沒有權限"),
                "應為在地化訊息；實際: " + inner.errors.get(0).getMessage());
            IllegalStateException raw = new IllegalStateException("boom");
            sink.sendError(sender, raw);
            assertTrue(inner.errors.get(1) == raw, "非 CommandException 應原樣轉交");
        }

        @Test
        @DisplayName("在地化回空時退回例外原文（不送空字串）")
        void emptyLocalization_fallsBackToOriginal() {
            CommandRegistryTest.RecordingReplySink inner =
                new CommandRegistryTest.RecordingReplySink();
            CommandMessages empty = new CommandMessages() {
                @Override
                public String noPermission(String p, String c, String s) {
                    return "";
                }
            };
            LocalizingReplySink sink = new LocalizingReplySink(inner, empty);
            CommandRegistryTest.TestSender sender =
                new CommandRegistryTest.TestSender("Steve", true);
            CommandException ex = new CommandException(CommandErrorKind.NO_PERMISSION,
                "no permission: punish.ban");
            sink.sendError(sender, ex);
            assertEquals("no permission: punish.ban", inner.errors.get(0).getMessage());
        }

        @Test
        @DisplayName("send 與 sendPlayerAsync 原樣委派")
        void passthrough_delegated() {
            CommandRegistryTest.RecordingReplySink inner =
                new CommandRegistryTest.RecordingReplySink();
            LocalizingReplySink sink = new LocalizingReplySink(inner,
                DefaultCommandMessages.instance());
            CommandRegistryTest.TestSender sender =
                new CommandRegistryTest.TestSender("Steve", true);
            sink.send(sender, "hi");
            assertEquals(List.of("hi"), inner.sent);
            assertThrows(NullPointerException.class, () -> new LocalizingReplySink(null,
                DefaultCommandMessages.instance()));
        }
    }

    @Nested
    @DisplayName("MessageService 轉接")
    class ServiceAdapter {

        @Test
        @DisplayName("key 命中時回傳 message 模組格式化結果")
        void hit_returnsFormatted() {
            when(messageService.format(anyString(), any())).thenReturn("沒有權限：punish.ban");
            MessageServiceCommandMessages adapter = new MessageServiceCommandMessages(
                messageService, "command.error.", DefaultCommandMessages.instance());
            assertEquals("沒有權限：punish.ban",
                adapter.noPermission("punish.ban", "punish", "ban"));
        }

        @Test
        @DisplayName("key 缺失（空字串）時退回 fallback 預設")
        void missingKey_fallsBack() {
            when(messageService.format(anyString(), any())).thenReturn("");
            MessageServiceCommandMessages adapter = new MessageServiceCommandMessages(
                messageService, "command.error.", DefaultCommandMessages.instance());
            String out = adapter.noPermission("punish.ban", "punish", "ban");
            assertEquals(DefaultCommandMessages.instance()
                .noPermission("punish.ban", "punish", "ban"), out);
        }

        @Test
        @DisplayName("null 建構參數拋 NullPointerException")
        void nullArgs_rejected() {
            assertThrows(NullPointerException.class, () -> new MessageServiceCommandMessages(
                null, "command.error.", DefaultCommandMessages.instance()));
        }
    }
}
