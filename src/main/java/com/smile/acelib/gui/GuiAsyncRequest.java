package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * 非同步更新請求合約（Supported API）。
 *
 * <p>後續插件在發起非同步資料載入前，先透過
 * {@link GuiService#beginAsyncUpdate(UUID, long, int)} 取得本物件；非同步結果回來後，
 * 將本物件連同 {@link GuiPage} 與 renderer 傳入
 * {@link GuiService#applyAsyncUpdate(GuiAsyncRequest, GuiPage, Runnable)}。</p>
 *
 * <p>本物件攜帶重新驗證所需的全部維度（非同步結果回來時必須重新確認）：</p>
 * <ul>
 *   <li>{@link #playerUuid()} — 目標玩家 UUID（不持有 {@code Player} reference）</li>
 *   <li>{@link #sessionGeneration()} — 請求建立時綁定的 session generation</li>
 *   <li>{@link #pageIndex()} — 請求所屬頁碼（結果必須仍屬於目前頁面或請求）</li>
 *   <li>{@link #requestGeneration()} — 單調遞增的請求序號；同一 session 內後發的請求
 *       會取得更大的值，舊請求套用時因序號不符被拒絕（{@code ACELIB-GUI-016}）</li>
 * </ul>
 *
 * <h2>不可變</h2>
 * <p>所有欄位為 {@code final}；本物件不持有 {@link org.bukkit.entity.Player}
 * reference，可安全跨執行緒 / 跨 region 保存直到非同步結果回來。</p>
 *
 * @see GuiService
 * @since 1.0.0
 */
public final class GuiAsyncRequest {

    private final UUID playerUuid;
    private final long sessionGeneration;
    private final int pageIndex;
    private final long requestGeneration;

    GuiAsyncRequest(UUID playerUuid, long sessionGeneration, int pageIndex,
                    long requestGeneration) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        if (sessionGeneration <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] sessionGeneration 必須 > 0；實際: "
                    + sessionGeneration);
        }
        this.sessionGeneration = sessionGeneration;
        this.pageIndex = pageIndex;
        if (requestGeneration <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] requestGeneration 必須 > 0；實際: "
                    + requestGeneration);
        }
        this.requestGeneration = requestGeneration;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public long sessionGeneration() {
        return sessionGeneration;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public long requestGeneration() {
        return requestGeneration;
    }

    @Override
    public String toString() {
        return "GuiAsyncRequest{playerUuid=" + playerUuid
            + ", sessionGeneration=" + sessionGeneration
            + ", pageIndex=" + pageIndex
            + ", requestGeneration=" + requestGeneration + "}";
    }
}
