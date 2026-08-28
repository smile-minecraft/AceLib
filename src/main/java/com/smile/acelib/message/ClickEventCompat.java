package com.smile.acelib.message;

import net.kyori.adventure.text.event.ClickEvent;

/**
 * Adventure 4 / 5 相容的 click event 描述子。
 *
 * <p>Adventure 5.2.0 將 {@code ClickEvent.Action} 由 enum 改為 sealed class，並移除
 * {@code ClickEvent.value()}。同一份 production JAR 必須同時在 4.26.1 與 5.2.0 runtime
 * 建立 Bedrock fallback hint，因此本 helper 只依賴兩版共有的 binary subset：</p>
 * <ul>
 *   <li>{@code click.action().toString()}：兩版皆回傳協定名（run_command / suggest_command /
 *       open_url / copy_to_clipboard / change_page），大小寫一致；</li>
 *   <li>{@code click.payload()}：兩版皆存在；</li>
 *   <li>{@code Payload.Text.value()} / {@code Payload.Int.integer()}：兩版皆存在。</li>
 * </ul>
 *
 * <p>禁止在 production 使用 {@code ClickEvent.Action} 的 static 常數 / subtype、
 * {@code values()} / {@code valueOf()}、{@code ClickEvent.value()}、{@code action().name()}
 * —— 這些在 v5 會造成 linkage error。對應防護見
 * {@code AdventureCompatBytecodeGateTest}。</p>
 */
final class ClickEventCompat {

    /** 與 Bedrock fallback 四種可讀 action 對應的內部列舉（非 Adventure API）。 */
    enum Kind {
        RUN_COMMAND,
        SUGGEST_COMMAND,
        OPEN_URL,
        COPY_TO_CLIPBOARD,
        UNKNOWN
    }

    /** 不可變的描述子：action 種類 + 安全純文字 payload。 */
    static final class Descriptor {
        final Kind kind;
        final String payload;

        Descriptor(Kind kind, String payload) {
            this.kind = kind;
            this.payload = payload;
        }
    }

    private ClickEventCompat() {
    }

    /**
     * 將 click event 描述為內部 {@link Descriptor}。
     *
     * @param click 來源 click event（Adventure 4 或 5 實例皆可）
     * @return 非空描述子；未知 / non-text payload 歸 {@link Kind#UNKNOWN} 且 payload 為
     *         安全純文字（不拋 linkage error、不隱藏錯誤）
     */
    static Descriptor describe(ClickEvent click) {
        Kind kind = mapKind(click.action().toString());
        String payload = extractPayload(click);
        return new Descriptor(kind, payload);
    }

    private static Kind mapKind(String actionString) {
        return switch (actionString.toLowerCase()) {
            case "run_command" -> Kind.RUN_COMMAND;
            case "suggest_command" -> Kind.SUGGEST_COMMAND;
            case "open_url" -> Kind.OPEN_URL;
            case "copy_to_clipboard" -> Kind.COPY_TO_CLIPBOARD;
            default -> Kind.UNKNOWN;
        };
    }

    private static String extractPayload(ClickEvent click) {
        ClickEvent.Payload payload = click.payload();
        if (payload instanceof ClickEvent.Payload.Text text) {
            return text.value();
        }
        if (payload instanceof ClickEvent.Payload.Int num) {
            return String.valueOf(num.integer());
        }
        // 未知 / non-text payload：fail-safe 空字串（可讀、非 executable）。
        return "";
    }
}
