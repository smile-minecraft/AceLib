package com.smile.acelib.message;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 帶狀態的單次渲染結果（Supported API）。
 *
 * <p>在 {@link RenderedMessage} 之上多攜帶三項診斷資訊：</p>
 * <ul>
 *   <li>{@link #status} — 本次渲染的狀態分類（成功／語系未載入／缺 key／渲染失敗）</li>
 *   <li>{@link #availableLocales} — 磁碟 {@code lang/} 目錄下實際存在的語系
 *       （依檔名排序；僅在 {@link RenderStatus#LOCALE_NOT_LOADED} 與
 *       {@link RenderStatus#KEY_MISSING} 時填入，其餘狀態為空清單。
 *       內建資源（plugin JAR 內）無法枚舉，不列入）</li>
 *   <li>{@link #diagnosis} — enriched 診斷字串：缺 key 時含完整 key 與可用語系，
 *       語系未載入時含請求語系與預設語系；成功時為空字串。
 *       {@link RenderedMessage#diagnosis()} 維持原樣，不被改寫。</li>
 * </ul>
 *
 * @param rendered 單次渲染結果；never null（缺 key 或失敗時為空值表達，不為 null）
 * @param status 本次渲染的狀態；never null
 * @param availableLocales 磁碟上可用的語系；never null（不相關的狀態為空清單）
 * @param diagnosis enriched 診斷字串；never null（成功時為空字串）
 * @since 1.5.0
 */
public record DetailedRender(
        RenderedMessage rendered,
        RenderStatus status,
        List<Locale> availableLocales,
        String diagnosis) {

    /**
     * 緊湊建構子：拒絕 null 欄位，可用語系複製為不可變清單。
     */
    public DetailedRender {
        Objects.requireNonNull(rendered, "rendered");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableLocales, "availableLocales");
        Objects.requireNonNull(diagnosis, "diagnosis");
        availableLocales = List.copyOf(availableLocales);
    }
}
