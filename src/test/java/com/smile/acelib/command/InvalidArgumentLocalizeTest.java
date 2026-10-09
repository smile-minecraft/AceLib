package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code INVALID_ARGUMENT} 在地化分支測試（第三階段收尾）。
 *
 * <p>型別化引數在拋出點已用訊息表產生訊息；presentation 層
 * （{@link LocalizingReplySink} 經 {@link CommandMessages#localize}）
 * 必須能依 kind + vars 重算出同一條訊息（缺 key 才退回原文），
 * 而不是落到 default 回空字串再退回原文。</p>
 */
@DisplayName("INVALID_ARGUMENT 在地化分支")
class InvalidArgumentLocalizeTest {

    /** 只實作 invalidArgument 並記錄參數的訊息表 stub。 */
    static final class RecordingMessages implements CommandMessages {
        String lastArg;
        String lastValue;
        String lastReason;
        String reply = "-localized-";

        @Override
        public String invalidArgument(String arg, String value, String reason) {
            lastArg = arg;
            lastValue = value;
            lastReason = reason;
            return reply;
        }
    }

    @Test
    @DisplayName("localize 對 INVALID_ARGUMENT 回傳 invalidArgument 產生的字串")
    void localize_invalidArgument_delegatesToInvalidArgument() {
        RecordingMessages messages = new RecordingMessages();
        messages.reply = "無效的引數 <amount>: 'abc'";
        CommandException ex = new CommandException(CommandErrorKind.INVALID_ARGUMENT,
            "original english",
            Map.of("arg", "amount", "value", "abc", "reason", "not a number"));
        assertEquals("無效的引數 <amount>: 'abc'", messages.localize(ex));
        assertEquals("amount", messages.lastArg);
        assertEquals("abc", messages.lastValue);
        assertEquals("not a number", messages.lastReason);
    }

    @Test
    @DisplayName("缺 reason 但有 options 的舊 vars 仍能重算出選項訊息")
    void localize_legacyOptionsVars_rebuildsReason() {
        RecordingMessages messages = new RecordingMessages();
        messages.reply = "選項錯誤";
        CommandException ex = new CommandException(CommandErrorKind.INVALID_ARGUMENT,
            "original english",
            Map.of("arg", "mode", "value", "loud", "options", "silent,public"));
        assertEquals("選項錯誤", messages.localize(ex));
        assertTrue(messages.lastReason != null
                && messages.lastReason.contains("silent")
                && messages.lastReason.contains("public"),
            "reason 應重建出選項內容；實際: " + messages.lastReason);
    }

    @Test
    @DisplayName("固定選項錯誤經 presentation 層以其訊息表重算（非退回原文）")
    void fixedOptionError_relocalizedByPresentationMessages() {
        RecordingMessages parseMessages = new RecordingMessages();
        parseMessages.reply = "parse-table message";
        RecordingMessages viewMessages = new RecordingMessages();
        viewMessages.reply = "view-table message";
        CommandArgument<String> modeArg = Arguments.fixed("mode", "silent", "public");
        CommandException thrown = assertThrows(CommandException.class,
            () -> modeArg.parse("loud", parseMessages));
        assertEquals("ACELIB-CMD-015", thrown.getCode());
        assertEquals("parse-table message", thrown.getMessage());

        CommandRegistryTest.RecordingReplySink inner =
            new CommandRegistryTest.RecordingReplySink();
        LocalizingReplySink sink = new LocalizingReplySink(inner, viewMessages);
        CommandRegistryTest.TestSender sender =
            new CommandRegistryTest.TestSender("Console", false);
        sink.sendError(sender, thrown);
        assertEquals(1, inner.errors.size());
        assertEquals("view-table message", inner.errors.get(0).getMessage());
        assertEquals("mode", viewMessages.lastArg);
        assertEquals("loud", viewMessages.lastValue);
    }

    @Test
    @DisplayName("BigDecimal 錯誤的 vars 與 localize 分支對得上")
    void bigDecimalError_varsMatchLocalizeBranch() {
        RecordingMessages parseMessages = new RecordingMessages();
        parseMessages.reply = "parse-table message";
        RecordingMessages viewMessages = new RecordingMessages();
        viewMessages.reply = "view-table message";
        CommandArgument<BigDecimal> priceArg = Arguments.bigDecimal("price",
            new BigDecimal("0"), new BigDecimal("100"), 2);
        CommandException thrown = assertThrows(CommandException.class,
            () -> priceArg.parse("999.999", parseMessages));
        assertEquals(CommandErrorKind.INVALID_ARGUMENT, thrown.getKind());
        assertEquals("ACELIB-CMD-015", thrown.getCode());
        assertEquals("price", thrown.getVars().get("arg"));
        assertEquals("view-table message", viewMessages.localize(thrown));
        assertEquals(String.valueOf(thrown.getVars().get("reason")),
            viewMessages.lastReason);
    }
}
