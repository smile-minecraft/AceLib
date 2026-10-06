package com.smile.acelib.gui;

import java.util.UUID;

/**
 * 送出前重新驗證回呼（Supported API）。
 *
 * <p>下游在 {@link GuiScope#confirmWithRevalidation} 送出確認票券時提供：
 * 服務先執行本回呼，只有回傳 {@code SUCCESS} 才執行 domain action。
 * 典型用途是送出當下再查一次餘額／庫存／權限 — 通過檢查的舊結果不得當成
 * 送出依據。</p>
 *
 * <p>回呼執行時不持有任何內部鎖，可安全呼叫作用域查詢；
 * 回呼抛例外視為驗證失敗（fail-closed），domain action 不執行且票券仍
 * 一次性失效。驗證失敗（非 SUCCESS）時服務會自動取消該票券並回傳驗證結果，
 * callback 不執行。</p>
 *
 * @see GuiScope#confirmWithRevalidation(UUID, long, String, GuiRevalidation)
 * @since 1.4.0
 */
@FunctionalInterface
public interface GuiRevalidation {

    /**
     * 重新驗證。
     *
     * @param playerUuid 送出玩家 UUID；永不為 null
     * @param generation 送出當下的 session generation
     * @return 驗證結果；只有 {@code SUCCESS} 會繼續執行 domain action
     */
    GuiResult revalidate(UUID playerUuid, long generation);
}
