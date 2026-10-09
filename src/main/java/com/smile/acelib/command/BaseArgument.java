package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 型別化引數共用基底（package 內）。
 *
 * <p>收斂名稱校驗、前綴過濾與 {@code ACELIB-CMD-015} 錯誤建構，避免
 * 十種引數各自重寫。</p>
 */
abstract class BaseArgument<T> implements CommandArgument<T> {

    protected final String name;

    protected BaseArgument(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("argument name cannot be empty");
        }
        this.name = name;
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * 單 token 不變條件：空白、空字串、含空白字元一律拒絕。
     *
     * @param raw 輸入；不可為 null
     * @throws CommandException {@code ACELIB-CMD-015} 當違反不變條件
     */
    protected void requireSingleToken(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isEmpty() || raw.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(raw, "must be a single token without whitespace");
        }
    }

    /**
     * 建構 {@code ACELIB-CMD-015} 例外（訊息經指定訊息表產生）。
     */
    protected CommandException invalid(String value, String reason,
                                       CommandMessages messages) {
        CommandMessages effective = messages == null
            ? DefaultCommandMessages.instance()
            : messages;
        return new CommandException(CommandErrorKind.INVALID_ARGUMENT,
            effective.invalidArgument(name, value, reason),
            Map.of("arg", name, "value", value, "reason", reason));
    }

    /** 建構 {@code ACELIB-CMD-015} 例外（預設英文訊息）。 */
    protected CommandException invalid(String value, String reason) {
        return invalid(value, reason, null);
    }

    /** 大小寫不敏感前綴過濾（保留候選原形，不可變回傳）。 */
    protected static List<String> filterPrefix(Collection<String> candidates,
                                               String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String folded = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null
                && candidate.toLowerCase(java.util.Locale.ROOT).startsWith(folded)) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    protected static CommandMessages effective(CommandMessages messages) {
        return messages == null ? DefaultCommandMessages.instance() : messages;
    }
}
