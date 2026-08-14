package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * 未啟用 / 已停用狀態下的可診斷 facade（Internal）。
 *
 * <p>任何狀態下呼叫本類別的操作，都會回傳 {@link GuiState#REJECTED} 或
 * {@link GuiState#FAILED} 並附帶對應的 {@link GuiErrorCode#NOT_READY} 或
 * {@link GuiErrorCode#SHUTDOWN} 結果 —
 * <strong>永不為 null，絕不丟例外（除了 null inputs 的契約例外）</strong>。
 * 後續插件於 onEnable 之前或 plugin disable 之後呼叫
 * {@code AceLibApi.getGuiService()} 即取得此 instance。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link GuiService#forUnavailable(String)} 或 {@link com.smile.acelib.AceLibApi}
 * 取得 {@link GuiService} 介面。</p>
 *
 * @see GuiService
 * @since 1.0.0
 */
final class GuiServiceUnavailableImpl implements GuiService {

    /** 標記本 facade 為「未啟用」或「已停用」。 */
    private final String code;

    GuiServiceUnavailableImpl(String code) {
        if (!GuiErrorCode.NOT_READY.equals(code)
                && !GuiErrorCode.SHUTDOWN.equals(code)) {
            throw new IllegalArgumentException(
                "GuiServiceUnavailableImpl.code 必須為 NOT_READY 或 SHUTDOWN，實際: "
                    + code);
        }
        this.code = code;
    }

    // ----- contract: null inputs throw IllegalArgumentException -----

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    @Override
    public GuiResult openInventory(GuiArgument argument) {
        requireNonNull(argument, "argument");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult createConfirmation(UUID playerUuid, long generation,
                                        String actionId, Runnable callback) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionId, "actionId");
        requireNonNull(callback, "callback");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult confirm(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult cancel(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult closeInventory(UUID playerUuid, long generation) {
        requireNonNull(playerUuid, "playerUuid");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult getActiveSession(UUID playerUuid) {
        requireNonNull(playerUuid, "playerUuid");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration,
                                      int pageIndex) {
        requireNonNull(playerUuid, "playerUuid");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page,
                                          Runnable renderer) {
        requireNonNull(request, "request");
        requireNonNull(page, "page");
        requireNonNull(renderer, "renderer");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public GuiResult validateClick(UUID playerUuid, long generation, int slot) {
        requireNonNull(playerUuid, "playerUuid");
        return GuiResult.rejected(code, "gui service is unavailable: " + code);
    }

    @Override
    public String getModuleStatus() {
        return Objects.equals(code, GuiErrorCode.SHUTDOWN) ? "FAILED" : "NOT_INITIALIZED";
    }

    @Override
    public void shutdown() {
        // no-op for unavailable facade: idempotent + 留 audit trail 只留於 status 字串
    }
}
