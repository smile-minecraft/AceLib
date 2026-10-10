package com.example;

import com.smile.acelib.message.BedrockFallbackStyle;
import com.smile.acelib.message.DetailedRender;
import com.smile.acelib.message.MessageService;
import com.smile.acelib.message.RenderStatus;
import com.smile.acelib.message.SendResult;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * AceLib 1.5.0 訊息線的外部 consumer 範例。
 *
 * <p>本類別位於 AceLib 外部套件（{@code com.example}），只用公開 API：
 * 以 {@link MessageService#renderDetailed} 分辨語系未載入、缺 key 與渲染失敗；
 * 以 {@code *WithFallbackResult} 拿回 {@link SendResult} 確認基岩降級是否真的套用；
 * 以 {@link MessageService#formatPlain} 給 console 與外部頻道純文字；
 * 以 {@link BedrockFallbackStyle} 在可讀提示與攤平純文字之間選擇。</p>
 *
 * <p>需要伺服器的方法（第一個參數為 {@code MessageService} 或
 * {@code Player}）隨伺服器啟動執行；純分類與摘要方法可在單元測試直接呼叫。</p>
 */
public final class MessageV150Example {

    private MessageV150Example() {
    }

    /**
     * 以目前全域語系渲染一次，並回傳帶狀態的結果。
     *
     * @param messages 訊息服務；不可為 null
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 帶狀態的單次渲染結果；永不為 null
     */
    public static DetailedRender renderForAudit(MessageService messages,
            String key, Map<String, Object> vars) {
        Objects.requireNonNull(messages, "messages");
        return messages.renderDetailed(key, vars);
    }

    /**
     * 以指定語系渲染一次，並回傳帶狀態的結果。
     *
     * @param messages 訊息服務；不可為 null
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @param locale 指定語系；可為 null（→ 全域目前語系）
     * @return 帶狀態的單次渲染結果；永不為 null
     */
    public static DetailedRender renderForAudit(MessageService messages,
            String key, Map<String, Object> vars, Locale locale) {
        Objects.requireNonNull(messages, "messages");
        return messages.renderDetailed(key, vars, locale);
    }

    /**
     * 渲染一次並把狀態翻成一句可記錄的摘要。
     *
     * @param messages 訊息服務；不可為 null
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 狀態摘要；永不為 null
     */
    public static String auditRender(MessageService messages,
            String key, Map<String, Object> vars) {
        Objects.requireNonNull(messages, "messages");
        DetailedRender detailed = messages.renderDetailed(key, vars);
        return describe(detailed.status(), detailed.diagnosis());
    }

    /**
     * 把渲染狀態翻成一句可記錄的摘要（純函式，不需伺服器）。
     *
     * @param status 渲染狀態；不可為 null
     * @param diagnosis 診斷字串；可為 null（→ 空字串）
     * @return 狀態摘要；永不為 null
     */
    public static String describe(RenderStatus status, String diagnosis) {
        Objects.requireNonNull(status, "status");
        String detail = diagnosis == null ? "" : diagnosis;
        return switch (status) {
            case OK -> "OK";
            case LOCALE_NOT_LOADED -> "LOCALE_NOT_LOADED: " + detail;
            case KEY_MISSING -> "KEY_MISSING: " + detail;
            case RENDER_FAILED -> "RENDER_FAILED: " + detail;
        };
    }

    /**
     * 給 console 與外部頻道用的純文字（不帶前綴）。
     *
     * @param messages 訊息服務；不可為 null
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 去除標記的純文字；key 缺失或讀取失敗時為空字串
     */
    public static String plainForConsole(MessageService messages,
            String key, Map<String, Object> vars) {
        Objects.requireNonNull(messages, "messages");
        return messages.formatPlain(key, vars);
    }

    /**
     * 給 console 與外部頻道用的純文字（帶前綴）。
     *
     * @param messages 訊息服務；不可為 null
     * @param key 訊息 key；不可為 null
     * @param vars 變數替換表；可為 null
     * @return 去除標記的純文字；key 缺失或讀取失敗時為空字串
     */
    public static String plainWithPrefix(MessageService messages,
            String key, Map<String, Object> vars) {
        Objects.requireNonNull(messages, "messages");
        return messages.formatPlain(key, vars, true);
    }

    /**
     * 對單一玩家發送聊天訊息（預設降級風格），並回傳發送結果。
     *
     * @param messages 訊息服務；不可為 null
     * @param player 目標玩家；可為 null（→ 未送達）
     * @param message 原始 Component；可為 null（→ 未送達）
     * @param locale 每次呼叫的語系覆寫；可為 null
     * @return 發送結果（含是否確實套用基岩降級）；永不為 null
     */
    public static SendResult sendWithDefaultStyle(MessageService messages,
            Player player, Component message, Locale locale) {
        Objects.requireNonNull(messages, "messages");
        return messages.sendChatWithFallbackResult(player, message, locale);
    }

    /**
     * 對單一玩家發送聊天訊息（攤平成純文字），並回傳發送結果。
     *
     * @param messages 訊息服務；不可為 null
     * @param player 目標玩家；可為 null（→ 未送達）
     * @param message 原始 Component；可為 null（→ 未送達）
     * @param locale 每次呼叫的語系覆寫；可為 null
     * @return 發送結果（含是否確實套用基岩降級）；永不為 null
     */
    public static SendResult sendPlainText(MessageService messages,
            Player player, Component message, Locale locale) {
        Objects.requireNonNull(messages, "messages");
        return messages.sendChatWithFallbackResult(
            player, message, locale, BedrockFallbackStyle.PLAIN_TEXT);
    }

    /**
     * 把發送結果翻成一句可記錄的摘要（純函式，不需伺服器）。
     *
     * @param result 發送結果；不可為 null
     * @return 發送摘要；永不為 null
     */
    public static String summarizeSend(SendResult result) {
        Objects.requireNonNull(result, "result");
        return "delivered=" + result.delivered()
            + ", fallbackApplied=" + result.fallbackApplied();
    }

    /**
     * 預設的基岩降級風格：click 轉可讀提示，保留樣式。
     *
     * @return {@link BedrockFallbackStyle#HINTS}；永不為 null
     */
    public static BedrockFallbackStyle defaultFallbackStyle() {
        return BedrockFallbackStyle.HINTS;
    }

    /**
     * 攤平的基岩降級風格：整體轉純文字，不帶任何互動與樣式。
     *
     * @return {@link BedrockFallbackStyle#PLAIN_TEXT}；永不為 null
     */
    public static BedrockFallbackStyle plainTextStyle() {
        return BedrockFallbackStyle.PLAIN_TEXT;
    }
}
