package com.smile.acelib.command;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在地化回覆出口裝飾器（presentation 層）。
 *
 * <p>{@link #send}／{@link #sendPlayerAsync} 原樣委派；
 * {@link #sendError} 在錯誤為 {@link CommandException} 時經
 * {@link CommandMessages#localize} 轉為在地化字串後再委派。
 * 在地化結果為空（缺 key）時退回例外原文，不送空字串；
 * 非 {@link CommandException} 原樣轉交。</p>
 *
 * @see CommandMessages
 * @see MessageServiceCommandMessages
 * @since 1.4.0
 */
public final class LocalizingReplySink implements ReplySink {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final ReplySink delegate;
    private final CommandMessages messages;

    /**
     * @param delegate 底層出口；不可為 null
     * @param messages 訊息表；不可為 null
     */
    public LocalizingReplySink(ReplySink delegate, CommandMessages messages) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public void send(Sender sender, String message) {
        delegate.send(sender, message);
    }

    @Override
    public void sendError(Sender sender, Throwable error) {
        Objects.requireNonNull(error, "error");
        if (!(error instanceof CommandException commandError)) {
            delegate.sendError(sender, error);
            return;
        }
        String localized;
        try {
            localized = messages.localize(commandError);
        } catch (Throwable t) {
            logFallback(commandError, t);
            delegate.sendError(sender, error);
            return;
        }
        if (localized == null || localized.isEmpty()) {
            delegate.sendError(sender, error);
            return;
        }
        delegate.sendError(sender, new LocalizedCommandError(commandError, localized));
    }

    @Override
    public void sendPlayerAsync(PlayerHandle player, String message) {
        delegate.sendPlayerAsync(player, message);
    }

    private static void logFallback(CommandException original, Throwable failure) {
        try {
            LOGGER.log(Level.FINE,
                "localizing command error failed; falling back to original message: "
                    + failure.getMessage());
        } catch (Throwable ignored) {
            // 日誌失敗不中斷回覆。
        }
    }

    /**
     * 在地化後的錯誤殼：保留原 kind／code／vars（管理員診斷可用），
     * 訊息換為在地化字串。
     */
    static final class LocalizedCommandError extends CommandException {
        private final String localized;
        private final String code;

        LocalizedCommandError(CommandException original, String localized) {
            super(original.getKind(), original.getMessage(), original.getVars());
            this.localized = localized;
            // CUSTOM kind 的 code 由 caller 自訂，必須原樣保留（非 kind 預設值）。
            this.code = original.getCode();
        }

        @Override
        public String getMessage() {
            return localized;
        }

        @Override
        public String getCode() {
            return code;
        }
    }
}
