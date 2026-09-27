package com.smile.acelib.command;

/**
 * 指令目錄錯誤代碼常數（{@code ACELIB-CMD-013}／{@code ACELIB-CMD-014}）。
 *
 * <p>這兩個代碼不是指令執行期結果，不屬於
 * {@link CommandErrorKind}（dispatch 結果分類）；以 package-private 常數承載，
 * 避免不必要的公開面成長。已登記於
 * {@code com.smile.acelib.diagnostics.ErrorCodeRegistry} 與
 * {@code docs/reference/error-codes.md}。</p>
 */
final class CommandCatalogErrorCodes {

    private CommandCatalogErrorCodes() {
        // utility class
    }

    /** 013 — 跨擁有者同名指令描述並存（warning，資料仍收錄）。 */
    static final String DUPLICATE_NAME = "ACELIB-CMD-013";

    /** 014 — 指令目錄服務未就緒或已停用，發布被拒。 */
    static final String NOT_READY = "ACELIB-CMD-014";
}
